package com.dilara.assistant.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dilara.assistant.data.api.DilaraClient
import com.dilara.assistant.data.api.OpenAIClient
import com.dilara.assistant.data.api.OpenAIMessage
import com.dilara.assistant.data.api.SyncMemoryItem
import com.dilara.assistant.data.local.MemoryRepository
import com.dilara.assistant.modules.DilaraMode
import com.dilara.assistant.modules.PersonalityManager
import com.dilara.assistant.service.SpeechService
import com.dilara.assistant.service.TtsService
import com.dilara.assistant.service.WakeWordService
import com.dilara.assistant.service.WhisperSTT
import com.dilara.assistant.tools.ToolExecutor
import com.dilara.assistant.util.AttachmentCapture
import com.dilara.assistant.util.ScreenCapturePipeline
import com.dilara.assistant.util.VisionCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isActive: Boolean = false,
    val isThinking: Boolean = false,
    val isSpeaking: Boolean = false,
    val isListening: Boolean = false,
    val mode: DilaraMode = DilaraMode.NORMAL,
    val apiKeyMissing: Boolean = false,
    val syncStatus: SyncStatus = SyncStatus.IDLE,
)

enum class SyncStatus { IDLE, SYNCING, SUCCESS, ERROR }

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val memory = MemoryRepository(app)
    private val personality = PersonalityManager()
    private val tts = TtsService(app)
    private val speech = SpeechService(app)
    private val whisper = WhisperSTT(app)
    private val toolExecutor = ToolExecutor(app)

    private var openAIClient: OpenAIClient? = null
    private var syncClient: DilaraClient? = null

    // Konuşma geçmişi — LLM'e gönderilen mesajlar (sistem hariç)
    private val conversationHistory = mutableListOf<OpenAIMessage>()

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        viewModelScope.launch {
            val apiKey = memory.getApiKey()
            if (apiKey.isNotBlank()) {
                openAIClient = OpenAIClient(apiKey)
                _state.value = _state.value.copy(apiKeyMissing = false)
            } else {
                _state.value = _state.value.copy(apiKeyMissing = true)
            }

            val serverUrl = memory.getServerUrl()
            if (serverUrl.isNotBlank()) {
                syncClient = DilaraClient(serverUrl)
            }

            val userName = memory.getUserName()
            personality.setUserName(userName)

            val savedMode = memory.getMode()
            personality.mode = if (savedMode == "ciddi") DilaraMode.SERIOUS else DilaraMode.NORMAL

            val history = memory.getHistory()
            conversationHistory.addAll(history.map { OpenAIMessage(it.role, it.content) })
            val restoredMessages = history.map { stored ->
                ChatMessage(
                    text = stored.content,
                    isUser = stored.role == "user",
                )
            }

            val isActive = memory.getIsActive()
            _state.value = _state.value.copy(
                mode = personality.mode,
                isActive = isActive,
                messages = restoredMessages,
                isThinking = false,
                isSpeaking = false,
                isListening = false,
            )
        }

        registerWakeReceiver()
    }

    override fun onCleared() {
        tts.shutdown()
        speech.destroy()
        whisper.release()
        openAIClient?.close()
        syncClient?.close()
        unregisterWakeReceiver()
        super.onCleared()
    }

    // ── Aktivasyon ────────────────────────────────────────────────────────────

    fun toggleActivation() {
        val newActive = !_state.value.isActive
        _state.value = _state.value.copy(isActive = newActive)
        viewModelScope.launch { memory.saveIsActive(newActive) }
        if (newActive) {
            viewModelScope.launch { speak("Aktif oldum. Nasıl yardımcı olabilirim?") }
        } else {
            tts.stop()
            _state.value = _state.value.copy(isSpeaking = false)
        }
    }

    // ── Mesaj gönder ──────────────────────────────────────────────────────────

    fun send(text: String) {
        if (!_state.value.isActive || text.isBlank()) return
        if (_state.value.isThinking) return

        // Mod değiştirme komutu kontrolü
        val newMode = personality.detectModeCommand(text)
        if (newMode != null) {
            personality.mode = newMode
            viewModelScope.launch { memory.saveMode(if (newMode == DilaraMode.SERIOUS) "ciddi" else "normal") }
            _state.value = _state.value.copy(mode = newMode)
        }

        appendUserMessage(text)
        processWithLLM(text)
    }

    // ── Sesli giriş ───────────────────────────────────────────────────────────

    // ── Ses girişi (Whisper tabanlı) ──────────────────────────────────────────

    fun startListening() {
        if (!_state.value.isActive || _state.value.isThinking) return
        if (_state.value.isListening) {
            // İkinci basış → kaydı durdur ve transkript et
            stopAndTranscribe()
        } else {
            // İlk basış → kaydı başlat
            whisper.startRecording()
            _state.value = _state.value.copy(isListening = true)
            // 20 saniye sonra otomatik durdur
            viewModelScope.launch {
                delay(20_000)
                if (_state.value.isListening) stopAndTranscribe()
            }
        }
    }

    private fun stopAndTranscribe() {
        val filePath = whisper.stopRecording()
        _state.value = _state.value.copy(isListening = false)
        if (filePath == null) return

        _state.value = _state.value.copy(isThinking = true)
        viewModelScope.launch(Dispatchers.IO) {
            val apiKey = memory.getApiKey()
            if (apiKey.isBlank()) {
                withContext(Dispatchers.Main) {
                    appendAssistantMessage("🎤 OpenAI API anahtarı eksik.")
                    _state.value = _state.value.copy(isThinking = false)
                }
                return@launch
            }
            val text = whisper.transcribe(filePath, apiKey)
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(isThinking = false)
                if (!text.isNullOrBlank()) {
                    send(text)
                } else {
                    appendAssistantMessage("🎤 Ses tanınamadı. Daha yüksek sesle konuş veya tekrar dene.")
                }
            }
        }
    }

    fun analyzeCamera() {
        if (!_state.value.isActive || _state.value.isThinking) return
        val label = "📷 Kameraya bak"
        appendUserMessage(label)
        runVisionCapture("Bu fotoğrafta ne var? Türkçe anlat.", label) {
            VisionCapture.captureCamera?.invoke(it)
        }
    }

    fun analyzeScreen() {
        if (!_state.value.isActive || _state.value.isThinking) return
        val label = "👁 Ekrana bak"
        val prompt = "Telefon ekranında ne görüyorsun? Türkçe ve kısa anlat."
        appendUserMessage(label)
        _state.value = _state.value.copy(isThinking = true, isSpeaking = false)

        ScreenCapturePipeline.start(prompt, label) { result ->
            viewModelScope.launch {
                result.fold(
                    onSuccess = { reply ->
                        memory.appendHistory("user", label)
                        memory.appendHistory("assistant", reply)
                        appendAssistantMessage(reply)
                        _state.value = _state.value.copy(isThinking = false)
                        speak(reply)
                    },
                    onFailure = { error ->
                        appendAssistantMessage("Görsel analiz hatası: ${error.message?.take(100)}")
                        _state.value = _state.value.copy(isThinking = false, isSpeaking = false)
                    },
                )
            }
        }

        val launcher = VisionCapture.launchScreenCapture
        if (launcher == null) {
            ScreenCapturePipeline.complete(Result.failure(Exception("Ekran analizi başlatılamadı.")))
        } else {
            launcher.invoke()
        }
    }

    // ── Ek dosya / medya analizi ──────────────────────────────────────────────

    fun analyzePickedImage() {
        if (!_state.value.isActive || _state.value.isThinking) return
        val label = "🖼 Galeri resmi"
        appendUserMessage(label)
        runVisionCapture("Bu resimde ne var? Türkçe ve detaylıca anlat.", label) {
            AttachmentCapture.captureImage?.invoke(it)
        }
    }

    fun analyzePickedVideo() {
        if (!_state.value.isActive || _state.value.isThinking) return
        val label = "🎬 Video karesi"
        appendUserMessage(label)
        runVisionCapture("Bu videoda ne görüyorsun? Türkçe ve kısa anlat.", label) {
            AttachmentCapture.captureVideo?.invoke(it)
        }
    }

    fun analyzePickedFile() {
        if (!_state.value.isActive || _state.value.isThinking) return
        _state.value = _state.value.copy(isThinking = true, isSpeaking = false)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    AttachmentCapture.readFile?.invoke()
                        ?: Result.failure(Exception("Dosya okuyucu hazır değil."))
                }
                val content = result.getOrThrow()
                _state.value = _state.value.copy(isThinking = false)

                if (content.startsWith("\u0000VISION\u0000")) {
                    // Görsel analiz sonucu — "Galeriden resim" ile aynı davranış
                    val reply = content.removePrefix("\u0000VISION\u0000")
                    val label = "📎 Dosyadan görsel"
                    memory.appendHistory("user", label)
                    memory.appendHistory("assistant", reply)
                    appendUserMessage(label)
                    appendAssistantMessage(reply)
                    speak(reply)
                } else {
                    // Metin dosyası — LLM'e gönder
                    send("📎 Dosya içeriği:\n\n${content.take(4000)}")
                }
            } catch (e: Exception) {
                appendAssistantMessage("Dosya okunamadı: ${e.message?.take(100)}")
                _state.value = _state.value.copy(isThinking = false)
            }
        }
    }

    private fun runVisionCapture(
        prompt: String,
        userLabel: String,
        capture: suspend (String) -> Result<String>?,
    ) {
        _state.value = _state.value.copy(isThinking = true, isSpeaking = false)
        viewModelScope.launch {
            try {
                val reply = withContext(Dispatchers.IO) {
                    capture(prompt)?.getOrThrow() ?: "Görsel analiz kullanılamıyor."
                }
                memory.appendHistory("user", userLabel)
                memory.appendHistory("assistant", reply)
                appendAssistantMessage(reply)
                _state.value = _state.value.copy(isThinking = false)
                speak(reply)
            } catch (e: Exception) {
                val errorMsg = "Görsel analiz hatası: ${e.message?.take(100)}"
                appendAssistantMessage(errorMsg)
                _state.value = _state.value.copy(isThinking = false, isSpeaking = false)
            }
        }
    }

    // ── LLM pipeline ──────────────────────────────────────────────────────────

    private fun processWithLLM(userText: String) {
        val client = openAIClient
        if (client == null) {
            _state.value = _state.value.copy(apiKeyMissing = true)
            return
        }

        _state.value = _state.value.copy(isThinking = true)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val factsSummary = memory.getFactsSummary()
                val systemPrompt = personality.buildSystemPrompt(factsSummary)

                // Mesaj listesi: sistem + geçmiş + yeni kullanıcı mesajı
                val messages = mutableListOf(
                    OpenAIMessage(role = "system", content = systemPrompt),
                )
                messages.addAll(conversationHistory)

                val reply = client.chatWithTools(
                    messages = messages,
                    tools = toolExecutor.tools,
                    toolExecutor = { name, args ->
                        toolExecutor.execute(
                            name = name,
                            args = args,
                            onRemember = { text, category -> memory.addFact(text, category) },
                            onModeChange = { mode ->
                                val newMode = if (mode == "ciddi") DilaraMode.SERIOUS else DilaraMode.NORMAL
                                personality.mode = newMode
                                viewModelScope.launch { memory.saveMode(mode) }
                                _state.value = _state.value.copy(mode = newMode)
                            },
                        )
                    },
                    temperature = if (personality.mode == DilaraMode.SERIOUS) 0.3 else 0.65,
                )

                // Geçmişe ekle (kullanıcı + asistan)
                conversationHistory.add(OpenAIMessage(role = "user", content = userText))
                conversationHistory.add(OpenAIMessage(role = "assistant", content = reply))
                if (conversationHistory.size > 40) {
                    repeat(conversationHistory.size - 40) { conversationHistory.removeAt(0) }
                }

                // Kalıcı hafızaya kaydet
                memory.appendHistory("user", userText)
                memory.appendHistory("assistant", reply)

                withContext(Dispatchers.Main) {
                    appendAssistantMessage(reply)
                    _state.value = _state.value.copy(isThinking = false)
                    speak(reply)
                }
            } catch (e: Exception) {
                val errorMsg = "Bir sorun çıktı: ${e.message?.take(100)}"
                withContext(Dispatchers.Main) {
                    appendAssistantMessage(errorMsg)
                    _state.value = _state.value.copy(isThinking = false)
                }
            }
        }
    }

    // ── TTS ───────────────────────────────────────────────────────────────────

    private fun speak(text: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isSpeaking = true)
            tts.speak(text)
            _state.value = _state.value.copy(isSpeaking = false)
        }
    }

    // ── Wake word broadcast ───────────────────────────────────────────────────

    private var wakeReceiver: BroadcastReceiver? = null

    private fun registerWakeReceiver() {
        wakeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == WakeWordService.ACTION_WAKE_DETECTED) {
                    if (_state.value.isActive && !_state.value.isThinking) {
                        startListening()
                    }
                }
            }
        }
        val filter = IntentFilter(WakeWordService.ACTION_WAKE_DETECTED)
        getApplication<Application>().registerReceiver(wakeReceiver, filter,
            Context.RECEIVER_NOT_EXPORTED)
    }

    private fun unregisterWakeReceiver() {
        wakeReceiver?.let {
            runCatching { getApplication<Application>().unregisterReceiver(it) }
        }
        wakeReceiver = null
    }

    // ── Konuşma geçmişi ───────────────────────────────────────────────────────

    private fun appendUserMessage(text: String) {
        _state.value = _state.value.copy(
            messages = _state.value.messages + ChatMessage(text, isUser = true)
        )
    }

    private fun appendAssistantMessage(text: String) {
        _state.value = _state.value.copy(
            messages = _state.value.messages + ChatMessage(text, isUser = false)
        )
    }

    fun clearHistory() {
        viewModelScope.launch {
            memory.clearHistory()
            conversationHistory.clear()
            _state.value = _state.value.copy(messages = emptyList())
        }
    }

    // ── Ayarlar ───────────────────────────────────────────────────────────────

    fun saveSettings(apiKey: String, serverUrl: String, userName: String) {
        viewModelScope.launch {
            if (apiKey.isNotBlank()) {
                memory.saveApiKey(apiKey)
                openAIClient?.close()
                openAIClient = OpenAIClient(apiKey)
                _state.value = _state.value.copy(apiKeyMissing = false)
            }
            if (serverUrl.isNotBlank()) {
                memory.saveServerUrl(serverUrl)
                syncClient?.close()
                syncClient = DilaraClient(serverUrl)
            }
            if (userName.isNotBlank()) {
                memory.saveUserName(userName)
                personality.setUserName(userName)
            }
        }
    }

    // ── Backend Sync ──────────────────────────────────────────────────────────

    /**
     * Yerel hafızayı backend'e push eder.
     * Önce login dener, token alır, sonra push yapar.
     */
    fun syncToBackend(username: String, password: String) {
        val client = syncClient ?: run {
            appendAssistantMessage("Önce ayarlar ekranından sunucu adresi gir.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(syncStatus = SyncStatus.SYNCING)
            }
            try {
                // Token al (önce mevcut token dene, yoksa login)
                var token = memory.getAuthToken()
                if (token.isBlank()) {
                    token = client.login(username, password).getOrThrow().accessToken
                    memory.saveAuthToken(token)
                }

                // Hafızayı topla ve push et
                val facts = memory.getFacts()
                val items = facts.map { fact ->
                    SyncMemoryItem(
                        id = java.util.UUID.nameUUIDFromBytes(fact.text.toByteArray()).toString(),
                        textEncrypted = fact.text, // İleride AES şifreleme eklenecek
                        category = fact.category,
                    )
                }
                val result = client.push(token, items).getOrThrow()

                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(syncStatus = SyncStatus.SUCCESS)
                    appendAssistantMessage("${result.upserted} kayıt sunucuya gönderildi.")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(syncStatus = SyncStatus.ERROR)
                    appendAssistantMessage("Senkronizasyon başarısız: ${e.message?.take(80)}")
                }
            }
        }
    }

    /**
     * Backend'den hafızayı çekip yerel depoya yazar.
     */
    fun syncFromBackend(username: String, password: String) {
        val client = syncClient ?: return
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(syncStatus = SyncStatus.SYNCING)
            }
            try {
                var token = memory.getAuthToken()
                if (token.isBlank()) {
                    token = client.login(username, password).getOrThrow().accessToken
                    memory.saveAuthToken(token)
                }
                val items = client.pull(token).getOrThrow()
                items.forEach { item -> memory.addFact(item.textEncrypted, item.category) }

                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(syncStatus = SyncStatus.SUCCESS)
                    appendAssistantMessage("${items.size} kayıt sunucudan alındı.")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(syncStatus = SyncStatus.ERROR)
                    appendAssistantMessage("İndirme başarısız: ${e.message?.take(80)}")
                }
            }
        }
    }
}
