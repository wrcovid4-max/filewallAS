package com.filewall.ui.language

import androidx.annotation.StringRes
import com.filewall.R

/**
 * Every language the app supports. To add a language: add one entry here and a values-xx folder
 * with its strings. The globe menu reads this list, so the new language appears automatically.
 */
enum class AppLanguage(
    val tag: String,
    val menuLabel: String,
    val shortLabel: String,
    @StringRes val nameRes: Int,
) {
    ENGLISH("en", "English (ENG)", "ENG", R.string.language_name_en),
    ENGLISH_US("en-US", "English US (ENG)", "ENG", R.string.language_name_en_us),
    ENGLISH_NZ("en-NZ", "English NZ (ENG)", "ENG", R.string.language_name_en_nz),
    URDU("ur", "Urdu (اردو)", "اردو", R.string.language_name_ur),
    CHINESE("zh", "Chinese (中文)", "中文", R.string.language_name_zh),
    ARABIC("ar", "Arabic (العربية)", "العربية", R.string.language_name_ar),
    SPANISH("es", "Spanish (Español)", "ESP", R.string.language_name_es),
    CHINESE_TRADITIONAL("zh-TW", "Chinese Traditional (繁體中文)", "繁體", R.string.language_name_zh_tw),
    PERSIAN("fa", "Persian (فارسی)", "فارسی", R.string.language_name_fa),
    PUNJABI("pa", "Punjabi (ਪੰਜਾਬੀ)", "ਪੰਜਾਬੀ", R.string.language_name_pa),
    PUNJABI_PK("pa-Arab-PK", "Punjabi Pakistan (پنجابی)", "پنجابی", R.string.language_name_pa_pk);

    companion object {
        /** Maps a locale tag such as "zh-CN" to its language. Unknown tags fall back to English. */
        fun fromTag(appliedTag: String): AppLanguage =
            values().firstOrNull { appliedTag.equals(it.tag, ignoreCase = true) }
                ?: values().firstOrNull { appliedTag.startsWith(it.tag) }
                ?: ENGLISH
    }
}
