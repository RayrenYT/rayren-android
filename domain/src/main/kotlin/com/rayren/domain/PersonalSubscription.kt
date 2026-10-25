package com.rayren.domain

import com.google.gson.JsonObject
import java.net.URI

object PersonalSubscription {
    const val BOT_URL="https://t.me/rayrenhelpbot?start=rayren_app"
    fun isUrl(url:String):Boolean = runCatching {
        val u=URI(url)
        u.scheme=="https"&&u.host=="rayren.icu"&&u.port==-1&&u.userInfo==null&&u.fragment==null&&u.query==null&&
            Regex("/incy/rayren-app/[A-Za-z0-9_-]{43}").matches(u.path)
    }.getOrDefault(false)
    fun parse(obj:JsonObject):ImportResult {
        ensure(obj.number("version")==1,"Обновите Rayren для импорта аккаунта")
        val account=obj.text("account_id")
        ensure(Regex("[a-f0-9]{32}").matches(account),"Некорректный аккаунт подписки")
        val documents=obj.getAsJsonArray("documents") ?: throw ValidationException("Нет основной подписки")
        val reserve=obj.getAsJsonArray("reserve_profiles") ?: throw ValidationException("Нет списка БС")
        ensure(documents.size()<=ImportLimits.MAX_PROFILES&&reserve.size()<=32,"Слишком большой аккаунт")
        val main=mutableListOf<Profile>()
        documents.forEach { doc ->
            ensure(doc.isJsonObject&&!doc.asJsonObject.has("format")&&!doc.asJsonObject.has("documents"),"Некорректный документ аккаунта")
            val rows=ImportParser.parse(doc.toString()).profiles
            ensure(rows.all{it.engine==Engine.XRAY},"Некорректные основные серверы")
            ensure(main.size+rows.size<=ImportLimits.MAX_PROFILES,ImportLimits.COUNT_MESSAGE)
            main.addAll(rows)
        }
        val bs=reserve.map { entry ->
            ensure(entry.isJsonPrimitive&&entry.asJsonPrimitive.isString,"Некорректный профиль БС")
            ImportParser.parseUri(entry.asString).also{ensure(it.engine!=Engine.XRAY,"Некорректный движок БС")}.let{it.copy(name="БС · ${it.name}")}
        }
        ensure(main.isNotEmpty()||bs.isEmpty(),"БС без действующего аккаунта")
        return ImportResult((main+bs).distinctBy{it.id},accountId=account)
    }
    fun previous(data:VaultData,url:String,accountId:String?,old:Subscription?):Subscription? {
        if(accountId==null){ensure(old?.accountId==null,"Ответ аккаунта изменил формат");return old}
        ensure(isUrl(url),"Личный аккаунт импортируется только с HTTPS Rayren")
        ensure(old?.accountId==null||old.accountId==accountId,"Ссылка принадлежит другому аккаунту")
        return old ?: data.subscriptions.find{it.accountId==accountId&&isUrl(it.url)}
    }
}
