package com.dilara.assistant

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.DisplayMetrics
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.dilara.assistant.data.local.MemoryRepository
import com.dilara.assistant.service.VisionLLM
import com.dilara.assistant.service.WakeWordService
import com.dilara.assistant.ui.DilaraApp
import com.dilara.assistant.ui.theme.DilaraTheme
import com.dilara.assistant.util.BitmapUtils
import com.dilara.assistant.util.MediaProjectionStore
import com.dilara.assistant.util.ScreenCaptureHelper
import com.dilara.assistant.util.VisionCapture
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {

    private lateinit var memory: MemoryRepository
    private var visionLLM: VisionLLM? = null

    private var cameraContinuation: CancellableContinuation<Bitmap?>? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val micGranted = results[Manifest.permission.RECORD_AUDIO] == true
        if (micGranted) startWakeWordService()
    }

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview(),
    ) { bitmap ->
        cameraContinuation?.resume(bitmap)
        cameraContinuation = null
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        MediaProjectionStore.onPermissionResult(result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        memory = MemoryRepository(applicationContext)
        MediaProjectionStore.init(applicationContext)
        registerVisionCapture()
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

    override fun onDestroy() {
        if (isFinishing) {
            VisionCapture.captureCamera = null
            VisionCapture.captureScreen = null
            visionLLM?.close()
            MediaProjectionStore.release()
        }
        super.onDestroy()
    }

    private fun registerVisionCapture() {
        VisionCapture.captureCamera = { prompt ->
            runVisionCapture(prompt) { vision ->
                val bitmap = captureCameraBitmap()
                    ?: return@runVisionCapture Result.failure(Exception("Kamera iptal edildi."))
                vision.describe(BitmapUtils.toBase64Jpeg(bitmap), prompt = prompt)
            }
        }

        VisionCapture.captureScreen = { prompt ->
            runVisionCapture(prompt) { vision ->
                val metrics = DisplayMetrics().also {
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay.getRealMetrics(it)
                }
                val projection = MediaProjectionStore.awaitProjection {
                    screenCaptureLauncher.launch(MediaProjectionStore.createCaptureIntent())
                }
                // İzin ekranından dönünce kısa bekle — UI otursun, yakalama tamamlansın
                delay(350)
                val bitmap = ScreenCaptureHelper.captureWithProjection(projection, metrics)
                vision.describe(BitmapUtils.toBase64Jpeg(bitmap), prompt = prompt)
            }
        }
    }

    private suspend fun runVisionCapture(
        prompt: String,
        block: suspend (VisionLLM) -> Result<String>,
    ): Result<String> {
        val apiKey = memory.getApiKey()
        if (apiKey.isBlank()) {
            return Result.failure(Exception("OpenAI API anahtarı ayarlarda eksik."))
        }
        if (visionLLM == null) visionLLM = VisionLLM(apiKey)
        return block(visionLLM!!)
    }

    private suspend fun captureCameraBitmap(): Bitmap? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            cameraContinuation = cont
            takePictureLauncher.launch(null)
            cont.invokeOnCancellation { cameraContinuation = null }
        }
    }

    private fun requestRequiredPermissions() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR,
                Manifest.permission.BLUETOOTH_CONNECT,
            ),
        )
    }

    private fun startWakeWordService() {
        val intent = Intent(this, WakeWordService::class.java)
        startForegroundService(intent)
    }
}
