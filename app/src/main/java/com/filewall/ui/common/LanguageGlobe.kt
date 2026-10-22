package com.filewall.ui.common

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Remembers the language chosen inside the app. An empty tag means the phone's language. */
object AppLanguage {
    private const val PREFS = "filewall_language"
    private const val KEY = "tag"

    /** Language tag to display name, in each language's own script. */
    val options: List<Pair<String, String>> = listOf(
        "" to "Phone language",
        "en" to "English",
        "es" to "Español",
        "ur" to "اردو",
        "ar" to "العربية",
        "fa" to "فارسی",
        "zh" to "中文（简体）",
        "zh-TW" to "中文（繁體）",
        "pa" to "ਪੰਜਾਬੀ (ਗੁਰਮੁਖੀ)",
        "pa-Arab-PK" to "پنجابی (شاہمکھی)",
    )

    fun saved(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""

    fun save(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
    }

    /** Wraps a context so the app's text uses the chosen language. Used when the activity starts. */
    fun wrap(base: Context): Context {
        val tag = saved(base)
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}

/** The globe button, kept in the top-right corner of every screen. Opens the language list. */
@Composable
fun LanguageGlobe(modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = modifier) {
        Icon(Icons.Filled.Language, "Language", tint = MaterialTheme.colorScheme.onSurface)
    }
    if (open) {
        val context = LocalContext.current
        var chosen by remember { mutableStateOf(AppLanguage.saved(context)) }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Language") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AppLanguage.options.forEach { (tag, name) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { chosen = tag }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = chosen == tag, onClick = { chosen = tag })
                            Text(name, Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (chosen != AppLanguage.saved(context)) {
                        AppLanguage.save(context, chosen)
                        (context as? Activity)?.recreate()
                    }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}
