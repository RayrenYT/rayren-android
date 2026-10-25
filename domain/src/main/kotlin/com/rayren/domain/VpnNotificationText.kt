package com.rayren.domain

import java.util.Locale

data class NotificationDisplay(val content:String,val mode:String,val canPing:Boolean)

/** Keep LiveBridge's traffic text, but do not resend identical notifications every tick. */
class NotificationRefreshGate {
    private var previous:NotificationDisplay?=null
    private var postedAt=0L
    fun shouldPost(display:NotificationDisplay,now:Long):Boolean {
        if(display==previous&&now>=postedAt&&now-postedAt<60000)return false
        previous=display;postedAt=now
        return true
    }
}

/** Standard notification text also consumed by LiveBridge's VPN traffic detector. */
object VpnNotificationText {
    fun content(server:String,detail:String,connected:Boolean,down:Long,up:Long):String = buildString {
        if(connected)append("VPN подключён · ")
        if(server.isNotBlank())append(server).append("\n")
        append(detail)
        if(connected)append("\n↓ ").append(rate(down)).append(" · ↑ ").append(rate(up))
    }
    private fun rate(bytes:Long):String = String.format(Locale.US,"%.1f KB/s",bytes.coerceAtLeast(0)/1024.0)
}
