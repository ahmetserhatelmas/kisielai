package com.dilara.assistant.service

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class VisionLLM(private val apiKey: String) {

    private val json = Json { ignoreUnknownKeys = true }

    private val http = HttpClient(Android) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 15_000
        }
    }

    suspend fun describe(
        imageBase64: String,
        mimeType: String = "image/jpeg",
        prompt: String = "Bu görselde ne var? Türkçe ve kısa anlat.",
        model: String = "gpt-4o-mini",
    ): Result<String> = describeFrames(listOf(imageBase64), mimeType, prompt, model)

    /**
     * Birden fazla kareyi tek istekte gönderir. Ekran kaydı gibi
     * zaman içinde değişen bir görüntüyü "video" gibi yorumlamak için.
     */
    suspend fun describeFrames(
        framesBase64: List<String>,
        mimeType: String = "image/jpeg",
        prompt: String = "Bu kareler bir ekran kaydının sırayla alınmış parçalarıdır. Ekranda zaman içinde ne olduğunu Türkçe anlat.",
        model: String = "gpt-4o-mini",
    ): Result<String> = runCatching {
        if (framesBase64.isEmpty()) throw Exception("Kare yok.")
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 700)
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            add(buildJsonObject { put("type", "text"); put("text", prompt) })
                            framesBase64.forEach { frame ->
                                add(
                                    buildJsonObject {
                                        put("type", "image_url")
                                        putJsonObject("image_url") {
                                            put("url", "data:$mimeType;base64,$frame")
                                        }
                                    },
                                )
                            }
                        }
                    },
                )
            }
        }
        val payload = json.encodeToString(JsonObject.serializer(), body)

        // Geçici ağ hatalarında (connection abort vb.) bir kez yeniden dene
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                val response = http.post("https://api.openai.com/v1/chat/completions") {
                    contentType(ContentType.Application.Json)
                    bearerAuth(apiKey)
                    setBody(TextContent(payload, ContentType.Application.Json))
                }

                if (!response.status.isSuccess()) {
                    val err = runCatching { response.body<VisionErrorResponse>() }.getOrNull()
                    throw Exception(err?.error?.message ?: "HTTP ${response.status}")
                }

                return@runCatching response.body<VisionChatResponse>()
                    .choices.firstOrNull()
                    ?.message
                    ?.content
                    ?.trim()
                    ?: "Görsel analiz edilemedi."
            } catch (e: Exception) {
                lastError = e
                if (attempt == 0) kotlinx.coroutines.delay(800)
            }
        }
        throw lastError ?: Exception("Görsel analiz başarısız.")
    }

    fun close() = http.close()
}

@Serializable
private data class VisionChatResponse(val choices: List<VisionChoice>)

@Serializable
private data class VisionChoice(val message: VisionMessage)

@Serializable
private data class VisionMessage(val content: String? = null)

@Serializable
private data class VisionErrorResponse(val error: VisionErrorDetail)

@Serializable
private data class VisionErrorDetail(val message: String)
