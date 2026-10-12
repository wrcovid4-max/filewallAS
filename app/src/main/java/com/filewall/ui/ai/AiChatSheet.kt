package com.filewall.ui.ai

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filewall.ai.GeminiClient
import com.filewall.ui.common.AiDisclaimer
import com.filewall.ui.common.InfoIcon
import kotlinx.coroutines.launch

private const val AI_TAG = "FileWallDoc"
private const val CONTEXT_LIMIT = 60_000

/**
 * Ask AI, for one document or across the vault.
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
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ai_consent", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var allowed by remember { mutableStateOf(consentKey != null && prefs.getBoolean(consentKey, false)) }
    var contextText by remember { mutableStateOf<String?>(null) }
    val turns = remember { mutableStateListOf<GeminiClient.Turn>() }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun send() {
        val question = input.trim()
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
                "Sorry, I could not answer right now (${e.message})."
            }
            Log.i(AI_TAG, "ai: answered ${answer.length} chars")
            turns.add(GeminiClient.Turn(false, answer))
            sending = false
            listState.animateScrollToItem(turns.size)
        }
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
                InfoIcon(
                    "Ask AI",
                    "Ask questions about your files. Answers come from Google Gemini. The text you allow " +
                        "is sent to Google when you ask. FileWall keeps no copy of it.",
                )
            }
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
                            Text(
                                text = "Ask a question to start.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(turns) { turn -> Bubble(turn) }
                    if (sending) {
                        item {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        maxLines = 4,
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 16.sp,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                    IconButton(onClick = { send() }, enabled = !sending && input.isNotBlank()) {
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
