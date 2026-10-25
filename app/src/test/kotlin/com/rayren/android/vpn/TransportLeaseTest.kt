package com.rayren.android.vpn

import com.rayren.domain.*
import org.junit.Assert.*
import org.junit.Test

class TransportLeaseTest {
    private fun profile(id:String,engine:Engine)=Profile(id,id,engine,"fixture",config=Json.obj())
    @Test fun preventsDuplicateSessionsForExternalProviders(){
        for(engine in listOf(Engine.OPENFLUX,Engine.OLCRTC)){
            val active=TransportLease.forProfile(profile("active",engine))
            val probe=TransportLease.forProfile(profile("another",engine))
            assertSame(active,probe);assertTrue(active.tryLock())
            try{assertFalse(probe.tryLock())}finally{active.unlock()}
            assertTrue(probe.tryLock());probe.unlock()
        }
    }
    @Test fun separateXrayProfilesCanBeProbedWhileConnected(){
        val active=TransportLease.forProfile(profile("active-xray",Engine.XRAY))
        val probe=TransportLease.forProfile(profile("other-xray",Engine.XRAY))
        assertSame(active,TransportLease.forProfile(profile("active-xray",Engine.XRAY)))
        assertNotSame(active,probe)
        assertTrue(active.tryLock());try{assertTrue(probe.tryLock());probe.unlock()}finally{active.unlock()}
    }
}
