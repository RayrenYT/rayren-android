package com.rayren.android.vpn

import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.*
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.rayren.android.*
import com.rayren.domain.*
import com.rayren.nativebridge.rayrencore.Protector
import com.rayren.nativebridge.rayrencore.Rayrencore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class RayrenVpnService:VpnService() {
    private val dispatcher=Executors.newSingleThreadExecutor { r->Thread(r,"rayren-vpn-controller") }.asCoroutineDispatcher()
    private val scope=CoroutineScope(SupervisorJob()+dispatcher)
    private var worker:Job?=null
    private var tun:ParcelFileDescriptor?=null
    private var hev:TProxyService?=null
    private var engine:TransportEngine?=null
    private var listener:Listener?=null
    private var selection=Selection()
    private var transportLease:Mutex?=null
    private val routeGeneration=AtomicLong()
    // Network callbacks and user actions wake the controller without polling.
    private val changes=Channel<Unit>(Channel.CONFLATED)
    private val notificationGate=NotificationRefreshGate()
    private val requestedPing=AtomicReference<String?>(null)
    private val networks=ConcurrentHashMap<Network,NetworkCapabilities>()
    private val links=ConcurrentHashMap<Network,LinkProperties>()
    @Volatile private var physical:Network?=null
    @Volatile private var stopping=false
    private val networkGeneration=AtomicLong()
    private lateinit var connectivity:ConnectivityManager
    private val app get()=applicationContext.rayren
    private var status=Connection()
    private val protector=object:Protector {override fun protect(fd:Long):Boolean=fd>=0&&fd<=Int.MAX_VALUE&&this@RayrenVpnService.protect(fd.toInt())}

    private val callback=object:ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network:Network,capabilities:NetworkCapabilities){
            val old=networks.put(network,capabilities);selectNetwork()
            if(network==physical&&(old?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)!=capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)))changes.trySend(Unit)
        }
        override fun onLost(network:Network){networks.remove(network);links.remove(network);selectNetwork()}
        override fun onLinkPropertiesChanged(network:Network,properties:LinkProperties){val old=links.put(network,properties);if(old!=null&&old!=properties&&network==physical){networkGeneration.incrementAndGet();changes.trySend(Unit)}}
    }
    private fun selectNetwork(){
        val next=networks.entries.sortedWith(compareByDescending<Map.Entry<Network,NetworkCapabilities>>{it.value.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}.thenByDescending{it.value.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)}).firstOrNull()?.key
        if(next!=physical){physical=next;networkGeneration.incrementAndGet();runCatching{setUnderlyingNetworks(next?.let{arrayOf(it)})};changes.trySend(Unit)}
    }

    override fun onCreate(){
        super.onCreate();connectivity=getSystemService(ConnectivityManager::class.java)
        connectivity.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),callback)
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==STOP){requestStop();return START_NOT_STICKY}
        if(intent?.action==PING){
            val c=app.state.value.connection
            if(c.phase==Phase.CONNECTED){requestedPing.set(c.profileId);app.probeState(c.profileId,ProbeState.CHECKING);changes.trySend(Unit)}
            else if(tun==null&&worker?.isActive!=true)stopSelf()
            return START_NOT_STICKY
        }
        if(intent?.action==RESELECT){
            routeGeneration.incrementAndGet();requestedPing.getAndSet(null)?.let{app.probeState(it,ProbeState.CANCELLED)}
            Rayrencore.cancelRequests()
            changes.trySend(Unit)
            if(tun!=null||worker?.isActive==true)app.connection(app.state.value.connection.copy(phase=Phase.RECONNECTING,latency=null,detail="Переключаем сервер"))else stopSelf()
            return START_NOT_STICKY
        }
        foreground()
        if(worker?.isActive!=true&&!stopping&&tun==null)worker=scope.launch{runVpn()}
        return START_STICKY
    }
    override fun onRevoke(){requestStop();super.onRevoke()}
    override fun onDestroy(){
        stopping=true;Rayrencore.cancelRequests();worker?.cancel()
        runCatching{connectivity.unregisterNetworkCallback(callback)}
        scope.launch {cleanup();scope.cancel();dispatcher.close()}
        super.onDestroy()
    }
    private fun requestStop(){
        if(stopping)return
        stopping=true;app.connection(app.state.value.connection.copy(phase=Phase.STOPPING,detail="Завершение подключения"))
        Rayrencore.cancelRequests()
        worker?.cancel()
        scope.launch{cleanup();app.connection(Connection());stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
    }

    private fun publish(phase:Phase,detail:String="",profile:Profile?=null){
        val network=physical;val caps=network?.let{networks[it]}
        val label=when{caps==null->"Нет сети";caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)->"Wi-Fi";caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)->"Мобильная сеть";caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)->"Ethernet";else->"Другая сеть"}
        val privateDns=network?.let{connectivity.getLinkProperties(it)}?.let{Build.VERSION.SDK_INT>=28&&it.isPrivateDnsActive} ?: false
        status=status.copy(phase=phase,detail=detail,profileId=profile?.id ?: status.profileId,serverName=profile?.name ?: status.serverName,network=label,privateDns=privateDns)
        app.connection(status)
        val display=notificationDisplay(when(phase){Phase.CONNECTED->"Подключено · $label";Phase.BLOCKED->"Трафик заблокирован · $detail";Phase.RECONNECTING->"Восстановление соединения";else->detail.ifBlank{"Подключение"}})
        if(notificationGate.shouldPost(display,now()))getSystemService(NotificationManager::class.java).notify(NOTIFICATION,notification(display))
    }

    private suspend fun runVpn(){
        try {
            app.awaitLoaded()
            val data=app.state.value.data;val p=data.preferences
            PolicyCompiler.validate(p)
            ensure(data.profiles.isNotEmpty(),"Добавьте подписку или сервер")
            ensure(p.mode!=Mode.MANUAL||data.profiles.any{it.id==p.selectedId},"Выберите доступный сервер")
            Rayrencore.initialize(filesDir.absolutePath)
            copyGeoAssets()
            listener=newListener()
            val mtu=if(p.autoMtu)1280 else p.mtu
            establish(p,mtu)
            val config=File(noBackupFilesDir,"hev-session.yml")
            val l=listener!!
            config.writeText("""
                tunnel:
                  mtu: $mtu
                  ipv4: 198.18.0.1
                  ipv6: 'fd00:7261:7972::1'
                socks5:
                  port: ${l.vpnPort}
                  address: 127.0.0.1
                  udp: 'udp'
                  username: '${l.username}'
                  password: '${l.password}'
                misc:
                  log-level: error
                  log-file: /dev/null
                  connect-timeout: ${p.timeoutMs}
                  max-session-count: 2048
            """.trimIndent())
            hev=TProxyService()
            ensure(hev!!.TProxyStartService(config.absolutePath,tun!!.fd),"Не удалось запустить обработчик VPN")
            val smartScanned=mutableSetOf<String>()
            var previousRevision=routeGeneration.get()
            var previousNetworkGeneration=-1L
            while(currentCoroutineContext().isActive&&!stopping){
                val revision=routeGeneration.get()
                if(revision!=previousRevision){selection=Selection();smartScanned.clear();previousRevision=revision;status=status.copy(profileId="",serverName="",latency=null,exitIp="")}
                val data=app.state.value.data;val p=data.preferences
                val network=physical
                if(network==null){publish(Phase.BLOCKED,"Нет физической сети");awaitChange(30000);continue}
                val caps=networks[network]
                if(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)==true){publish(Phase.BLOCKED,"Войдите в Wi-Fi через системное уведомление");awaitChange(30000);continue}
                val generation=networkGeneration.get()
                val networkKey=RouteRecovery.networkKey(network.toString(),generation)
                if(generation!=previousNetworkGeneration){
                    previousNetworkGeneration=generation;selection=Selection();smartScanned.clear()
                    app.networkChanged()
                    status=status.copy(latency=null,exitIp="",upRate=0,downRate=0)
                    app.event("NETWORK_RESELECT_${p.mode}")
                }
                if(p.mode==Mode.SMART&&networkKey !in smartScanned){
                    val scanDeadline=now()+45000
                    for(candidate in selection.scanCandidates(data.profiles,p,networkKey,now(),subscriptions=data.subscriptions)){
                        if(routeGeneration.get()!=revision||networkGeneration.get()!=generation||physical!=network||now()>=scanDeadline)break
                        try{
                            checkRoute(network,generation,revision)
                            publish(Phase.CONNECTING,"По пингу: проверка серверов",candidate)
                            startEngine(candidate,p,network){checkRoute(network,generation,revision)}
                            val elapsed=probe(p,candidate.isBalancer){checkRoute(network,generation,revision)}
                            checkRoute(network,generation,revision)
                            selection.success(networkKey,candidate.id,listOf(elapsed),now());app.latency(candidate.id,elapsed)
                        }
                        catch(e:CancellationException){throw e}
                        catch(_:RouteChanged){break}
                        catch(_:NetworkChanged){break}
                        catch(_:Exception){if(networkGeneration.get()!=generation||physical!=network||routeGeneration.get()!=revision)break;selection.failure(networkKey,candidate.id,now());app.probeState(candidate.id,ProbeState.FAILED);app.event("BALANCE_PROBE_FAILED")}
                        finally{stopEngines()}
                    }
                    if(networkGeneration.get()==generation&&physical==network&&routeGeneration.get()==revision)smartScanned.add(networkKey)
                }
                if(routeGeneration.get()!=revision||networkGeneration.get()!=generation||physical!=network)continue
                val candidate=selection.candidates(data.profiles,p,networkKey,now(),subscriptions=data.subscriptions).firstOrNull()
                if(candidate==null){status=status.copy(decision=null);publish(Phase.BLOCKED,if(SelectionSources.ids(data).isEmpty())"Выберите подписки для этого режима в разделе «Серверы»"else "Нет доступного маршрута; ожидание повторной попытки");routeDelay(selection.nextRetry(data.profiles,networkKey,now()),revision,generation);continue}
                var failed=false
                try {
                    status=status.copy(latency=null,exitIp="",upRate=0,downRate=0,decision=null)
                    publish(Phase.CONNECTING,"Проверка защищённого маршрута",candidate)
                    val start=now()
                    startEngine(candidate,p,network){checkRoute(network,generation,revision)}
                    val samples=listOf(probe(p,candidate.isBalancer){checkRoute(network,generation,revision)},probe(p){checkRoute(network,generation,revision)})
                    checkRoute(network,generation,revision)
                    ensure(!stopping,"Подключение остановлено")
                    if(networkGeneration.get()!=generation)throw NetworkChanged()
                    val latency=samples.sorted()[samples.size/2]
                    selection.success(networkKey,candidate.id,samples,now(),now()-start);app.latency(candidate.id,latency)
                    status=status.copy(latency=latency,started=if(status.started>0)status.started else System.currentTimeMillis(),decision=selection.explain(candidate,data.profiles,p,networkKey,System.currentTimeMillis(),data.subscriptions))
                    publish(Phase.CONNECTED,"Маршрут проверен",candidate);app.event("ROUTE_VERIFIED_${candidate.engine}")
                    status=status.copy(exitIp=Rayrencore.exitIP(l.probePort.toLong(),l.username,l.password))
                    checkRoute(network,generation,revision)
                    publish(Phase.CONNECTED,"Маршрут проверен",candidate)
                    var lastHealth=now();var failures=0;var statsAt=now()
                    while(currentCoroutineContext().isActive&&!stopping){
                        awaitChange(minOf(5000L,(p.healthSeconds*1000L-(now()-lastHealth)).coerceAtLeast(1)))
                        if(routeGeneration.get()!=revision)throw RouteChanged()
                        if(networkGeneration.get()!=generation)throw NetworkChanged()
                        ensure(Rayrencore.coreRunning()&&hev?.TProxyIsRunning()==true,"Движок остановился")
                        var up=0L;var down=0L
                        Rayrencore.stats().split(';').forEach { row->val parts=row.split(',');if(parts.size==3&&(parts[0]=="proxy"||parts[0].startsWith("proxy-"))){if(parts[1]=="uplink")up+=parts[2].toLongOrNull() ?: 0;else if(parts[1]=="downlink")down+=parts[2].toLongOrNull() ?: 0} }
                        val interval=(now()-statsAt).coerceAtLeast(1);statsAt=now()
                        status=status.copy(up=status.up+up,down=status.down+down,upRate=up*1000/interval,downRate=down*1000/interval)
                        publish(Phase.CONNECTED,"Маршрут проверен",candidate)
                        if(requestedPing.getAndSet(null)==candidate.id||now()-lastHealth>=p.healthSeconds*1000){
                            lastHealth=now()
                            app.probeState(candidate.id,ProbeState.CHECKING);publish(Phase.CONNECTED,"Проверка задержки…",candidate)
                            try{val measurement=probe(p){checkRoute(network,generation,revision)};checkRoute(network,generation,revision);selection.success(networkKey,candidate.id,listOf(measurement),now());app.latency(candidate.id,measurement);status=status.copy(latency=measurement,decision=selection.explain(candidate,data.profiles,p,networkKey,System.currentTimeMillis(),data.subscriptions));failures=0}
                            catch(e:CancellationException){throw e}catch(e:Exception){checkRoute(network,generation,revision);status=status.copy(latency=null,decision=null);app.probeState(candidate.id,ProbeState.FAILED);failures++;lastHealth=now()-p.healthSeconds*1000+3000;app.event("HEALTH_PROBE_FAILED");if(failures>=2)throw ValidationException("Проверка интернета не проходит")}
                            if(routeGeneration.get()!=revision)throw RouteChanged()
                            publish(Phase.CONNECTED,if(failures==0)"Маршрут проверен" else "Проверка не прошла",candidate)
                        }
                    }
                } catch(e:CancellationException){throw e}
                catch(_:RouteChanged){publish(Phase.RECONNECTING,"Переключаем сервер")}
                catch(_:NetworkChanged){app.event("PHYSICAL_NETWORK_CHANGED");publish(Phase.RECONNECTING,"Смена сети")}
                catch(_:Exception){
                    if(networkGeneration.get()!=generation||physical!=network){app.event("PHYSICAL_NETWORK_CHANGED");publish(Phase.RECONNECTING,"Смена сети")}
                    else if(routeGeneration.get()!=revision)publish(Phase.RECONNECTING,"Переключаем сервер")
                    else {failed=true;selection.failure(networkKey,candidate.id,now());app.probeState(candidate.id,ProbeState.FAILED);app.event("TRANSPORT_FAILED_${candidate.engine}");publish(Phase.BLOCKED,"Маршрут недоступен; трафик удерживается")}
                }
                finally{requestedPing.getAndSet(null)?.let{app.probeState(it,ProbeState.CANCELLED)};if(app.state.value.probes[candidate.id]?.state==ProbeState.CHECKING)app.probeState(candidate.id,ProbeState.CANCELLED);if(stopping)detachTunnel();stopEngines()}
                if(routeGeneration.get()!=revision||networkGeneration.get()!=generation||physical!=network)continue
                if(!RouteRecovery.enabled(p)){publish(Phase.BLOCKED,"Автоподключение выключено. Выберите сервер для новой попытки.");while(!stopping&&routeGeneration.get()==revision)awaitChange(30000)}
                routeDelay(if(failed&&p.mode==Mode.MANUAL)selection.nextRetry(listOf(candidate),networkKey,now())else 1500,revision,generation)
            }
        }catch(_:CancellationException) { }
        catch(e:Exception){
            app.event("VPN_SETUP_FAILED")
            val detail=if(e is ValidationException)e.reason else "Не удалось запустить VPN. Проверьте настройки."
            if(tun!=null){publish(Phase.BLOCKED,detail);withContext(NonCancellable){stopEngines()}}
            else{app.message(detail);app.connection(Connection(detail=detail));stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
        }
        finally {if(stopping)withContext(NonCancellable){cleanup()}}
    }

    private suspend fun startEngine(profile:Profile,p:Preferences,network:Network,guard:()->Unit={}){
        val lease=TransportLease.forProfile(profile);lease.lock();transportLease=lease
        Rayrencore.beginRequests()
        guard()
        ensure(app.state.value.data.profiles.any{it.id==profile.id},"Сервер удалён")
        ensure(connectivity.bindProcessToNetwork(network),"Физическая сеть больше недоступна")
        val l=listener!!
        val bootstrap=mutableMapOf<String,List<String>>()
        // Resolver names are control-plane bootstrap. Cache only these and the
        // transport endpoint; application domains always go through Rayren DNS.
        (listOf(p.dns)+p.dnsRules.map{it.server}).filter{it.contains("://")}.forEach{endpoint->
            java.net.URI(endpoint).host?.removeSurrounding("[","]")?.let{host->bootstrap[host]=NetworkResolver.resolve(network,host)}
        }
        SmartBalance.endpoints(profile).forEach { address ->
            guard()
            val resolved=NetworkResolver.resolve(network,address)
            if(resolved.isNotEmpty())bootstrap[address]=resolved else ensure(profile.isBalancer,"Не удалось разрешить адрес сервера")
        }
        engine=when(profile.engine){Engine.XRAY->XrayTransport();Engine.OPENFLUX->OpenFluxTransport();Engine.OLCRTC->OlcRtcTransport()}
        val systemDns=connectivity.getLinkProperties(network)?.dnsServers?.firstOrNull()?.hostAddress ?: "1.1.1.1"
        val prepared=if(profile.engine==Engine.OLCRTC)profile.copy(config=profile.config.deepCopy().apply{addProperty("dns",if(systemDns.contains(':'))"[$systemDns]:53" else "$systemDns:53")})else profile
        engine!!.start(prepared,l,protector)
        val deadline=now()+p.timeoutMs
        while(!engine!!.ready()&&now()<deadline){guard();delay(250)}
        guard()
        ensure(engine!!.ready(),"Транспорт не завершил подключение")
        ensure(!stopping,"Подключение остановлено")
        if(p.rules.any{it.kind in setOf(RuleKind.GEOIP,RuleKind.GEOSITE)})ensure(File(filesDir,"geoip.dat").exists()&&File(filesDir,"geosite.dat").exists(),"Для geo-правил нужны базы GeoIP/GeoSite")
        if(p.dnsMode==DnsMode.TUNNEL_DOT)Rayrencore.startDoT(l.dnsPort.toLong(),l.probePort.toLong(),l.username,l.password,p.dns)
        Rayrencore.startCore(PolicyCompiler.compile(profile,p,l,bootstrap,systemDns))
    }
    private fun probe(p:Preferences,balanced:Boolean=false,guard:()->Unit={}):Long{
        val l=listener!!
        val timing=ProbeTiming.measure(balanced,p.timeoutMs,guard){timeout->Rayrencore.probeReport(l.probePort.toLong(),l.username,l.password,p.probeUrl,timeout.toLong(),p.probeStatus.toLong())}
        guard()
        app.event(timing.diagnostic())
        return timing.totalMs
    }
    private fun stopEngines(){runCatching{Rayrencore.stopDoT()};runCatching{Rayrencore.stopCore()};runCatching{engine?.stop()};engine=null;transportLease?.unlock();transportLease=null}
    private fun detachTunnel(){runCatching{hev?.TProxyStopService()};hev=null;runCatching{tun?.close()};tun=null}
    private fun cleanup(){detachTunnel();stopEngines();runCatching{connectivity.bindProcessToNetwork(null)};File(noBackupFilesDir,"hev-session.yml").delete()}

    private fun establish(p:Preferences,mtu:Int){
        val builder=Builder().setSession("Rayren").setMtu(mtu).addAddress("198.18.0.1",30).addAddress("fd00:7261:7972::1",126).addRoute("0.0.0.0",0).addRoute("::",0).addDnsServer("198.18.0.2").setBlocking(false)
        if(Build.VERSION.SDK_INT>=29)builder.setMetered(physical?.let{networks[it]?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)}!=true)
        val installed=(p.packages+RussianBypass.packages(p)).filter{pkg->runCatching{packageManager.getApplicationInfo(pkg,0)}.isSuccess}.toSet()
        when(p.appMode){
            AppMode.INCLUDE->{val included=RussianBypass.includedPackages(p,installed,packageName);ensure(included.isNotEmpty(),"Нет установленных приложений для VPN после исключений обхода");included.forEach{builder.addAllowedApplication(it)}}
            else->RussianBypass.excludedPackages(p,installed,packageName).forEach{builder.addDisallowedApplication(it)}
        }
        builder.setConfigureIntent(PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        tun=builder.establish() ?: throw ValidationException("Android не разрешил VPN")
        setUnderlyingNetworks(physical?.let{arrayOf(it)})
        app.event("TUN_ESTABLISHED_MTU_$mtu")
    }
    private fun copyGeoAssets(){for(name in listOf("geoip.dat","geosite.dat")){if(assets.list("")?.contains(name)==true&&!File(filesDir,name).exists())assets.open(name).use{input->File(filesDir,name).outputStream().use{input.copyTo(it)}}}}
    private fun newListener():Listener {
        val sockets=(1..4).map{ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))}
        val ports=sockets.map{it.localPort};sockets.forEach{it.close()}
        fun token():String{val bytes=ByteArray(24);SecureRandom().nextBytes(bytes);return android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING)}
        return Listener(ports[0],ports[1],ports[2],token(),token(),ports[3])
    }
    private fun now()=SystemClock.elapsedRealtime()
    private fun checkRoute(network:Network,generation:Long,revision:Long){
        if(stopping)throw CancellationException("VPN stopped")
        if(routeGeneration.get()!=revision)throw RouteChanged()
        if(networkGeneration.get()!=generation||physical!=network)throw NetworkChanged()
    }
    private suspend fun awaitChange(ms:Long){withTimeoutOrNull(ms.coerceAtLeast(1)){changes.receive()}}
    private suspend fun routeDelay(ms:Long,revision:Long,generation:Long){val deadline=now()+ms;while(now()<deadline&&routeGeneration.get()==revision&&networkGeneration.get()==generation&&!stopping)awaitChange(deadline-now())}
    private fun foreground(){
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Подключение Rayren",NotificationManager.IMPORTANCE_LOW))
        val display=notificationDisplay("Запуск VPN");notificationGate.shouldPost(display,now())
        if(Build.VERSION.SDK_INT>=34)startForeground(NOTIFICATION,notification(display),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)else startForeground(NOTIFICATION,notification(display))
    }
    private fun notificationDisplay(text:String):NotificationDisplay {
        val server=status.serverName.ifBlank{app.state.value.data.profiles.find{it.id==status.profileId}?.name.orEmpty()}
        val checking=app.state.value.probes[status.profileId]?.state==ProbeState.CHECKING
        val detail=if(status.phase==Phase.CONNECTED)(if(checking)"Пинг: проверка…" else status.latency?.let{"Пинг: $it мс"} ?: "Пинг: нет ответа")+" · ${status.network}" else text
        val content=VpnNotificationText.content(server,detail,status.phase==Phase.CONNECTED,status.downRate,status.upRate)
        return NotificationDisplay(content,ModeText.short(app.state.value.data.preferences.mode),status.phase==Phase.CONNECTED&&!checking)
    }
    private fun notification(display:NotificationDisplay):Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,1,Intent(this,RayrenVpnService::class.java).setAction(STOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val ping=PendingIntent.getService(this,2,Intent(this,RayrenVpnService::class.java).setAction(PING),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder=Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_vpn).setContentTitle("Rayren").setContentText(display.content).setStyle(Notification.BigTextStyle().bigText(display.content)).setCategory(Notification.CATEGORY_SERVICE).setShowWhen(false).setSubText(display.mode).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
        if(display.canPing)builder.addAction(Notification.Action.Builder(null,"Пинг",ping).build())
        return builder.addAction(Notification.Action.Builder(null,"Отключить",stop).build()).build()
    }
    private class RouteChanged:Exception()
    private class NetworkChanged:Exception()
    companion object {const val START="com.rayren.START";const val STOP="com.rayren.STOP";const val RESELECT="com.rayren.RESELECT";const val PING="com.rayren.PING";private const val CHANNEL="rayren_vpn";private const val NOTIFICATION=100}
}
