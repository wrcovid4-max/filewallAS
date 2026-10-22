package com.filewall.ui.security

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap

private tailrec fun Context.findFragmentActivity(): androidx.fragment.app.FragmentActivity? = when (this) {
    is androidx.fragment.app.FragmentActivity -> this
    is android.content.ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

/** One installed app that can be opened from the home screen. */
private data class AppEntry(val label: String, val pkg: String, val icon: Drawable)

private fun loadApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.loadIcon(pm)) }
        .filter { it.pkg != context.packageName }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
}

/** Settings card: "Hide Apps" lists installed apps and makes a new shortcut for one of them. */
@Composable
fun HideAppsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val apps = remember { loadApps(context) }
    var listOpen by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<AppEntry?>(null) }
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(18.dp),
    ) {
        Text("Hide Apps", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(
            "Make a new shortcut for any app, with its own name and icon. Then remove the original icon from your home screen.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = {
            val host = context.findFragmentActivity()
            if (host != null) {
                // The phone's own biometric screen (fingerprint or face, or screen lock) before the list opens.
                com.filewall.util.Biometrics.prompt(
                    activity = host,
                    title = "Hide Apps",
                    subtitle = "Confirm it's you to choose an app",
                    negativeLabel = "Cancel",
                    onSuccess = { listOpen = true },
                    onFailure = { },
                )
            }
        }) { Text("Hide Apps") }
    }
    if (listOpen) {
        Dialog(onDismissRequest = { listOpen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Choose an app", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = { listOpen = false }) { Text("Close") }
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(apps, key = { it.pkg }) { app ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        chosen = app
                                        listOpen = false
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Image(
                                    bitmap = app.icon.toBitmap(96, 96).asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                )
                                Spacer(Modifier.width(14.dp))
                                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        }
    }
    chosen?.let { app -> ShortcutDialog(app = app, onDone = { chosen = null }) }
}

@Composable
private fun ShortcutDialog(app: AppEntry, onDone: () -> Unit) {
    val context = LocalContext.current
    var name by remember(app.pkg) { mutableStateOf(app.label) }
    var picked by remember(app.pkg) { mutableStateOf<Bitmap?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }?.let {
                picked = Bitmap.createScaledBitmap(it, 192, 192, true)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Create shortcut") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        bitmap = (picked ?: app.icon.toBitmap(192, 192)).asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = { picker.launch("image/*") }) { Text("Pick icon picture") }
                }
                Text(
                    "After adding it, long-press the original icon on your home screen and choose Remove (not Uninstall).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                addShortcut(context, app, name, picked)
                onDone()
            }) { Text("Add to home screen") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

private fun addShortcut(context: Context, app: AppEntry, name: String, picked: Bitmap?) {
    val launch = context.packageManager.getLaunchIntentForPackage(app.pkg)
    if (launch == null) {
        Toast.makeText(context, "This app cannot be opened from a shortcut", Toast.LENGTH_SHORT).show()
        return
    }
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
        Toast.makeText(context, "Your home screen does not allow new shortcuts", Toast.LENGTH_LONG).show()
        return
    }
    val label = name.ifBlank { app.label }
    val info = ShortcutInfoCompat.Builder(context, "hide_${app.pkg}_${System.currentTimeMillis()}")
        .setShortLabel(label)
        .setLongLabel(label)
        .setIcon(IconCompat.createWithBitmap(picked ?: app.icon.toBitmap(192, 192)))
        .setIntent(launch)
        .build()
    ShortcutManagerCompat.requestPinShortcut(context, info, null)
    Toast.makeText(context, "Confirm 'Add' on your home screen", Toast.LENGTH_LONG).show()
}
