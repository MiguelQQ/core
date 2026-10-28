package com.maxrave.common

/**
 * Shared Discord desktop link details.
 *
 * Desktop Compose has no WebView, and this build does not ship JCEF, so Windows cannot run the
 * Android login page. Two link modes share these constants:
 * - a real user token (browser login, then paste) sent to the Discord gateway
 * - [WINDOWS_IPC_TOKEN], which is not a Discord token: on Windows it means "talk to the local
 *   Discord app" over `\\.\pipe\discord-ipc-N`
 */
object DiscordDesktop {
    const val APPLICATION_ID = "1563405206408929401"

    /** Stored in place of a user token when Rich Presence uses the local Discord app. */
    const val WINDOWS_IPC_TOKEN = "killmon-windows-ipc"

    const val LOGIN_URL = "https://discord.com/login"

    fun isWindowsIpcToken(token: String): Boolean = token == WINDOWS_IPC_TOKEN

    /**
     * Turns a pasted Discord user token into the value the gateway expects.
     *
     * The login page stores the token as a JSON string, so the usual console snippet (and a manual
     * copy) often includes the surrounding quotes. Returns null when the text cannot be a token.
     * The Windows IPC sentinel is rejected here so a paste cannot silently switch modes.
     */
    fun normalizeUserToken(raw: String): String? {
        var token = raw.trim()
        token = stripWrappingQuotes(token)
        if (token.startsWith("Bearer ", ignoreCase = true)) {
            token = stripWrappingQuotes(token.substring(7).trim())
        }
        if (token.equals("null", ignoreCase = true) || token.equals("undefined", ignoreCase = true)) {
            return null
        }
        if (isWindowsIpcToken(token)) return null
        // Real user tokens are a single long word (base64.base64.base64, or mfa.*).
        if (token.length !in USER_TOKEN_MIN..USER_TOKEN_MAX) return null
        if (token.any { it.isWhitespace() }) return null
        if (!token.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }) return null
        return token
    }

    private fun stripWrappingQuotes(value: String): String {
        var token = value.trim()
        while (token.length >= 2 &&
            ((token.first() == '"' && token.last() == '"') || (token.first() == '\'' && token.last() == '\''))
        ) {
            token = token.substring(1, token.length - 1).trim()
        }
        return token
    }

    private const val USER_TOKEN_MIN = 20
    private const val USER_TOKEN_MAX = 400
}

/**
 * Listening activity sent to the local Discord client. Image fields are https URLs when we have
 * them; the pipe client drops them and retries if that Discord build only accepts asset keys.
 */
data class DiscordIpcActivity(
    val name: String,
    val details: String?,
    val state: String?,
    val largeImage: String?,
    val smallImage: String?,
    val largeText: String?,
    val smallText: String?,
    val startMillis: Long?,
    val endMillis: Long?,
    val type: Int,
)

fun discordIpcHandshakeJson(clientId: String): String =
    """{"v":1,"client_id":${jsonQuote(clientId)}}"""

fun discordIpcSetActivityJson(
    activity: DiscordIpcActivity,
    pid: Long,
    nonce: String,
    applicationId: String,
): String {
    val body = StringBuilder()
    body.append("{\"cmd\":\"SET_ACTIVITY\",\"nonce\":")
    body.append(jsonQuote(nonce))
    body.append(",\"args\":{\"pid\":")
    body.append(pid)
    body.append(",\"activity\":")
    body.append(activityJson(activity, applicationId))
    body.append("}}")
    return body.toString()
}

private fun activityJson(
    activity: DiscordIpcActivity,
    applicationId: String,
): String {
    val body = StringBuilder()
    body.append('{')
    body.append("\"name\":")
    body.append(jsonQuote(activity.name))
    body.append(",\"type\":")
    body.append(activity.type)
    body.append(",\"application_id\":")
    body.append(jsonQuote(applicationId))
    appendStringField(body, "details", activity.details)
    appendStringField(body, "state", activity.state)
    val timestamps = timestampJson(activity.startMillis, activity.endMillis)
    if (timestamps != null) {
        body.append(",\"timestamps\":")
        body.append(timestamps)
    }
    val assets = assetsJson(activity)
    if (assets != null) {
        body.append(",\"assets\":")
        body.append(assets)
    }
    body.append('}')
    return body.toString()
}

private fun timestampJson(
    startMillis: Long?,
    endMillis: Long?,
): String? {
    if (startMillis == null && endMillis == null) return null
    val body = StringBuilder("{")
    var wrote = false
    if (startMillis != null) {
        body.append("\"start\":")
        body.append(startMillis)
        wrote = true
    }
    if (endMillis != null) {
        if (wrote) body.append(',')
        body.append("\"end\":")
        body.append(endMillis)
    }
    body.append('}')
    return body.toString()
}

private fun assetsJson(activity: DiscordIpcActivity): String? {
    if (activity.largeImage.isNullOrBlank() &&
        activity.smallImage.isNullOrBlank() &&
        activity.largeText.isNullOrBlank() &&
        activity.smallText.isNullOrBlank()
    ) {
        return null
    }
    val body = StringBuilder("{")
    var first = true
    first = appendAsset(body, "large_image", activity.largeImage, first)
    first = appendAsset(body, "large_text", activity.largeText, first)
    first = appendAsset(body, "small_image", activity.smallImage, first)
    appendAsset(body, "small_text", activity.smallText, first)
    body.append('}')
    return body.toString()
}

private fun appendAsset(
    body: StringBuilder,
    key: String,
    value: String?,
    first: Boolean,
): Boolean {
    if (value.isNullOrBlank()) return first
    if (!first) body.append(',')
    body.append('"')
    body.append(key)
    body.append("\":")
    body.append(jsonQuote(value))
    return false
}

private fun appendStringField(
    body: StringBuilder,
    key: String,
    value: String?,
) {
    if (value.isNullOrBlank()) return
    body.append(",\"")
    body.append(key)
    body.append("\":")
    body.append(jsonQuote(value))
}

internal fun jsonQuote(value: String): String = buildString {
    append('"')
    for (ch in value) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else ->
                if (ch.code < 0x20) {
                    append("\\u")
                    append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    append(ch)
                }
        }
    }
    append('"')
}
