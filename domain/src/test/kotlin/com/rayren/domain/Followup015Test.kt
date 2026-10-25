package com.rayren.domain

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class Followup015Test {
    private val node=ImportParser.parse(VLESS).profiles.single()
    @Test fun inactiveServerCanBeRemovedWithoutChangingActiveOrOtherSubscription(){
        val other=node.copy(id="other")
        val data=VaultData(listOf(node,other),listOf(Subscription("sub","https://example.com/s",listOf(node.id,other.id))))
        val result=ServerList.remove(data,other.id,node.id)
        assertEquals(listOf(node),result.profiles)
        assertEquals(listOf(node.id),result.subscriptions.single().profileIds)
        bad{ServerList.remove(data,node.id,node.id)}
    }
    @Test fun deletingIdleSelectionClearsIt(){assertEquals("",ServerList.remove(VaultData(listOf(node),preferences=Preferences(selectedId=node.id)),node.id,"").preferences.selectedId)}
    @Test fun autoRandomizesInsteadOfFollowingSubscriptionOrder(){
        val nodes=(1..20).map{node.copy(id="n$it")}
        val starts=(1..30).map{Selection(Random(it)).candidates(nodes,Preferences(),"wifi",1).first().id}.toSet()
        assertTrue(starts.size>5)
        assertEquals(20,Selection(Random(1)).candidates(nodes,Preferences(),"wifi",1).map{it.id}.distinct().size)
    }
    @Test fun repairedRu2AndItsBalancerParticipateInBothAutomaticModes(){
        val ru2=node.copy(id="ru2",name="🇷🇺 RU-2 · Finland",config=node.config.deepCopy().apply{addProperty("testMarker","ru2")})
        val balance=SmartBalance.create(listOf(node,ru2))
        val all=listOf(node,ru2,balance)
        for(mode in listOf(Mode.AUTO,Mode.SMART))assertEquals(all.toSet(),Selection().candidates(all,Preferences(mode=mode),"wifi",1).toSet())
        assertEquals(listOf(ru2),Selection().candidates(all,Preferences(mode=Mode.MANUAL,selectedId=ru2.id),"wifi",1))
    }
    @Test fun whitelistIsSeparateFromCountrySuffixes(){
        val domains=RussianBypass.domains(Preferences(bypassWhitelistSites=true))
        assertTrue("vk.com" in domains);assertTrue("yastatic.net" in domains)
        assertFalse("ru" in domains);assertFalse("xn--p1ai" in domains)
        assertFalse("example.ru" in domains);assertFalse("telegram.org" in domains)
        assertTrue(RussianBypass.domains(Preferences()).isEmpty())
    }
    @Test fun fiveHundredServersHaveTenBoundedPagesWithoutLoss(){
        val nodes=(1..500).map{node.copy(id="node$it",name="Server $it")}
        assertEquals(10,ServerList.pages(nodes.size))
        assertEquals(nodes,(0..9).flatMap{ServerList.page(nodes,it)})
        assertEquals(50,ServerList.page(nodes,999).size)
        assertTrue(Json.gson.toJson(VaultData(profiles=nodes)).toByteArray().size<4*1024*1024-64)
    }
    @Test fun realFiveHundredLineSubscriptionParses(){
        val uri=VLESS.substringBefore('#')
        // A different endpoint port produces distinct identity, not just a new display name.
        val sample=java.net.URI(uri)
        val lines=(10000..10499).joinToString("\n"){port->uri.replace(":${sample.port}",":$port")+"#Server$port"}
        assertEquals(500,ImportParser.parse(lines).profiles.size)
    }
    @Test fun pingSortKeepsUnknownAndFailedAtEndAndStableTies(){
        val nodes=(1..5).map{node.copy(id="$it")}
        val probes=mapOf("1" to ProbeResult(ProbeState.FAILED),"2" to ProbeResult(ProbeState.OK,400),"3" to ProbeResult(ProbeState.OK,100),"5" to ProbeResult(ProbeState.OK,100))
        assertEquals(listOf("3","5","2","4","1"),ServerList.ordered(nodes,probes,true).map{it.id})
        assertEquals(nodes,ServerList.ordered(nodes,probes,false))
    }
    @Test fun oldPreferencesKeepBackgroundRefreshAndNoMassPing(){
        val old=Json.gson.fromJson("""{"mode":"AUTO"}""",Preferences::class.java)
        assertFalse(old.disableAutoRefresh);assertFalse(old.pingOnOpen);assertFalse(old.bypassWhitelistSites)
    }
    @Test fun listSettingsCanChangeWhileVpnRunsButTunnelSettingsCannot(){
        val old=Preferences()
        assertTrue(ServerList.canApplyWhileConnected(old,old.copy(sortServersByPing=true,pingOnOpen=true,refreshOnOpen=true,disableAutoRefresh=true,refreshHours=24)))
        assertFalse(ServerList.canApplyWhileConnected(old,old.copy(bypassWhitelistSites=true)))
        assertFalse(ServerList.canApplyWhileConnected(old,old.copy(timeoutMs=3000)))
    }
    @Test fun whitelistDnsIsScopedAndProbeStillUsesProxy(){
        val config=Json.parse(PolicyCompiler.compile(node,Preferences(bypassWhitelistSites=true),Listener(10808,10809,10810,"testuser","testpassword"),systemDns="192.0.2.53")).asJsonObject
        val dns=config.getAsJsonObject("dns").getAsJsonArray("servers")
        assertEquals("dns-whitelist",dns.first().asJsonObject.text("tag"))
        assertEquals("192.0.2.53",dns.first().asJsonObject.text("address"))
        assertTrue(dns.first().asJsonObject.getAsJsonArray("domains").any{it.asString=="domain:vk.com"})
        assertTrue(dns.first().asJsonObject.get("skipFallback").asBoolean)
        assertEquals("https://1.1.1.1/dns-query",dns.last().asJsonObject.text("address"))
        val rules=config.getAsJsonObject("routing").getAsJsonArray("rules")
        assertEquals("proxy",rules.first().asJsonObject.text("outboundTag"))
        assertTrue(rules.any{it.asJsonObject.getAsJsonArray("inboundTag")?.any{tag->tag.asString=="dns-whitelist"}==true&&it.asJsonObject.text("outboundTag")=="direct"})
        java.io.File("build/native-fixtures").mkdirs()
        java.io.File("build/native-fixtures/whitelist.json").writeText(config.toString())
    }
}
