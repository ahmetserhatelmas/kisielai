package com.dilara.assistant.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.sqrt

private val Context.embeddingStore by preferencesDataStore(name = "dilara_embeddings")
private val KEY_ENTRIES = stringPreferencesKey("entries_json")

private val json = Json { ignoreUnknownKeys = true }

@Serializable
data class EmbeddingEntry(
    val id: String,
    val text: String,
    val category: String,
    val vector: List<Float>,
    val timestampMs: Long = System.currentTimeMillis(),
)

/**
 * Basit vektör hafızası.
 *
 * - Embedding'ler OpenAI text-embedding-3-small ile üretilir
 * - DataStore'da JSON olarak saklanır
 * - Arama: cosine similarity
 * - Maksimum 200 kayıt (eski kayıtlar silinir)
 */
class EmbeddingStore(private val context: Context) {

    companion object {
        private const val MAX_ENTRIES = 200
        private const val EMBEDDING_MODEL = "text-embedding-3-small"
        private const val EMBEDDING_URL = "https://api.openai.com/v1/embeddings"
    }

    // ── Kaydetme ─────────────────────────────────────────────────────────────

    suspend fun store(
        text: String,
        category: String,
        apiKey: String,
        id: String = java.util.UUID.randomUUID().toString(),
    ): Boolean {
        val vector = embed(text, apiKey) ?: return false
        val entries = getEntries().toMutableList()
        entries.removeIf { it.text.equals(text, ignoreCase = true) }
        entries.add(EmbeddingEntry(id = id, text = text, category = category, vector = vector))
        if (entries.size > MAX_ENTRIES) {
            entries.sortBy { it.timestampMs }
            repeat(entries.size - MAX_ENTRIES) { entries.removeAt(0) }
        }
        context.embeddingStore.edit { it[KEY_ENTRIES] = json.encodeToString(entries) }
        return true
    }

    // ── Arama ─────────────────────────────────────────────────────────────────

    suspend fun search(
        query: String,
        apiKey: String,
        topK: Int = 5,
        minScore: Float = 0.70f,
    ): List<EmbeddingEntry> {
        val queryVec = embed(query, apiKey) ?: return emptyList()
        return getEntries()
            .map { entry -> Pair(entry, cosineSimilarity(queryVec, entry.vector)) }
            .filter { (_, score) -> score >= minScore }
            .sortedByDescending { (_, score) -> score }
            .take(topK)
            .map { (entry, _) -> entry }
    }

    suspend fun searchAsText(query: String, apiKey: String, topK: Int = 5): String {
        val results = search(query, apiKey, topK)
        if (results.isEmpty()) return ""
        return results.joinToString("\n") { "- [${it.category}] ${it.text}" }
    }

    // ── Tüm kayıtlar ──────────────────────────────────────────────────────────

    suspend fun getEntries(): List<EmbeddingEntry> {
        val raw = context.embeddingStore.data.map { it[KEY_ENTRIES] ?: "[]" }.first()
        return runCatching { json.decodeFromString<List<EmbeddingEntry>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun clear() {
        context.embeddingStore.edit { it[KEY_ENTRIES] = "[]" }
    }

    // ── OpenAI Embedding API ──────────────────────────────────────────────────

    private suspend fun embed(text: String, apiKey: String): List<Float>? {
        if (apiKey.isBlank()) return null
        return runCatching {
            val client = HttpClient(Android) {
                install(ContentNegotiation) { json(json) }
                install(HttpTimeout) { requestTimeoutMillis = 20_000 }
            }
            val response: EmbeddingResponse = client.post(EMBEDDING_URL) {
                contentType(ContentType.Application.Json)
                bearerAuth(apiKey)
                setBody(EmbeddingRequest(model = EMBEDDING_MODEL, input = text))
            }.body()
            client.close()
            response.data.firstOrNull()?.embedding
        }.getOrNull()
    }

    // ── Cosine similarity ─────────────────────────────────────────────────────

    private fun cosineSimilarity(a: List<Float>, b: List<Float>): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) {
            dot  += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0f) 0f else dot / denom
    }
}

// ── Embedding API modelleri ────────────────────────────────────────────────

@Serializable
private data class EmbeddingRequest(val model: String, val input: String)

@Serializable
private data class EmbeddingResponse(val data: List<EmbeddingData>)

@Serializable
private data class EmbeddingData(val embedding: List<Float>)
