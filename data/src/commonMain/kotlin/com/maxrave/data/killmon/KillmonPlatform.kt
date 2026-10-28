package com.maxrave.data.killmon

/** Applies the app language the way the platform already does from Ajustes. No-op when it already matches. */
internal expect fun applyPlatformLanguage(code: String)

/** Nombre que ven los otros aparatos de la misma cuenta. [platform] es android, windows, macos, linux o ios. */
internal data class KillmonDeviceLabel(
    val platform: String,
    val displayName: String,
)

internal expect fun killmonDeviceLabel(): KillmonDeviceLabel
