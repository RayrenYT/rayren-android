package com.rayren.domain

import java.net.InetAddress

/** Literal endpoints are already addresses. Never send them as DNS question names. */
object BootstrapAddress {
    fun literal(host:String):String? {
        val value=host.removeSurrounding("[","]")
        val parts=value.split('.')
        if(parts.size==4&&parts.all{it.isNotEmpty()&&it.all{c->c in '0'..'9'}&&it.toIntOrNull() in 0..255})
            return parts.joinToString("."){it.toInt().toString()}
        // A colon and the numeric-only alphabet prevent getByName from doing DNS.
        if(':' in value&&value.matches(Regex("[0-9a-fA-F:.]+")))
            return runCatching{InetAddress.getByName(value).hostAddress}.getOrNull()
        return null
    }
    suspend fun resolve(host:String,lookup:suspend (String)->List<String>):List<String> =
        literal(host)?.let{listOf(it)} ?: lookup(host)
}
