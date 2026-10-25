package com.rayren.domain

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

internal val HYSTERIA_SALAMANDER="hysteria2://test-auth@vpn.example:443?sni=example.com&obfs=salamander&obfs-password=fixture-obfs-password&pinSHA256=${"12".repeat(32)}&insecure=0"

class ImportCompatibilityTest {
    @Test fun httpIsDownloadedInsteadOfParsedAsServer() {
        assertEquals(ImportInput.Remote("http://example.com:8445/rayren/test"), ImportInputResolver.resolve("http://example.com:8445/rayren/test"))
    }

    @Test fun httpsAndWhitespaceAndBom() {
        assertEquals(ImportInput.Remote("https://example.com/sub/test"), ImportInputResolver.resolve(" \uFEFF HTTPS://example.com/sub/test \n"))
    }

    @Test fun singleLinkCopiedWithBotCaption() {
        val result = ImportInputResolver.resolve("🔗 Основная ссылка: http://example.com:8445/rayren/test\nДобавьте эту ссылку в VPN-клиент.")
        assertEquals(ImportInput.Remote("http://example.com:8445/rayren/test"), result)
    }

    @Test fun multipleSubscriptionLinksAreNotSilentlyChosen() {
        try {
            ImportInputResolver.resolve("Основная: http://example.com/main\nINCY: https://example.com/incy")
            fail("Expected an explicit choice of subscription")
        } catch(e:ValidationException) { assertEquals("MULTIPLE_SUBSCRIPTION_URLS", e.code) }
    }

    @Test fun httpDeepLink() {
        assertEquals(ImportInput.Remote("http://example.com:8445/sub/test"), ImportInputResolver.resolve("rayren://import?url=http%3A%2F%2Fexample.com%3A8445%2Fsub%2Ftest"))
    }

    @Test fun profileAndJsonEndpointsStayInline() {
        val uri = "$VLESS&spx=https%3A%2F%2Fexample.com"
        assertTrue(ImportInputResolver.resolve(uri) is ImportInput.Inline)
        assertTrue(ImportInputResolver.resolve("# Subscription\n$VLESS&spx=https://example.com/path") is ImportInput.Inline)
        val json = """{"dns":{"servers":["https://example.com/dns-query"]},"outbounds":[]}"""
        assertEquals(ImportInput.Inline(json), ImportInputResolver.resolve(json))
        val b64 = Base64.getEncoder().encodeToString(VLESS.toByteArray())
        assertEquals(ImportInput.Inline(b64), ImportInputResolver.resolve(b64))
    }

    @Test fun unsafeUrlFormsStillRejected() {
        listOf("file:///secret", "ftp://example.com/sub", "http://user:password@example.com/sub", "http://example.com/sub#fragment", "http://example.com:99999/sub", "https://example.com:0/sub").forEach { url -> bad { ImportParser.subscriptionUrl(url) } }
    }

    @Test fun inputDescriptionsDoNotLeakCredentials() {
        assertFalse(ImportInput.Remote("https://example.com/private-token").toString().contains("private-token"))
        assertFalse(ImportInput.Inline(VLESS).toString().contains(TEST_ID))
    }

    private fun flat(protocol:String="vless") = Json.obj(
        "tag" to "Synthetic FI",
        "protocol" to protocol,
        "settings" to Json.obj("address" to "vpn.example", "port" to 443, "id" to TEST_ID, "encryption" to "none", "flow" to "xtls-rprx-vision"),
        "streamSettings" to Json.obj("network" to "tcp", "security" to "reality", "realitySettings" to Json.obj("serverName" to "example.com", "publicKey" to "A".repeat(43), "shortId" to "0011", "fingerprint" to "chrome")),
    )

    @Test fun simplifiedIncyVlessRetainsCredentialsAndReality() {
        val p = ImportParser.parse(flat().toString()).profiles.single()
        val settings = p.config.getAsJsonObject("settings").getAsJsonArray("vnext")[0].asJsonObject
        assertEquals(TEST_ID, settings.getAsJsonArray("users")[0].asJsonObject.text("id"))
        assertEquals("xtls-rprx-vision", settings.getAsJsonArray("users")[0].asJsonObject.text("flow"))
        assertEquals("0011", p.config.getAsJsonObject("streamSettings").getAsJsonObject("realitySettings").text("shortId"))
    }

    @Test fun incyConfigArrayDeduplicatesSmartBalanceAndIgnoresRemotePolicy() {
        val outbound = flat()
        val direct = Json.obj("protocol" to "freedom", "settings" to Json.obj("redirect" to "untrusted.example:80"))
        val document = Json.obj("remarks" to "Fixture", "outbounds" to listOf(outbound, direct, Json.obj("protocol" to "blackhole")), "inbounds" to listOf(Json.obj("listen" to "0.0.0.0", "port" to 9999)), "routing" to Json.obj("balancers" to listOf(Json.obj("selector" to listOf("Synthetic")))))
        val result = ImportParser.parse(Json.gson.toJson(listOf(document, document)))
        assertEquals(1, result.profiles.size)
        assertEquals(1, result.warnings.size)
        assertFalse(result.profiles.single().config.toString().contains("untrusted.example"))
        assertFalse(result.profiles.single().config.has("routing"))
    }

    @Test fun simplifiedVmessTrojanAndShadowsocks() {
        assertEquals("vmess", ImportParser.parse(flat("vmess").toString()).profiles.single().protocol)
        val trojan = Json.obj("protocol" to "trojan", "settings" to Json.obj("address" to "vpn.example", "port" to 443, "password" to "test-password"), "streamSettings" to Json.obj("security" to "tls"))
        assertEquals("test-password", ImportParser.parse(trojan.toString()).profiles.single().config.getAsJsonObject("settings").getAsJsonArray("servers")[0].asJsonObject.text("password"))
        val ss = Json.obj("protocol" to "shadowsocks", "settings" to Json.obj("address" to "vpn.example", "port" to 8388, "method" to "aes-128-gcm", "password" to "test-password"))
        assertEquals("shadowsocks", ImportParser.parse(ss.toString()).profiles.single().protocol)
    }

    @Test fun simplifiedJsonStillRejectsUnsafeTlsAndInvalidIds() {
        val unsafe = flat().apply { getAsJsonObject("streamSettings").getAsJsonObject("realitySettings").addProperty("allowInsecure", true) }
        bad { ImportParser.parse(unsafe.toString()) }
        val invalid = flat().apply { getAsJsonObject("settings").addProperty("id", "invalid") }
        bad { ImportParser.parse(invalid.toString()) }
    }

    @Test fun mixedMainSubscriptionWithSalamanderImportsAllNodes() {
        for(text in listOf("$VLESS\n$HYSTERIA_SALAMANDER", Base64.getEncoder().encodeToString("$VLESS\n$HYSTERIA_SALAMANDER".toByteArray()))) {
            val result=ImportParser.parse(text)
            assertEquals(2,result.profiles.size)
            val stream=result.profiles.single{it.protocol=="hysteria"}.config.getAsJsonObject("streamSettings")
            assertEquals("fixture-obfs-password",stream.getAsJsonObject("finalmask").getAsJsonArray("udp")[0].asJsonObject.getAsJsonObject("settings").text("password"))
            assertEquals("12".repeat(32),stream.getAsJsonObject("tlsSettings").text("pinnedPeerCertSha256"))
            assertFalse(stream.getAsJsonObject("tlsSettings").get("allowInsecure").asBoolean)
        }
    }

    @Test fun jsonHysteriaRetainsSalamanderAndCertificatePin() {
        val original=ImportParser.parse(HYSTERIA_SALAMANDER).profiles.single()
        val restored=ImportParser.parse(original.config.toString()).profiles.single()
        assertEquals(original.config,restored.config)
    }

    @Test fun invalidMaskAndPinsAreRejectedRatherThanDropped() {
        listOf(
            HYSTERIA_SALAMANDER.replace("obfs=salamander","obfs=unknown"),
            HYSTERIA_SALAMANDER.replace("fixture-obfs-password",""),
            HYSTERIA_SALAMANDER.replace("12".repeat(32),"invalid"),
            HYSTERIA_SALAMANDER.replace("insecure=0","insecure=1"),
        ).forEach { bad { ImportParser.parse(it) } }
    }

    @Test fun unknownJsonMaskIsNotSilentlyRemoved() {
        val config=ImportParser.parse(HYSTERIA_SALAMANDER).profiles.single().config
        config.getAsJsonObject("streamSettings").getAsJsonObject("finalmask").getAsJsonArray("udp")[0].asJsonObject.addProperty("type","unknown")
        bad { ImportParser.parse(config.toString()) }
    }

    @Test fun realityPqvSurvivesUriAndJsonImport() {
        val key=Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(1952))
        val original=ImportParser.parse(VLESS.substringBefore('#')+"&pqv=$key&spx=%2Ftest").profiles.single()
        val restored=ImportParser.parse(original.config.toString()).profiles.single()
        assertEquals(key,restored.config.getAsJsonObject("streamSettings").getAsJsonObject("realitySettings").text("mldsa65Verify"))
        assertEquals("/test",restored.config.getAsJsonObject("streamSettings").getAsJsonObject("realitySettings").text("spiderX"))
        bad { ImportParser.parse(VLESS.substringBefore('#')+"&pqv=invalid") }
    }

    @Test fun tlsPinAndAlpnSurviveJsonImport() {
        val original=ImportParser.parse("trojan://test-password@vpn.example:443?alpn=http%2F1.1&pinSHA256=${"AB:".repeat(31)}AB").profiles.single()
        val restored=ImportParser.parse(original.config.toString()).profiles.single()
        val tls=restored.config.getAsJsonObject("streamSettings").getAsJsonObject("tlsSettings")
        assertEquals("ab".repeat(32),tls.text("pinnedPeerCertSha256"))
        assertEquals(listOf("http/1.1"),tls.getAsJsonArray("alpn").map{it.asString})
    }
}
