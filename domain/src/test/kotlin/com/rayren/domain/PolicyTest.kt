package com.rayren.domain

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PolicyTest {
    private val profile=ImportParser.parse(VLESS).profiles.single()
    private val listener=Listener(10808,10809,10810,"testuser","testpassword",10811)
    private fun config(p:Preferences=Preferences(),engine:Profile=profile)=Json.parse(PolicyCompiler.compile(engine,p,listener)).asJsonObject
    @Test fun onePolicyRouterWithAuthenticatedLoopback(){val c=config();val ins=c.getAsJsonArray("inbounds");assertEquals(2,ins.size());ins.forEach{assertEquals("127.0.0.1",it.asJsonObject.text("listen"));assertEquals("password",it.asJsonObject.getAsJsonObject("settings").text("auth"))}}
    @Test fun safetyBeforeCustomDirect(){val rules=config(Preferences(rules=listOf(Rule(RuleKind.PORT,"443",Target.DIRECT)))).getAsJsonObject("routing").getAsJsonArray("rules");assertEquals("proxy",rules[0].asJsonObject.text("outboundTag"));assertEquals("dns-out",rules[1].asJsonObject.text("outboundTag"));assertEquals("block",rules[2].asJsonObject.text("outboundTag"));assertTrue(rules.indexOf(rules.find{it.asJsonObject.text("port")=="853"})<rules.indexOf(rules.find{it.asJsonObject.text("port")=="443"}))}
    @Test fun includeCannotBeEmpty(){bad{PolicyCompiler.validate(Preferences(appMode=AppMode.INCLUDE))}}
    @Test fun strictDnsConflict(){bad{PolicyCompiler.validate(Preferences(dnsMode=DnsMode.DIRECT_SYSTEM))};bad{PolicyCompiler.validate(Preferences(dnsRules=listOf(DnsRule("ru","1.1.1.1",true))))}}
    @Test fun dotRoutesOnlyLocalDnsDirect(){val c=config(Preferences(dnsMode=DnsMode.TUNNEL_DOT,dns="tls://1.1.1.1:853"));assertTrue(c.getAsJsonObject("dns").toString().contains("tcp://127.0.0.1:10811"));assertTrue(c.getAsJsonObject("routing").toString().contains("dns-query"))}
    @Test fun dotRejectsInvalidTlsSettings(){bad{PolicyCompiler.validate(Preferences(dnsMode=DnsMode.TUNNEL_DOT,dns="tls://1.1.1.1:853?insecure=true"))}}
    @Test fun splitDnsNoSystemFallback(){val c=config(Preferences(strictDns=false,dnsRules=listOf(DnsRule("ru","9.9.9.9",true))));val dns=c.getAsJsonObject("dns");assertEquals("dns-rule-0",dns.getAsJsonArray("servers")[0].asJsonObject.text("tag"));assertTrue(dns.get("disableFallback").asBoolean);assertTrue(dns.get("disableFallbackIfMatch").asBoolean)}
    @Test fun ipv6BlockedOnUnsupportedEngine(){val rules=config(Preferences(ipv6=Ipv6Mode.DUAL),profile.copy(engine=Engine.OPENFLUX)).getAsJsonObject("routing").getAsJsonArray("rules");assertTrue(rules.any{it.asJsonObject.getAsJsonArray("ip")?.any{ip->ip.asString=="::/0"}==true})}
    @Test fun rtcUdpBlocked(){assertTrue(config(engine=profile.copy(engine=Engine.OLCRTC)).getAsJsonObject("routing").getAsJsonArray("rules").any{it.asJsonObject.text("network")=="udp"&&it.asJsonObject.text("outboundTag")=="block"})}
    @Test fun fluxNeverReceivesDomainForLocalDns(){assertEquals("ForceIPv4",config(engine=profile.copy(engine=Engine.OPENFLUX)).getAsJsonArray("outbounds")[0].asJsonObject.text("targetStrategy"))}
    @Test fun proxyOnlyDefaultDirect(){val rules=config(Preferences(preset=Preset.PROXY_ONLY)).getAsJsonObject("routing").getAsJsonArray("rules");assertEquals("direct",rules.last().asJsonObject.text("outboundTag"))}
    @Test fun ruleValidation(){bad{PolicyCompiler.validateRule(Rule(RuleKind.IP,"10.0.0.1/99",Target.BLOCK))};bad{PolicyCompiler.validateRule(Rule(RuleKind.PORT,"999-1",Target.BLOCK))};bad{PolicyCompiler.validateRule(Rule(RuleKind.GEOSITE,"../../secret",Target.BLOCK))};PolicyCompiler.validateRule(Rule(RuleKind.IP,"2001:db8::/32",Target.PROXY))}
    @Test fun emitNativeValidationFixtures(){
        val folder=File("build/native-fixtures").apply{mkdirs()}
        val uris=listOf(VLESS,"vless://$TEST_ID@127.0.0.1:17444?security=none#LocalTest","trojan://testpassword@vpn.example:443","ss://YWVzLTEyOC1nY206ZXhhbXBsZQ@vpn.example:8388","hysteria2://testpassword@vpn.example:443",HYSTERIA_SALAMANDER,VLESS.substringBefore('#')+"&pqv="+java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(1952)))
        uris.forEachIndexed{i,uri->File(folder,"protocol-$i.json").writeText(PolicyCompiler.compile(ImportParser.parse(uri).profiles.single(),Preferences(),listener))}
        listOf(Preferences(),Preferences(dnsMode=DnsMode.TUNNEL_DOT,dns="tls://1.1.1.1:853"),Preferences(ipv6=Ipv6Mode.DUAL,lanBypass=true,preset=Preset.RUSSIA_DIRECT),Preferences(dnsMode=DnsMode.DIRECT_SYSTEM,strictDns=false),Preferences(strictDns=false,dnsRules=listOf(DnsRule("ru","9.9.9.9",true))),Preferences(rules=listOf(Rule(RuleKind.PROTOCOL,"bittorrent",Target.BLOCK),Rule(RuleKind.SUFFIX,"example.com",Target.DIRECT)))).forEachIndexed{i,p->File(folder,"policy-$i.json").writeText(PolicyCompiler.compile(profile,p,listener))}
        listOf(Engine.OPENFLUX,Engine.OLCRTC).forEach{e->File(folder,"${e.name}.json").writeText(PolicyCompiler.compile(profile.copy(engine=e),Preferences(),listener))}
        listOf("tls","reality").forEach { security ->
            val settings=Json.obj("serverName" to "example.com","fingerprint" to "chrome","publicKey" to "A".repeat(43),"shortId" to "0011","allowInsecure" to false)
            val flat=Json.obj("protocol" to "vless","settings" to Json.obj("address" to "vpn.example","port" to 443,"id" to TEST_ID,"encryption" to "none","flow" to ""),"streamSettings" to Json.obj("network" to "tcp","security" to security,(if(security=="reality")"realitySettings" else "tlsSettings") to settings))
            File(folder,"incy-flat-$security.json").writeText(PolicyCompiler.compile(ImportParser.parse(flat.toString()).profiles.single(),Preferences(),listener))
        }
    }
}
