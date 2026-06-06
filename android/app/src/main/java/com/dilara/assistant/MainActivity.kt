package com.dilara.assistant

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.dilara.assistant.service.WakeWordService
import com.dilara.assistant.ui.DilaraApp
import com.dilara.assistant.ui.theme.DilaraTheme

class MainActivity : ComponentActivity() {

    // Runtime izinler — kullanıcı ilk açışta sorar
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // İzin sonucunu ViewModel'e iletmeye gerek yok;
        // mikrofon izni verildiyse servisler zaten çalışır.
        val micGranted = results[Manifest.permission.RECORD_AUDIO] == true
        if (micGranted) startWakeWordService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestRequiredPermissions()

        setContent {
            DilaraTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    DilaraApp()
                }
            }
        }
    }

    private fun requestRequiredPermissions() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        )
    }

    private fun startWakeWordService() {
        val intent = Intent(this, WakeWordService::class.java)
        startForegroundService(intent)
    }
}
