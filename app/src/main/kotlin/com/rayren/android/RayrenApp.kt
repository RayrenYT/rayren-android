package com.rayren.android

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.*
import com.rayren.android.data.SecureVault
import com.rayren.android.data.Subscriptions
import com.rayren.android.vpn.RayrenVpnService
import com.rayren.android.vpn.ProbeClient
import com.rayren.android.vpn.TransportLease
import com.rayren.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

enum class Phase { IDLE, CONNECTING, CONNECTED, RECONNECTING, BLOCKED, STOPPING }
data class Connection(val phase:Phase=Phase.IDLE,val profileId:String="",val latency:Long?=null,val exitIp:String="",val up:Long=0,val down:Long=0,val upRate:Long=0,val downRate:Long=0,val started:Long=0,val network:String="Нет сети",val privateDns:Boolean=false,val detail:String="",val serverName:String="",val decision:ExplainableDecision?=null)
data class AppState(val data:VaultData=VaultData(),val loaded:Boolean=false,val busy:Boolean=false,val message:String="",val fatal:Boolean=false,val connection:Connection=Connection(),val events:List<String> = emptyList(),val latencies:Map<String,Long> = emptyMap(),val probes:Map<String,ProbeResult> = emptyMap(),val probing:Boolean=false,val probeDone:Int=0,val probeTotal:Int=0,val probeStopping:Boolean=false)

class RayrenApp:Application() {
    lateinit var controller:AppController; private set
    override fun onCreate(){
        super.onCreate()
        val process=if(android.os.Build.VERSION.SDK_INT>=28)getProcessName() else getSystemService(android.app.ActivityManager::class.java).runningAppProcesses?.find{it.pid==android.os.Process.myPid()}?.processName.orEmpty()
        if(!process.endsWith(":probe"))controller=AppController(this)
    }
}
val Context.rayren get()=(applicationContext as RayrenApp).controller

class AppController(private val context:Context) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutable=MutableStateFlow(AppState())
    val state=mutable.asStateFlow()
    private val vault=SecureVault(context)
    private val lock=Mutex()
    private val subscriptions=Subscriptions()
    private val probeGate=Mutex()
    private var probeJob:Job?=null
    @Volatile private var stopProbes=false
    init { scope.launch { try { val data=vault.read();mutable.update{it.copy(data=data,loaded=true)};schedule(data.preferences.refreshHours) }catch(_:Exception){mutable.update{it.copy(loaded=true,fatal=true,message="Не удалось открыть защищённое хранилище. Данные сохранены; перезапустите приложение.")}} } }
    suspend fun awaitLoaded(){while(!mutable.value.loaded)delay(50);ensure(!mutable.value.fatal,"Хранилище недоступно")}
    fun message(value:String){mutable.update{it.copy(message=value)}}
    fun clearMessage(){message("")}
    fun event(code:String){val line=java.time.LocalTime.now().withNano(0).toString()+" · "+Redaction.sanitize(code);mutable.update{it.copy(events=(it.events+line).takeLast(if(it.data.preferences.debug)200 else 60))}}
    fun connection(value:Connection){mutable.update{it.copy(connection=value)}}
    fun latency(id:String,value:Long){mutable.update{it.copy(latencies=it.latencies+(id to value),probes=it.probes+(id to ProbeResult(ProbeState.OK,value)))}}
    fun probeState(id:String,state:ProbeState){mutable.update{it.copy(probes=it.probes+(id to ProbeResult(state)))}}
    fun pingActive(){if(mutable.value.connection.phase==Phase.CONNECTED)context.startService(Intent(context,RayrenVpnService::class.java).setAction(RayrenVpnService.PING))}
    fun cancelProbes(){stopProbes=true;mutable.update{it.copy(probeStopping=it.probing)}}
    fun networkChanged(){cancelProbes();mutable.update{it.copy(latencies=emptyMap(),probes=emptyMap())}}
    @Synchronized fun ping(id:String?=null,ids:Set<String>?=null){
        if(id!=null&&mutable.value.connection.let{it.phase==Phase.CONNECTED&&it.profileId==id}){pingActive();return}
        if(probeJob?.isActive==true)return
        stopProbes=false
        probeJob=scope.launch {
            val data=mutable.value.data
            val profiles=data.profiles.filter{(id==null||it.id==id)&&(ids==null||it.id in ids)&&(!it.experimental||data.preferences.experimental)}
            mutable.update{it.copy(probing=true,probeDone=0,probeTotal=profiles.size,probeStopping=false)}
            try{for(profile in profiles){
                if(stopProbes)break
                probeGate.withLock {
                    if(stopProbes)return@withLock
                    if(mutable.value.connection.let{it.phase==Phase.CONNECTED&&it.profileId==profile.id}){pingActive()}
                    else {
                        val lease=TransportLease.forProfile(profile)
                        if(!lease.tryLock()){probeState(profile.id,ProbeState.BUSY)}
                        else try{
                            probeState(profile.id,ProbeState.CHECKING)
                            val value=ProbeClient(context).measure(profile,data.preferences){event(it.diagnostic())}
                            if(stopProbes)probeState(profile.id,ProbeState.CANCELLED)else if(value!=null)latency(profile.id,value) else probeState(profile.id,ProbeState.FAILED)
                        }finally{lease.unlock()}
                    }
                    mutable.update{it.copy(probeDone=it.probeDone+1)}
                }
            }}finally{mutable.update{it.copy(probing=false,probeStopping=false)}}
        }
    }
    fun start(){ContextCompat.startForegroundService(context,Intent(context,RayrenVpnService::class.java).setAction(RayrenVpnService.START))}
    fun stop(){context.startService(Intent(context,RayrenVpnService::class.java).setAction(RayrenVpnService.STOP))}
    private suspend fun commit(data:VaultData){vault.write(data);mutable.update{it.copy(data=data)}}

    fun import(text:String,key:String="")=scope.launch {
        try{awaitLoaded()}catch(_:Exception){message("Защищённое хранилище недоступно");return@launch}
        lock.withLock {
            if(mutable.value.fatal)return@withLock
            mutable.update{it.copy(busy=true)}
            var stage=ImportStage.INPUT
            try {
                val input=ImportInputResolver.resolve(text)
                val current=mutable.value.data
                if(input is ImportInput.Remote) {
                    val url=input.url;val old=current.subscriptions.find{it.url==url}
                    val signing=key.trim().ifBlank{old?.signingKey.orEmpty()}
                    val fetched=subscriptions.fetch(url,old,signing){stage=it}
                    stage=ImportStage.SAVE
                    applyFetch(url,old,signing,fetched)
                    message(if(fetched.result==null)"Подписка не изменилась" else "Импортировано серверов: ${fetched.result.profiles.size}"+fetched.result.warnings.firstOrNull()?.let{". $it"}.orEmpty())
                } else {
                    stage=ImportStage.VERIFY
                    val payload=ManifestVerifier.payload((input as ImportInput.Inline).text,key.trim())
                    stage=ImportStage.PARSE
                    val parsed=ImportParser.parse(payload)
                    stage=ImportStage.SAVE
                    commit(current.copy(profiles=(current.profiles+parsed.profiles).distinctBy{it.id}))
                    message("Импортировано серверов: ${parsed.profiles.size}"+parsed.warnings.firstOrNull()?.let{". $it"}.orEmpty())
                }
                event("IMPORT_OK")
            }catch(e:Exception){
                message(if(e is ValidationException)e.reason else "Импорт не выполнен. Проверьте ссылку и сеть; рабочие профили сохранены.")
                event("IMPORT_FAILED_${stage}_${importFailureCode(e)}")
            }
            finally{mutable.update{it.copy(busy=false)}}
        }
    }

    private suspend fun applyFetch(url:String,old:Subscription?,signing:String,f:com.rayren.android.data.FetchResult) {
        val current=mutable.value.data
        val previous=if(f.result==null)old else PersonalSubscription.previous(current,url,f.result.accountId,old)
        val entry=Subscription(previous?.id ?: stableId(url),url,f.result?.profiles?.map{it.id} ?: previous!!.profileIds,f.etag,f.modified,System.currentTimeMillis(),f.result?.revision ?: previous?.revision ?: 0,signing,previous?.name ?: if(f.result?.accountId!=null)"Мой Rayren"else f.name,previous?.pinned ?: false,f.result?.accountId ?: previous?.accountId)
        val updated=SubscriptionUpdate.apply(current,entry.copy(excludeAuto=previous?.excludeAuto ?: false,excludeSmart=previous?.excludeSmart ?: false,reservePolicy=previous?.reservePolicy),f.result?.profiles)
        commit(updated)
        val c=mutable.value.connection
        if(c.phase !in setOf(Phase.IDLE,Phase.STOPPING)&&c.profileId.isNotEmpty()&&updated.profiles.none{it.id==c.profileId}){
            cancelProbes()
            context.startService(Intent(context,RayrenVpnService::class.java).setAction(RayrenVpnService.RESELECT))
        }
    }

    suspend fun refresh(force:Boolean=false,id:String?=null):Boolean {
        awaitLoaded()
        return lock.withLock {
            mutable.update{it.copy(busy=true)}
            var success=true
            try { mutable.value.data.subscriptions.filter{id==null||it.id==id}.forEach { source ->
                try {applyFetch(source.url,source,source.signingKey,subscriptions.fetch(source.url,if(force)source.copy(etag="",modified="")else source,source.signingKey));event("REFRESH_OK")}
                catch(e:Exception){success=false;event("REFRESH_FAILED_${importFailureCode(e)}_PREVIOUS_RETAINED")}
            } } finally {mutable.update{it.copy(busy=false)}}
            success
        }
    }
    fun pinSubscription(id:String,pinned:Boolean)=scope.launch{lock.withLock{
        try{awaitLoaded();val d=mutable.value.data;commit(d.copy(subscriptions=d.subscriptions.map{if(it.id==id)it.copy(pinned=pinned)else it}))}catch(_:Exception){message("Не удалось закрепить подписку")}
    }}
    fun selectionSource(id:String,auto:Boolean,smart:Boolean,reserve:ReservePolicy)=scope.launch{lock.withLock{
        try{
            awaitLoaded();val d=mutable.value.data
            ensure(mutable.value.connection.phase!=Phase.STOPPING,"Дождитесь отключения VPN")
            ensure(id=="manual"||d.subscriptions.any{it.id==id},"Подписка больше недоступна")
            val next=if(id=="manual")d.copy(preferences=d.preferences.copy(excludeManualAuto=!auto,excludeManualSmart=!smart))
                else d.copy(subscriptions=d.subscriptions.map{if(it.id==id)it.copy(excludeAuto=!auto,excludeSmart=!smart,reservePolicy=reserve)else it})
            if(next==d)return@withLock
            commit(next)
            val c=mutable.value.connection
            if(c.phase!=Phase.IDLE&&d.preferences.mode!=Mode.MANUAL){
                cancelProbes();context.startService(Intent(context,RayrenVpnService::class.java).setAction(RayrenVpnService.RESELECT))
            }
            message("Выбор подписок сохранён")
        }catch(e:Exception){message(if(e is ValidationException)e.reason else "Не удалось сохранить выбор подписок")}
    }}
    fun removeSubscription(id:String)=scope.launch{lock.withLock{
        try{
            awaitLoaded();ensure(mutable.value.connection.phase!=Phase.STOPPING,"Дождитесь отключения VPN")
            val updated=SubscriptionUpdate.remove(mutable.value.data,id)
            commit(updated);cancelProbes()
            val remaining=updated.profiles.map{it.id}.toSet()
            mutable.update{it.copy(probes=it.probes.filterKeys{key->key in remaining},latencies=it.latencies.filterKeys{key->key in remaining})}
            val c=mutable.value.connection
            if(c.phase!=Phase.IDLE&&(c.profileId !in remaining||c.phase==Phase.BLOCKED)){
                context.startService(Intent(context,RayrenVpnService::class.java).setAction(RayrenVpnService.RESELECT))
            }
            message("Подписка удалена")
        }catch(e:Exception){message(if(e is ValidationException)e.reason else "Не удалось удалить подписку")}
    }}
    fun editSubscription(id:String,name:String,url:String,onSaved:()->Unit)=scope.launch{lock.withLock{
        try{
            awaitLoaded()
            val current=mutable.value.data
            val normalized=SubscriptionUpdate.editedUrl(current,id,url)
            val old=current.subscriptions.first{it.id==id}
            val edited=old.copy(name=ServerGroups.title(name))
            if(normalized==old.url){commit(current.copy(subscriptions=current.subscriptions.map{if(it.id==id)edited else it}))}
            else {
                mutable.update{it.copy(busy=true)}
                // A new URL has independent validators/revision. Keep the trusted signing key.
                val fetched=subscriptions.fetch(normalized,null,old.signingKey)
                applyFetch(normalized,edited,old.signingKey,fetched)
            }
            message("Подписка сохранена")
            withContext(Dispatchers.Main){onSaved()}
        }catch(e:Exception){message(if(e is ValidationException)e.reason else "Не удалось проверить новую ссылку. Прежняя подписка сохранена.");event("SUBSCRIPTION_EDIT_FAILED_${importFailureCode(e)}")}
        finally{mutable.update{it.copy(busy=false)}}
    }}
    fun refreshSubscription(id:String)=scope.launch{try{message(if(refresh(force=true,id=id))"Подписка обновлена"else "Не удалось обновить подписку. Прежние серверы сохранены.")}catch(_:Exception){message("Не удалось обновить подписку. Прежние серверы сохранены.")}}
    fun refreshNow()=scope.launch{try{message(if(refresh(force=true))"Подписки обновлены" else "Часть подписок недоступна. Предыдущие профили сохранены.")}catch(_:Exception){message("Не удалось обновить подписки. Данные сохранены.")}}
    fun save(preferences:Preferences)=scope.launch{lock.withLock{
        try{
            awaitLoaded()
            ensure(mutable.value.connection.phase==Phase.IDLE||ServerList.canApplyWhileConnected(mutable.value.data.preferences,preferences),"Для изменения подключения или обхода сначала отключите VPN. Настройки списка можно менять при работающем VPN.")
            PolicyCompiler.validate(preferences)
            commit(mutable.value.data.copy(preferences=preferences));schedule(preferences.refreshHours);message("Настройки сохранены")
        }catch(e:Exception){message(if(e is ValidationException)e.reason else "Настройки не сохранены")}
    }}
    private fun routeChange(change:(VaultData)->Preferences)=scope.launch {
        cancelProbes()
        if(mutable.value.probing)message("Завершаем текущую проверку и переключаем сервер…")
        probeGate.withLock {lock.withLock {
            try{
                awaitLoaded()
                ensure(mutable.value.connection.phase!=Phase.STOPPING,"Дождитесь отключения VPN")
                val d=mutable.value.data;val p=change(d);PolicyCompiler.validate(p)
                if(p==d.preferences&&mutable.value.connection.phase!=Phase.BLOCKED)return@withLock
                commit(d.copy(preferences=p))
                if(mutable.value.connection.phase!=Phase.IDLE){context.startService(Intent(context,RayrenVpnService::class.java).setAction(RayrenVpnService.RESELECT));message("Переключаем маршрут…")}
            }catch(e:Exception){message(if(e is ValidationException)e.reason else "Не удалось выбрать сервер")}
        }}
    }
    fun choose(id:String){routeChange{d->RouteChoice.select(d.preferences,d.profiles.find{it.id==id} ?: throw ValidationException("Сервер больше недоступен"))}}
    fun chooseMode(mode:Mode){routeChange{d->RouteChoice.mode(d.preferences,mode,d.profiles,mutable.value.connection.profileId)}}
    fun chooseRegion(region:String){routeChange{d->d.preferences.copy(mode=Mode.AUTO,region=region)}}
    fun remove(id:String)=scope.launch{lock.withLock{
        try{awaitLoaded();val c=mutable.value.connection;commit(ServerList.remove(mutable.value.data,id,if(c.phase==Phase.IDLE)""else c.profileId));message("Профиль удалён")}
        catch(e:Exception){message(if(e is ValidationException)e.reason else "Не удалось удалить профиль")}
    }}
    private fun schedule(hours:Int){
        if(mutable.value.data.preferences.disableAutoRefresh){WorkManager.getInstance(context).cancelUniqueWork("rayren-subscriptions");return}
        val request=PeriodicWorkRequestBuilder<RefreshWorker>(hours.toLong().coerceIn(1,168),TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("rayren-subscriptions",ExistingPeriodicWorkPolicy.UPDATE,request)
    }
    private var lastOpenRefresh=0L
    suspend fun serversOpened(){
        try {
            awaitLoaded()
            if(mutable.value.data.preferences.refreshOnOpen&&System.currentTimeMillis()-lastOpenRefresh>300000){
                lastOpenRefresh=System.currentTimeMillis();refresh()
            }
        } catch(e:CancellationException){throw e}
        catch(_:Exception){message("Не удалось обновить подписки при открытии")}
    }
    fun pingVisible(ids:Set<String>){
        if(!mutable.value.data.preferences.pingOnOpen||mutable.value.probing)return
        val now=System.currentTimeMillis()
        val stale=ids.filter{mutable.value.probes[it]?.let{p->now-p.at<300000}!=true}.toSet()
        if(stale.isNotEmpty())ping(ids=stale)
    }
    fun diagnostics():String {
        val s=mutable.value;val c=s.connection;val p=s.data.profiles.find{it.id==c.profileId}
        return buildString {
            appendLine("Rayren ${BuildConfig.VERSION_NAME}")
            appendLine("Android ${android.os.Build.VERSION.RELEASE} / API ${android.os.Build.VERSION.SDK_INT}")
            appendLine("VPN: ${c.phase}; engine: ${p?.engine ?: "none"}; mode: ${s.data.preferences.mode}")
            appendLine("Network: ${c.network}; Private DNS: ${c.privateDns}")
            appendLine("DNS: ${s.data.preferences.dnsMode}; strict: ${s.data.preferences.strictDns}")
            appendLine("Routing: ${s.data.preferences.preset}; IPv6: ${s.data.preferences.ipv6}; MTU: ${if(s.data.preferences.autoMtu)1280 else s.data.preferences.mtu}")
            appendLine("Latency: ${c.latency ?: "unknown"}; bytes up/down: ${c.up}/${c.down}")
            appendLine("Profile URLs, keys, server addresses and exit IP intentionally omitted.")
            s.events.forEach{appendLine(it)}
        }
    }
}

private fun importFailureCode(e:Exception):String = when(e) {
    is ValidationException -> e.code
    is javax.net.ssl.SSLException -> "TLS"
    is java.net.SocketTimeoutException -> "TIMEOUT"
    is java.io.InterruptedIOException -> "TIMEOUT"
    is java.io.IOException -> "NETWORK"
    else -> "INTERNAL"
}

class RefreshWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result = try{if(applicationContext.rayren.refresh())Result.success()else Result.retry()}catch(_:Exception){Result.retry()}
}
