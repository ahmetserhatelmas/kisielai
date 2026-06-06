package com.dilara.assistant.data.api

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

// ── Sync veri modelleri ───────────────────────────────────────────────────

@Serializable
data class SyncMemoryItem(
    val id: String,
    @SerialName("text_encrypted") val textEncrypted: String,
    val category: String = "fact",
    val importance: Float = 0.5f,
    val metadata: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class SyncBatch(val items: List<SyncMemoryItem>)

@Serializable
data class SyncPushResult(val upserted: Int)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class RegisterRequest(
    val username: String,
    val password: String,
    @SerialName("display_name") val displayName: String = "",
)

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
)

@Serializable
data class ProfileResponse(
    val username: String,
    @SerialName("display_name") val displayName: String = "",
)

// ── İstemci ───────────────────────────────────────────────────────────────

/**
 * Dilara backend istemcisi (FastAPI senkronizasyon sunucusu).
 *
 * Tüm uç noktalar:
 *   POST /auth/register
 *   POST /auth/login
 *   GET  /profile/me
 *   POST /sync/push
 *   GET  /sync/pull
 */
class DilaraClient(private val baseUrl: String) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val http = HttpClient(Android) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 10_000 }
    }

    // ── Auth ─────────────────────────────────────────────────────────────────

    suspend fun register(username: String, password: String, displayName: String = ""): Result<TokenResponse> =
        runCatching {
            http.post("$baseUrl/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest(username, password, displayName))
            }.body()
        }

    suspend fun login(username: String, password: String): Result<TokenResponse> =
        runCatching {
            val resp = http.post("$baseUrl/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest(username, password))
            }
            if (!resp.status.isSuccess()) throw Exception("Giriş başarısız: ${resp.status}")
            resp.body<TokenResponse>()
        }

    // ── Profil ────────────────────────────────────────────────────────────────

    suspend fun getProfile(token: String): Result<ProfileResponse> =
        runCatching {
            http.get("$baseUrl/profile/me") {
                bearerAuth(token)
            }.body()
        }

    // ── Senkronizasyon ────────────────────────────────────────────────────────

    /**
     * Yerel hafızayı sunucuya gönder.
     * Hafıza içeriği client tarafında şifrelenmiş olarak gelir —
     * sunucu içeriği okuyamaz.
     */
    suspend fun push(token: String, items: List<SyncMemoryItem>): Result<SyncPushResult> =
        runCatching {
            val resp = http.post("$baseUrl/sync/push") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(SyncBatch(items))
            }
            if (!resp.status.isSuccess()) throw Exception("Push başarısız: ${resp.status}")
            resp.body<SyncPushResult>()
        }

    /**
     * Sunucudan son değişiklikleri çek.
     * since null ise tüm hafıza indirilir.
     */
    suspend fun pull(token: String, since: String? = null): Result<List<SyncMemoryItem>> =
        runCatching {
            http.get("$baseUrl/sync/pull") {
                bearerAuth(token)
                if (since != null) parameter("since", since)
            }.body()
        }

    /** Sunucunun erişilebilir olup olmadığını kontrol et. */
    suspend fun ping(): Boolean = runCatching {
        http.get("$baseUrl/health").status.isSuccess()
    }.getOrDefault(false)

    fun close() = http.close()
}
