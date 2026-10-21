package com.filewall.ui.security

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

private const val PREFS = "hidden_apps"
private const val KEY = "locked_packages"

/** An app that can be opened from the phone's home screen. */
private data class AppEntry(val label: String, val pkg: String, val icon: Drawable)

/** Launcher apps on this phone, sorted by name. Works on Android 11 through the manifest queries block. */
private fun loadApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .map { info -> AppEntry(info.loadLabel(pm).toString(), info.activityInfo.packageName, info.loadIcon(pm)) }
        .filter { it.pkg != context.packageName }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
}

/** The Hide Apps button in Settings. Lists the apps, then asks "Lock this app?" for the one tapped. */
@Composable
fun HideAppsSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var locked by remember { mutableStateOf(prefs.getStringSet(KEY, emptySet()).orEmpty().toSet()) }
    var showList by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<AppEntry?>(null) }
    val apps = remember(showList) { if (showList) loadApps(context) else emptyList() }

    OutlinedButton(onClick = { showList = true }, modifier = modifier.fillMaxWidth()) {
        Text("Hide Apps")
    }

    if (showList) {
        AlertDialog(
            onDismissRequest = { showList = false },
            title = { Text("Hide Apps") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(apps, key = { it.pkg }) { app ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { pending = app }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(
                                painter = BitmapPainter(app.icon.toBitmap().asImageBitmap()),
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(app.label, modifier = Modifier.weight(1f))
                            if (app.pkg in locked) {
                                Text("Locked", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showList = false }) { Text("Close") } },
        )
    }

    pending?.let { app ->
        val isLocked = app.pkg in locked
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(if (isLocked) "Unlock this app?" else "Lock this app?") },
            text = { Text(app.label) },
            confirmButton = {
                TextButton(onClick = {
                    val next = if (isLocked) locked - app.pkg else locked + app.pkg
                    prefs.edit().putStringSet(KEY, next).apply()
                    locked = next
                    pending = null
                }) { Text(if (isLocked) "Unlock" else "Lock") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}
