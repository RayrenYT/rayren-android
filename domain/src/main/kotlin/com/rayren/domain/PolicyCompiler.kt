package com.rayren.domain

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.net.InetAddress
import java.net.URI

data class Listener(val vpnPort: Int, val probePort: Int, val enginePort: Int, val username: String, val password: String,val dnsPort:Int=10811)

object PolicyCompiler {
    fun validate(p: Preferences) {
        ensure(p.mtu in 1280..1500, "MTU должен быть от 1280 до 1500")
        ensure(p.timeoutMs in 3000..60000, "Таймаут должен быть от 3 до 60 секунд")
        ensure(p.healthSeconds in 15..600, "Интервал проверки должен быть от 15 до 600 секунд")
        ensure(p.refreshHours in 1..168,"Интервал обновления должен быть от 1 до 168 часов")
        ensure(p.appMode != AppMode.INCLUDE || p.packages.isNotEmpty(),"В режиме «Только выбранные» нужно выбрать приложения")
        ensure(p.packages.size<=1000 && p.packages.all{it.matches(Regex("[A-Za-z0-9_.]+"))},"Некорректный список приложений")
        ensure(p.probeStatus in 200..299,"Ожидаемый HTTP статус должен быть 2xx")
        validateHttps(p.probeUrl)
        if(p.dnsMode==DnsMode.TUNNEL_DOH) validateHttps(p.dns)
        else if(p.dnsMode==DnsMode.TUNNEL_DOT) {val u=runCatching{URI(p.dns)}.getOrNull();ensure(u?.scheme=="tls"&&!u.host.isNullOrBlank()&&u.userInfo==null&&u.port in listOf(-1,853)&&u.path.isNullOrEmpty()&&u.query==null&&u.fragment==null,"Для DoT укажите tls://dns.example:853")}
        else if(p.dnsMode!=DnsMode.DIRECT_SYSTEM) ensure(isIp(p.dns),"DNS должен быть IP-адресом")
        ensure(!p.strictDns || p.dnsMode in setOf(DnsMode.TUNNEL_DOH,DnsMode.TUNNEL_DOT,DnsMode.TUNNEL_UDP),"Защита DNS требует DNS через VPN")
        ensure(p.dnsRules.size<=64,"Не более 64 правил DNS")
        p.dnsRules.forEach {r->validateRule(Rule(RuleKind.SUFFIX,r.suffix,Target.PROXY));if(r.server.startsWith("https://"))validateHttps(r.server)else ensure(isIp(r.server),"DNS правила: нужен IP или HTTPS URL");ensure(!p.strictDns||!r.direct,"Прямой DNS несовместим с запретом DNS вне VPN")}
        ensure(p.rules.size<=256,"Слишком много правил")
        p.rules.forEach(::validateRule)
        ensure(p.bypassSiteGroups.all{it in RussianBypass.ids}&&p.bypassAppGroups.all{it in RussianBypass.ids},"Неизвестная группа обхода VPN")
        ensure(p.directSites.size<=128,"Не более 128 сайтов в обходе")
        p.directSites.forEach{ensure(RussianBypass.site(it)==it,"Некорректный домен обхода")}
    }

    fun validateRule(r:Rule) {
        ensure(r.value.isNotBlank()&&r.value.length<=253&&!r.value.any{it.isISOControl()},"Пустое или слишком длинное правило")
        when(r.kind) {
            RuleKind.IP -> { val parts=r.value.split('/'); ensure(parts.size<=2&&isIp(parts[0]),"Некорректный IP/CIDR"); if(parts.size==2) ensure(parts[1].toIntOrNull() in 0..if(parts[0].contains(':'))128 else 32,"Некорректная маска CIDR") }
            RuleKind.DOMAIN,RuleKind.SUFFIX -> ensure(r.value.removePrefix("*.").matches(Regex("[\\p{L}0-9._-]+"))&&!r.value.startsWith('.'),"Некорректный домен")
            RuleKind.GEOIP,RuleKind.GEOSITE -> ensure(r.value.matches(Regex("[a-zA-Z0-9@_-]+")),"Некорректное имя geo-правила")
            RuleKind.PORT -> { val parts=r.value.split('-'); ensure(parts.size in 1..2&&parts.all{it.toIntOrNull() in 1..65535},"Некорректный порт"); if(parts.size==2) ensure(parts[0].toInt()<=parts[1].toInt(),"Некорректный диапазон портов") }
            RuleKind.NETWORK -> ensure(r.value in setOf("tcp","udp","tcp,udp"),"Укажите tcp или udp")
            RuleKind.PROTOCOL -> ensure(r.value in setOf("http","tls","quic","bittorrent"),"Поддерживаются http, tls, quic, bittorrent")
        }
    }

    fun compile(profile:Profile,p:Preferences,l:Listener,bootstrap:Map<String,List<String>> = emptyMap(),systemDns:String="1.1.1.1"):String {
        validate(p)
        val proxyOutbounds=if(profile.engine==Engine.XRAY) profile.xrayOutbounds().map{it.deepCopy()} else listOf(Json.obj("protocol" to "socks","settings" to Json.obj("servers" to listOf(Json.obj("address" to "127.0.0.1","port" to l.enginePort,"users" to listOf(Json.obj("user" to l.username,"pass" to l.password)))))))
        ensure(!profile.isBalancer||proxyOutbounds.size in 2..32,"Некорректный Smart Balance")
        // OpenFlux's SOCKS adapter resolves domain targets on the local OS. Resolve
        // them here with Rayren DNS first, and fail closed instead of passing a hostname.
        proxyOutbounds.forEachIndexed { index,outbound ->
            outbound.addProperty("tag",if(profile.isBalancer)"proxy-$index"else "proxy")
            // Xray can send the hostname to the remote proxy without an extra DoH round trip.
            // External SOCKS engines must keep resolution inside Rayren to avoid OS DNS leaks.
            outbound.addProperty("targetStrategy",when { !profile.supportsIpv6->"ForceIPv4";p.ipv6==Ipv6Mode.PREFER_IPV4->"ForceIPv4v6";p.ipv6==Ipv6Mode.PREFER_IPV6->"ForceIPv6v4";else->"AsIs" })
        }
        val rules=JsonArray()
        // Probe traffic bypasses user routing, but always uses the selected proxy.
        rules.add(Json.obj("type" to "field","inboundTag" to listOf("probe"),"outboundTag" to "proxy"))
        rules.add(Json.obj("type" to "field","inboundTag" to listOf("vpn"),"port" to "53","outboundTag" to "dns-out"))
        val blockV6=p.ipv6==Ipv6Mode.BLOCK||!profile.supportsIpv6
        if(blockV6) rules.add(Json.obj("type" to "field","ip" to listOf("::/0"),"outboundTag" to "block"))
        val dnsDirect=p.dnsMode in setOf(DnsMode.DIRECT_SYSTEM,DnsMode.DIRECT_CUSTOM)
        rules.add(Json.obj("type" to "field","inboundTag" to listOf("dns-query"),"outboundTag" to if(dnsDirect||p.dnsMode==DnsMode.TUNNEL_DOT)"direct" else "proxy"))
        if(p.bypassWhitelistSites)rules.add(Json.obj("type" to "field","inboundTag" to listOf("dns-whitelist"),"outboundTag" to "direct"))
        p.dnsRules.forEachIndexed{i,r->rules.add(Json.obj("type" to "field","inboundTag" to listOf("dns-rule-$i"),"outboundTag" to if(r.direct)"direct"else "proxy"))}
        if(p.strictDns) rules.add(Json.obj("type" to "field","port" to "853","outboundTag" to "proxy"))
        if(!profile.supportsUdp) rules.add(Json.obj("type" to "field","network" to "udp","outboundTag" to "block"))
        p.rules.forEach { r -> rules.add(rule(r)) }
        if(p.lanBypass) rules.add(Json.obj("type" to "field","ip" to listOf("10.0.0.0/8","172.16.0.0/12","192.168.0.0/16","fc00::/7","fe80::/10"),"outboundTag" to "direct"))
        val directDomains=RussianBypass.domains(p)
        if(directDomains.isNotEmpty()) rules.add(Json.obj("type" to "field","domain" to directDomains.map{"domain:$it"},"outboundTag" to "direct"))
        rules.add(Json.obj("type" to "field","network" to "tcp,udp","outboundTag" to if(p.preset==Preset.PROXY_ONLY)"direct" else "proxy"))
        val address=when(p.dnsMode){DnsMode.DIRECT_SYSTEM->systemDns;DnsMode.TUNNEL_DOT->"tcp://127.0.0.1:${l.dnsPort}";else->p.dns}
        val hosts=JsonObject(); bootstrap.forEach{(key,value)->hosts.add(key,Json.gson.toJsonTree(value))}
        // Only the explicitly enabled whitelist gets physical-network DNS. Other
        // domains keep the configured tunnel DNS; user DNS rules take precedence.
        val whitelistDns=if(p.bypassWhitelistSites)listOf(Json.obj("address" to systemDns,"domains" to WhitelistBypass.domains.map{"domain:$it"},"tag" to "dns-whitelist","skipFallback" to true,"finalQuery" to true))else emptyList()
        val servers=p.dnsRules.mapIndexed{i,r->Json.obj("address" to r.server,"domains" to listOf("domain:${r.suffix.removePrefix("*.")}"),"tag" to "dns-rule-$i","skipFallback" to true,"finalQuery" to true)}+whitelistDns+Json.obj("address" to address,"skipFallback" to false)
        val dns=Json.obj("tag" to "dns-query","hosts" to hosts,"servers" to servers,"queryStrategy" to if(blockV6)"UseIPv4"else "UseIP","disableCache" to false,"disableFallback" to true,"disableFallbackIfMatch" to true)
        val inbounds=listOf("vpn" to l.vpnPort,"probe" to l.probePort).map{(tag,port)->Json.obj("tag" to tag,"listen" to "127.0.0.1","port" to port,"protocol" to "socks","settings" to Json.obj("auth" to "password","accounts" to listOf(Json.obj("user" to l.username,"pass" to l.password)),"udp" to true,"ip" to "127.0.0.1"),"sniffing" to Json.obj("enabled" to (tag=="vpn"),"destOverride" to listOf("http","tls","quic"),"routeOnly" to true))}
        val routing=Json.obj("domainStrategy" to "IPIfNonMatch","rules" to rules)
        if(profile.isBalancer){
            rules.forEach{element->val rule=element.asJsonObject;if(rule.text("outboundTag")=="proxy"){rule.remove("outboundTag");rule.addProperty("balancerTag","proxy")}}
            routing.add("rules",rules)
            routing.add("balancers",Json.gson.toJsonTree(listOf(Json.obj("tag" to "proxy","selector" to listOf("proxy-"),"fallbackTag" to "proxy-0","strategy" to Json.obj("type" to "leastPing")))))
        }
        val result=Json.obj("log" to Json.obj("loglevel" to "none"),"stats" to JsonObject(),"policy" to Json.obj("system" to Json.obj("statsOutboundUplink" to true,"statsOutboundDownlink" to true)),"dns" to dns,"inbounds" to inbounds,"outbounds" to (proxyOutbounds+listOf(Json.obj("tag" to "direct","protocol" to "freedom","targetStrategy" to if(blockV6)"ForceIPv4" else "ForceIP"),Json.obj("tag" to "block","protocol" to "blackhole"),Json.obj("tag" to "dns-out","protocol" to "dns"))),"routing" to routing)
        if(profile.isBalancer)result.add("observatory",Json.obj("subjectSelector" to listOf("proxy-"),"probeUrl" to p.probeUrl,"probeInterval" to "${p.healthSeconds}s","enableConcurrency" to true))
        return result.toString()
    }

    private fun rule(r:Rule):JsonObject {
        val value=r.value.removePrefix("*.")
        val o=Json.obj("type" to "field","outboundTag" to r.target.name.lowercase())
        when(r.kind) {
            RuleKind.DOMAIN->o.add("domain",Json.gson.toJsonTree(listOf("full:$value")))
            RuleKind.SUFFIX->o.add("domain",Json.gson.toJsonTree(listOf("domain:$value")))
            RuleKind.IP->o.add("ip",Json.gson.toJsonTree(listOf(value)))
            RuleKind.GEOIP->o.add("ip",Json.gson.toJsonTree(listOf("geoip:$value")))
            RuleKind.GEOSITE->o.add("domain",Json.gson.toJsonTree(listOf("geosite:$value")))
            RuleKind.PORT->o.addProperty("port",value)
            RuleKind.NETWORK->o.addProperty("network",value)
            RuleKind.PROTOCOL->o.add("protocol",Json.gson.toJsonTree(listOf(value)))
        }
        return o
    }
    private fun validateHttps(s:String) { val u=runCatching{URI(s)}.getOrNull();ensure(u?.scheme=="https"&&!u.host.isNullOrBlank()&&u.userInfo==null&&s.length<=2048,"Нужен корректный HTTPS URL") }
    private fun isIp(s:String):Boolean = if(s.contains(':')) s.matches(Regex("[0-9a-fA-F:]+"))&&runCatching{InetAddress.getByName(s).address.size==16}.getOrDefault(false) else s.split('.').let{it.size==4&&it.all{v->v.isNotBlank()&&v.all(Char::isDigit)&&v.toIntOrNull() in 0..255}}
}
