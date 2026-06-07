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
import androidx.lifecycle.lifecycleScope
import com.dilara.assistant.data.local.MemoryRepository
import com.dilara.assistant.service.VisionLLM
import com.dilara.assistant.service.WakeWordService
import com.dilara.assistant.ui.DilaraApp
import com.dilara.assistant.ui.theme.DilaraTheme
import com.dilara.assistant.util.BitmapUtils
import com.dilara.assistant.util.MediaProjectionStore
import com.dilara.assistant.util.ScreenCaptureHelper
import com.dilara.assistant.util.ScreenCapturePipeline
import com.dilara.assistant.util.VisionCapture
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        if (results[Manifest.permission.RECORD_AUDIO] == true) startWakeWordService()
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
        if (MediaProjectionStore.ensureProjection() != null) {
            lifecycleScope.launch { executePendingScreenCapture() }
        } else if (ScreenCapturePipeline.hasPending()) {
            ScreenCapturePipeline.complete(
                Result.failure(Exception("Ekran kaydı izni verilmedi.")),
            )
        }
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

    override fun onResume() {
        super.onResume()
        if (ScreenCapturePipeline.hasPending() && MediaProjectionStore.ensureProjection() != null) {
            lifecycleScope.launch { executePendingScreenCapture() }
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            VisionCapture.captureCamera = null
            VisionCapture.launchScreenCapture = null
            visionLLM?.close()
            MediaProjectionStore.release()
            ScreenCapturePipeline.clear()
        }
        super.onDestroy()
    }

    private fun registerVisionCapture() {
        VisionCapture.captureCamera = { prompt ->
            runVisionCapture { vision ->
                val bitmap = captureCameraBitmap()
                    ?: return@runVisionCapture Result.failure(Exception("Kamera iptal edildi."))
                vision.describe(BitmapUtils.toBase64Jpeg(bitmap), prompt = prompt)
            }
        }

        VisionCapture.launchScreenCapture = launch@{
            if (ScreenCapturePipeline.permissionRequestInFlight) return@launch
            if (MediaProjectionStore.ensureProjection() != null) {
                lifecycleScope.launch { executePendingScreenCapture() }
            } else {
                ScreenCapturePipeline.permissionRequestInFlight = true
                screenCaptureLauncher.launch(MediaProjectionStore.createCaptureIntent())
            }
        }
    }

    private suspend fun executePendingScreenCapture() {
        if (ScreenCapturePipeline.captureInProgress) return
        val job = ScreenCapturePipeline.pending ?: return
        ScreenCapturePipeline.captureInProgress = true
        try {
            val result = runVisionCapture { vision ->
                val metrics = DisplayMetrics().also {
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay.getRealMetrics(it)
                }
                val projection = MediaProjectionStore.ensureProjection()
                    ?: return@runVisionCapture Result.failure(Exception("Ekran izni yok."))
                delay(400)
                val bitmap = try {
                    ScreenCaptureHelper.captureWithProjection(projection, metrics)
                } catch (e: Exception) {
                    ScreenCaptureHelper.captureActivityWindow(this@MainActivity)
                }
                vision.describe(BitmapUtils.toBase64Jpeg(bitmap), prompt = job.prompt)
            }
            ScreenCapturePipeline.complete(result)
        } finally {
            ScreenCapturePipeline.captureInProgress = false
        }
    }

    private suspend fun runVisionCapture(
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
        startForegroundService(Intent(this, WakeWordService::class.java))
    }
}
