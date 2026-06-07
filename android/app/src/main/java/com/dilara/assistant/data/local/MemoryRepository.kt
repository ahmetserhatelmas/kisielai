package com.dilara.assistant.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "dilara_memory")

private val KEY_USER_NAME   = stringPreferencesKey("user_name")
private val KEY_MODE        = stringPreferencesKey("mode")
private val KEY_FACTS       = stringPreferencesKey("facts_json")
private val KEY_HISTORY     = stringPreferencesKey("history_json")
private val KEY_OPENAI_KEY  = stringPreferencesKey("openai_api_key")
private val KEY_SERVER_URL  = stringPreferencesKey("server_url")
private val KEY_AUTH_TOKEN  = stringPreferencesKey("auth_token")
private val KEY_IS_ACTIVE   = booleanPreferencesKey("is_active")

private val json = Json { ignoreUnknownKeys = true }

// Kısa vadeli konuşma geçmişinde tutulacak maksimum mesaj sayısı
private const val MAX_HISTORY = 40

@Serializable
data class StoredMessage(val role: String, val content: String)

@Serializable
data class UserFact(val text: String, val category: String = "fact")

class MemoryRepository(private val context: Context) {

    // ── API Anahtarı ─────────────────────────────────────────────────────────

    suspend fun getApiKey(): String =
        context.dataStore.data.map { it[KEY_OPENAI_KEY] ?: "" }.first()

    suspend fun saveApiKey(key: String) {
        context.dataStore.edit { it[KEY_OPENAI_KEY] = key }
    }

    // ── Kullanıcı adı ve mod ──────────────────────────────────────────────────

    suspend fun getUserName(): String =
        context.dataStore.data.map { it[KEY_USER_NAME] ?: "Kullanıcı" }.first()

    suspend fun saveUserName(name: String) {
        context.dataStore.edit { it[KEY_USER_NAME] = name }
    }

    suspend fun getMode(): String =
        context.dataStore.data.map { it[KEY_MODE] ?: "normal" }.first()

    suspend fun saveMode(mode: String) {
        context.dataStore.edit { it[KEY_MODE] = mode }
    }

    // ── Konuşma geçmişi ────────────────────────────────────────────────────────

    suspend fun getHistory(): List<StoredMessage> {
        val raw = context.dataStore.data.map { it[KEY_HISTORY] ?: "[]" }.first()
        return runCatching { json.decodeFromString<List<StoredMessage>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun appendHistory(role: String, content: String) {
        val current = getHistory().toMutableList()
        current.add(StoredMessage(role, content))
        if (current.size > MAX_HISTORY) {
            // Baştaki kullanıcı+asistan çiftlerini sil, sistemi koru
            repeat(current.size - MAX_HISTORY) { current.removeAt(0) }
        }
        context.dataStore.edit { it[KEY_HISTORY] = json.encodeToString(current) }
    }

    suspend fun clearHistory() {
        context.dataStore.edit { it[KEY_HISTORY] = "[]" }
    }

    // ── Uzun vadeli kullanıcı gerçekleri ─────────────────────────────────────

    suspend fun getFacts(): List<UserFact> {
        val raw = context.dataStore.data.map { it[KEY_FACTS] ?: "[]" }.first()
        return runCatching { json.decodeFromString<List<UserFact>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun addFact(text: String, category: String = "fact") {
        val current = getFacts().toMutableList()
        if (current.none { it.text.equals(text, ignoreCase = true) }) {
            current.add(UserFact(text, category))
            if (current.size > 100) current.removeAt(0)
            context.dataStore.edit { it[KEY_FACTS] = json.encodeToString(current) }
        }
    }

    suspend fun getFactsSummary(): String {
        val facts = getFacts()
        if (facts.isEmpty()) return ""
        return facts.joinToString("\n") { "- [${it.category}] ${it.text}" }
    }

    // ── Vektör hafıza (semantik arama) ───────────────────────────────────────

    private val embeddingStore = EmbeddingStore(context)

    /**
     * Metni hem düz listeye hem de vektör deposuna kaydet.
     * Vektör için apiKey gerekiyor; yoksa sadece düz listeye ekler.
     */
    suspend fun addFactSemantic(text: String, category: String = "genel", apiKey: String = "") {
        addFact(text, category) // her zaman düz listeye ekle
        if (apiKey.isNotBlank()) {
            embeddingStore.store(text, category, apiKey)
        }
    }

    /**
     * Semantik arama — apiKey varsa vektör araması, yoksa düz metin filtresi.
     */
    suspend fun searchFacts(query: String, apiKey: String = "", topK: Int = 5): String {
        return if (apiKey.isNotBlank()) {
            val result = embeddingStore.searchAsText(query, apiKey, topK)
            result.ifBlank { getFactsSummary() } // fallback
        } else {
            // Basit substring filtresi
            val words = query.lowercase().split(" ").filter { it.length > 2 }
            getFacts()
                .filter { fact -> words.any { w -> fact.text.lowercase().contains(w) } }
                .takeIf { it.isNotEmpty() }
                ?.joinToString("\n") { "- [${it.category}] ${it.text}" }
                ?: getFactsSummary()
        }
    }

    // ── Server URL ────────────────────────────────────────────────────────────

    suspend fun getServerUrl(): String =
        context.dataStore.data.map { it[KEY_SERVER_URL] ?: "" }.first()

    suspend fun saveServerUrl(url: String) {
        context.dataStore.edit { it[KEY_SERVER_URL] = url.trimEnd('/') }
    }

    suspend fun getAuthToken(): String =
        context.dataStore.data.map { it[KEY_AUTH_TOKEN] ?: "" }.first()

    suspend fun saveAuthToken(token: String) {
        context.dataStore.edit { it[KEY_AUTH_TOKEN] = token }
    }

    // ── Aktivasyon durumu ─────────────────────────────────────────────────────

    suspend fun getIsActive(): Boolean =
        context.dataStore.data.map { it[KEY_IS_ACTIVE] ?: false }.first()

    suspend fun saveIsActive(active: Boolean) {
        context.dataStore.edit { it[KEY_IS_ACTIVE] = active }
    }
}
