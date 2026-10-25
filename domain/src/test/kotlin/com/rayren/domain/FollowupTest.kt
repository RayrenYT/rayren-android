package com.rayren.domain

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.Locale

class FollowupTest {
    private val first=ImportParser.parse("vless://$TEST_ID@127.0.0.1:17444?security=none#First").profiles.single()
    private val second=ImportParser.parse("vless://$TEST_ID@127.0.0.1:17445?security=none#Second").profiles.single()
    private val listener=Listener(10808,10809,10810,"testuser","testpassword",10811)
    private fun source(selectors:List<String> = listOf("node-"))=Json.obj("remarks" to "Smart Balance","outbounds" to listOf(first.config.deepCopy().apply{addProperty("tag","node-first")},second.config.deepCopy().apply{addProperty("tag","node-second")},Json.obj("protocol" to "freedom","tag" to "node-direct")),"routing" to Json.obj("balancers" to listOf(Json.obj("selector" to selectors,"fallbackTag" to "node-direct","strategy" to Json.obj("type" to "random")))))
    @Test fun keepsBalancerAndMembersWithoutImportingDirectFallback(){
        val parsed=ImportParser.parse(source().toString()).profiles
        assertEquals(3,parsed.size)
        val balance=parsed.single{it.isBalancer}
        assertEquals(2,balance.xrayOutbounds().size)
        assertEquals(listOf("node-first","node-second"),parsed.filterNot{it.isBalancer}.map{it.name})
        val config=Json.parse(PolicyCompiler.compile(balance,Preferences(),listener)).asJsonObject
        val routing=config.getAsJsonObject("routing")
        assertEquals("proxy",routing.getAsJsonArray("rules")[0].asJsonObject.text("balancerTag"))
        val balancer=routing.getAsJsonArray("balancers").single().asJsonObject
        assertEquals("leastPing",balancer.getAsJsonObject("strategy").text("type"))
        assertEquals("proxy-0",balancer.text("fallbackTag"))
        assertEquals(Preferences().probeUrl,config.getAsJsonObject("observatory").text("probeUrl"))
        File("build/native-fixtures").mkdirs()
        File("build/native-fixtures/smart-balance.json").writeText(config.toString())
    }
    @Test fun selectorOnlyUsesMatchedProxyMembers(){
        assertEquals(2,ImportParser.parse(source(listOf("node-first")).toString()).profiles.size)
        assertFalse(ImportParser.parse(source(listOf("absent")).toString()).profiles.any{it.isBalancer})
        bad{SmartBalance.create(listOf(first,first))}
        bad{SmartBalance.create(listOf(first,second.copy(engine=Engine.OPENFLUX)))}
    }
    @Test fun deduplicatedMembersDoNotRemoveBalancer(){
        val result=ImportParser.parse(Json.gson.toJsonTree(listOf(source(),first.config,second.config)).toString())
        assertEquals(3,result.profiles.size)
        assertEquals(listOf("127.0.0.1"),SmartBalance.endpoints(result.profiles.first()))
    }
    @Test fun xrayDomainsGoToRemoteWhileExternalEngineDnsStaysProtected(){
        fun strategy(p:Profile)=Json.parse(PolicyCompiler.compile(p,Preferences(),listener)).asJsonObject.getAsJsonArray("outbounds")[0].asJsonObject.text("targetStrategy")
        assertEquals("AsIs",strategy(first))
        assertEquals("ForceIPv4",strategy(first.copy(engine=Engine.OPENFLUX)))
        assertEquals("ForceIPv4",strategy(first.copy(engine=Engine.OLCRTC)))
    }
    @Test fun groupsPreserveSharedOwnershipAndManualProfilesWithoutLeakingTokens(){
        val subs=listOf(Subscription("a","https://host.example/secretToken",listOf(first.id)),Subscription("b","https://host.example/anotherSecret",listOf(first.id),name="Рабочая"))
        val groups=ServerGroups.list(VaultData(listOf(first,second),subs))
        assertEquals(3,groups.size)
        assertEquals(first,groups[0].profiles.single());assertEquals(first,groups[1].profiles.single())
        assertEquals(second,groups[2].profiles.single())
        assertFalse(groups.joinToString{it.name}.contains("secret",true))
        assertEquals("Рабочая",groups[1].name)
    }
    @Test fun olderSubscriptionAndHeaderNameRemainCompatible(){
        val old=Json.gson.fromJson("""{"id":"a","url":"https://example.com/private","profileIds":[],"etag":"","modified":"","lastSuccess":0,"revision":0,"signingKey":""}""",Subscription::class.java)
        assertNotNull(ServerGroups.list(VaultData(subscriptions=listOf(old))).single().name)
        assertEquals("Rayren",ServerGroups.title("base64:"+Base64.getEncoder().encodeToString("Rayren".toByteArray())))
        assertNull(ServerGroups.title("base64:???"))
    }
    @Test fun liveBridgeTextContainsVpnSpeedEvenWhenIdleAndKeepsServerAndPing(){
        val previous=Locale.getDefault()
        try{Locale.setDefault(Locale.forLanguageTag("ru-RU"))
            val text=VpnNotificationText.content("Финляндия","Пинг: 300 мс",true,0,2048)
            assertTrue(text.contains("VPN"));assertTrue(text.contains("Финляндия"));assertTrue(text.contains("300 мс"))
            assertTrue(text.contains("↓ 0.0 KB/s"));assertTrue(text.contains("↑ 2.0 KB/s"))
            assertFalse(VpnNotificationText.content("","Подключение",false,0,0).contains("KB/s"))
        }finally{Locale.setDefault(previous)}
    }
    @Test fun timingRejectsInvalidDataAndExportsOnlyDurations(){
        assertNull(ProbeTiming.parse("{}"));assertNull(ProbeTiming.parse("""{"totalMs":1,"connectMs":2,"tlsMs":0,"responseMs":0}"""))
        val timing=ProbeTiming.parse("""{"totalMs":333,"connectMs":1,"tlsMs":222,"responseMs":110,"url":"secret"}""")!!
        assertEquals(333L,timing.totalMs)
        assertFalse(timing.diagnostic().contains("secret"))
    }
    @Test fun freshBalancerCanRetryAfterInitialFallbackButOrdinaryProbeCannot(){
        var calls=0
        val ok="""{"totalMs":333,"connectMs":1,"tlsMs":222,"responseMs":110}"""
        val result=ProbeTiming.measure(true){calls++;if(calls==1)throw java.io.IOException("first unavailable") else ok}
        assertEquals(2,calls);assertEquals(333L,result.totalMs)
        calls=0
        try{ProbeTiming.measure(false){calls++;throw java.io.IOException()};fail("Expected failure")}catch(_:java.io.IOException){}
        assertEquals(1,calls)
        calls=0
        try{ProbeTiming.measure(true,1000){calls++;throw java.io.IOException()};fail("Expected failure")}catch(_:java.io.IOException){}
        assertTrue(calls in 2..8)
    }
}
