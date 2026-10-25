package com.rayren.android.data

import com.rayren.domain.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.concurrent.TimeUnit

class SubscriptionsTest {
    @Test fun largeChunkedSubscriptionOverTwoMegabytesImportsAllProfiles(){
        val body=(1..1200).joinToString("\n"){"vless://11111111-1111-4111-8111-111111111111@n$it.example:443?security=none#"+"Node$it".padEnd(2000,'x')}
        assertTrue(body.length>2*1024*1024)
        MockWebServer().use { server->
            server.enqueue(MockResponse().setChunkedBody(body,8192))
            assertEquals(1200,Subscriptions().fetch(server.url("/large").toString()).result!!.profiles.size)
        }
    }
    @Test fun oversizedChunkedDownloadIsRejectedBeforeParsing(){
        MockWebServer().use { server->
            server.enqueue(MockResponse().setChunkedBody("x".repeat(ImportLimits.MAX_BYTES+1),8192))
            try{Subscriptions().fetch(server.url("/large").toString());fail("Oversized body accepted")}
            catch(e:ValidationException){assertEquals(ImportLimits.SIZE_MESSAGE,e.reason)}
        }
    }
    private val vless = "vless://11111111-1111-4111-8111-111111111111@vpn.example:443?security=none#Fixture"
    private fun verifiedFixture(server:MockWebServer):OkHttpClient {
        val cert=HeldCertificate.Builder().addSubjectAlternativeName("fixture.rayren.test").build()
        val clientCert=HeldCertificate.Builder().commonName("Rayren test client").build()
        val serverTls=HandshakeCertificates.Builder().heldCertificate(cert).addTrustedCertificate(clientCert.certificate).build()
        server.useHttps(serverTls.sslSocketFactory(),false)
        server.requireClientAuth()
        val trust=HandshakeCertificates.Builder().heldCertificate(clientCert).addTrustedCertificate(cert.certificate).build()
        return OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(),trust.trustManager)
            .dns(object:okhttp3.Dns{override fun lookup(hostname:String)=if(hostname=="fixture.rayren.test")listOf(java.net.InetAddress.getByName("127.0.0.1"))else okhttp3.Dns.SYSTEM.lookup(hostname)})
            .connectionSpecs(listOf(okhttp3.ConnectionSpec.Builder(okhttp3.ConnectionSpec.MODERN_TLS).tlsVersions(okhttp3.TlsVersion.TLS_1_2).build())).build()
    }

    @Test fun httpImportUsesDownloaderAndParsesPlainSubscription() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(vless))
            val input = ImportInputResolver.resolve("Основная ссылка: ${server.url("/rayren/test")}") as ImportInput.Remote
            val stages = mutableListOf<ImportStage>()
            val result = Subscriptions().fetch(input.url, onStage={stages.add(it)})
            assertEquals(1, result.result!!.profiles.size)
            assertEquals(listOf(ImportStage.DOWNLOAD, ImportStage.VERIFY, ImportStage.PARSE), stages)
            assertEquals("/rayren/test", server.takeRequest().path)
        }
    }

    @Test fun existingServerSelectsCompatibleJsonWithoutChangingTheSubscriptionUrl() {
        MockWebServer().use { server ->
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse {
                    val ua=request.getHeader("User-Agent").orEmpty()
                    return if(ua.startsWith("Rayren/") && ua.contains("Happ/JSON-compatible")) {
                        MockResponse().setBody("""[{"remarks":"Fixture","outbounds":[{"protocol":"vless","settings":{"address":"vpn.example","port":443,"id":"11111111-1111-4111-8111-111111111111","encryption":"none"},"streamSettings":{"network":"tcp","security":"none"}}]}]""")
                    } else MockResponse().setResponseCode(500).setBody("subscription error")
                }
            }
            val result=Subscriptions().fetch(server.url("/rayren/test").toString())
            assertEquals(1,result.result!!.profiles.size)
            val request=server.takeRequest()
            assertEquals("/rayren/test",request.path)
            assertTrue(request.getHeader("Accept").orEmpty().contains("application/json"))
        }
    }

    @Test fun httpsImportUsesCertificateVerification() {
        MockWebServer().use { server ->
            // Test-only mutual TLS prevents local inspection replacing the fixture
            // certificate; the client still trusts only the private test root.
            val client=verifiedFixture(server)
            server.enqueue(MockResponse().setBody(Base64.getEncoder().encodeToString(vless.toByteArray())))
            assertEquals(1, Subscriptions(client).fetch(server.url("/sub/test").newBuilder().host("fixture.rayren.test").build().toString()).result!!.profiles.size)
        }
    }

    @Test fun untrustedHttpsIsNotRetriedAsPlainHttp() {
        MockWebServer().use { server ->
            val cert = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
            server.requestClientAuth()
            try {
                Subscriptions().fetch(server.url("/sub/test").newBuilder().host("127.0.0.1").build().toString())
                fail("Untrusted certificate accepted")
            } catch(_:javax.net.ssl.SSLException) { }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun incyJsonResponseUsesSimplifiedXraySettings() {
        MockWebServer().use { server ->
            val outbound = Json.obj("protocol" to "vless", "settings" to Json.obj("address" to "vpn.example", "port" to 443, "id" to "11111111-1111-4111-8111-111111111111", "encryption" to "none", "flow" to ""), "streamSettings" to Json.obj("network" to "tcp", "security" to "tls", "tlsSettings" to Json.obj("serverName" to "example.com", "allowInsecure" to false)))
            server.enqueue(MockResponse().setBody(Json.gson.toJson(listOf(Json.obj("remarks" to "INCY Fixture", "outbounds" to listOf(outbound, Json.obj("protocol" to "freedom")))))))
            val result = Subscriptions().fetch(server.url("/incy/test").toString()).result!!
            assertEquals(1, result.profiles.size)
            assertEquals("INCY Fixture", result.profiles.single().name)
            assertEquals("tls", result.profiles.single().config.getAsJsonObject("streamSettings").text("security"))
        }
    }

    @Test fun server500IsReportedBeforeParsingAndDoesNotAlterOldSubscription() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("subscription error"))
            val url = server.url("/sub/private-token").toString()
            val old = Subscription("old", url, listOf("existing-profile"))
            val stages = mutableListOf<ImportStage>()
            try {
                Subscriptions().fetch(url, old, onStage={stages.add(it)})
                fail("Expected HTTP failure")
            } catch(e:ValidationException) {
                assertEquals("HTTP_500", e.code)
                assertTrue(e.reason.contains("HTTP 500"))
                assertFalse(e.reason.contains("private-token"))
            }
            assertEquals(listOf(ImportStage.DOWNLOAD), stages)
            assertEquals(listOf("existing-profile"), old.profileIds)
        }
    }

    @Test fun redirectsAreNotFollowed() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/must-not-fetch")))
            try { Subscriptions().fetch(server.url("/sub/test").toString()); fail("Redirect followed") }
            catch(e:ValidationException) { assertEquals("HTTP_302", e.code) }
            assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        }
    }

    @Test fun httpsResponseCannotRedirectCredentialsToHttp() {
        MockWebServer().use { secure -> MockWebServer().use { plain ->
            val client=verifiedFixture(secure)
            secure.enqueue(MockResponse().setResponseCode(302).addHeader("Location",plain.url("/secret")))
            try { Subscriptions(client).fetch(secure.url("/sub/test").newBuilder().host("fixture.rayren.test").build().toString()); fail("HTTPS downgrade followed") }
            catch(e:ValidationException) { assertEquals("HTTP_302",e.code) }
            assertEquals(0,plain.requestCount)
        } }
    }

    @Test fun unchangedSubscriptionUsesValidatorsAndRetainsProfileIds() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(304))
            val url=server.url("/sub/test").toString()
            val old=Subscription("old",url,listOf("existing-profile"),"etag-test","Wed, 21 Oct 2015 07:28:00 GMT")
            val result=Subscriptions().fetch(url,old)
            assertNull(result.result)
            val request=server.takeRequest()
            assertEquals(old.etag,request.getHeader("If-None-Match"))
            assertEquals(old.modified,request.getHeader("If-Modified-Since"))
        }
    }

    @Test fun pinnedSignatureCannotBeBypassedOnLegacyHttp() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(vless))
            try { Subscriptions().fetch(server.url("/sub/test").toString(),key="pinned-key"); fail("Unsigned downgrade accepted") }
            catch(e:ValidationException) { assertEquals("Подписка должна иметь подпись", e.reason) }
        }
    }
}
