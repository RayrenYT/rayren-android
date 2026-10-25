package com.rayren.domain

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class PersonalSubscriptionTest {
    private val account="a".repeat(32)
    private val url="https://rayren.icu/incy/rayren-app/"+"x".repeat(43)
    private val node=ImportParser.parse(VLESS).profiles.single()
    private val flux="openflux://"+Base64.getEncoder().encodeToString("""{"transport":"cupsonline","url":"https://interview.cups.online/test","key":"example-key-not-real-1234"}""".toByteArray())
    private fun data(main:Boolean=true,bs:Boolean=true)=Json.obj("format" to "rayren-account-v1","version" to 1,"account_id" to account,
        "documents" to if(main)listOf(Json.obj("outbounds" to listOf(node.config)))else emptyList<Any>(),"reserve_profiles" to if(bs)listOf(flux)else emptyList<String>())
    @Test fun mainAndCupsImportTogetherAndReservePolicyApplies(){
        val result=ImportParser.parse(data().toString())
        assertEquals(account,result.accountId)
        assertEquals(setOf(Engine.XRAY,Engine.OPENFLUX),result.profiles.map{it.engine}.toSet())
        assertTrue(result.profiles.last().name.startsWith("БС"))
        assertEquals(Engine.XRAY,Selection().candidates(result.profiles,Preferences(),"wifi",1).first().engine)
    }
    @Test fun userWithoutCupsGetsOnlyMain(){assertEquals(1,ImportParser.parse(data(bs=false).toString()).profiles.size)}
    @Test fun explicitExpiredAccountCanBeEmptyButOrdinaryEmptyFeedIsRejected(){
        assertTrue(ImportParser.parse(data(false,false).toString()).profiles.isEmpty())
        bad{ImportParser.parse("[]")}
        bad{ImportParser.parse(data(false,true).toString())}
    }
    @Test fun malformedReserveDoesNotPartiallyImportMain(){
        bad{ImportParser.parse(data().apply{add("reserve_profiles",Json.gson.toJsonTree(listOf("openflux://broken")))}.toString())}
        bad{ImportParser.parse(data().apply{add("reserve_profiles",Json.gson.toJsonTree(listOf(VLESS)))}.toString())}
    }
    @Test fun accountMustHaveValidStableIdentityAndVersion(){
        bad{ImportParser.parse(data().apply{addProperty("account_id","another user's name")}.toString())}
        bad{ImportParser.parse(data().apply{addProperty("version",2)}.toString())}
    }
    @Test fun personalLinksAcceptOnlyExactHttpsOriginAndPath(){
        assertTrue(PersonalSubscription.isUrl(url))
        for(value in listOf(url.replace("https:","http:"),url.replace("rayren.icu","evil.example"),url+"?user=1",url+"#x",url.replace("rayren.icu","rayren.icu:443")))assertFalse(value,PersonalSubscription.isUrl(value))
    }
    @Test fun rotatedLinkUpdatesSameAccountAndDoesNotOverwriteAnother(){
        val old=Subscription("personal",url,listOf(node.id),name="My name",pinned=true,accountId=account)
        val other=old.copy(id="other",accountId="b".repeat(32),url=url.replace('x','z'))
        val vault=VaultData(listOf(node),listOf(old,other))
        assertEquals(old,PersonalSubscription.previous(vault,url.replace('x','y'),account,null))
        bad{PersonalSubscription.previous(vault,"https://example.com/feed",account,null)}
        bad{PersonalSubscription.previous(vault,url,"b".repeat(32),old)}
        bad{PersonalSubscription.previous(vault,url,null,old)}
    }
    @Test fun losingCupsRemovesOnlyAccountOwnedNodesAndKeepsOtherSubscriptions(){
        val result=ImportParser.parse(data().toString());val personal=Subscription("personal",url,result.profiles.map{it.id},accountId=account)
        val manual=node.copy(id="manual")
        val other=Subscription("other","https://example.com/other",listOf(node.id))
        val vault=VaultData(result.profiles+manual,listOf(personal,other))
        val changed=SubscriptionUpdate.apply(vault,personal.copy(profileIds=emptyList()),emptyList())
        assertEquals(setOf(node.id,manual.id),changed.profiles.map{it.id}.toSet())
        assertEquals(other,changed.subscriptions.last())
    }
    private fun bad(block:()->Unit){try{block();fail("Accepted invalid input")}catch(_:ValidationException){}}
}
