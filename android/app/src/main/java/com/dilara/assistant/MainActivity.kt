package com.dilara.assistant

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
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
import com.dilara.assistant.service.ScreenCaptureService
import com.dilara.assistant.ui.DilaraApp
import com.dilara.assistant.ui.theme.DilaraTheme
import com.dilara.assistant.util.AttachmentCapture
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
    private var imagePickerCont: CancellableContinuation<Uri?>? = null
    private var videoPickerCont: CancellableContinuation<Uri?>? = null
    private var filePickerCont: CancellableContinuation<Uri?>? = null

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

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        imagePickerCont?.resume(uri)
        imagePickerCont = null
    }

    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        videoPickerCont?.resume(uri)
        videoPickerCont = null
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        filePickerCont?.resume(uri)
        filePickerCont = null
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        MediaProjectionStore.onPermissionResult(result.resultCode, result.data)
        if (MediaProjectionStore.ensureProjection() != null) {
            lifecycleScope.launch { executePendingScreenCapture() }
        } else {
            stopScreenCaptureService()
            if (ScreenCapturePipeline.hasPending()) {
                ScreenCapturePipeline.complete(
                    Result.failure(Exception("Ekran kaydı izni verilmedi.")),
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        memory = MemoryRepository(applicationContext)
        MediaProjectionStore.init(applicationContext)
        registerVisionCapture()
        registerAttachmentCapture()
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
            AttachmentCapture.captureImage = null
            AttachmentCapture.captureVideo = null
            AttachmentCapture.readFile = null
            visionLLM?.close()
            MediaProjectionStore.release()
            ScreenCapturePipeline.clear()
            stopScreenCaptureService()
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
                // Android 14+: getMediaProjection() çağrılmadan önce
                // foregroundServiceType="mediaProjection" servisi çalışıyor olmalı.
                startForegroundService(Intent(this, ScreenCaptureService::class.java))
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
            stopScreenCaptureService()
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

    private fun stopScreenCaptureService() {
        stopService(Intent(this, ScreenCaptureService::class.java))
    }

    // ── Ek medya / dosya yakalama ─────────────────────────────────────────────

    private fun registerAttachmentCapture() {
        AttachmentCapture.captureImage = { prompt ->
            runVisionCapture { vision ->
                val uri = pickUri(imagePickerLauncher, { imagePickerCont = it }, "image/*")
                    ?: return@runVisionCapture Result.failure(Exception("Resim seçimi iptal edildi."))
                val bitmap = decodeBitmapFromUri(uri)
                    ?: return@runVisionCapture Result.failure(Exception("Resim yüklenemedi."))
                vision.describe(BitmapUtils.toBase64Jpeg(bitmap), prompt = prompt)
            }
        }

        AttachmentCapture.captureVideo = { prompt ->
            runVisionCapture { vision ->
                val uri = pickUri(videoPickerLauncher, { videoPickerCont = it }, "video/*")
                    ?: return@runVisionCapture Result.failure(Exception("Video seçimi iptal edildi."))
                val bitmap = captureVideoFrame(uri)
                    ?: return@runVisionCapture Result.failure(Exception("Video karesi alınamadı."))
                vision.describe(BitmapUtils.toBase64Jpeg(bitmap), prompt = prompt)
            }
        }

        AttachmentCapture.readFile = {
            runCatching {
                val uri = pickUri(filePickerLauncher, { filePickerCont = it }, "*/*")
                    ?: throw Exception("Dosya seçimi iptal edildi.")
                val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                when {
                    mime.startsWith("image/") -> {
                        val bitmap = decodeBitmapFromUri(uri)
                            ?: throw Exception("Resim yüklenemedi.")
                        val analysis = runVisionCapture { vision ->
                            vision.describe(
                                BitmapUtils.toBase64Jpeg(bitmap),
                                prompt = "Bu resimde ne var? Türkçe ve detaylıca anlat.",
                            )
                        }.getOrThrow()
                        "\u0000VISION\u0000$analysis"
                    }
                    mime.startsWith("video/") -> {
                        val bitmap = captureVideoFrame(uri)
                            ?: throw Exception("Video karesi alınamadı.")
                        val analysis = runVisionCapture { vision ->
                            vision.describe(
                                BitmapUtils.toBase64Jpeg(bitmap),
                                prompt = "Bu görselde ne görüyorsun? Türkçe anlat.",
                            )
                        }.getOrThrow()
                        "\u0000VISION\u0000$analysis"
                    }
                    else -> readFileContent(uri)
                }
            }
        }
    }

    private suspend fun pickUri(
        launcher: androidx.activity.result.ActivityResultLauncher<String>,
        setCont: (CancellableContinuation<Uri?>) -> Unit,
        mimeType: String,
    ): Uri? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            setCont(cont)
            launcher.launch(mimeType)
        }
    }

    private fun decodeBitmapFromUri(uri: Uri): Bitmap? = runCatching {
        val stream = contentResolver.openInputStream(uri) ?: return null
        stream.use { android.graphics.BitmapFactory.decodeStream(it) }
    }.getOrNull()

    private fun captureVideoFrame(uri: Uri): Bitmap? = runCatching {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(this, uri)
        val frame = retriever.getFrameAtTime(1_000_000L)
            ?: retriever.getFrameAtTime(0L)
        retriever.release()
        frame
    }.getOrNull()

    private fun readFileContent(uri: Uri): String {
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"

        // Okunabilir dosya adı al
        val fileName = runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment ?: "dosya"

        return when {
            mime == "application/pdf" -> readPdfContent(uri)

            // Metin tabanlı formatlar
            mime.startsWith("text/") ||
            mime == "application/json" ||
            mime == "application/xml" ||
            mime == "application/javascript" -> {
                val content = contentResolver.openInputStream(uri)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: return "[Dosya okunamadı]"
                // Çok uzun dosyaları kes
                if (content.length > 12_000) {
                    content.take(12_000) + "\n\n[... dosya çok uzun, ilk 12.000 karakter gösterildi]"
                } else {
                    content
                }
            }

            // Görsel/video → artık yukarıdaki readFile callback'inde handle ediliyor
            mime.startsWith("image/") || mime.startsWith("video/") ->
                "[Görsel dosya — desteklenmeyen format için 📎 → Galeriden resim/video kullan.]"

            // Binary/office/zip vb. → desteklenmiyor
            else ->
                "[Desteklenmeyen dosya türü: $mime ($fileName). Şu an yalnızca .txt, .csv, .json, .xml ve .pdf okunabilir.]"
        }
    }

    private fun readPdfContent(uri: Uri): String = runCatching {
        val fd = contentResolver.openFileDescriptor(uri, "r") ?: return "[PDF açılamadı]"
        fd.use {
            val renderer = android.graphics.pdf.PdfRenderer(it)
            val pageCount = renderer.pageCount
            renderer.close()
            "[PDF dosyası — $pageCount sayfa. Not: PDF metin çıkarma henüz desteklenmiyor, görsel analiz için 📷 kul.]"
        }
    }.getOrElse { "[PDF okunamadı: ${it.message}]" }
}
