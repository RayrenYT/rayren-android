package com.rayren.domain

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class Followup018Test {
    private val a=ImportParser.parse(VLESS).profiles.single()
    private val b=a.copy(id="b",name="Other")
    private val manual=a.copy(id="manual-node")
    private val one=Subscription("one","https://one.example/private",listOf(a.id),name="Ordinary")
    private val two=Subscription("two","https://two.example/private",listOf(b.id))
    private fun lines(count:Int)=(1..count).joinToString("\n"){"vless://11111111-1111-4111-8111-111111111111@n$it.example:443?security=none#Server$it"}

    @Test fun importsFiveThousandUrisAndBase64WithoutLosingLastPage(){
        val plain=lines(5000)
        for(text in listOf(plain,Base64.getEncoder().encodeToString(plain.toByteArray()))){
            val rows=ImportParser.parse(text).profiles
            assertEquals(5000,rows.size);assertEquals(100,ServerList.pages(rows.size))
            assertEquals("Server5000",ServerList.page(rows,99).last().name)
        }
    }
    @Test fun importsLargeJsonAndKeepsAllMembersOfOversizedBalancer(){
        val outbounds=(1..1200).map{a.config.deepCopy().apply{
            addProperty("tag","node-$it");getAsJsonObject("settings").getAsJsonArray("vnext")[0].asJsonObject.addProperty("address","n$it.example")
        }}
        val document=Json.obj("outbounds" to outbounds,"routing" to Json.obj("balancers" to listOf(Json.obj("selector" to listOf("node-")))))
        val parsed=ImportParser.parse(document.toString())
        assertEquals(1200,parsed.profiles.size);assertFalse(parsed.profiles.any{it.isBalancer})
        assertTrue(parsed.warnings.single().contains("Большие балансировщики"))
        assertEquals(1200,ImportParser.parse(Json.gson.toJson(outbounds)).profiles.size)
    }
    @Test fun countsAndBytesRemainBounded(){
        bad{ImportParser.parse(lines(5001))}
        bad{ImportInputResolver.resolve("x".repeat(ImportLimits.MAX_BYTES+1))}
        bad{ImportParser.parse("я".repeat(ImportLimits.MAX_BYTES/2+1))}
    }
    @Test fun deletionPreservesSharedAndManualProfilesAndOtherSourceOptions(){
        val original=VaultData(listOf(a,b,manual),listOf(one,two.copy(profileIds=listOf(a.id,b.id),excludeAuto=true,pinned=true)))
        val next=SubscriptionUpdate.remove(original,one.id)
        assertEquals(original.profiles,next.profiles);assertEquals(listOf(original.subscriptions[1]),next.subscriptions)
        assertEquals(2,original.subscriptions.size)
        assertEquals(listOf(manual),SubscriptionUpdate.remove(next,two.id).profiles)
    }
    @Test fun deletingSelectedSourceResetsSelectionAndLastDeletionIsValid(){
        val original=VaultData(listOf(a),listOf(one),Preferences(mode=Mode.MANUAL,selectedId=a.id))
        val next=SubscriptionUpdate.remove(original,one.id)
        assertTrue(next.profiles.isEmpty());assertTrue(next.subscriptions.isEmpty())
        assertEquals(Mode.AUTO,next.preferences.mode);assertEquals("",next.preferences.selectedId)
        bad{SubscriptionUpdate.remove(original,"absent")}
    }
    @Test fun eachModeUsesOnlyItsEnabledSourcesAndManualRemainsAvailable(){
        val data=VaultData(listOf(a,b,manual),listOf(one.copy(excludeAuto=true),two.copy(excludeSmart=true)),Preferences(excludeManualAuto=true,excludeManualSmart=true))
        val s=Selection()
        assertEquals(listOf(b),s.candidates(data.profiles,data.preferences,"wifi",1000,data.subscriptions))
        assertEquals(listOf(a),s.candidates(data.profiles,data.preferences.copy(mode=Mode.SMART),"wifi",1000,data.subscriptions))
        assertEquals(listOf(a),s.candidates(data.profiles,data.preferences.copy(mode=Mode.MANUAL,selectedId=a.id),"wifi",1000,data.subscriptions))
        assertEquals(listOf(b),s.scanCandidates(data.profiles,data.preferences,"wifi",1000,subscriptions=data.subscriptions))
    }
    @Test fun allSourcesOffNeverSilentlyFallsBackAndSharedEnabledOwnerWins(){
        val data=VaultData(listOf(a,b),listOf(one.copy(excludeAuto=true),two.copy(excludeAuto=true)))
        assertTrue(Selection().candidates(data.profiles,data.preferences,"wifi",1000,data.subscriptions).isEmpty())
        val shared=two.copy(profileIds=listOf(a.id,b.id))
        assertEquals(setOf(a.id,b.id),SelectionSources.ids(data.copy(subscriptions=listOf(one.copy(excludeAuto=true),shared))))
    }
    @Test fun unlabelledReserveCanBeMarkedAndOnlyFollowsOrdinaryFailure(){
        val reserved=two.copy(reservePolicy=ReservePolicy.RESERVE)
        assertEquals(setOf(b.id),ReserveRoutes.ids(listOf(a,b),listOf(one,reserved)))
        val s=Selection()
        assertEquals(a,s.candidates(listOf(b,a),Preferences(),"wifi",1000,listOf(one,reserved)).first())
        s.failure("wifi",a.id,1000)
        assertEquals(b,s.candidates(listOf(b,a),Preferences(),"wifi",1001,listOf(one,reserved)).first())
        assertEquals(a,s.candidates(listOf(b,a),Preferences(),"cellular",1001,listOf(one,reserved)).first())
    }
    @Test fun regularOverrideCorrectsDetectionButExplicitReserveSharedOwnerWins(){
        val cups=a.copy(engine=Engine.OPENFLUX,name="БС Cups")
        val regular=one.copy(reservePolicy=ReservePolicy.REGULAR)
        assertTrue(ReserveRoutes.ids(listOf(cups),listOf(regular)).isEmpty())
        assertEquals(setOf(a.id),ReserveRoutes.ids(listOf(cups),listOf(regular,two.copy(profileIds=listOf(a.id),reservePolicy=ReservePolicy.RESERVE))))
    }
    @Test fun oldRecordsDefaultToIncludedAndNewOptionsSurviveRoundTrip(){
        val old=Json.gson.fromJson("""{"id":"old","url":"https://example.com","profileIds":[]}""",Subscription::class.java)
        assertFalse(old.excludeAuto);assertFalse(old.excludeSmart);assertNull(old.reservePolicy)
        val data=VaultData(listOf(a),listOf(one.copy(excludeAuto=true,reservePolicy=ReservePolicy.RESERVE,accountId="a".repeat(32),pinned=true)),Preferences(excludeManualSmart=true))
        assertEquals(data,Json.gson.fromJson(Json.gson.toJson(data),VaultData::class.java))
        val next=SubscriptionUpdate.apply(data,data.subscriptions.single().copy(etag="new"),null)
        assertTrue(next.subscriptions.single().excludeAuto);assertEquals(ReservePolicy.RESERVE,next.subscriptions.single().reservePolicy)
    }
    @Test fun statusIsFreshScopedAndRecovers(){
        assertEquals(RouteState.UNKNOWN,RouteEvidence.state(null,1000))
        assertEquals(RouteState.FAILED,RouteEvidence.state(ProbeResult(ProbeState.FAILED,at=1000),1001))
        assertEquals(RouteState.HEALTHY,RouteEvidence.state(ProbeResult(ProbeState.OK,34,1002),1003))
        assertEquals(RouteState.UNKNOWN,RouteEvidence.state(ProbeResult(ProbeState.OK,34,1002),400000))
        assertEquals(RouteState.UNKNOWN,RouteEvidence.state(ProbeResult(ProbeState.OK,34,2000),1000))
    }
    @Test fun explanationContainsOnlyRealEvidenceNoProfileSecrets(){
        val secret=a.copy(name="https://private.example/token-secret")
        val s=Selection()
        assertEquals(DecisionConfidence.LOW,s.explain(secret,listOf(secret),Preferences(),"wifi",5000,listOf(one)).confidence)
        s.success("wifi",a.id,listOf(300),1000)
        val decision=s.explain(secret,listOf(secret),Preferences(),"wifi",5000,listOf(one))
        assertEquals(a.id,decision.selectedRouteId);assertEquals(300L,decision.latencyMs)
        assertTrue(DecisionReason.HTTPS_SUCCESS in decision.reasons)
        assertFalse(decision.toString().contains("private.example"));assertFalse(decision.toString().contains("token-secret"))
        assertNull(s.explain(secret,listOf(secret),Preferences(),"new-network",6000,listOf(one)).latencyMs)
    }
}
