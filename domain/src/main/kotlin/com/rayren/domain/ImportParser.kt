package com.rayren.domain

import com.google.gson.*
import java.io.ByteArrayInputStream
import java.net.IDN
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.UUID
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

object Json {
    val gson = GsonBuilder().disableHtmlEscaping().create()
    fun obj(vararg pairs: Pair<String, Any?>): JsonObject = gson.toJsonTree(pairs.toMap()).asJsonObject
    fun parse(text: String): JsonElement {
        ensure(text.toByteArray().size <= ImportLimits.MAX_BYTES, ImportLimits.SIZE_MESSAGE)
        var depth = 0; var quoted = false; var escaped = false
        text.forEach { c ->
            if (escaped) escaped = false
            else if (quoted && c == '\\') escaped = true
            else if (c == '"') quoted = !quoted
            else if (!quoted) { if (c == '{' || c == '[') depth++; if (c == '}' || c == ']') depth--; ensure(depth in 0..32, "Слишком сложный JSON") }
        }
        ensure(depth == 0 && !quoted, "Незавершённый JSON")
        return try { val reader=com.google.gson.stream.JsonReader(java.io.StringReader(text));reader.strictness=Strictness.STRICT;val element=JsonParser.parseReader(reader);ensure(reader.peek()==com.google.gson.stream.JsonToken.END_DOCUMENT,"Лишние данные после JSON");element } catch (_: Exception) { throw ValidationException("Некорректный JSON") }
    }
}

object ImportParser {
    private val protocols = setOf("vless", "vmess", "trojan", "shadowsocks", "hysteria")
    private val providers = setOf("cupsonline", "yandex", "vyandex", "boards", "mailru", "oneme", "direct", "telemost")
    fun parse(input: String): ImportResult {
        ensure(input.length <= ImportLimits.MAX_BYTES && input.toByteArray(Charsets.UTF_8).size <= ImportLimits.MAX_BYTES, ImportLimits.SIZE_MESSAGE)
        var text = input.trim().removePrefix("\uFEFF")
        ensure(text.isNotEmpty(), "Пустая подписка")
        if (!text.startsWith("{") && !text.startsWith("[") && !text.contains("://")) text = decode64(text).toString(Charsets.UTF_8).trim()
        val result = if (text.startsWith("{") || text.startsWith("[")) parseJson(Json.parse(text)) else {
            val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.take(ImportLimits.MAX_PROFILES+1).toList()
            ensure(lines.size <= ImportLimits.MAX_PROFILES, ImportLimits.COUNT_MESSAGE)
            ImportResult(lines.mapIndexed { index, line ->
                try { parseUri(line) } catch (e: ValidationException) {
                    throw ValidationException("Строка ${index + 1}: ${e.reason}", e.code)
                }
            })
        }
        ensure(result.profiles.isNotEmpty()||result.accountId!=null, "Поддерживаемые профили не найдены")
        ensure(result.profiles.size <= ImportLimits.MAX_PROFILES, ImportLimits.COUNT_MESSAGE)
        return result.copy(profiles = result.profiles.distinctBy { it.id })
    }

    fun subscriptionUrl(value: String): String {
        val text = value.trim().removePrefix("\uFEFF").trim()
        val raw = if (text.startsWith("rayren://", ignoreCase = true)) {
            val uri = try { URI(text) } catch (_: Exception) { throw ValidationException("Некорректная ссылка импорта", "INVALID_IMPORT_URL") }
            ensure(uri.host == "import", "Неизвестная команда Rayren")
            query(uri.rawQuery)["url"].orEmpty()
        } else text
        val uri = try { URI(raw) } catch (_: Exception) { throw ValidationException("Некорректный URL подписки", "INVALID_SUBSCRIPTION_URL") }
        ensure(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null && uri.port in -1..65535 && uri.port != 0 && raw.length <= 4096, "Для подписки нужен HTTP или HTTPS URL без логина и фрагмента", "INVALID_SUBSCRIPTION_URL")
        return uri.scheme.lowercase() + uri.toASCIIString().substring(uri.scheme.length)
    }

    private fun parseJson(root: JsonElement): ImportResult {
        if (root.isJsonArray) {
            ensure(root.asJsonArray.size() <= ImportLimits.MAX_PROFILES, ImportLimits.COUNT_MESSAGE)
            val profiles=mutableListOf<Profile>();val warnings=mutableSetOf<String>()
            root.asJsonArray.forEach {
                val result=parseJson(it)
                ensure(profiles.size+result.profiles.size<=ImportLimits.MAX_PROFILES,ImportLimits.COUNT_MESSAGE)
                profiles.addAll(result.profiles);warnings.addAll(result.warnings)
            }
            return ImportResult(profiles,warnings.toList())
        }
        ensure(root.isJsonObject, "Ожидается объект подписки")
        val obj = root.asJsonObject
        if(obj.text("format")=="rayren-account-v1")return PersonalSubscription.parse(obj)
        if (obj.has("version") || obj.has("schema_version")) {
            val version = obj.number("schema_version", obj.number("version"))
            ensure(version == 1, "Неподдерживаемая версия manifest")
            ensure(obj.number("min_client_version", 1) <= 1, "Нужно обновить Rayren")
            val revision = obj.text("revision", "0").toLongOrNull() ?: throw ValidationException("Некорректная ревизия")
            ensure(revision >= 0, "Некорректная ревизия")
            obj.text("expires_at").takeIf { it.isNotBlank() }?.let { expiry ->
                val instant = try { java.time.Instant.parse(expiry) } catch (_: Exception) { throw ValidationException("Некорректный срок manifest") }
                ensure(instant.isAfter(java.time.Instant.now()), "Срок manifest истёк")
            }
            val profiles = obj.getAsJsonArray("profiles") ?: obj.getAsJsonObject("xray")?.getAsJsonArray("profiles")
            ensure(profiles != null, "Manifest не содержит profiles")
            ensure(profiles!!.size() <= ImportLimits.MAX_PROFILES, ImportLimits.COUNT_MESSAGE)
            return ImportResult(profiles.map { item ->
                if (item.isJsonPrimitive) parseUri(item.asString) else {
                    val row = item.asJsonObject
                    val p = if (row.has("uri")) parseUri(row.text("uri")) else legacyOutbound(row.getAsJsonObject("outbound") ?: row)
                    p.copy(name = cleanName(row.text("name", p.name)), region = row.text("region", p.region).take(32), priority = row.number("priority").coerceIn(-100,100))
                }
            }, revision = revision)
        }
        if (obj.has("outbounds")) {
            ensure(obj.getAsJsonArray("outbounds").size() <= ImportLimits.MAX_PROFILES, ImportLimits.COUNT_MESSAGE)
            val out = obj.getAsJsonArray("outbounds").filter { it.isJsonObject && it.asJsonObject.text("protocol") in protocols }
            ensure(out.isNotEmpty(), "JSON не содержит поддерживаемых proxy outbounds")
            val members=out.mapIndexed { index, item -> legacyOutbound(item.asJsonObject).let { p -> p.copy(name = cleanName(if(out.size==1)obj.text("remarks",p.name) else item.asJsonObject.text("tag",p.name).ifBlank{"Сервер ${index+1}"})) } }
            val balancers=obj.getAsJsonObject("routing")?.getAsJsonArray("balancers")?.mapNotNull { entry ->
                if(!entry.isJsonObject)return@mapNotNull null
                val selectors=entry.asJsonObject.getAsJsonArray("selector")?.map{it.asString}.orEmpty()
                val selected=out.mapIndexedNotNull { i,item -> if(selectors.any{item.asJsonObject.text("tag").startsWith(it)})members[i]else null }.distinctBy{it.id}
                if(selected.size !in 2..32)null else SmartBalance.create(selected,cleanName(obj.text("remarks","Smart Balance")).let{if(it.contains("Smart Balance",true))it else "Smart Balance · $it"})
            }.orEmpty()
            return ImportResult(balancers+members, listOf(if(out.size>32)"Серверы импортированы. Большие балансировщики не создаются: выбирайте режим «Авто» или «По пингу»."else "Серверы и балансировщики импортированы. Используются локальные правила Rayren; Smart Balance выбирает по пингу."))
        }
        if (obj.text("protocol") in protocols) return ImportResult(listOf(legacyOutbound(obj)))
        throw ValidationException("Неизвестный формат JSON")
    }

    fun parseUri(raw: String): Profile {
        ensure(raw.length <= 65536, "Ссылка слишком длинная")
        if (raw.startsWith("openflux://")) return flux(raw)
        if (raw.startsWith("olcrtc://")) return rtc(raw)
        if (raw.startsWith("vmess://")) return vmess(raw)
        val uri = try { URI(raw) } catch (_: Exception) { throw ValidationException("Некорректная ссылка сервера") }
        val scheme = uri.scheme?.lowercase()
        ensure(scheme in setOf("vless", "trojan", "ss", "hy2", "hysteria2"), "Неподдерживаемый протокол. Вставьте ссылку подписки или поддерживаемый ключ сервера.", "UNSUPPORTED_PROTOCOL")
        val host = host(uri.host.orEmpty().removeSurrounding("[", "]"))
        val port = uri.port.takeIf { it > 0 } ?: 443
        ensure(port in 1..65535, "Некорректный порт")
        val q = query(uri.rawQuery)
        val user = decode(uri.rawUserInfo.orEmpty())
        ensure(user.isNotEmpty() && user.length <= 4096, "Нет реквизитов сервера")
        val stream = stream(q, host, if (scheme == "trojan" || scheme == "hy2" || scheme == "hysteria2") "tls" else "none")
        val outbound = when (scheme) {
            "vless" -> {
                ensure(runCatching { UUID.fromString(user) }.isSuccess, "Некорректный UUID VLESS")
                val flow = q["flow"].orEmpty(); ensure(flow in setOf("", "xtls-rprx-vision"), "Неподдерживаемый flow")
                Json.obj("protocol" to "vless", "settings" to Json.obj("vnext" to listOf(Json.obj("address" to host,"port" to port,"users" to listOf(Json.obj("id" to user,"encryption" to "none","flow" to flow)) ))), "streamSettings" to stream)
            }
            "trojan" -> Json.obj("protocol" to "trojan", "settings" to Json.obj("servers" to listOf(Json.obj("address" to host,"port" to port,"password" to user))), "streamSettings" to stream)
            "ss" -> {
                ensure(!q.containsKey("plugin"), "Shadowsocks plugin пока не поддерживается")
                val auth = if (user.contains(':')) user else decode64(user).toString(Charsets.UTF_8)
                val pair = auth.split(':',limit=2); ensure(pair.size==2, "Некорректный Shadowsocks ключ")
                ensure(pair[0] in setOf("aes-128-gcm","aes-256-gcm","chacha20-ietf-poly1305","2022-blake3-aes-128-gcm","2022-blake3-aes-256-gcm","2022-blake3-chacha20-poly1305"),"Неподдерживаемый шифр Shadowsocks")
                Json.obj("protocol" to "shadowsocks", "settings" to Json.obj("servers" to listOf(Json.obj("address" to host,"port" to port,"method" to pair[0],"password" to pair[1]))))
            }
            else -> {
                ensure(q["insecure"] !in setOf("1","true"), "Нельзя отключать проверку TLS")
                val tls=Json.obj("serverName" to (q["sni"] ?: host),"allowInsecure" to false,"alpn" to listOf("h3"))
                addCertificatePin(tls,q)
                val transport=Json.obj("network" to "hysteria","security" to "tls","tlsSettings" to tls,"hysteriaSettings" to Json.obj("version" to 2,"auth" to user))
                val obfs=q["obfs"].orEmpty()
                ensure(obfs in setOf("","salamander"),"Неподдерживаемая маскировка Hysteria", "UNSUPPORTED_HYSTERIA_OBFS")
                if(obfs=="salamander") {
                    val password=q["obfs-password"].orEmpty()
                    ensure(password.length in 1..4096,"Нет корректного пароля Salamander", "INVALID_SALAMANDER_PASSWORD")
                    transport.add("finalmask",Json.obj("udp" to listOf(Json.obj("type" to "salamander","settings" to Json.obj("password" to password)))))
                } else ensure(q["obfs-password"].isNullOrEmpty(),"Пароль маскировки задан без Salamander", "INVALID_SALAMANDER_PASSWORD")
                Json.obj("protocol" to "hysteria", "settings" to Json.obj("version" to 2,"address" to host,"port" to port), "streamSettings" to transport)
            }
        }
        return profile(cleanName(decode(uri.rawFragment ?: host)), Engine.XRAY, outbound.text("protocol"), outbound)
    }

    private fun stream(q: Map<String,String>, host: String, defaultSecurity: String): JsonObject {
        val network = q["type"] ?: "tcp"
        ensure(network in setOf("tcp","raw","ws","grpc","xhttp","httpupgrade"), "Неподдерживаемый транспорт Xray")
        val security = q["security"] ?: defaultSecurity
        ensure(security in setOf("none","tls","reality"),"Неподдерживаемая защита Xray")
        ensure(q["allowInsecure"] !in setOf("1","true"), "Нельзя отключать проверку TLS")
        val o = Json.obj("network" to network,"security" to security)
        when (security) {
            "tls" -> {
                val tls=Json.obj("serverName" to (q["sni"] ?: host),"allowInsecure" to false,"fingerprint" to (q["fp"] ?: "chrome"),"alpn" to (q["alpn"]?.split(',') ?: listOf("h2","http/1.1")))
                addCertificatePin(tls,q)
                o.add("tlsSettings",tls)
            }
            "reality" -> {
                ensure(q["pbk"]?.matches(Regex("[A-Za-z0-9_-]{43}")) == true,"Некорректный REALITY public key")
                val sid=q["sid"].orEmpty(); ensure(sid.matches(Regex("[0-9a-fA-F]{0,16}")) && sid.length%2==0,"Некорректный REALITY short ID")
                o.add("realitySettings",Json.obj("serverName" to (q["sni"] ?: host),"fingerprint" to (q["fp"] ?: "chrome"),"publicKey" to q["pbk"],"shortId" to sid,"spiderX" to (q["spx"] ?: "/")))
                q["pqv"]?.takeIf{it.isNotEmpty()}?.let { key ->
                    ensure(key.matches(Regex("[A-Za-z0-9_-]+")) && decode64(key).size==1952,"Некорректный REALITY ML-DSA ключ", "INVALID_REALITY_PQV")
                    o.getAsJsonObject("realitySettings").addProperty("mldsa65Verify",key)
                }
            }
        }
        when (network) {
            "ws" -> o.add("wsSettings",Json.obj("path" to (q["path"] ?: "/"),"headers" to Json.obj("Host" to (q["host"] ?: host))))
            "grpc" -> o.add("grpcSettings",Json.obj("serviceName" to q["serviceName"].orEmpty(),"multiMode" to (q["mode"]=="multi")))
            "xhttp" -> o.add("xhttpSettings",Json.obj("path" to (q["path"] ?: "/"),"host" to (q["host"] ?: host),"mode" to (q["mode"] ?: "auto")))
            "httpupgrade" -> o.add("httpupgradeSettings",Json.obj("path" to (q["path"] ?: "/"),"host" to (q["host"] ?: host)))
        }
        return o
    }

    private fun addCertificatePin(tls:JsonObject,q:Map<String,String>) {
        val raw=(q["pinSHA256"] ?: q["pcs"]).orEmpty()
        if(raw.isEmpty())return
        val pins=raw.split(',').map{it.trim().replace(":", "").lowercase()}
        ensure(pins.size in 1..8 && pins.all{it.matches(Regex("[0-9a-f]{64}"))},"Некорректный SHA-256 сертификата", "INVALID_CERTIFICATE_PIN")
        tls.addProperty("pinnedPeerCertSha256",pins.joinToString(","))
    }

    private fun legacyOutbound(o: JsonObject): Profile {
        val protocol=o.text("protocol"); ensure(protocol in protocols,"Неподдерживаемый outbound")
        // Reconstruct an allowlisted URI and parse it. Never execute imported inbounds, paths, APIs or routing.
        val settings=o.getAsJsonObject("settings") ?: throw ValidationException("Нет settings")
        // Xray/3x-ui also emit simplified settings: address, port, id/password at the top level.
        val server = (settings.getAsJsonArray(if(protocol in setOf("vless","vmess")) "vnext" else "servers")?.firstOrNull()?.asJsonObject)
            ?: settings.takeIf { it.has("address") && it.has("port") }
            ?: throw ValidationException("Нет адреса сервера", "MISSING_SERVER_ADDRESS")
        val address=host(server.text("address")); val port=server.number("port"); ensure(port in 1..65535,"Некорректный порт")
        val user=server.getAsJsonArray("users")?.firstOrNull()?.asJsonObject ?: server.takeIf { protocol in setOf("vless","vmess") }
        val s=o.getAsJsonObject("streamSettings") ?: JsonObject()
        val security=s.text("security","none"); val tls=s.getAsJsonObject(if(security=="reality") "realitySettings" else "tlsSettings") ?: JsonObject()
        ensure(tls.text("allowInsecure")!="true","Нельзя отключать проверку TLS")
        val network=s.text("network","tcp")
        val transport=s.getAsJsonObject(when(network){"ws"->"wsSettings";"grpc"->"grpcSettings";"xhttp"->"xhttpSettings";else->"httpupgradeSettings"}) ?: JsonObject()
        val q=mutableMapOf("security" to security,"type" to network,"sni" to tls.text("serverName",address),"fp" to tls.text("fingerprint","chrome"),"pbk" to tls.text("publicKey",tls.text("password")),"sid" to tls.text("shortId"),"flow" to (user?.text("flow").orEmpty()),"path" to transport.text("path","/"),"host" to (transport.getAsJsonObject("headers")?.text("Host") ?: transport.text("host",address)),"serviceName" to transport.text("serviceName"),"mode" to transport.text("mode","auto"))
        tls.text("pinnedPeerCertSha256").takeIf{it.isNotEmpty()}?.let{q["pinSHA256"]=it}
        tls.text("mldsa65Verify").takeIf{it.isNotEmpty()}?.let{q["pqv"]=it}
        tls.text("spiderX").takeIf{it.isNotEmpty()}?.let{q["spx"]=it}
        tls.getAsJsonArray("alpn")?.takeIf{it.size()>0}?.let{q["alpn"]=it.joinToString(","){value->value.asString}}
        s.getAsJsonObject("finalmask")?.takeIf{it.size()>0}?.let { mask ->
            ensure(protocol=="hysteria" && mask.keySet()==setOf("udp"),"Неподдерживаемая маскировка Xray", "UNSUPPORTED_FINALMASK")
            val udp=mask.getAsJsonArray("udp")
            ensure(udp?.size()==1 && udp[0].isJsonObject,"Неподдерживаемая маскировка Xray", "UNSUPPORTED_FINALMASK")
            val item=udp[0].asJsonObject
            ensure(item.text("type")=="salamander","Неподдерживаемая маскировка Xray", "UNSUPPORTED_FINALMASK")
            val obfsSettings=item.getAsJsonObject("settings") ?: throw ValidationException("Нет настроек Salamander","INVALID_SALAMANDER_PASSWORD")
            ensure(obfsSettings.keySet()==setOf("password"),"Неподдерживаемые настройки Salamander","UNSUPPORTED_FINALMASK")
            q["obfs"]="salamander";q["obfs-password"]=obfsSettings.text("password")
        }
        val auth=when(protocol){"vless","vmess"->user?.text("id").orEmpty();"shadowsocks"->server.text("method")+":"+server.text("password");"hysteria"->s.getAsJsonObject("hysteriaSettings")?.text("auth").orEmpty();else->server.text("password")}
        if(protocol=="vmess") {
            val clean=Json.obj("protocol" to "vmess","settings" to Json.obj("vnext" to listOf(Json.obj("address" to address,"port" to port,"users" to listOf(Json.obj("id" to auth,"alterId" to 0,"security" to "auto"))))),"streamSettings" to stream(q,address,"none"))
            ensure(runCatching{UUID.fromString(auth)}.isSuccess,"Некорректный UUID VMess")
            return profile(cleanName(o.text("tag",address)),Engine.XRAY,protocol,clean)
        }
        if(protocol=="hysteria") { q["security"]="tls"; q.remove("type") }
        val scheme=when(protocol){"shadowsocks"->"ss";"hysteria"->"hysteria2";else->protocol}
        val url="$scheme://${encode(auth)}@${if(address.contains(':')) "[$address]" else address}:$port?${q.entries.joinToString("&"){encode(it.key)+"="+encode(it.value)}}#${encode(o.text("tag",address))}"
        return parseUri(url)
    }

    private fun vmess(raw:String):Profile {
        val o=Json.parse(decode64(raw.removePrefix("vmess://")).toString(Charsets.UTF_8)).asJsonObject
        val address=host(o.text("add")); val id=o.text("id")
        ensure(runCatching{UUID.fromString(id)}.isSuccess,"Некорректный UUID VMess")
        ensure(o.number("port") in 1..65535,"Некорректный порт")
        val q=mapOf("type" to o.text("net","tcp"),"security" to o.text("tls","none").ifBlank{"none"},"sni" to o.text("sni",address),"host" to o.text("host",address),"path" to o.text("path","/"))
        val out=Json.obj("protocol" to "vmess","settings" to Json.obj("vnext" to listOf(Json.obj("address" to address,"port" to o.number("port"),"users" to listOf(Json.obj("id" to id,"alterId" to 0,"security" to "auto"))))),"streamSettings" to stream(q,address,"none"))
        return profile(cleanName(o.text("ps",address)),Engine.XRAY,"vmess",out)
    }

    private fun flux(raw:String):Profile {
        val payload=raw.removePrefix("openflux://")
        val modern=payload.startsWith("v1/")
        val data=if(modern) {
            val compressed=decode64(payload.removePrefix("v1/")); val inflater=Inflater(true)
            try { InflaterInputStream(ByteArrayInputStream(compressed),inflater).use { input ->
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(1024)
                while(true){val n=input.read(buffer);if(n<0)break;ensure(out.size()+n<=16384,"OpenFlux payload слишком большой");out.write(buffer,0,n)}
                out.toByteArray()
            } } catch(e:ValidationException){throw e} catch(_:Exception){throw ValidationException("Повреждённый OpenFlux URI")} finally { inflater.end() }
        } else decode64(payload)
        val o=Json.parse(data.toString(Charsets.UTF_8)).asJsonObject
        ensure(o.text("mode").isEmpty(),"OpenFlux stream-mode пока не поддерживается")
        val transports=if(modern) o.getAsJsonArray("transports") ?: throw ValidationException("Нет OpenFlux transports") else null
        ensure(transports==null || transports.size() in 1..8,"Некорректный список OpenFlux")
        val first=transports?.first()?.asJsonObject
        val provider=first?.text("type") ?: o.text("transport","yandex")
        ensure(provider in providers,"Неподдерживаемый OpenFlux provider")
        transports?.forEach { ensure(it.isJsonObject && it.asJsonObject.text("type") in providers,"Неподдерживаемый OpenFlux provider") }
        val secret=o.text("secret",o.text("key")); ensure(secret.length>=16,"Ключ OpenFlux должен содержать не менее 16 символов")
        val codec=o.text("codec","batched").ifBlank{"batched"}; ensure(codec in setOf("batched","legacy"),"Неизвестный codec")
        val url=first?.text("url") ?: o.text("url")
        ensure(url.isNotBlank() || provider=="oneme" || (provider=="direct"&&!first?.text("dial").isNullOrBlank()),"Нет параметров OpenFlux")
        val config=Json.obj("provider" to provider,"url" to url,"secret" to secret,"codec" to codec,"token" to o.text("token"),"uid" to o.text("uid"))
        if(modern && (o.text("negotiate")=="true" || transports!!.size()>1 || o.text("context").isNotBlank() || provider=="direct")) {
            val clean=transports!!.map { e->e.asJsonObject.let { t -> Json.obj("type" to t.text("type"),"name" to t.text("name"),"url" to t.text("url"),"priority" to t.number("priority"),"params" to if(t.text("type")=="direct") Json.obj("dial" to t.text("dial")) else JsonObject()) } }
            config.addProperty("session",Json.obj("context" to o.text("context"),"transports" to clean).toString())
        }
        return profile(cleanName(o.text("name",o.text("remarks","OpenFlux · $provider"))),Engine.OPENFLUX,provider,config)
    }

    private fun rtc(raw:String):Profile {
        val m=Regex("^olcrtc://([a-z]+)\\?([a-z0-9]+)(?:<([^>]*)>)?@(.+)#([0-9a-fA-F]{64})(?:\\$(.*))?$").matchEntire(raw) ?: throw ValidationException("Некорректный olcRTC URI")
        val (provider,transport,options,room,key,name)=m.destructured
        ensure(provider in setOf("telemost","jitsi","wbstream"),"Неподдерживаемый RTC provider")
        ensure(transport in setOf("datachannel","vp8channel","seichannel"),"Неподдерживаемый RTC transport")
        val q=query(options)
        val roomUrl=if(room.startsWith("https://")) room else when(provider){"telemost"->"https://telemost.yandex.ru/j/$room";"jitsi"->"https://meet.jit.si/$room";else->room}
        ensure(roomUrl.length<=4096,"Слишком длинный room URL")
        return profile(cleanName(if(name.isBlank()) "olcRTC · $provider" else decode(name)),Engine.OLCRTC,provider,Json.obj("provider" to provider,"transport" to transport,"room" to roomUrl,"key" to key,"device" to q["clientId"].orEmpty()))
    }

    private fun profile(name:String,engine:Engine,protocol:String,config:JsonObject):Profile = Profile(stableId(engine.name+config.toString()),name,engine,protocol,guessRegion(name),config=config)
    private fun guessRegion(name:String):String = when { name.contains("Finland",true)||name.contains("Фин",true)->"FI";name.contains("Nether",true)||name.contains("Нидер",true)->"NL";name.contains("Russia",true)||name.contains("Россия",true)->"RU";name.contains("warp",true)->"WARP";else->"" }
    private fun cleanName(value:String)=value.filter { !it.isISOControl() }.take(100).ifBlank { "Сервер" }
    private fun host(value:String):String { ensure(value.isNotBlank()&&value.length<=253&&!value.any { it.isWhitespace()||it in "/\\@" },"Некорректный адрес сервера"); return if(value.contains(':')) value else try{IDN.toASCII(value)}catch(_:Exception){throw ValidationException("Некорректный адрес сервера")} }
    private fun query(raw:String?)=raw.orEmpty().split('&').filter{it.isNotEmpty()}.associate { val kv=it.split('=',limit=2); decode(kv[0]) to decode(kv.getOrElse(1){""}) }
    private fun decode(value:String)=try{URLDecoder.decode(value.replace("+","%2B"),"UTF-8")}catch(_:Exception){throw ValidationException("Ошибка кодирования ссылки")}
    private fun encode(value:String)=java.net.URLEncoder.encode(value,"UTF-8").replace("+","%20")
    private fun decode64(raw:String):ByteArray = try { val s=raw.filterNot{it.isWhitespace()}; if(s.contains('-')||s.contains('_')) Base64.getUrlDecoder().decode(s) else Base64.getDecoder().decode(s) } catch(_:Exception){throw ValidationException("Некорректный Base64")}
}
