package com.rayren.domain

object ServerList {
    const val PAGE_SIZE=50
    fun ordered(profiles:List<Profile>,probes:Map<String,ProbeResult>,byPing:Boolean):List<Profile> =
        if(!byPing)profiles else profiles.sortedWith(compareBy<Profile>{
            val result=probes[it.id];if(result?.state==ProbeState.OK)0 else if(result?.state==ProbeState.FAILED)2 else 1
        }.thenBy{probes[it.id]?.takeIf{p->p.state==ProbeState.OK}?.ms ?: Long.MAX_VALUE})
    fun pages(count:Int)=((count+PAGE_SIZE-1)/PAGE_SIZE).coerceAtLeast(1)
    fun page(profiles:List<Profile>,index:Int)=profiles.drop(index.coerceIn(0,pages(profiles.size)-1)*PAGE_SIZE).take(PAGE_SIZE)
    fun canApplyWhileConnected(old:Preferences,next:Preferences)=old.copy(
        sortServersByPing=next.sortServersByPing,pingOnOpen=next.pingOnOpen,refreshOnOpen=next.refreshOnOpen,
        disableAutoRefresh=next.disableAutoRefresh,refreshHours=next.refreshHours)==next
    fun remove(data:VaultData,id:String,activeId:String):VaultData {
        ensure(id!=activeId,"Сначала переключитесь на другой сервер или отключите VPN")
        return data.copy(profiles=data.profiles.filter{it.id!=id},
            subscriptions=data.subscriptions.map{it.copy(profileIds=it.profileIds-id)},
            preferences=if(data.preferences.selectedId==id)data.preferences.copy(selectedId="")else data.preferences)
    }
}
