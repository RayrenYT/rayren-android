package com.rayren.android

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.rayren.android.ui.RayrenScreen

class MainActivity:ComponentActivity() {
    private var importDraft by mutableStateOf<String?>(null)
    private val controller get()=applicationContext.rayren
    private val permission=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if(result.resultCode==RESULT_OK)startVpn() else controller.message("Разрешение VPN не предоставлено")
    }
    private val notifications=registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val scan=registerForActivityResult(ScanContract()) { result -> result.contents?.let{importDraft=it} }
    private val export=registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if(uri!=null)runCatching { contentResolver.openOutputStream(uri)?.bufferedWriter()?.use{it.write(controller.diagnostics())} }
            .onSuccess{controller.message("Диагностика сохранена без ключей и адресов серверов")}
            .onFailure{controller.message("Не удалось сохранить отчёт")}
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);enableEdgeToEdge();acceptIntent(intent)
        setContent { RayrenScreen(controller,importDraft,{importDraft=it},::connect,{
            scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Наведите камеру на QR-код подписки").setBeepEnabled(false).setOrientationLocked(false))
        },{export.launch("Rayren-diagnostics.txt")},{
            runCatching{startActivity(Intent(Settings.ACTION_VPN_SETTINGS))}.onFailure{controller.message("Откройте настройки VPN в Android")}
        }) }
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);acceptIntent(intent)}
    private fun acceptIntent(intent:Intent?){
        val value=when(intent?.action){Intent.ACTION_VIEW->intent.dataString;Intent.ACTION_SEND->intent.getStringExtra(Intent.EXTRA_TEXT);else->null}
        if(value!=null&&value.length<=com.rayren.domain.ImportLimits.MAX_BYTES)importDraft=value
    }
    private fun connect(){
        if(controller.state.value.connection.phase!=Phase.IDLE){controller.stop();return}
        if(controller.state.value.data.profiles.isEmpty()){importDraft="";return}
        val prepare=VpnService.prepare(this)
        if(prepare!=null)permission.launch(prepare)else startVpn()
    }
    private fun startVpn(){
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        controller.start()
    }
}
