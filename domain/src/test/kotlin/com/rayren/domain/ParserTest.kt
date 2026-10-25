package com.rayren.domain

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.zip.Deflater

internal const val TEST_ID="11111111-1111-4111-8111-111111111111"
internal val VLESS="vless://$TEST_ID@vpn.example:443?security=reality&sni=example.com&pbk=${"A".repeat(43)}&sid=0011&flow=xtls-rprx-vision#Finland"
internal fun bad(block:()->Unit){try{block();fail("Should reject input")}catch(_:ValidationException){}}

class ParserTest {
    @Test fun realityTyped(){val p=ImportParser.parse(VLESS).profiles.single();assertEquals("FI",p.region);assertEquals(Engine.XRAY,p.engine);assertEquals("reality",p.config.getAsJsonObject("streamSettings").text("security"));assertFalse(p.toString().contains(TEST_ID))}
    @Test fun multipleBase64Dedup(){val encoded=Base64.getEncoder().encodeToString("$VLESS\n$VLESS".toByteArray());assertEquals(1,ImportParser.parse(encoded).profiles.size)}
    @Test fun invalidRealityKey(){bad{ImportParser.parse(VLESS.replace("A".repeat(43),"short"))}}
    @Test fun unsafeTls(){bad{ImportParser.parse("trojan://password@vpn.example:443?allowInsecure=true")}}
    @Test fun hy2HasQuicAlpn(){val p=ImportParser.parse("hysteria2://password@vpn.example:443?sni=example.com").profiles.single();assertEquals("h3",p.config.getAsJsonObject("streamSettings").getAsJsonObject("tlsSettings").getAsJsonArray("alpn")[0].asString)}
    @Test fun shadowsocks(){val auth=Base64.getUrlEncoder().withoutPadding().encodeToString("aes-128-gcm:example".toByteArray());val p=ImportParser.parse("ss://$auth@vpn.example:8388#NL").profiles.single();assertEquals("shadowsocks",p.protocol)}
    @Test fun rejectPlugin(){bad{ImportParser.parse("ss://YWVzLTEyOC1nY206ZXhhbXBsZQ@vpn.example:8388?plugin=exec")}}
    @Test fun hostnameAndPortValidation(){bad{ImportParser.parse("vless://$TEST_ID@:443")};bad{ImportParser.parse("trojan://pass@vpn.example:99999")}}
    @Test fun deepLink(){assertEquals("https://example.com/sub/token",ImportParser.subscriptionUrl("rayren://import?url=https%3A%2F%2Fexample.com%2Fsub%2Ftoken"))}
    @Test fun rejectUnsafeDeepLink(){bad{ImportParser.subscriptionUrl("rayren://execute?url=https://example.com")};bad{ImportParser.subscriptionUrl("rayren://import?url=file:///etc/passwd")};bad{ImportParser.subscriptionUrl("https://name:secret@example.com/sub")}}
    @Test fun allOrNothing(){bad{ImportParser.parse("$VLESS\nnot-supported://invalid")}}
    @Test fun manifestVersionAndExpiry(){bad{ImportParser.parse("""{"schema_version":2,"profiles":["$VLESS"]}""")};bad{ImportParser.parse("""{"schema_version":1,"expires_at":"2000-01-01T00:00:00Z","profiles":["$VLESS"]}""")}}
    @Test fun unifiedManifest(){val p=ImportParser.parse("""{"schema_version":1,"revision":7,"profiles":[{"uri":"$VLESS","name":"Preferred","priority":10,"region":"NL"}]}""");assertEquals(7,p.revision);assertEquals("NL",p.profiles.single().region);assertEquals(10,p.profiles.single().priority)}
    @Test fun rawConfigCannotSetInboundsOrPaths(){val safe=ImportParser.parse(VLESS).profiles.single().config;val raw=Json.obj("inbounds" to listOf(Json.obj("listen" to "0.0.0.0","port" to 22)),"log" to Json.obj("access" to "/data/secret"),"outbounds" to listOf(safe));val p=ImportParser.parse(raw.toString()).profiles.single();assertFalse(p.config.toString().contains("/data/secret"));assertFalse(p.config.has("inbounds"))}
    @Test fun nestingLimit(){bad{Json.parse("[".repeat(34)+"0"+"]".repeat(34))}}
    @Test fun strictJson(){bad{Json.parse("{unquoted: 1}")};bad{Json.parse("{} {}")};bad{Json.parse("{\"x\":NaN}")}}
    @Test fun payloadLimit(){bad{ImportParser.parse("x".repeat(2*1024*1024+1))}}
    @Test fun fluxModern(){val raw="""{"secret":"example-key-not-real-1234","codec":"batched","transports":[{"type":"cupsonline","url":"https://interview.cups.online/test"}]}""";val compressed=java.io.ByteArrayOutputStream();val def=Deflater(6,true);java.util.zip.DeflaterOutputStream(compressed,def).use{it.write(raw.toByteArray())};def.end();val p=ImportParser.parse("openflux://v1/"+Base64.getUrlEncoder().withoutPadding().encodeToString(compressed.toByteArray())).profiles.single();assertEquals(Engine.OPENFLUX,p.engine);assertEquals("cupsonline",p.protocol);assertFalse(p.supportsIpv6)}
    @Test fun fluxLegacy(){val raw="""{"transport":"cupsonline","url":"https://interview.cups.online/test","key":"example-key-not-real-1234"}""";assertEquals(Engine.OPENFLUX,ImportParser.parse("openflux://"+Base64.getEncoder().encodeToString(raw.toByteArray())).profiles.single().engine)}
    @Test fun rtcExperimental(){val p=ImportParser.parse("olcrtc://telemost?datachannel@123456789#"+"a".repeat(64)+"\$My%20room").profiles.single();assertTrue(p.experimental);assertFalse(p.supportsUdp);assertEquals("My room",p.name)}
    @Test fun noSecretsInLogs(){val text=Redaction.sanitize("connection $VLESS token=secrettoken key=secretkey $TEST_ID");assertFalse(text.contains(TEST_ID));assertFalse(text.contains("secrettoken"));assertFalse(text.contains("secretkey"));assertFalse(text.contains("vpn.example"))}
}
