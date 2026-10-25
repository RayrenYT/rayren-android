package com.rayren.domain

import java.net.URI
import java.util.Base64

data class ServerGroup(val id:String,val name:String,val profiles:List<Profile>,val source:Subscription?=null)

object ServerGroups {
    fun title(raw:String?):String? {
        if(raw.isNullOrBlank())return null
        val decoded=if(raw.startsWith("base64:"))runCatching{String(Base64.getDecoder().decode(raw.removePrefix("base64:")),Charsets.UTF_8)}.getOrDefault("") else raw
        return decoded.filterNot{it.isISOControl()}.trim().take(80).takeIf{it.isNotBlank()}
    }
    fun list(data:VaultData):List<ServerGroup> {
        val byId=data.profiles.associateBy{it.id}
        val owned=data.subscriptions.flatMap{it.profileIds}.toSet()
        val groups=data.subscriptions.mapIndexed{i,s->
            val host=runCatching{URI(s.url).host}.getOrNull().orEmpty()
            ServerGroup(s.id,title(s.name) ?: "Подписка ${i+1}"+if(host.isBlank())"" else " · $host",s.profileIds.distinct().mapNotNull{byId[it]}.sortedByDescending{it.isBalancer},s)
        }
        val manual=data.profiles.filter{it.id !in owned}.sortedByDescending{it.isBalancer}
        return groups.sortedByDescending{it.source?.pinned==true}+if(manual.isEmpty())emptyList()else listOf(ServerGroup("manual","Добавлены вручную",manual))
    }
}

/** Merge only a successfully fetched subscription; unrelated/shared profiles remain owned. */
object SubscriptionUpdate {
    fun remove(current:VaultData,id:String):VaultData {
        val source=current.subscriptions.find{it.id==id} ?: throw ValidationException("Подписка больше недоступна")
        val others=current.subscriptions.filter{it.id!=id}
        val obsolete=source.profileIds.toSet()-others.flatMap{it.profileIds}.toSet()
        val profiles=current.profiles.filter{it.id !in obsolete}
        val preferences=if(current.preferences.selectedId in obsolete)current.preferences.copy(selectedId="",mode=Mode.AUTO)else current.preferences
        return current.copy(profiles=profiles,subscriptions=others,preferences=preferences)
    }
    fun editedUrl(current:VaultData,id:String,url:String):String {
        ensure(current.subscriptions.any{it.id==id},"Подписка больше недоступна")
        val normalized=ImportParser.subscriptionUrl(url)
        ensure(current.subscriptions.none{it.id!=id&&it.url==normalized},"Эта ссылка уже есть в другой подписке")
        return normalized
    }
    fun apply(current:VaultData,entry:Subscription,newProfiles:List<Profile>?):VaultData {
        val old=current.subscriptions.find{it.id==entry.id}
        ensure(newProfiles!=null||old?.url==entry.url,"Для новой ссылки нужна успешно загруженная подписка")
        val others=current.subscriptions.filter{it.id!=entry.id}
        val stillOwned=others.flatMap{it.profileIds}.toSet()
        val obsolete=if(newProfiles!=null)old?.profileIds.orEmpty().toSet()-stillOwned else emptySet()
        val profiles=(current.profiles.filter{it.id !in obsolete}+newProfiles.orEmpty()).distinctBy{it.id}
        val p=current.preferences
        val preferences=if(p.selectedId.isNotEmpty()&&profiles.none{it.id==p.selectedId})p.copy(selectedId=entry.profileIds.firstOrNull{id->profiles.any{it.id==id&&!it.experimental}} ?: profiles.firstOrNull{!it.experimental}?.id.orEmpty()) else p
        return current.copy(profiles=profiles,preferences=preferences,subscriptions=if(old==null)others+entry else current.subscriptions.map{if(it.id==entry.id)entry else it})
    }
}

/** Shared profiles are eligible if at least one owning source is enabled. Manual is separate. */
object SelectionSources {
    fun ids(data:VaultData,mode:Mode=data.preferences.mode):Set<String> = ids(data.profiles,data.subscriptions,data.preferences,mode)
    fun ids(profiles:List<Profile>,subscriptions:List<Subscription>,p:Preferences,mode:Mode=p.mode):Set<String> {
        if(mode==Mode.MANUAL)return profiles.map{it.id}.toSet()
        val owned=subscriptions.flatMap{it.profileIds}.toSet()
        val enabled=subscriptions.filter{if(mode==Mode.AUTO)!it.excludeAuto else !it.excludeSmart}.flatMap{it.profileIds}.toSet()
        val manualAllowed=if(mode==Mode.AUTO)!p.excludeManualAuto else !p.excludeManualSmart
        return enabled+if(manualAllowed)profiles.filter{it.id !in owned}.map{it.id}else emptyList()
    }
}

object SmartBalance {
    fun create(members:List<Profile>,name:String="Smart Balance"):Profile {
        val unique=members.distinctBy{it.id}
        ensure(unique.size in 2..32,"Smart Balance поддерживает от 2 до 32 серверов")
        ensure(unique.all{it.engine==Engine.XRAY&&!it.isBalancer},"Smart Balance поддерживает серверы Xray")
        val config=Json.obj("outbounds" to unique.map{it.config})
        return Profile(stableId("smart-balance:$config"),name,Engine.XRAY,"smart-balance",config=config)
    }
    fun endpoints(profile:Profile):List<String> = if(profile.engine!=Engine.XRAY)emptyList()else profile.xrayOutbounds().mapNotNull{out->
        val settings=out.getAsJsonObject("settings") ?: return@mapNotNull null
        val server=(settings.getAsJsonArray("vnext") ?: settings.getAsJsonArray("servers"))?.firstOrNull()?.asJsonObject ?: settings
        server.text("address").takeIf{it.isNotBlank()}
    }.distinct()
}
