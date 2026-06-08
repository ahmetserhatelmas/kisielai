package com.dilara.assistant.service

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * OpenAI Whisper tabanlı STT.
 * Google SpeechRecognizer'dan bağımsız, emülatörde de çalışır.
 *
 * Kullanım:
 *  startRecording() → (kullanıcı konuşur) → stopRecording() → transcribe()
 */
class WhisperSTT(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputPath: String? = null

    private val http = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 15_000
        }
    }

    fun startRecording() {
        val file = File(context.cacheDir, "whisper_input.m4a")
        outputPath = file.absolutePath
        file.delete()

        recorder = (
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
            else @Suppress("DEPRECATION") MediaRecorder()
        ).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16_000)
            setAudioChannels(1)
            setAudioEncodingBitRate(96_000)
            setOutputFile(outputPath)
            prepare()
            start()
        }
    }

    /** Kaydı durdurur, dosya yolunu döner. Hata veya çok kısa kayıt → null. */
    fun stopRecording(): String? = try {
        recorder?.stop()
        recorder?.release()
        recorder = null
        outputPath?.takeIf { File(it).length() > 1_000 }
    } catch (e: Exception) {
        recorder?.release()
        recorder = null
        null
    }

    /** Ses dosyasını Whisper API'ye gönderir, metni döner. */
    suspend fun transcribe(filePath: String, apiKey: String): String? = runCatching {
        val file = File(filePath)
        if (!file.exists() || file.length() < 1_000) return null

        val response = http.post("https://api.openai.com/v1/audio/transcriptions") {
            bearerAuth(apiKey)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("model", "whisper-1")
                        append("language", "tr")
                        append(
                            "file",
                            file.readBytes(),
                            Headers.build {
                                append(HttpHeaders.ContentType, "audio/mp4")
                                append(HttpHeaders.ContentDisposition, "filename=\"audio.m4a\"")
                            },
                        )
                    },
                ),
            )
        }

        if (!response.status.isSuccess()) return null
        response.body<WhisperResponse>().text.trim().takeIf { it.isNotBlank() }
    }.getOrNull()

    fun release() {
        runCatching { recorder?.stop() }
        recorder?.release()
        recorder = null
        http.close()
    }
}

@Serializable
private data class WhisperResponse(val text: String = "")
