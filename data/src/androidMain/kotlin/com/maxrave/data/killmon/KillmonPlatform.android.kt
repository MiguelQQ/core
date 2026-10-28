package com.maxrave.data.killmon

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

internal actual fun killmonDeviceLabel(): KillmonDeviceLabel {
    val model = android.os.Build.MODEL?.trim().orEmpty()
    val maker = android.os.Build.MANUFACTURER?.trim().orEmpty()
    val name =
        when {
            model.isBlank() -> "Android"
            maker.isBlank() || model.startsWith(maker, ignoreCase = true) -> model
            else -> "$maker $model"
        }
    return KillmonDeviceLabel("android", name.take(80))
}

internal actual fun applyPlatformLanguage(code: String) {
    if (code.isBlank()) return
    val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    if (current == code) return
    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(code))
}
