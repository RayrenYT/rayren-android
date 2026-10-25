package com.rayren.android.data

import com.rayren.domain.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class FetchResult(val result:ImportResult?,val etag:String,val modified:String,val name:String?=null)

class Subscriptions(client:OkHttpClient=OkHttpClient()) {
    private val client=client.newBuilder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(25,TimeUnit.SECONDS).callTimeout(40,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    fun fetch(url:String,old:Subscription?=null,key:String="",onStage:(ImportStage)->Unit={}):FetchResult {
        val safe=ImportParser.subscriptionUrl(url)
        // Existing Rayren/3x-ui deployments select their native JSON using the Happ/ marker.
        // Identify Rayren truthfully while advertising the JSON format we can import.
        val request=Request.Builder().url(safe)
            .header("User-Agent","Rayren/${com.rayren.android.BuildConfig.VERSION_NAME} Android (Happ/JSON-compatible)")
            .header("Accept","application/json, text/plain;q=0.9").apply{
            old?.etag?.takeIf{it.isNotBlank()}?.let{header("If-None-Match",it)}
            old?.modified?.takeIf{it.isNotBlank()}?.let{header("If-Modified-Since",it)}
        }.build()
        onStage(ImportStage.DOWNLOAD)
        client.newCall(request).execute().use { response->
            if(response.code==304){ensure(old!=null,"304 без сохранённой подписки");return FetchResult(null,old!!.etag,old.modified)}
            ensure(response.isSuccessful,"Сервер подписки вернул HTTP ${response.code}. Сохранённые профили не изменены.","HTTP_${response.code}")
            val body=response.body ?: throw ValidationException("Пустой ответ подписки")
            ensure(body.contentLength()<=ImportLimits.MAX_BYTES,ImportLimits.SIZE_MESSAGE)
            val output=java.io.ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer=ByteArray(8192)
                while(true) { val count=input.read(buffer);if(count<0)break;ensure(output.size()+count<=ImportLimits.MAX_BYTES,ImportLimits.SIZE_MESSAGE);output.write(buffer,0,count) }
            }
            val bytes=output.toByteArray()
            ensure(bytes.size<=ImportLimits.MAX_BYTES,ImportLimits.SIZE_MESSAGE)
            onStage(ImportStage.VERIFY)
            val payload=ManifestVerifier.payload(bytes.toString(Charsets.UTF_8),key)
            onStage(ImportStage.PARSE)
            val result=ImportParser.parse(payload)
            ensure(result.accountId==null||PersonalSubscription.isUrl(safe),"Личный аккаунт импортируется только с HTTPS Rayren")
            ensure(!PersonalSubscription.isUrl(safe)||result.accountId!=null,"Неверный ответ личного аккаунта")
            ensure(result.revision >= (old?.revision ?: 0),"Отклонён откат ревизии подписки")
            return FetchResult(result,response.header("ETag").orEmpty().take(512),response.header("Last-Modified").orEmpty().take(128),ServerGroups.title(response.header("profile-title")))
        }
    }
}
