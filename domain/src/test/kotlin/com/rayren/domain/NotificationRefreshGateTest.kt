package com.rayren.domain

import org.junit.Assert.*
import org.junit.Test

class NotificationRefreshGateTest {
    private val idle=NotificationDisplay(VpnNotificationText.content("Server","Пинг: 300 мс",true,0,0),"Авто",true)
    @Test fun unchangedIdleTicksOnlyPostOncePerMinute(){
        val gate=NotificationRefreshGate()
        assertTrue(gate.shouldPost(idle,1000))
        for(t in 6000L..56000L step 5000L)assertFalse(gate.shouldPost(idle,t))
        assertTrue(gate.shouldPost(idle,61000));assertFalse(gate.shouldPost(idle,66000))
    }
    @Test fun serverSpeedModeAndActionChangesAreImmediate(){
        val gate=NotificationRefreshGate();gate.shouldPost(idle,1000)
        val traffic=idle.copy(content=VpnNotificationText.content("Server","Пинг: 300 мс",true,2048,1024))
        assertTrue(gate.shouldPost(traffic,2000))
        assertTrue(gate.shouldPost(traffic.copy(canPing=false),2001))
        assertTrue(gate.shouldPost(traffic.copy(mode="Ручной",canPing=false),2002))
        assertTrue(gate.shouldPost(idle.copy(content="Другой сервер"),2003))
    }
    @Test fun clockResetDoesNotSuppressStatus(){val gate=NotificationRefreshGate();gate.shouldPost(idle,5000);assertTrue(gate.shouldPost(idle,0))}
}
