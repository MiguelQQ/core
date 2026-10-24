package com.maxrave.data.killmon

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

internal actual fun applyPlatformLanguage(code: String) {
    if (code.isBlank()) return
    val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    if (current == code) return
    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(code))
}
