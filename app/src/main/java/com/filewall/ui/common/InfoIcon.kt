package com.filewall.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.filewall.R

/** A small (i) button that explains a control in plain words. Same idea as Cash Memer's. */
@Composable
fun InfoIcon(title: String, body: String, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = modifier.size(32.dp)) {
        Icon(
            imageVector = Icons.Filled.Info,
            contentDescription = "About $title",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title, style = MaterialTheme.typography.titleLarge) },
            text = { Text(body, style = MaterialTheme.typography.bodyLarge) },
            confirmButton = {
                TextButton(onClick = { open = false }) {
                    Text(stringResourceSafe(R.string.action_close))
                }
            },
        )
    }
}

/** The AI disclaimer, shown under any AI chat input, worded as in Cash Memer. */
@Composable
fun AiDisclaimer(modifier: Modifier = Modifier) {
    Text(
        text = "This is an AI and can make mistakes. The model used here is Gemini by Google Inc.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}
