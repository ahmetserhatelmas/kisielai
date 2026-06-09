package com.dilara.assistant.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dilara.assistant.modules.DilaraMode
import com.dilara.assistant.viewmodel.ChatViewModel
import com.dilara.assistant.viewmodel.SyncStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DilaraApp(vm: ChatViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val listState = rememberLazyListState()
    var showSettings by remember { mutableStateOf(false) }

    // İlk açılışta API key eksikse ayarlar ekranını aç
    LaunchedEffect(state.apiKeyMissing) {
        if (state.apiKeyMissing) showSettings = true
    }

    if (showSettings) {
        SettingsDialog(
            onDismiss = { showSettings = false },
            onSave = { apiKey, serverUrl, userName ->
                vm.saveSettings(apiKey, serverUrl, userName)
                showSettings = false
            },
            onSyncPush = { user, pass -> vm.syncToBackend(user, pass) },
            onSyncPull = { user, pass -> vm.syncFromBackend(user, pass) },
            syncStatus = state.syncStatus,
        )
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Dilara", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                            Spacer(Modifier.width(8.dp))
                            ModeChip(state.mode)
                        }
                        Text(
                            statusText(state),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                },
                actions = {
                    // Sync durumu göstergesi
                    if (state.syncStatus == SyncStatus.SYNCING) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(4.dp))
                    }
                    if (state.messages.isNotEmpty()) {
                        IconButton(onClick = { vm.clearHistory() }) {
                            Text("🗑", fontSize = 18.sp)
                        }
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Text("⚙️", fontSize = 18.sp)
                    }
                    Button(
                        onClick = { vm.toggleActivation() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.isActive) Color(0xFF2EA043)
                            else MaterialTheme.colorScheme.primary,
                        ),
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Text(if (state.isActive) "Aktif" else "Yetki Ver")
                    }
                }
            )
        },
        bottomBar = {
            ChatInput(
                enabled = state.isActive && !state.isThinking,
                isListening = state.isListening,
                isScreenRecording = state.isScreenRecording,
                onSend = { vm.send(it) },
                onMic = { vm.startListening() },
                onCamera = { vm.analyzeCamera() },
                onScreen = { vm.analyzeScreen() },
                onAttachImage = { vm.analyzePickedImage() },
                onAttachVideo = { vm.analyzePickedVideo() },
                onAttachFile = { vm.analyzePickedFile() },
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (state.messages.isEmpty()) {
                EmptyState(active = state.isActive)
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.messages) { msg ->
                        ChatBubble(text = msg.text, isUser = msg.isUser)
                    }
                }
            }

            // Düşünme göstergesi
            AnimatedVisibility(
                visible = state.isThinking,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp),
            ) {
                ThinkingDots()
            }
        }
    }
}

// ── Bileşenler ───────────────────────────────────────────────────────────────

@Composable
private fun ModeChip(mode: DilaraMode) {
    val (text, color) = when (mode) {
        DilaraMode.SERIOUS -> Pair("Ciddi", Color(0xFFE53935))
        DilaraMode.NORMAL  -> Pair("Normal", Color(0xFF43A047))
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.15f),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            fontSize = 11.sp,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ThinkingDots() {
    val transition = rememberInfiniteTransition(label = "dots")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = 3f,
        animationSpec = infiniteRepeatable(keyframes { durationMillis = 900 }),
        label = "phase",
    )
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(3) { i ->
                val alpha = if (phase.toInt() == i) 1f else 0.3f
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
                )
            }
        }
    }
}

@Composable
private fun EmptyState(active: Boolean) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Merhaba!", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                if (active) "Aktif moddayım. Yazabilir, 🎤 konuşabilir, 📷 kamera veya 👁 ekran analizi kullanabilirsin."
                else "Henüz aktif değilim.\nBaşlamak için 'Yetki Ver' butonuna bas.",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                lineHeight = 24.sp,
            )
        }
    }
}

@Composable
private fun ChatBubble(text: String, isUser: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp,
            ),
            color = if (isUser) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(0.88f),
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                color = if (isUser) Color.White
                else MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                lineHeight = 22.sp,
            )
        }
    }
}

@Composable
private fun ChatInput(
    enabled: Boolean,
    isListening: Boolean,
    isScreenRecording: Boolean,
    onSend: (String) -> Unit,
    onMic: () -> Unit,
    onCamera: () -> Unit,
    onScreen: () -> Unit,
    onAttachImage: () -> Unit,
    onAttachVideo: () -> Unit,
    onAttachFile: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var showAttachMenu by remember { mutableStateOf(false) }

    val sendMessage = {
        if (text.isNotBlank()) {
            onSend(text)
            text = ""
        }
    }

    Surface(
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding(),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .fillMaxWidth(),
        ) {
            // Üst satır: geniş metin kutusu + gönder
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = {
                        Text(if (enabled) "Bir şey yaz..." else "Önce yetki ver")
                    },
                    enabled = enabled,
                    modifier = Modifier
                        .weight(1f)
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown &&
                                keyEvent.key == Key.Enter &&
                                !keyEvent.isShiftPressed
                            ) {
                                sendMessage()
                                true
                            } else false
                        },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = { sendMessage() }),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = sendMessage,
                    enabled = enabled && text.isNotBlank(),
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text("➤", fontSize = 18.sp, color = Color.White)
                }
            }

            Spacer(Modifier.height(10.dp))

            // Alt satır: aksiyon butonları eşit aralıklı
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    FilledIconButton(
                        onClick = { showAttachMenu = true },
                        enabled = enabled,
                        modifier = Modifier.size(44.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color(0xFF2C5F2E),
                        ),
                    ) {
                        Text("📎", fontSize = 16.sp)
                    }
                    DropdownMenu(
                        expanded = showAttachMenu,
                        onDismissRequest = { showAttachMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("🖼  Galeriden resim") },
                            onClick = { showAttachMenu = false; onAttachImage() },
                            enabled = enabled,
                        )
                        DropdownMenuItem(
                            text = { Text("🎬  Galeriden video") },
                            onClick = { showAttachMenu = false; onAttachVideo() },
                            enabled = enabled,
                        )
                        DropdownMenuItem(
                            text = { Text("📄  Dosya seç") },
                            onClick = { showAttachMenu = false; onAttachFile() },
                            enabled = enabled,
                        )
                    }
                }

                FilledIconButton(
                    onClick = onCamera,
                    enabled = enabled,
                    modifier = Modifier.size(44.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                    ),
                ) {
                    Text("📷", fontSize = 16.sp)
                }

                val screenColor = if (isScreenRecording) Color(0xFFE53935) else MaterialTheme.colorScheme.tertiary
                FilledIconButton(
                    onClick = onScreen,
                    enabled = enabled || isScreenRecording,
                    modifier = Modifier.size(44.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = screenColor,
                    ),
                ) {
                    Text(if (isScreenRecording) "⏹" else "👁", fontSize = 16.sp)
                }

                val micColor = if (isListening) Color(0xFFE53935) else MaterialTheme.colorScheme.primary
                FilledIconButton(
                    onClick = onMic,
                    enabled = enabled,
                    modifier = Modifier.size(44.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = micColor),
                ) {
                    Text(if (isListening) "⏹" else "🎤", fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    onDismiss: () -> Unit,
    onSave: (apiKey: String, serverUrl: String, userName: String) -> Unit,
    onSyncPush: (username: String, password: String) -> Unit,
    onSyncPull: (username: String, password: String) -> Unit,
    syncStatus: SyncStatus,
) {
    var apiKey     by remember { mutableStateOf("") }
    var serverUrl  by remember { mutableStateOf("") }
    var userName   by remember { mutableStateOf("") }
    var syncUser   by remember { mutableStateOf("") }
    var syncPass   by remember { mutableStateOf("") }
    var showSync   by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 8.dp) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text("Ayarlar", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Spacer(Modifier.height(20.dp))

                // ── OpenAI ────────────────────────────────────────────────
                Text("OpenAI API Anahtarı", fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it },
                    label = { Text("sk-...") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                Spacer(Modifier.height(16.dp))

                // ── Kullanıcı adı ─────────────────────────────────────────
                Text("Adın (Dilara sana nasıl hitap etsin)", fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = userName, onValueChange = { userName = it },
                    label = { Text("Örn: İbo") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                Spacer(Modifier.height(16.dp))

                // ── Sunucu sync ───────────────────────────────────────────
                Text("Senkronizasyon Sunucusu (opsiyonel)", fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = serverUrl, onValueChange = { serverUrl = it },
                    label = { Text("http://sunucu:8000") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )

                if (serverUrl.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { showSync = !showSync }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (showSync) "Sync kapat ▲" else "Sync seçenekleri ▼")
                    }
                    AnimatedVisibility(visible = showSync) {
                        Column {
                            OutlinedTextField(
                                value = syncUser, onValueChange = { syncUser = it },
                                label = { Text("Kullanıcı adı") },
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = syncPass, onValueChange = { syncPass = it },
                                label = { Text("Şifre") },
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { onSyncPush(syncUser, syncPass) },
                                    enabled = syncUser.isNotBlank() && syncPass.isNotBlank() && syncStatus != SyncStatus.SYNCING,
                                    modifier = Modifier.weight(1f),
                                ) { Text("↑ Gönder") }
                                OutlinedButton(
                                    onClick = { onSyncPull(syncUser, syncPass) },
                                    enabled = syncUser.isNotBlank() && syncPass.isNotBlank() && syncStatus != SyncStatus.SYNCING,
                                    modifier = Modifier.weight(1f),
                                ) { Text("↓ Al") }
                            }
                            if (syncStatus == SyncStatus.SYNCING) {
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            if (syncStatus == SyncStatus.SUCCESS) {
                                Spacer(Modifier.height(4.dp))
                                Text("✓ Tamamlandı", color = Color(0xFF2EA043), fontSize = 13.sp)
                            }
                            if (syncStatus == SyncStatus.ERROR) {
                                Spacer(Modifier.height(4.dp))
                                Text("✗ Hata oluştu (chat ekranında detay var)", color = Color(0xFFE53935), fontSize = 13.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("İptal") }
                    Button(
                        onClick = { onSave(apiKey, serverUrl, userName) },
                        enabled = apiKey.isBlank() || apiKey.startsWith("sk-"),
                        modifier = Modifier.weight(1f),
                    ) { Text("Kaydet") }
                }
            }
        }
    }
}

private fun statusText(state: com.dilara.assistant.viewmodel.ChatUiState): String = when {
    state.isScreenRecording -> "🔴 Ekran kaydı sürüyor… Bitirmek için göz simgesine bas."
    state.isListening -> "🔴 Kaydediliyor… Bitirmek için mikrofona tekrar bas."
    state.isSpeaking  -> "Konuşuyorum..."
    state.isThinking  -> "Düşünüyorum..."
    state.isActive    -> "Aktif · ${state.mode.label}"
    else              -> "Pasif"
}
