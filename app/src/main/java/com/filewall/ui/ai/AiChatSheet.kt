package com.filewall.ui.ai

import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Surface
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.filewall.ai.GeminiClient
import com.filewall.data.ai.ChatHistory
import com.filewall.data.ai.SavedChat
import com.filewall.ui.common.AiDisclaimer
import com.filewall.ui.common.InfoIcon
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.UUID

private const val AI_TAG = "FileWallDoc"
private const val CONTEXT_LIMIT = 60_000
private const val ERROR_TEXT = "Sorry, I couldn't get an answer right now. Please try again."
private const val MODEL_NOTE =
    "Powered by Google Gemini. If the model is busy, FileWall switches to a backup model automatically."

/**
 * Ask AI, for one document or across the vault. Same inputs as Cash Memer's Ask AI: suggestion
 * chips, voice input, new chat, Recent chats, the model note and the disclaimer.
 *
 * Nothing is sent until the user allows it. [consentKey] remembers the choice. When it is null
 * the item is hidden: the user is asked every time, and no chat is saved to Recent chats.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatSheet(
    title: String,
    subtitle: String,
    consentKey: String?,
    loadContext: suspend () -> String,
    onDismiss: () -> Unit,
    onGoToPage: ((Int) -> Unit)? = null,
    documentKey: String? = null,
    suggestions: List<String> = listOf(
        "What is this about?",
        "Summarise the key points",
        "What are the important dates?",
    ),
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ai_consent", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var allowed by remember {
        mutableStateOf(
            consentKey != null &&
                (com.filewall.data.ai.AiConsent.allAllowed(context) || prefs.getBoolean(consentKey, false)),
        )
    }
    var contextText by remember { mutableStateOf<String?>(null) }
    val turns = remember { mutableStateListOf<GeminiClient.Turn>() }
    var chatId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var docChips by remember { mutableStateOf<List<String>?>(null) }
    var historyTick by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()

    // Documents get three questions written for them. Saved per document, unless the item is hidden.
    LaunchedEffect(allowed, documentKey) {
        val key = documentKey ?: return@LaunchedEffect
        if (!allowed) return@LaunchedEffect
        val cached = if (consentKey != null) com.filewall.data.ai.DocSuggestions.load(context, key) else null
        if (cached != null) {
            docChips = cached
            return@LaunchedEffect
        }
        val text = contextText ?: loadContext().also { contextText = it }
        if (text.isBlank()) {
            docChips = emptyList()
            return@LaunchedEffect
        }
        GeminiClient.suggestQuestions(text.take(CONTEXT_LIMIT))
            .onSuccess { questions ->
                docChips = questions
                if (consentKey != null && questions.size == 3) {
                    com.filewall.data.ai.DocSuggestions.save(context, key, questions)
                }
            }
            .onFailure { e ->
                Log.e(AI_TAG, "ai: could not write document questions", e)
                docChips = emptyList()
            }
    }
    // Where the screen is while it is being dragged, and how far it has slid in on open.
    val dragOffset = remember { Animatable(0f) }
    val entered = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        entered.animateTo(1f, tween(durationMillis = 320, easing = FastOutSlowInEasing))
    }

    fun send(raw: String) {
        val question = raw.trim()
        if (question.isEmpty() || sending) return
        // "page 5" or "go to page 5" opens that page straight away.
        val explicitPage = Regex("""(?i)(?:go\s+to\s+|open\s+|show\s+)?page\s+(\d+)""").find(question)
        if (explicitPage != null && onGoToPage != null) {
            onGoToPage(explicitPage.groupValues[1].toInt())
            onDismiss()
            return
        }
        val history = turns.toList()
        turns.add(GeminiClient.Turn(true, question))
        input = ""
        sending = true
        scope.launch {
            val ctx = contextText ?: loadContext().also { contextText = it }
            Log.i(AI_TAG, "ai: asking, question=${question.length} chars, context=${ctx.length} chars")
            val result = GeminiClient.ask(question, ctx.take(CONTEXT_LIMIT), history.takeLast(8))
            val answer = result.getOrElse { e ->
                Log.e(AI_TAG, "ai: failed", e)
                ERROR_TEXT
            }
            Log.i(AI_TAG, "ai: answered ${answer.length} chars")
            val pageLine = Regex("PAGE:\\s*(\\d+)\\s*\$").find(answer)
            val shown = if (pageLine != null) answer.removeRange(pageLine.range).trimEnd() else answer
            turns.add(GeminiClient.Turn(false, shown, pageLine?.groupValues?.get(1)?.toIntOrNull()))
            if (consentKey != null) {
                ChatHistory.save(
                    context,
                    SavedChat(
                        id = chatId,
                        title = (turns.firstOrNull { it.fromUser }?.text ?: "Chat").take(60),
                        subtitle = subtitle,
                        updatedAt = System.currentTimeMillis(),
                        turns = turns.toList(),
                    ),
                )
                historyTick++
            }
            sending = false
            if (turns.isNotEmpty()) listState.animateScrollToItem(turns.size - 1)
        }
    }

    // Voice input, as in Cash Memer: speak the question, and it lands in the text box.
    val recognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null
    }
    DisposableEffect(recognizer) {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                listening = false
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!spoken.isNullOrBlank()) input = spoken
            }

            override fun onError(error: Int) {
                listening = false
            }

            override fun onEndOfSpeech() {
                listening = false
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        onDispose { recognizer?.destroy() }
    }
    fun startListening() {
        val engine = recognizer ?: return
        listening = true
        engine.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM),
        )
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening()
    }

    // Full screen, not a pop-up: the chat takes over the whole screen.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = dragOffset.value + (1f - entered.value) * 120.dp.toPx()
                    alpha = entered.value
                },
            color = MaterialTheme.colorScheme.background,
        ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            DismissGrab(offset = dragOffset, onDismiss = onDismiss)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (allowed && consentKey != null) {
                    IconButton(onClick = { showHistory = !showHistory }) {
                        Icon(
                            Icons.Filled.History,
                            contentDescription = "Recent chats",
                            tint = if (showHistory) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (allowed) {
                    IconButton(onClick = {
                        chatId = UUID.randomUUID().toString()
                        turns.clear()
                        input = ""
                        showHistory = false
                        Log.i(AI_TAG, "ai: new chat")
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = "New chat", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                InfoIcon(
                    "Ask AI",
                    "Ask questions about your files. Answers come from Google Gemini. The text you allow " +
                        "is sent to Google when you ask. FileWall keeps no copy of it on Google's side.",
                )
            }
            Text(
                text = MODEL_NOTE,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            if (!allowed) {
                ConsentCard(
                    rememberChoice = consentKey != null,
                    onAllow = {
                        allowed = true
                        if (consentKey != null) prefs.edit().putBoolean(consentKey, true).apply()
                        Log.i(AI_TAG, "ai: allowed, remembered=${consentKey != null}")
                    },
                    onNotNow = onDismiss,
                )
            } else if (showHistory) {
                val chats = remember(historyTick, showHistory) { ChatHistory.all(context) }
                RecentChats(
                    chats = chats,
                    onOpen = { chat ->
                        chatId = chat.id
                        turns.clear()
                        turns.addAll(chat.turns)
                        showHistory = false
                        Log.i(AI_TAG, "ai: opened saved chat ${chat.id}")
                    },
                    onDelete = { chat ->
                        ChatHistory.delete(context, chat.id)
                        historyTick++
                        Log.i(AI_TAG, "ai: deleted saved chat ${chat.id}")
                    },
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (turns.isEmpty()) {
                        item {
                            Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                                Text(
                                    text = "Ask about your files",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "Tap a suggestion, or type or speak your own question.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(12.dp))
                                val chips = if (documentKey != null) docChips else suggestions
                                if (chips == null) {
                                    Text(
                                        text = "Preparing questions for this document…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    chips.forEach { suggestion ->
                                        AssistChip(
                                            onClick = { send(suggestion) },
                                            label = { Text(suggestion) },
                                            modifier = Modifier.padding(bottom = 8.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    items(turns) { turn -> Bubble(turn, onGoToPage) }
                    if (sending) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    text = "Thinking…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (listening) {
                    Text(
                        text = "Listening… tap the mic to stop",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFE53935),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        if (listening) {
                            recognizer?.stopListening()
                            listening = false
                        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            startListening()
                        } else {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }) {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = "Speak your question",
                            tint = if (listening) Color(0xFFE53935) else MaterialTheme.colorScheme.primary,
                        )
                    }
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        maxLines = 4,
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 16.sp,
                        ),
                        decorationBox = { inner ->
                            Box {
                                if (input.isEmpty()) {
                                    Text(
                                        text = "Type your question",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                inner()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                    IconButton(onClick = { send(input) }, enabled = !sending && input.isNotBlank()) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                AiDisclaimer()
            }
        }
        }
    }
}

/** Recent chats: tap one to reopen it, or use the bin to delete it. */
@Composable
private fun RecentChats(
    chats: List<SavedChat>,
    onOpen: (SavedChat) -> Unit,
    onDelete: (SavedChat) -> Unit,
) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "Recent chats",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        if (chats.isEmpty()) {
            Text(
                text = "No chats yet. Your conversations will appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(chats, key = { it.id }) { chat ->
                    val visible = remember(chat.id) { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
                    androidx.compose.animation.AnimatedVisibility(
                        visibleState = visible,
                        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
                        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
                    ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .clickable { onOpen(chat) }
                            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = chat.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${chat.subtitle} · ${formatWhen(chat.updatedAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { visible.targetState = false; scope.launch { kotlinx.coroutines.delay(220); onDelete(chat) } }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Delete chat",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    }
                }
            }
        }
    }
}

private fun formatWhen(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

@Composable
private fun Bubble(turn: GeminiClient.Turn, onGoToPage: ((Int) -> Unit)?) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (turn.fromUser) Alignment.End else Alignment.Start,
    ) {
        Text(
            text = turn.text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (turn.fromUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (turn.fromUser) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
        val page = turn.page
        if (page != null && onGoToPage != null) {
            AssistChip(
                onClick = { onGoToPage(page) },
                label = { Text("Go to page $page") },
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun ConsentCard(rememberChoice: Boolean, onAllow: () -> Unit, onNotNow: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(18.dp),
    ) {
        Text(
            text = "Send to Google Gemini?",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (rememberChoice) {
                "To answer, FileWall sends the text it needs to Google Gemini. Choose Not now and nothing is sent. " +
                    "Your choice is remembered for this item."
            } else {
                "This item is in the hidden vault, so you are asked every time. Nothing is remembered, " +
                    "and the chat is not saved to Recent chats."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onNotNow) { Text("Not now") }
            Spacer(Modifier.size(8.dp))
            Button(onClick = onAllow) { Text("Allow") }
        }
    }
}

/**
 * A grab bar at the top centre. The screen follows the finger as it is dragged down. Released
 * far enough, it slides away and closes; released short of that, it springs back.
 */
@Composable
private fun DismissGrab(offset: Animatable<Float, AnimationVector1D>, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { 110.dp.toPx() }
    val slideAway = with(LocalDensity.current) { 900.dp.toPx() }
    Box(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = {},
                    onVerticalDrag = { _, amount ->
                        scope.launch { offset.snapTo((offset.value + amount).coerceAtLeast(0f)) }
                    },
                    onDragEnd = {
                        scope.launch {
                            if (offset.value > threshold) {
                                offset.animateTo(slideAway, tween(durationMillis = 220, easing = FastOutLinearInEasing))
                                onDismiss()
                            } else {
                                offset.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow))
                            }
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 44.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)),
        )
    }
}
