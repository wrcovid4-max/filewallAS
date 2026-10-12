package com.filewall.ui.ai

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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.filewall.ai.GeminiClient
import com.filewall.ui.common.AiDisclaimer
import com.filewall.ui.common.InfoIcon
import kotlinx.coroutines.launch

private const val AI_TAG = "FileWallDoc"
private const val CONTEXT_LIMIT = 60_000
private const val ERROR_TEXT = "Sorry, I couldn't get an answer right now. Please try again."
private const val MODEL_NOTE =
    "Powered by Google Gemini. If the model is busy, FileWall switches to a backup model automatically."

/**
 * Ask AI, for one document or across the vault. The same inputs as Cash Memer's Ask AI:
 * suggestion chips, voice input, a new-chat button, the model note and the disclaimer.
 *
 * Nothing is sent until the user allows it. [consentKey] remembers the choice; null means the
 * item is hidden, so the user is asked every time and nothing is remembered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatSheet(
    title: String,
    subtitle: String,
    consentKey: String?,
    loadContext: suspend () -> String,
    onDismiss: () -> Unit,
    suggestions: List<String> = listOf(
        "What is this about?",
        "Summarise the key points",
        "What are the important dates?",
    ),
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ai_consent", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var allowed by remember { mutableStateOf(consentKey != null && prefs.getBoolean(consentKey, false)) }
    var contextText by remember { mutableStateOf<String?>(null) }
    val turns = remember { mutableStateListOf<GeminiClient.Turn>() }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun send(raw: String) {
        val question = raw.trim()
        if (question.isEmpty() || sending) return
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
            turns.add(GeminiClient.Turn(false, answer))
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

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
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
                if (allowed) {
                    IconButton(onClick = {
                        turns.clear()
                        input = ""
                        Log.i(AI_TAG, "ai: new chat")
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = "New chat", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                InfoIcon(
                    "Ask AI",
                    "Ask questions about your files. Answers come from Google Gemini. The text you allow " +
                        "is sent to Google when you ask. FileWall keeps no copy of it.",
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
                                suggestions.forEach { suggestion ->
                                    AssistChip(
                                        onClick = { send(suggestion) },
                                        label = { Text(suggestion) },
                                        modifier = Modifier.padding(bottom = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                    items(turns) { turn -> Bubble(turn) }
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

@Composable
private fun Bubble(turn: GeminiClient.Turn) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (turn.fromUser) Arrangement.End else Arrangement.Start,
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
                "This item is in the hidden vault, so you are asked every time. Nothing is remembered."
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
