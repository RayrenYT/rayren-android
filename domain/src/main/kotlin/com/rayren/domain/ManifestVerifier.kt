package com.rayren.domain

import java.util.Base64
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

object ManifestVerifier {
    fun payload(raw:String,pinnedKey:String):String {
        val trimmed=raw.trim()
        val obj=if(trimmed.startsWith("{")) Json.parse(trimmed).asJsonObject else null
        if(obj?.text("format")!="rayren-signed-v1") {
            ensure(pinnedKey.isBlank(),"Подписка должна иметь подпись")
            return raw
        }
        ensure(pinnedKey.isNotBlank(),"Для подписанного manifest нужен заранее полученный ключ проверки")
        return try {
            val key=Base64.getUrlDecoder().decode(pinnedKey.trim())
            val data=Base64.getUrlDecoder().decode(obj.text("payload"))
            val signature=Base64.getUrlDecoder().decode(obj.text("signature"))
            ensure(key.size==32&&signature.size==64&&data.size<=ImportLimits.MAX_BYTES,"Некорректный размер подписи")
            val verifier=Ed25519Signer()
            verifier.init(false,Ed25519PublicKeyParameters(key,0))
            verifier.update(data,0,data.size)
            ensure(verifier.verifySignature(signature),"Подпись manifest не прошла проверку")
            data.toString(Charsets.UTF_8)
        } catch(e:ValidationException){throw e} catch(_:Exception){throw ValidationException("Повреждённая подпись manifest")}
    }
}
