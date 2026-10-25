package com.rayren.domain

import org.junit.Assert.*
import org.junit.Test
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom
import java.util.Base64

class SignatureTest {
    private val secret=Ed25519PrivateKeyParameters(SecureRandom())
    private fun b64(bytes:ByteArray)=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private val payload="""{"schema_version":1,"revision":4,"profiles":["$VLESS"]}"""
    private fun envelope():String {val signer=Ed25519Signer();signer.init(true,secret);val data=payload.toByteArray();signer.update(data,0,data.size);return Json.obj("format" to "rayren-signed-v1","payload" to b64(data),"signature" to b64(signer.generateSignature())).toString()}
    @Test fun validExactBytes(){assertEquals(payload,ManifestVerifier.payload(envelope(),b64(secret.generatePublicKey().encoded)))}
    @Test fun tamperingRejected(){val obj=Json.parse(envelope()).asJsonObject;obj.addProperty("payload",b64(payload.replace("revision\":4","revision\":5").toByteArray()));bad{ManifestVerifier.payload(obj.toString(),b64(secret.generatePublicKey().encoded))}}
    @Test fun pinnedKeyCannotDowngradeToUnsigned(){bad{ManifestVerifier.payload(payload,b64(secret.generatePublicKey().encoded))}}
    @Test fun cannotTrustKeyFromEnvelope(){bad{ManifestVerifier.payload(envelope(),"")}}
    @Test fun wrongSignerRejected(){bad{ManifestVerifier.payload(envelope(),b64(Ed25519PrivateKeyParameters(SecureRandom()).generatePublicKey().encoded))}}
}
