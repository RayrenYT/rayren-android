package com.rayren.domain

import kotlin.random.Random

data class Health(val latency:Double = 500.0,val failures:Int = 0,val cooldownUntil:Long = 0,val lastSuccess:Long = 0,val connectMs:Long = 0)

class Selection(private val random:Random=Random.Default) {
    private val health=mutableMapOf<String,Health>()
    private fun key(network:String,id:String)="$network:$id"
    fun get(network:String,id:String)=health[key(network,id)] ?: Health()
    fun success(network:String,id:String,samples:List<Long>,now:Long,connectMs:Long=0):Health {
        require(samples.isNotEmpty()&&samples.all{it>=0})
        val median=samples.sorted()[samples.size/2].toDouble()
        val previous=get(network,id)
        return Health(if(previous.lastSuccess==0L)median else previous.latency*0.65+median*0.35,0,0,now,connectMs).also{health[key(network,id)]=it}
    }
    fun failure(network:String,id:String,now:Long):Health {
        val old=get(network,id); val count=(old.failures+1).coerceAtMost(8)
        return old.copy(failures=count,cooldownUntil=now+(15000L shl (count-1)).coerceAtMost(300000)).also{health[key(network,id)]=it}
    }
    fun candidates(profiles:List<Profile>,p:Preferences,network:String,now:Long,subscriptions:List<Subscription> = emptyList()):List<Profile> {
        val eligible=profiles.filter{(!it.experimental||p.experimental)&&(p.region.isEmpty()||it.region.equals(p.region,true))}
        if(p.mode==Mode.MANUAL)return eligible.filter{it.id==p.selectedId}
        val allowed=SelectionSources.ids(profiles,subscriptions,p)
        val automatic=eligible.filter{it.id in allowed}
        val untestedEngines=automatic.groupBy{it.engine}.filterValues{rows->rows.all{get(network,it.id).let{h->h.failures==0&&h.lastSuccess==0L}}}.keys
        val reserves=if(p.mode==Mode.AUTO)ReserveRoutes.ids(profiles,subscriptions)else emptySet()
        val available=automatic.filter{get(network,it.id).cooldownUntil<=now}
        val ordered=if(p.mode==Mode.AUTO)available.shuffled(random)else available
        return ordered.sortedWith(compareBy<Profile>{profile->
            // Once each ordinary node has failed on this network, try reserves even
            // if an earlier node's cooldown elapsed during a long subscription scan.
            if(p.mode!=Mode.AUTO)0 else if(profile.id in reserves)1 else if(get(network,profile.id).failures==0)0 else 2
        }.thenBy{profile->
            val h=get(network,profile.id)
            when{h.lastSuccess>0&&h.failures==0->0;profile.engine in untestedEngines->1;else->2}
        }.thenBy{if(p.mode==Mode.AUTO)0.0 else score(it,p.mode,get(network,it.id))})
    }
    fun scanCandidates(profiles:List<Profile>,p:Preferences,network:String,now:Long,limit:Int=12,subscriptions:List<Subscription> = emptyList()):List<Profile> {
        val ranked=candidates(profiles,p,network,now,subscriptions)
        if(p.mode==Mode.AUTO)return ranked.take(limit)
        return (ranked.distinctBy{it.engine}+ranked).distinctBy{it.id}.take(limit)
    }
    fun nextRetry(profiles:List<Profile>,network:String,now:Long):Long = (profiles.map{get(network,it.id).cooldownUntil}.filter{it>now}.minOrNull()?.minus(now) ?: 15000L).coerceIn(1000,300000)
    fun explain(selected:Profile,profiles:List<Profile>,p:Preferences,network:String,recordedAt:Long,subscriptions:List<Subscription>):ExplainableDecision {
        val allowed=SelectionSources.ids(profiles,subscriptions,p)
        val eligible=profiles.filter{it.id in allowed&&(!it.experimental||p.experimental)&&(p.region.isBlank()||it.region.equals(p.region,true))}
        val reserves=ReserveRoutes.ids(profiles,subscriptions)
        val ordinary=eligible.filter{it.id !in reserves}
        val h=get(network,selected.id)
        val reasons=buildList {
            if(h.lastSuccess>0&&h.failures==0)add(DecisionReason.HTTPS_SUCCESS)
            add(when(p.mode){Mode.MANUAL->DecisionReason.MANUAL;Mode.SMART->DecisionReason.PING_ORDER;Mode.AUTO->DecisionReason.AUTO_ORDER})
            if(p.mode==Mode.AUTO){
                if(selected.id in reserves){
                    if(ordinary.isEmpty())add(DecisionReason.ONLY_RESERVES)
                    else if(ordinary.all{get(network,it.id).failures>0})add(DecisionReason.RESERVE_FALLBACK)
                }else if(eligible.any{it.id in reserves})add(DecisionReason.RESERVE_NOT_NEEDED)
            }
            add(DecisionReason.LIMITED_EVIDENCE)
        }
        return ExplainableDecision(selected.id,reasons,h.latency.toLong().takeIf{h.lastSuccess>0&&h.failures==0},recordedAt,
            if(h.lastSuccess>0&&h.failures==0)DecisionConfidence.MEDIUM else DecisionConfidence.LOW,
            eligible.count{it.id!=selected.id&&get(network,it.id).failures>0})
    }
    fun shouldSwitch(current:Profile,next:Profile,mode:Mode,network:String,now:Long,connectedAt:Long):Boolean {
        val currentHealth=get(network,current.id)
        if(currentHealth.failures>=2)return true
        if(now-connectedAt<60000)return false
        return score(next,mode,get(network,next.id))<score(current,mode,currentHealth)*0.8
    }
    private fun score(p:Profile,mode:Mode,h:Health):Double = h.latency+h.failures*1500+h.connectMs*0.05-p.priority*5+if(mode==Mode.AUTO)when(p.engine){Engine.XRAY->0.0;Engine.OPENFLUX->350.0;Engine.OLCRTC->1000.0}else 0.0
}

object ReserveRoutes {
    private val label=Regex("(?iu)(?:^|[^a-zа-я0-9])(?:бс|bs|cups|openflux|whitelist|white[ _-]*list|белые[ _-]+списки)(?:$|[^a-zа-я0-9])")
    fun ids(profiles:List<Profile>,subscriptions:List<Subscription> = emptyList()):Set<String> {
        val forcedReserve=subscriptions.filter{it.reservePolicy==ReservePolicy.RESERVE}.flatMap{it.profileIds}.toSet()
        val forcedRegular=subscriptions.filter{it.reservePolicy==ReservePolicy.REGULAR}.flatMap{it.profileIds}.toSet()-forcedReserve
        val namedSubscriptions=subscriptions.filter{it.reservePolicy!=ReservePolicy.REGULAR&&label.containsMatchIn(it.name.orEmpty())}.flatMap{it.profileIds}.toSet()
        val direct=profiles.filter{it.id in forcedReserve || (it.id !in forcedRegular && (it.engine!=Engine.XRAY||it.id in namedSubscriptions||label.containsMatchIn(it.name)))}
        val memberConfigs=direct.filter{it.engine==Engine.XRAY}.flatMap{it.xrayOutbounds()}.map{it.toString()}.toSet()
        return direct.map{it.id}.toSet()+profiles.filter{it.id !in forcedRegular&&it.isBalancer&&it.xrayOutbounds().any{out->out.toString() in memberConfigs}}.map{it.id}
    }
}

object RouteRecovery {
    fun networkKey(network:String,generation:Long)="$network@$generation"
    fun enabled(p:Preferences)=p.mode!=Mode.MANUAL||p.reconnect
}

object Redaction {
    private val uri=Regex("(?i)(?:https?|vless|vmess|ss|trojan|hysteria2|hy2|openflux|olcrtc|rayren)://[^\\s]+")
    private val uuid=Regex("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b")
    private val key=Regex("(?i)(key|secret|token|password|uuid|authorization)([\\s\"':=]+)[^\\s,;]+")
    fun sanitize(text:String):String = key.replace(uuid.replace(uri.replace(text,"[URL скрыт]"),"[UUID скрыт]")){"${it.groupValues[1]}=[скрыто]"}.take(1024)
}
