package com.rayren.domain

import org.junit.Assert.*
import org.junit.Test

class SelectionTest {
    private val xray=ImportParser.parse(VLESS).profiles.single()
    private val flux=xray.copy(id="flux",engine=Engine.OPENFLUX)
    private val rtc=xray.copy(id="rtc",engine=Engine.OLCRTC,experimental=true)
    private val profiles=listOf(rtc,flux,xray)
    private val preferences=Preferences(experimental=true)
    @Test fun autoOnlyReturnsEligibleCandidates(){assertEquals(profiles.toSet(),Selection().candidates(profiles,preferences,"wifi",1000).toSet())}
    @Test fun fallsBackToAlternativeTransport(){val s=Selection();s.failure("wifi",xray.id,1000);assertNotEquals(xray,s.candidates(profiles,preferences,"wifi",1001).first())}
    @Test fun fallsBackToRtcWhenEnabled(){val s=Selection();s.failure("wifi",xray.id,1000);s.failure("wifi",flux.id,1000);assertEquals(rtc,s.candidates(profiles,preferences,"wifi",1001).first());assertTrue(s.candidates(profiles,Preferences(),"wifi",1001).isEmpty())}
    @Test fun failureIsNetworkSpecific(){val s=Selection();s.failure("wifi",xray.id,1000);assertEquals(profiles.toSet(),s.candidates(profiles,preferences,"lte",1001).toSet())}
    @Test fun exponentialBackoffBounded(){val s=Selection();assertEquals(16000,s.failure("wifi",xray.id,1000).cooldownUntil);assertEquals(31000,s.failure("wifi",xray.id,1000).cooldownUntil);repeat(20){s.failure("wifi",xray.id,1000)};assertEquals(301000,s.get("wifi",xray.id).cooldownUntil)}
    @Test fun smartUsesRealMeasurements(){val s=Selection();s.success("wifi",xray.id,listOf(400,300,500),1000);s.success("wifi",flux.id,listOf(50,40,60),1000);assertEquals(flux,s.candidates(profiles,preferences.copy(mode=Mode.SMART),"wifi",1001).first())}
    @Test fun hysteresisAvoidsPingPong(){val s=Selection();s.success("wifi",xray.id,listOf(100),1000);s.success("wifi",flux.id,listOf(85),1000);assertFalse(s.shouldSwitch(xray,flux,Mode.SMART,"wifi",100000,0));s.success("wifi",flux.id,listOf(1),1001);assertFalse(s.shouldSwitch(xray,flux,Mode.SMART,"wifi",40000,0));assertTrue(s.shouldSwitch(xray,flux,Mode.SMART,"wifi",100000,0))}
    @Test fun manualNeverSwitchesUnexpectedly(){val s=Selection();assertEquals(listOf(flux),s.candidates(profiles,preferences.copy(mode=Mode.MANUAL,selectedId=flux.id),"wifi",1000))}
    @Test fun regionRestriction(){assertTrue(Selection().candidates(profiles,preferences.copy(region="NL"),"wifi",1000).isEmpty())}
    @Test fun mobileFallbackUsesCupsOnlyAfterAllOrdinaryNodesFailed(){
        val nodes=(1..40).map{xray.copy(id="x$it")}+flux
        val s=Selection();val wifi=RouteRecovery.networkKey("wifi",1);val mobile=RouteRecovery.networkKey("mobile",2)
        s.success(wifi,nodes[0].id,listOf(50),1000)
        assertEquals(nodes.toSet(),s.candidates(nodes,Preferences(),mobile,1001).toSet())
        s.failure(mobile,nodes[0].id,1002)
        assertNotEquals(flux,s.candidates(nodes,Preferences(),mobile,1003).first())
        nodes.drop(1).filter{it.engine==Engine.XRAY}.forEachIndexed{i,node->s.failure(mobile,node.id,2000L+i*1000)}
        // Several original cooldowns have expired; do not starve the reserve forever.
        assertEquals(flux,s.candidates(nodes,Preferences(),mobile,60000).first())
        s.success(mobile,flux.id,listOf(900),60001)
        assertEquals(flux,s.candidates(nodes,Preferences(),mobile,60002).first())
        // A new Wi-Fi generation is independent of both the old cache and mobile failures.
        assertEquals(nodes.toSet(),s.candidates(nodes,Preferences(),RouteRecovery.networkKey("wifi",3),30001).toSet())
    }
    @Test fun smartScanAlwaysIncludesAlternativeTransportWithinLimit(){
        val nodes=(1..40).map{xray.copy(id="x$it")}+flux+rtc
        val plan=Selection().scanCandidates(nodes,preferences.copy(mode=Mode.SMART),"mobile",1000)
        assertEquals(12,plan.size)
        assertEquals(setOf(Engine.XRAY,Engine.OPENFLUX,Engine.OLCRTC),plan.take(3).map{it.engine}.toSet())
        assertEquals(12,plan.map{it.id}.distinct().size)
    }
    @Test fun measuredSmartWinnerBeatsUnmeasuredNominalLatency(){
        val s=Selection();s.success("mobile",flux.id,listOf(1200),1000)
        assertEquals(flux,s.candidates(listOf(xray,flux),Preferences(mode=Mode.SMART),"mobile",1001).first())
    }
    @Test fun failedCupsDoesNotPreventTryingRemainingXrayNodes(){
        val other=xray.copy(id="second")
        val s=Selection();s.failure("mobile",xray.id,1000);s.failure("mobile",flux.id,1001)
        assertEquals(other,s.candidates(listOf(xray,other,flux),Preferences(),"mobile",1002).first())
    }
    @Test fun automaticRecoveryIsAlwaysEnabledAndManualChoiceIsPreserved(){
        assertTrue(RouteRecovery.enabled(Preferences(mode=Mode.AUTO,reconnect=false)))
        assertTrue(RouteRecovery.enabled(Preferences(mode=Mode.SMART,reconnect=false)))
        assertFalse(RouteRecovery.enabled(Preferences(mode=Mode.MANUAL,reconnect=false)))
        val s=Selection();s.failure("mobile",xray.id,1000)
        assertEquals(listOf(xray),s.candidates(listOf(xray,flux),Preferences(mode=Mode.MANUAL,selectedId=xray.id),"mobile",1001))
    }
    @Test fun changedNetworkGuardStopsBalancedRetryBeforeOldResultIsUsed(){
        var attempts=0;var changed=false
        try{ProbeTiming.measure(true,3000,guard={if(changed)throw IllegalStateException("network changed")}){attempts++;changed=true;throw java.io.IOException("old network failed")};fail("Expected route interruption")}
        catch(e:IllegalStateException){assertEquals("network changed",e.message)}
        assertEquals(1,attempts)
    }
}
