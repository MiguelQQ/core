package com.maxrave.data.killmon

import java.util.Locale

internal actual fun applyPlatformLanguage(code: String) {
    if (code.isBlank()) return
    Locale.setDefault(Locale.forLanguageTag(if (code == "id-ID") "in-ID" else code))
}
