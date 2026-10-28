package com.maxrave.data.killmon

import java.util.Locale

internal actual fun killmonDeviceLabel(): KillmonDeviceLabel {
    val os = System.getProperty("os.name").orEmpty()
    val host =
        runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()
            ?.substringBefore('.')
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("localhost", true) }
    val (platform, title) =
        when {
            os.contains("win", ignoreCase = true) -> "windows" to "Windows"
            os.contains("mac", ignoreCase = true) -> "macos" to "Mac"
            else -> "linux" to "Linux"
        }
    val display =
        when {
            host != null -> "$title · $host"
            platform == "windows" -> "Killmon Desktop"
            else -> title
        }
    return KillmonDeviceLabel(platform, display.take(80))
}

internal actual fun applyPlatformLanguage(code: String) {
    if (code.isBlank()) return
    Locale.setDefault(Locale.forLanguageTag(if (code == "id-ID") "in-ID" else code))
}
