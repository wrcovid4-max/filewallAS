package com.filewall.ui.language

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.filewall.R

/**
 * The 🌐 language pill. It shows in the top-right corner of every screen. Picking a language asks
 * for confirmation, then applies it: AppCompat recreates the activity, which is the restart.
 */
@Composable
fun LanguageSwitcher(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Language the user picked, waiting for confirmation before the app restarts.
    var pendingLanguage by remember { mutableStateOf<String?>(null) }

    // Read the locale actually in effect, so the highlight always matches the content.
    val appliedTag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    val currentLanguage = AppLanguage.fromTag(appliedTag).tag

    Box(modifier) {
        LanguageToggle(
            language = currentLanguage,
            onLanguageChange = { tag -> if (tag != currentLanguage) pendingLanguage = tag },
        )
    }

    pendingLanguage?.let { tag ->
        val languageName = stringResource(
            when (tag) {
                "ur" -> R.string.language_name_ur
                "zh" -> R.string.language_name_zh
                "ar" -> R.string.language_name_ar
                "en-US" -> R.string.language_name_en_us
                "en-NZ" -> R.string.language_name_en_nz
                "es" -> R.string.language_name_es
                "zh-TW" -> R.string.language_name_zh_tw
                "fa" -> R.string.language_name_fa
                "pa" -> R.string.language_name_pa
                "pa-Arab-PK" -> R.string.language_name_pa_pk
                else -> R.string.language_name_en
            },
        )
        AlertDialog(
            onDismissRequest = { pendingLanguage = null },
            title = {
                Text(text = stringResource(R.string.language_restart_title), fontWeight = FontWeight.Bold)
            },
            text = {
                Text(text = stringResource(R.string.language_restart_body, languageName))
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingLanguage = null
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                }) {
                    Text(stringResource(R.string.action_restart))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingLanguage = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun LanguageToggle(
    language: String,
    onLanguageChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = AppLanguage.values().map { it.tag to it.menuLabel }
    val current = AppLanguage.fromTag(language).shortLabel
    Box {
        Surface(
            shape = RoundedCornerShape(percent = 50),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .clickable { expanded = true },
        ) {
            Text(
                text = "🌐 $current ▾",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (tag, label) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = label,
                            fontWeight = if (tag == language) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    trailingIcon = {
                        if (tag == language) {
                            Text(text = "✓", color = MaterialTheme.colorScheme.primary)
                        }
                    },
                    onClick = {
                        expanded = false
                        onLanguageChange(tag)
                    },
                )
            }
        }
    }
}
