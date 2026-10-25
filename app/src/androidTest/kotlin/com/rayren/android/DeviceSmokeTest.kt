package com.rayren.android

import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rayren.android.data.SecureVault
import com.rayren.android.vpn.TProxyService
import com.rayren.domain.*
import com.rayren.nativebridge.rayrencore.Rayrencore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** These tests never touch the user's vault and do not request VPN permission. */
@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    @Test fun nativeLibrariesLoad(){
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        Rayrencore.initialize(context.filesDir.absolutePath)
        assertTrue(Rayrencore.version().contains("Xray"))
        assertFalse(TProxyService().TProxyIsRunning())
    }
    @Test fun vaultRoundTripFreshIvAndTamperDetection(){
        val app=ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir=File(app.cacheDir,"vault-test-${System.nanoTime()}").apply{mkdirs()}
        val isolated=object:ContextWrapper(app){override fun getNoBackupFilesDir()=dir}
        try{
            val profile=ImportParser.parse("vless://11111111-1111-4111-8111-111111111111@vpn.example:443?security=none#Fixture").profiles.single()
            val data=VaultData(profiles=listOf(profile))
            val vault=SecureVault(isolated);vault.write(data)
            val file=File(dir,"rayren-vault.bin");val first=file.readBytes()
            assertFalse(first.toString(Charsets.UTF_8).contains("vpn.example"))
            assertEquals(profile.id,vault.read().profiles.single().id)
            vault.write(data);val second=file.readBytes();assertFalse(first.contentEquals(second))
            second[second.lastIndex]=(second.last().toInt() xor 1).toByte();file.writeBytes(second)
            try{vault.read();fail("Tampered vault was accepted")}catch(_:javax.crypto.AEADBadTagException){}
            assertTrue(file.readBytes().contentEquals(second))
        }finally{dir.listFiles()?.forEach{it.delete()};dir.delete()}
    }
}
