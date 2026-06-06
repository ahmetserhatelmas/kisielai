package com.dilara.assistant.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// ── Request ────────────────────────────────────────────────────────────────

@Serializable
data class ChatRequest(
    val model: String = "gpt-4o",
    val messages: List<OpenAIMessage>,
    val tools: List<OpenAITool>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
    val temperature: Double = 0.8,
    @SerialName("max_tokens") val maxTokens: Int = 1000,
)

@Serializable
data class OpenAIMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall,
)

@Serializable
data class FunctionCall(
    val name: String,
    val arguments: String,
)

@Serializable
data class OpenAITool(
    val type: String = "function",
    val function: FunctionDef,
)

@Serializable
data class FunctionDef(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

// ── Response ────────────────────────────────────────────────────────────────

@Serializable
data class ChatResponse(
    val choices: List<Choice>,
    val usage: Usage? = null,
)

@Serializable
data class Choice(
    val message: OpenAIMessage,
    @SerialName("finish_reason") val finishReason: String,
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)

@Serializable
data class OpenAIError(
    val error: ErrorDetail,
)

@Serializable
data class ErrorDetail(
    val message: String,
    val type: String? = null,
)
