package com.rayren.domain

import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.*
import kotlin.random.Random

class Followup016Test {
    private val node=ImportParser.parse(VLESS).profiles.single()
    private fun resolve(host:String,lookup:suspend (String)->List<String>):List<String> {
        var result:Result<List<String>>?=null
        val operation:suspend ()->List<String> = {BootstrapAddress.resolve(host,lookup)}
        operation.startCoroutine(object:Continuation<List<String>> {
            override val context=EmptyCoroutineContext
            override fun resumeWith(value:Result<List<String>>){result=value}
        })
        return result!!.getOrThrow()
    }
    @Test fun numericEndpointsWorkEvenWhenDnsRejectsEveryQuestion(){
        for(host in listOf("198.51.100.7","1.1.1.1","2001:db8::7","[2001:db8::8]","::ffff:192.0.2.8")) {
            val addresses=resolve(host){throw AssertionError("Literal must not query DNS")}
            assertEquals(1,addresses.size)
            assertNotNull(BootstrapAddress.literal(addresses.single()))
        }
    }
    @Test fun domainNamesStillUseNetworkResolverAndPropagateEmptyAnswer(){
        var queried=""
        assertEquals(listOf("192.0.2.9"),resolve("server.example"){queried=it;listOf("192.0.2.9")})
        assertEquals("server.example",queried)
        assertTrue(resolve("missing.example"){emptyList()}.isEmpty())
    }
    @Test fun malformedOrNamedInputIsNotTreatedAsNumericAddress(){
        for(host in listOf("999.1.2.3","1.2.3","example.com","a:b:c","https://1.1.1.1","1.1.1.1.example","","1::2::3"))
            assertNull(host,BootstrapAddress.literal(host))
    }
    @Test fun bothSingleAndBalancedNumericNodesSurviveUnavailableDns(){
        val first=node.copy(config=node.config.deepCopy().apply{getAsJsonObject("settings").getAsJsonArray("vnext")[0].asJsonObject.addProperty("address","198.51.100.7")})
        val second=first.copy(id="second",config=first.config.deepCopy().apply{getAsJsonObject("settings").getAsJsonArray("vnext")[0].asJsonObject.addProperty("address","198.51.100.8")})
        for(profile in listOf(first,SmartBalance.create(listOf(first,second)))){
            val bootstrap=SmartBalance.endpoints(profile).associateWith{resolve(it){emptyList()}}
            assertTrue(bootstrap.values.all{it.isNotEmpty()})
            Json.parse(PolicyCompiler.compile(profile,Preferences(),Listener(10808,10809,10810,"user","password"),bootstrap))
        }
    }
    @Test fun autoNeverStartsWithReserveWhenOrdinaryServerExists(){
        val flux=node.copy(id="cups",engine=Engine.OPENFLUX)
        val marked=node.copy(id="bs",name="БС • Москва")
        for(seed in 1..50)assertEquals(node,Selection(Random(seed)).candidates(listOf(flux,marked,node),Preferences(),"wifi",1000).first())
        val s=Selection();s.success("wifi",flux.id,listOf(20),1000)
        assertEquals(node,s.candidates(listOf(flux,node),Preferences(),"wifi",1001).first())
    }
    @Test fun namedSubscriptionAndItsBalancerAreReservedButUnrelatedNamesAreNot(){
        val bs=node.copy(id="bs",name="Москва",config=node.config.deepCopy().apply{addProperty("testMarker","bs")})
        val balance=SmartBalance.create(listOf(node,bs))
        val subscription=Subscription("sub","https://example.com/s",listOf(bs.id),name="БС подписка")
        val all=listOf(bs,balance,node)
        assertEquals(setOf(bs.id,balance.id),ReserveRoutes.ids(all,listOf(subscription)))
        assertEquals(node,Selection().candidates(all,Preferences(),"wifi",1000,listOf(subscription)).first())
        assertTrue(ReserveRoutes.ids(listOf(node.copy(name="Amsterdam ABS normal"))).isEmpty())
    }
    @Test fun onlyReservesStillConnectAndManualOrPingModeKeepTheirMeaning(){
        val bs=node.copy(id="bs",name="БС")
        assertEquals(bs,Selection().candidates(listOf(bs),Preferences(),"wifi",1000).first())
        val s=Selection();s.success("wifi",bs.id,listOf(20),1000);s.success("wifi",node.id,listOf(400),1000)
        assertEquals(bs,s.candidates(listOf(node,bs),Preferences(mode=Mode.SMART),"wifi",1001).first())
        assertEquals(listOf(bs),s.candidates(listOf(node,bs),Preferences(mode=Mode.MANUAL,selectedId=bs.id),"wifi",1001))
    }
}
