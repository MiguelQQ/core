package com.maxrave.data.killmon

/** Applies the app language the way the platform already does from Ajustes. No-op when it already matches. */
internal expect fun applyPlatformLanguage(code: String)
