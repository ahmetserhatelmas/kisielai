package com.dilara.assistant.data.api

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*

class OpenAIClient(private val apiKey: String) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val http = HttpClient(Android) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 15_000
        }
    }

    /**
     * Tek turlu chat isteği.
     * Araç çağrısı (tool_calls) içeriyorsa döndürür — caller loop yürütür.
     */
    suspend fun chat(request: ChatRequest): Result<ChatResponse> = runCatching {
        val response = http.post("https://api.openai.com/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            bearerAuth(apiKey)
            setBody(request)
        }
        if (!response.status.isSuccess()) {
            val err = runCatching { response.body<OpenAIError>() }.getOrNull()
            throw Exception(err?.error?.message ?: "HTTP ${response.status}")
        }
        response.body<ChatResponse>()
    }

    /**
     * Tam araç döngüsü: mesajları alır, gerektiğinde araç çağrılarını işler,
     * nihayetinde string cevap döner.
     */
    suspend fun chatWithTools(
        messages: MutableList<OpenAIMessage>,
        tools: List<OpenAITool>,
        toolExecutor: suspend (String, JsonObject) -> String,
        model: String = "gpt-4o",
        temperature: Double = 0.8,
    ): String {
        var iterations = 0
        while (iterations++ < 5) {
            val request = ChatRequest(
                model = model,
                messages = messages,
                tools = tools.ifEmpty { null },
                toolChoice = if (tools.isEmpty()) null else "auto",
                temperature = temperature,
            )
            val response = chat(request).getOrThrow()
            val choice = response.choices.firstOrNull()
                ?: return "Cevap gelmedi."

            val assistantMsg = choice.message
            messages.add(assistantMsg)

            if (choice.finishReason == "tool_calls") {
                val calls = assistantMsg.toolCalls ?: break
                for (call in calls) {
                    val args = runCatching {
                        Json.parseToJsonElement(call.function.arguments).jsonObject
                    }.getOrDefault(JsonObject(emptyMap()))
                    val result = runCatching {
                        toolExecutor(call.function.name, args)
                    }.getOrElse { "Araç hatası: ${it.message}" }
                    messages.add(
                        OpenAIMessage(
                            role = "tool",
                            content = result,
                            toolCallId = call.id,
                        )
                    )
                }
                continue
            }
            return assistantMsg.content?.trim() ?: ""
        }
        return "İşlem tamamlanamadı."
    }

    fun close() = http.close()
}
