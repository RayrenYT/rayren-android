package com.rayren.domain

import org.junit.Assert.*
import org.junit.Test

class SubscriptionManagementTest {
    private val a=ImportParser.parse(VLESS).profiles.single()
    private val b=a.copy(id="other",name="Other")
    private val c=a.copy(id="new",name="New")
    private val one=Subscription("one","https://one.example/private",listOf(a.id),etag="old",revision=10,name="One")
    private val two=Subscription("two","https://two.example/private",listOf(b.id),name="Two")
    @Test fun pinnedSubscriptionsSortFirstWithoutChangingOriginalOrderOrOwnership(){
        val data=VaultData(listOf(a,b),listOf(one,two.copy(pinned=true)))
        assertEquals(listOf("two","one"),ServerGroups.list(data).map{it.id})
        assertEquals(listOf("one","two"),data.subscriptions.map{it.id})
        assertEquals(listOf(b),ServerGroups.list(data).first().profiles)
        assertEquals(listOf("one","two"),ServerGroups.list(data.copy(subscriptions=listOf(one.copy(pinned=true),two.copy(pinned=true)))).map{it.id})
    }
    @Test fun oldVaultDefaultsToUnpinned(){
        val old=Json.gson.fromJson("""{"id":"one","url":"https://example.com","profileIds":[]}""",Subscription::class.java)
        assertFalse(old.pinned)
        assertEquals("one",ServerGroups.list(VaultData(subscriptions=listOf(old))).single().id)
    }
    @Test fun editedUrlRejectsDuplicateInvalidAndMissingSources(){
        val data=VaultData(subscriptions=listOf(one,two))
        assertEquals("https://new.example/token",SubscriptionUpdate.editedUrl(data,"one"," https://new.example/token "))
        bad{SubscriptionUpdate.editedUrl(data,"one",two.url)}
        bad{SubscriptionUpdate.editedUrl(data,"one","file:///secret")}
        bad{SubscriptionUpdate.editedUrl(data,"absent","https://new.example")}
        assertEquals("old",data.subscriptions.first().etag)
    }
    @Test fun sourceReplacementKeepsIdPinOrderAndUnrelatedManualProfile(){
        val original=VaultData(listOf(a,b,c),listOf(one.copy(pinned=true),two))
        val fresh=a.copy(id="replacement")
        val entry=one.copy(url="https://new.example/link",profileIds=listOf(fresh.id),pinned=true,etag="new",revision=1)
        val updated=SubscriptionUpdate.apply(original,entry,listOf(fresh))
        assertEquals(listOf("one","two"),updated.subscriptions.map{it.id})
        assertTrue(updated.subscriptions.first().pinned)
        assertEquals(setOf(b.id,c.id,fresh.id),updated.profiles.map{it.id}.toSet())
        assertEquals(one.url,original.subscriptions.first().url)
        assertTrue(original.profiles.any{it.id==a.id})
    }
    @Test fun sharedProfileIsRetainedWhenOneSubscriptionReplacesIt(){
        val original=VaultData(listOf(a,b),listOf(one,two.copy(profileIds=listOf(a.id,b.id))))
        val updated=SubscriptionUpdate.apply(original,one.copy(profileIds=listOf(c.id)),listOf(c))
        assertEquals(setOf(a.id,b.id,c.id),updated.profiles.map{it.id}.toSet())
        assertEquals(listOf(a.id,b.id),updated.subscriptions[1].profileIds)
    }
    @Test fun removedManualSelectionMovesToReplacementWhileExistingSelectionStays(){
        val original=VaultData(listOf(a,b),listOf(one,two),Preferences(mode=Mode.MANUAL,selectedId=a.id))
        val entry=one.copy(profileIds=listOf(c.id))
        assertEquals(c.id,SubscriptionUpdate.apply(original,entry,listOf(c)).preferences.selectedId)
        assertEquals(b.id,SubscriptionUpdate.apply(original.copy(preferences=Preferences(mode=Mode.MANUAL,selectedId=b.id)),entry,listOf(c)).preferences.selectedId)
    }
    @Test fun notModifiedRefreshPreservesProfilesAndPins(){
        val original=VaultData(listOf(a,b),listOf(one.copy(pinned=true),two))
        val entry=original.subscriptions.first().copy(lastSuccess=123)
        val updated=SubscriptionUpdate.apply(original,entry,null)
        assertEquals(original.profiles,updated.profiles)
        assertTrue(updated.subscriptions.first().pinned)
        assertEquals(123,updated.subscriptions.first().lastSuccess)
    }
    @Test fun newUrlCannotCommitWithoutFetchedProfiles(){
        val original=VaultData(listOf(a),listOf(one))
        bad{SubscriptionUpdate.apply(original,one.copy(url="https://changed.example/private"),null)}
        assertEquals(one.url,original.subscriptions.single().url)
        assertEquals(listOf(a),original.profiles)
    }
}
