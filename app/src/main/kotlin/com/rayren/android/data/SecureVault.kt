package com.rayren.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.rayren.domain.Json
import com.rayren.domain.VaultData
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureVault(context:Context) {
    private val file=AtomicFile(File(context.noBackupFilesDir,"rayren-vault.bin"))
    private val alias="rayren.vault.aes.v1"
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (store.getKey(alias,null) as? SecretKey)?.let{return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    @Synchronized fun read():VaultData {
        if(!file.baseFile.exists()&&!File(file.baseFile.path+".bak").exists())return VaultData()
        val raw=file.readFully(); require(raw.size in 32..com.rayren.domain.ImportLimits.VAULT_BYTES && raw[0]==1.toByte())
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,raw.copyOfRange(1,13)))
        cipher.updateAAD(byteArrayOf(1))
        val plain=cipher.doFinal(raw.copyOfRange(13,raw.size))
        return Json.gson.fromJson(plain.toString(Charsets.UTF_8),VaultData::class.java)
    }
    @Synchronized fun write(value:VaultData) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key()); cipher.updateAAD(byteArrayOf(1))
        val plain=Json.gson.toJson(value).toByteArray()
        com.rayren.domain.ensure(plain.size<=com.rayren.domain.ImportLimits.VAULT_BYTES-64,"Защищённое хранилище превысило 64 МБ")
        val ciphertext=cipher.doFinal(plain)
        val data=ByteBuffer.allocate(1+cipher.iv.size+ciphertext.size).put(1.toByte()).put(cipher.iv).put(ciphertext).array()
        val output=file.startWrite()
        try{output.write(data);file.finishWrite(output)}catch(e:Exception){file.failWrite(output);throw e}
    }
}
