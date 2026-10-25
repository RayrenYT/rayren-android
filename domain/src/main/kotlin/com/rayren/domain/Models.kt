package com.rayren.domain

import com.google.gson.JsonObject
import java.security.MessageDigest

enum class Engine { XRAY, OPENFLUX, OLCRTC }
enum class Mode { AUTO, SMART, MANUAL }
enum class ReservePolicy { DETECT, RESERVE, REGULAR }
enum class AppMode { ALL, INCLUDE, EXCLUDE }
enum class Ipv6Mode { BLOCK, DUAL, PREFER_IPV4, PREFER_IPV6 }
enum class Preset { GLOBAL, RUSSIA_DIRECT, PROXY_ONLY, CUSTOM }
enum class DnsMode { TUNNEL_DOH, TUNNEL_DOT, TUNNEL_UDP, DIRECT_SYSTEM, DIRECT_CUSTOM }
enum class RuleKind { DOMAIN, SUFFIX, IP, GEOIP, GEOSITE, PORT, NETWORK, PROTOCOL }
enum class Target { PROXY, DIRECT, BLOCK }

data class Rule(val kind: RuleKind, val value: String, val target: Target)
data class DnsRule(val suffix:String,val server:String,val direct:Boolean=false)

data class Profile(
    val id: String,
    val name: String,
    val engine: Engine,
    val protocol: String,
    val region: String = "",
    val priority: Int = 0,
    val config: JsonObject,
    val experimental: Boolean = engine == Engine.OLCRTC,
) {
    override fun toString() = "Profile(id=$id, engine=$engine)"
    val supportsIpv6 get() = engine == Engine.XRAY
    val supportsUdp get() = engine != Engine.OLCRTC
    val isBalancer get() = engine == Engine.XRAY && protocol == "smart-balance"
    fun xrayOutbounds(): List<JsonObject> = if(isBalancer) config.getAsJsonArray("outbounds").map{it.asJsonObject} else listOf(config)
}

data class Preferences(
    val mode: Mode = Mode.AUTO,
    val selectedId: String = "",
    val region: String = "",
    val experimental: Boolean = false,
    val appMode: AppMode = AppMode.ALL,
    val packages: Set<String> = emptySet(),
    val ipv6: Ipv6Mode = Ipv6Mode.BLOCK,
    val preset: Preset = Preset.GLOBAL,
    val lanBypass: Boolean = false,
    val rules: List<Rule> = emptyList(),
    val dnsMode: DnsMode = DnsMode.TUNNEL_DOH,
    val dns: String = "https://1.1.1.1/dns-query",
    val strictDns: Boolean = true,
    val dnsRules: List<DnsRule> = emptyList(),
    val reconnect: Boolean = true,
    val timeoutMs: Int = 15000,
    val healthSeconds: Int = 45,
    val mtu: Int = 1400,
    val autoMtu: Boolean = true,
    val probeUrl: String = "https://www.gstatic.com/generate_204",
    val probeStatus: Int = 204,
    val refreshHours: Int = 12,
    val debug: Boolean = false,
    val bypassRuDomains: Boolean = false,
    val bypassSiteGroups: Set<String> = emptySet(),
    val bypassAppGroups: Set<String> = emptySet(),
    val directSites: Set<String> = emptySet(),
    val bypassWhitelistSites: Boolean = false,
    val sortServersByPing: Boolean = false,
    val pingOnOpen: Boolean = false,
    val refreshOnOpen: Boolean = false,
    // Inverted for compatibility: old Gson records default missing booleans to false.
    val disableAutoRefresh: Boolean = false,
    val excludeManualAuto: Boolean = false,
    val excludeManualSmart: Boolean = false,
)

data class Subscription(
    val id: String,
    val url: String,
    val profileIds: List<String>,
    val etag: String = "",
    val modified: String = "",
    val lastSuccess: Long = 0,
    val revision: Long = 0,
    val signingKey: String = "",
    val name: String? = null,
    val pinned: Boolean = false,
    val accountId: String? = null,
    val excludeAuto: Boolean = false,
    val excludeSmart: Boolean = false,
    val reservePolicy: ReservePolicy? = null,
) { override fun toString() = "Subscription(id=$id)" }

data class VaultData(
    val profiles: List<Profile> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    val preferences: Preferences = Preferences(),
)

data class ImportResult(val profiles: List<Profile>, val warnings: List<String> = emptyList(), val revision: Long = 0, val accountId:String? = null)
class ValidationException(val reason: String, val code: String = "VALIDATION") : IllegalArgumentException(reason)
fun ensure(condition: Boolean, reason: String, code: String = "VALIDATION") { if (!condition) throw ValidationException(reason, code) }
fun stableId(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
fun JsonObject.text(name: String, fallback: String = "") = get(name)?.takeIf { it.isJsonPrimitive }?.asString ?: fallback
fun JsonObject.number(name: String, fallback: Int = 0) = text(name).toIntOrNull() ?: fallback
