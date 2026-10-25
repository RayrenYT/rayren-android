package com.rayren.android.vpn

import android.app.Service
import android.content.*
import android.net.*
import android.os.*
import com.google.gson.Gson
import com.rayren.domain.*
import com.rayren.nativebridge.rayrencore.Protector
import com.rayren.nativebridge.rayrencore.Rayrencore
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** Main-process ownership prevents duplicate provider sessions during route changes. */
object TransportLease {
    private val locks=ConcurrentHashMap<String,Mutex>()
    fun forProfile(p:Profile)=locks.getOrPut(if(p.engine==Engine.XRAY)p.id else p.engine.name){Mutex()}
}

private object ProbeRuntime {
    val dispatcher=Executors.newSingleThreadExecutor{r->Thread(r,"rayren-isolated-probe")}.asCoroutineDispatcher()
}

/** Separate native globals and physical-network binding: never changes the VPN's active core. */
class ProbeService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+ProbeRuntime.dispatcher)
    private var job:Job?=null
    private val main=Handler(Looper.getMainLooper())
    // A wedged native call must not leave a second provider session alive after timeout.
    private val watchdog=Runnable{android.os.Process.killProcess(android.os.Process.myPid())}
    private val messenger=Messenger(Handler(Looper.getMainLooper()){message->
        if(message.what==1&&job==null){
            main.postDelayed(watchdog,45000)
            val reply=message.replyTo
            val rawProfile=message.data.getString("profile").orEmpty()
            val rawPreferences=message.data.getString("preferences").orEmpty()
            job=scope.launch {
                var result=-1L
                var timing=""
                var transport:TransportEngine?=null
                val cm=getSystemService(ConnectivityManager::class.java)
                try {
                    ensure(rawProfile.length<=2*1024*1024&&rawPreferences.length<=65536,"Слишком большой профиль")
                    val gson=Gson()
                    val profile=gson.fromJson(rawProfile,Profile::class.java)
                    val settings=gson.fromJson(rawPreferences,Preferences::class.java)
                    val p=settings.copy(timeoutMs=settings.timeoutMs.coerceIn(3000,15000),preset=Preset.GLOBAL,rules=emptyList(),bypassRuDomains=false,bypassSiteGroups=emptySet(),directSites=emptySet(),bypassWhitelistSites=false)
                    val network=cm.allNetworks.filter{n->cm.getNetworkCapabilities(n)?.let{it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)}==true}.maxByOrNull{n->if(cm.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true)1 else 0}
                    ensure(network!=null&&cm.bindProcessToNetwork(network),"Нет физической сети")
                    Rayrencore.initialize(filesDir.absolutePath)
                    val sockets=(1..4).map{ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))}
                    val ports=sockets.map{it.localPort};sockets.forEach{it.close()}
                    val l=Listener(ports[0],ports[1],ports[2],UUID.randomUUID().toString(),UUID.randomUUID().toString(),ports[3])
                    val bootstrap=mutableMapOf<String,List<String>>()
                    (listOf(p.dns)+p.dnsRules.map{it.server}).filter{it.contains("://")}.forEach{endpoint->java.net.URI(endpoint).host?.removeSurrounding("[","]")?.let{host->bootstrap[host]=NetworkResolver.resolve(network!!,host)}}
                    SmartBalance.endpoints(profile).forEach { host ->
                        val resolved=NetworkResolver.resolve(network!!,host)
                        if(resolved.isNotEmpty())bootstrap[host]=resolved else ensure(profile.isBalancer,"Не удалось разрешить адрес сервера")
                    }
                    currentCoroutineContext().ensureActive()
                    val systemDns=cm.getLinkProperties(network!!)?.dnsServers?.firstOrNull()?.hostAddress ?: "1.1.1.1"
                    val prepared=if(profile.engine==Engine.OLCRTC)profile.copy(config=profile.config.deepCopy().apply{addProperty("dns",if(systemDns.contains(':'))"[$systemDns]:53" else "$systemDns:53")})else profile
                    val active=when(profile.engine){Engine.XRAY->XrayTransport();Engine.OPENFLUX->OpenFluxTransport();Engine.OLCRTC->OlcRtcTransport()}
                    transport=active
                    active.start(prepared,l,object:Protector{override fun protect(fd:Long)=fd>=0})
                    val deadline=SystemClock.elapsedRealtime()+p.timeoutMs
                    while(!active.ready()&&SystemClock.elapsedRealtime()<deadline)delay(100)
                    ensure(active.ready(),"Транспорт недоступен")
                    currentCoroutineContext().ensureActive()
                    if(p.dnsMode==DnsMode.TUNNEL_DOT)Rayrencore.startDoT(l.dnsPort.toLong(),l.probePort.toLong(),l.username,l.password,p.dns)
                    Rayrencore.startCore(PolicyCompiler.compile(profile,p,l,bootstrap,systemDns))
                    val measured=ProbeTiming.measure(profile.isBalancer,p.timeoutMs){timeout->Rayrencore.probeReport(l.probePort.toLong(),l.username,l.password,p.probeUrl,timeout.toLong(),p.probeStatus.toLong())}
                    timing=Json.gson.toJson(measured)
                    result=measured.totalMs
                }catch(_:Exception){result=-1}
                finally {
                    runCatching{Rayrencore.stopDoT()};runCatching{Rayrencore.stopCore()};runCatching{transport?.stop()};cm.bindProcessToNetwork(null)
                    main.post {main.removeCallbacks(watchdog);job=null;runCatching{reply.send(Message.obtain(null,2).apply{data=Bundle().apply{putLong("ms",result);putString("timing",timing)}})}}
                }
            }
        };true
    })
    override fun onBind(intent:Intent):IBinder=messenger.binder
    override fun onDestroy(){scope.cancel();super.onDestroy()}
}

class ProbeClient(private val context:Context) {
    suspend fun measure(profile:Profile,p:Preferences,onTiming:(ProbeTiming)->Unit={}):Long?=withContext(Dispatchers.Main.immediate){
            suspendCancellableCoroutine { continuation->
                var bound=false
                var finished=false
                lateinit var connection:ServiceConnection
                fun finish(value:Long?){if(finished)return;finished=true;if(bound){bound=false;runCatching{context.unbindService(connection)}};if(continuation.isActive)continuation.resume(value)}
                val reply=Messenger(Handler(Looper.getMainLooper()){m->if(m.what==2){ProbeTiming.parse(m.data.getString("timing").orEmpty())?.let(onTiming);finish(m.data.getLong("ms",-1).takeIf{it>=0})};true})
                connection=object:ServiceConnection {
                    override fun onServiceConnected(name:ComponentName,binder:IBinder){
                        if(finished)return
                        runCatching{Messenger(binder).send(Message.obtain(null,1).apply{replyTo=reply;data=Bundle().apply{putString("profile",Gson().toJson(profile));putString("preferences",Gson().toJson(p))}})}.onFailure{finish(null)}
                    }
                    override fun onServiceDisconnected(name:ComponentName){finish(null)}
                    override fun onBindingDied(name:ComponentName){finish(null)}
                    override fun onNullBinding(name:ComponentName){finish(null)}
                }
                try{bound=context.bindService(Intent(context,ProbeService::class.java),connection,Context.BIND_AUTO_CREATE);if(!bound)finish(null)}catch(_:Exception){finish(null)}
                continuation.invokeOnCancellation{Handler(Looper.getMainLooper()).post{finish(null)}}
            }
    }
}
