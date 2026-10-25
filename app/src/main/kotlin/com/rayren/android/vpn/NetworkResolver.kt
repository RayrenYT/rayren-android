package com.rayren.android.vpn

import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
import com.rayren.domain.BootstrapAddress
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** DNS must never occupy the VPN controller while the user is trying to stop it. */
object NetworkResolver {
    private val legacy=Executors.newFixedThreadPool(2){r->Thread(r,"rayren-bootstrap-dns").apply{isDaemon=true}}
    suspend fun resolve(network:Network,host:String):List<String> = BootstrapAddress.resolve(host){name->query(network,name)}
    private suspend fun query(network:Network,host:String):List<String> = withTimeoutOrNull(4000){
        suspendCancellableCoroutine<List<String>> { continuation->
            fun result(addresses:List<InetAddress>){if(continuation.isActive)continuation.resume(addresses.mapNotNull{it.hostAddress})}
            if(Build.VERSION.SDK_INT>=29){
                val cancel=CancellationSignal()
                continuation.invokeOnCancellation{cancel.cancel()}
                try{DnsResolver.getInstance().query(network,host,DnsResolver.FLAG_EMPTY,java.util.concurrent.Executor{it.run()},cancel,
                    object:DnsResolver.Callback<List<InetAddress>> {
                        override fun onAnswer(answer:List<InetAddress>,rcode:Int){result(if(rcode==0)answer else emptyList())}
                        override fun onError(error:DnsResolver.DnsException){result(emptyList())}
                    })}catch(_:Exception){result(emptyList())}
            }else{
                val task=legacy.submit{result(runCatching{network.getAllByName(host).toList()}.getOrDefault(emptyList()))}
                continuation.invokeOnCancellation{task.cancel(true);(legacy as java.util.concurrent.ThreadPoolExecutor).purge()}
            }
        }
    } ?: emptyList()
}
