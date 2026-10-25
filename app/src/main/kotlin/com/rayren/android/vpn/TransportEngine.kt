package com.rayren.android.vpn

import com.rayren.domain.*
import com.rayren.nativebridge.rayrencore.Protector
import com.rayren.nativebridge.rayrencore.Rayrencore

interface TransportEngine {
    val kind:Engine
    fun start(profile:Profile,listener:Listener,protector:Protector)
    fun ready():Boolean
    fun stop()
}

class XrayTransport:TransportEngine {
    override val kind=Engine.XRAY
    // The common router starts the protocol outbound; no secondary listener is needed.
    override fun start(profile:Profile,listener:Listener,protector:Protector){require(profile.engine==kind)}
    override fun ready()=true
    override fun stop()=Unit
}
class OpenFluxTransport:TransportEngine {
    override val kind=Engine.OPENFLUX
    override fun start(profile:Profile,listener:Listener,protector:Protector){Rayrencore.startOpenFlux(profile.config.toString(),listener.enginePort.toLong(),listener.username,listener.password)}
    override fun ready()=Rayrencore.openFluxReady()
    override fun stop(){Rayrencore.stopOpenFlux()}
}
class OlcRtcTransport:TransportEngine {
    override val kind=Engine.OLCRTC
    override fun start(profile:Profile,listener:Listener,protector:Protector){Rayrencore.startRtc(profile.config.toString(),listener.enginePort.toLong(),listener.username,listener.password,protector)}
    override fun ready()=Rayrencore.rtcReady()
    override fun stop(){Rayrencore.stopRtc()}
}

/** Method names/class path match the pinned HEV JNI registration. */
class TProxyService {
    external fun TProxyStartService(config:String,fd:Int):Boolean
    external fun TProxyStopService():Boolean
    external fun TProxyIsRunning():Boolean
    external fun TProxyGetStats():LongArray
    companion object {init{System.loadLibrary("hev-socks5-tunnel")}}
}
