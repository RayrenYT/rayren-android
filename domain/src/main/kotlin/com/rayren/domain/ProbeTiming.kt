package com.rayren.domain

data class ProbeTiming(val totalMs:Long,val connectMs:Long,val tlsMs:Long,val responseMs:Long) {
    fun diagnostic()="PROBE_HTTPS_GET total=${totalMs}ms socks=${connectMs}ms tls=${tlsMs}ms response=${responseMs}ms"
    companion object {
        // A new balancer has no observations yet. If its initial proxy fallback fails,
        // keep the same core alive and let a second request use the now observed healthy member.
        fun measure(balanced:Boolean=false,timeoutMs:Int=15000,guard:()->Unit={},request:(Int)->String):ProbeTiming {
            val budget=if(balanced)(timeoutMs*2).coerceAtMost(30000)else timeoutMs
            val start=System.nanoTime()
            fun remaining()=budget-(System.nanoTime()-start)/1_000_000
            while(true){
                guard()
                try{return parse(request(minOf(timeoutMs.toLong(),remaining().coerceAtLeast(1000)).toInt())) ?: throw ValidationException("Некорректный результат проверки")}
                catch(e:Exception){guard();if(!balanced||remaining()<1250)throw e}
                Thread.sleep(250)
            }
        }
        fun parse(raw:String):ProbeTiming?=runCatching{
            require(raw.length<512)
            val obj=Json.parse(raw).asJsonObject
            val v=listOf("totalMs","connectMs","tlsMs","responseMs").map{obj.get(it).asLong}
            require(v.all{it in 0..65000}&&v.drop(1).all{it<=v[0]})
            ProbeTiming(v[0],v[1],v[2],v[3])
        }.getOrNull()
    }
}
