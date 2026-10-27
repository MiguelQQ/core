package com.maxrave.data.killmon

import com.maxrave.common.SELECTED_LANGUAGE
import com.maxrave.domain.data.model.killmon.KillmonAuthResult
import com.maxrave.domain.data.model.killmon.KillmonSession
import com.maxrave.domain.data.model.killmon.KillmonUser
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.manager.DataStoreManager.Values.THEME_MODE_DARK
import com.maxrave.domain.manager.DataStoreManager.Values.THEME_MODE_LIGHT
import com.maxrave.domain.manager.DataStoreManager.Values.THEME_MODE_SYSTEM
import com.maxrave.domain.manager.DataStoreManager.Values.TRUE
import com.maxrave.domain.repository.KillmonAccountRepository
import com.maxrave.logger.Logger
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@OptIn(ExperimentalTime::class)
internal class KillmonAccountRepositoryImpl(
    private val dataStore: DataStoreManager,
    private val tokenStore: KillmonTokenStore,
    private val api: KillmonApi,
) : KillmonAccountRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refreshMutex = Mutex()
    private val prefsMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private val _ready = MutableStateFlow(false)
    override val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _session = MutableStateFlow<KillmonSession?>(null)
    override val session: StateFlow<KillmonSession?> = _session.asStateFlow()

    private var started = false
    private var acceptLocalEdits = false
    private var baseline: LocalPrefs? = null

    fun start() {
        if (started) return
        started = true
        scope.launch {
            try {
                publish(tokenStore.load())
                if (_session.value != null) {
                    prefsMutex.withLock {
                        try {
                            syncLocked(justRegistered = false)
                        } catch (e: Exception) {
                            Logger.e(TAG, "No se pudo comprobar la sesión", e)
                        }
                    }
                } else {
                    baseline = readLocal()
                    acceptLocalEdits = true
                }
            } catch (e: Exception) {
                Logger.e(TAG, "No se pudo leer la sesión", e)
                _session.value = null
            } finally {
                _ready.value = true
            }
            merge(
                dataStore.language,
                dataStore.location,
                dataStore.themeMode,
                dataStore.themeColorSource,
                dataStore.customThemeColor,
                dataStore.explicitContentEnabled,
                dataStore.quality,
                dataStore.downloadQuality,
                dataStore.nowPlayingStyle,
                dataStore.lyricsStyle,
                dataStore.enableLiquidGlass,
                dataStore.preferredAudioLanguage,
                dataStore.youtubeSubtitleLanguage,
            ).debounce(800).collect {
                prefsMutex.withLock {
                    if (!acceptLocalEdits) return@withLock
                    val current = readLocal()
                    if (current == baseline) return@withLock
                    baseline = current
                    dataStore.putString(KEY_LOCAL_EPOCH, Clock.System.now().toEpochMilliseconds().toString())
                    if (_session.value != null) {
                        try {
                            pushLocked()
                        } catch (e: Exception) {
                            Logger.e(TAG, "No se pudieron subir las preferencias", e)
                        }
                    }
                }
            }
        }
    }

    override suspend fun jamAccessToken(): String? {
        var accepted: String? = null
        authed { token ->
            when (val check = api.me(token)) {
                is ApiOutcome.Success -> { accepted = token; ApiOutcome.Success(Unit) }
                is ApiOutcome.Failure -> check
            }
        }
        return accepted
    }

    override suspend fun login(
        email: String,
        password: String,
    ): KillmonAuthResult = authenticate(justRegistered = false) { api.login(email.trim(), password) }

    override suspend fun register(
        email: String,
        password: String,
        displayName: String,
    ): KillmonAuthResult =
        authenticate(justRegistered = true) {
            api.register(email.trim(), password, displayName.trim())
        }

    override suspend fun logout() {
        val refresh = tokenStore.load()?.refreshToken
        if (!refresh.isNullOrBlank()) {
            runCatching { api.logout(refresh) }
        }
        prefsMutex.withLock {
            clearLocal()
            baseline = readLocal()
        }
    }

    override suspend fun deleteAccount(password: String): KillmonAuthResult =
        try {
            when (val result = authed { api.deleteMe(it, password) }) {
                is Authed.Ok -> {
                    prefsMutex.withLock { clearLocal() }
                    KillmonAuthResult.Success
                }
                is Authed.SignedOut -> KillmonAuthResult.Error(SESSION_EXPIRED)
                is Authed.Err -> KillmonAuthResult.Error(killmonErrorMessage(result.failure))
            }
        } catch (e: Exception) {
            Logger.e(TAG, "No se pudo eliminar la cuenta", e)
            KillmonAuthResult.Error("No se pudo conectar con Killmon. Revisa tu conexión.")
        }

    private suspend fun authenticate(
        justRegistered: Boolean,
        call: suspend () -> ApiOutcome<AuthPayload>,
    ): KillmonAuthResult =
        try {
            when (val result = call()) {
                is ApiOutcome.Failure -> KillmonAuthResult.Error(killmonErrorMessage(result))
                is ApiOutcome.Success -> {
                    prefsMutex.withLock {
                        saveAuth(result.value)
                        syncLocked(justRegistered)
                    }
                    KillmonAuthResult.Success
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "No se pudo contactar con Killmon", e)
            KillmonAuthResult.Error("No se pudo conectar con Killmon. Revisa tu conexión.")
        }

    private suspend fun syncLocked(justRegistered: Boolean) {
        acceptLocalEdits = false
        try {
            when (val me = authed { api.me(it) }) {
                is Authed.Ok -> updateUser(me.value)
                is Authed.SignedOut -> return
                is Authed.Err -> Unit
            }
            when (val remote = authed { api.getPreferences(it) }) {
                is Authed.SignedOut -> return
                is Authed.Err -> Logger.w(TAG, "No se pudieron leer las preferencias (${remote.failure.status})")
                is Authed.Ok -> {
                    val serverAt = remote.value.updatedAt
                    val serverEpoch = parseUpdatedAt(serverAt)
                    val localEpoch = dataStore.getString(KEY_LOCAL_EPOCH).first()?.toLongOrNull() ?: 0L
                    val pushLocal =
                        justRegistered || (localEpoch > 0L && (serverEpoch == null || localEpoch > serverEpoch))
                    if (pushLocal) {
                        pushLocked(serverAt)
                    } else {
                        applyRemote(remote.value.preferences, serverAt)
                    }
                }
            }
        } finally {
            baseline = readLocal()
            acceptLocalEdits = true
        }
    }

    private suspend fun pushLocked(precondition: String? = null) {
        if (_session.value == null) return
        val serverAt =
            precondition ?: dataStore.getString(KEY_SERVER_UPDATED_AT).first()?.takeIf { it.isNotBlank() }
        val doc = overlay(loadStoredDoc(), readLocal())
        when (val result = authed { api.putPreferences(it, doc, serverAt) }) {
            is Authed.Ok -> {
                val returned = result.value
                val saved = if (returned.preferences.isEmpty()) doc else returned.preferences
                dataStore.putString(KEY_PREFS_DOC, saved.toString())
                dataStore.putString(KEY_SERVER_UPDATED_AT, returned.updatedAt.orEmpty())
                val epoch = parseUpdatedAt(returned.updatedAt) ?: Clock.System.now().toEpochMilliseconds()
                dataStore.putString(KEY_LOCAL_EPOCH, epoch.toString())
            }
            is Authed.Err -> {
                val prefs = result.failure.preferences
                if (result.failure.status == 409 && prefs != null) {
                    Logger.w(TAG, "Preferencias en conflicto; se adopta la copia del servidor")
                    applyRemote(prefs, result.failure.updatedAt)
                } else {
                    Logger.w(TAG, "No se pudieron guardar las preferencias (${result.failure.status})")
                }
            }
            is Authed.SignedOut -> Unit
        }
    }

    private suspend fun applyRemote(
        prefs: JsonObject,
        updatedAt: String?,
    ) {
        dataStore.putString(KEY_PREFS_DOC, prefs.toString())
        dataStore.putString(KEY_SERVER_UPDATED_AT, updatedAt.orEmpty())
        val epoch = parseUpdatedAt(updatedAt) ?: Clock.System.now().toEpochMilliseconds()
        dataStore.putString(KEY_LOCAL_EPOCH, epoch.toString())

        prefs.string("language")?.takeIf { it.isNotBlank() }?.let { code ->
            dataStore.putString(SELECTED_LANGUAGE, code)
            applyPlatformLanguage(code)
        }
        prefs.string("region")?.takeIf { it.isNotBlank() }?.let { dataStore.setLocation(it) }
        prefs.string("theme")?.trim()?.uppercase()?.let { theme ->
            if (theme == THEME_MODE_SYSTEM || theme == THEME_MODE_DARK || theme == THEME_MODE_LIGHT) {
                dataStore.setThemeMode(theme)
            }
        }
        prefs.bool("explicit_content")?.let { dataStore.setExplicitContentEnabled(it) }
        prefs.string("quality")?.takeIf { it.isNotBlank() }?.let { dataStore.setQuality(it) }
        prefs.string("download_quality")?.takeIf { it.isNotBlank() }?.let { dataStore.setDownloadQuality(it) }
        prefs.string("now_playing_style")?.takeIf { it.isNotBlank() }?.let { dataStore.setNowPlayingStyle(it) }
        prefs.string("lyrics_style")?.takeIf { it.isNotBlank() }?.let { dataStore.setLyricsStyle(it) }
        prefs.string("theme_color_source")?.takeIf { it.isNotBlank() }?.let { dataStore.setThemeColorSource(it) }
        prefs.string("custom_theme_color")?.takeIf { it.isNotBlank() }?.let { dataStore.setCustomThemeColor(it) }
        prefs.bool("liquid_glass")?.let { dataStore.setEnableLiquidGlass(it) }
        prefs.string("preferred_audio_language")?.let { dataStore.setPreferredAudioLanguage(it) }
        prefs.string("youtube_subtitle_language")?.takeIf { it.isNotBlank() }?.let {
            dataStore.setYoutubeSubtitleLanguage(it)
        }
    }

    private suspend fun <T> authed(call: suspend (String) -> ApiOutcome<T>): Authed<T> {
        val tokens = tokenStore.load() ?: return Authed.SignedOut.also { _session.value = null }
        val first = call(tokens.accessToken)
        if (first is ApiOutcome.Success) return Authed.Ok(first.value)
        if (first is ApiOutcome.Failure && first.status != 401) return Authed.Err(first)

        val refreshed =
            refreshMutex.withLock {
                val latest = tokenStore.load() ?: return@withLock null
                if (latest.accessToken != tokens.accessToken) {
                    latest.accessToken
                } else {
                    when (val refreshed = api.refresh(latest.refreshToken)) {
                        is ApiOutcome.Success -> {
                            val payload = refreshed.value
                            tokenStore.save(
                                latest.copy(
                                    accessToken = payload.accessToken,
                                    refreshToken = payload.refreshToken,
                                    expiresAtEpochMs =
                                        Clock.System.now().toEpochMilliseconds() +
                                            payload.expiresIn.coerceAtLeast(1) * 1000,
                                    userId = payload.user.id.ifBlank { latest.userId },
                                    email = payload.user.email.ifBlank { latest.email },
                                    displayName = payload.user.displayName.ifBlank { latest.displayName },
                                    createdAt = payload.user.createdAt.ifBlank { latest.createdAt },
                                ),
                            )
                            publish(tokenStore.load())
                            payload.accessToken
                        }
                        else -> {
                            clearLocal()
                            null
                        }
                    }
                }
            } ?: return Authed.SignedOut

        return when (val second = call(refreshed)) {
            is ApiOutcome.Success -> Authed.Ok(second.value)
            is ApiOutcome.Failure -> {
                if (second.status == 401) {
                    clearLocal()
                    Authed.SignedOut
                } else {
                    Authed.Err(second)
                }
            }
        }
    }

    private suspend fun saveAuth(payload: AuthPayload) {
        val stored =
            StoredKillmonSession(
                accessToken = payload.accessToken,
                refreshToken = payload.refreshToken,
                expiresAtEpochMs =
                    Clock.System.now().toEpochMilliseconds() + payload.expiresIn.coerceAtLeast(1) * 1000,
                userId = payload.user.id,
                email = payload.user.email,
                displayName = payload.user.displayName,
                createdAt = payload.user.createdAt,
            )
        tokenStore.save(stored)
        publish(stored)
    }

    private suspend fun updateUser(user: UserPayload) {
        val latest = tokenStore.load() ?: return
        val stored =
            latest.copy(
                userId = user.id.ifBlank { latest.userId },
                email = user.email.ifBlank { latest.email },
                displayName = user.displayName.ifBlank { latest.displayName },
                createdAt = user.createdAt.ifBlank { latest.createdAt },
            )
        tokenStore.save(stored)
        publish(stored)
    }

    private suspend fun clearLocal() {
        tokenStore.clear()
        _session.value = null
        dataStore.putString(KEY_SERVER_UPDATED_AT, "")
        dataStore.putString(KEY_PREFS_DOC, "")
    }

    private fun publish(stored: StoredKillmonSession?) {
        _session.value =
            stored?.let {
                KillmonSession(
                    user =
                        KillmonUser(
                            id = it.userId,
                            email = it.email,
                            displayName = it.displayName,
                            createdAt = it.createdAt,
                        ),
                )
            }
    }

    private suspend fun readLocal(): LocalPrefs =
        LocalPrefs(
            language = dataStore.language.first(),
            region = dataStore.location.first(),
            theme = dataStore.themeMode.first(),
            themeColorSource = dataStore.themeColorSource.first(),
            customThemeColor = dataStore.customThemeColor.first(),
            explicitContent = dataStore.explicitContentEnabled.first(),
            quality = dataStore.quality.first(),
            downloadQuality = dataStore.downloadQuality.first(),
            nowPlayingStyle = dataStore.nowPlayingStyle.first(),
            lyricsStyle = dataStore.lyricsStyle.first(),
            liquidGlass = dataStore.enableLiquidGlass.first(),
            preferredAudioLanguage = dataStore.preferredAudioLanguage.first(),
            youtubeSubtitleLanguage = dataStore.youtubeSubtitleLanguage.first(),
        )

    private suspend fun loadStoredDoc(): JsonObject {
        val raw = dataStore.getString(KEY_PREFS_DOC).first()
        if (raw.isNullOrBlank()) return JsonObject(emptyMap())
        return runCatching { json.parseToJsonElement(raw) as JsonObject }.getOrDefault(JsonObject(emptyMap()))
    }

    private fun overlay(
        base: JsonObject,
        local: LocalPrefs,
    ): JsonObject {
        val map = base.toMutableMap()
        map["language"] = JsonPrimitive(local.language)
        map["region"] = JsonPrimitive(local.region)
        map["theme"] = JsonPrimitive(local.theme)
        if (map["favorites"] !is JsonArray) map["favorites"] = JsonArray(emptyList())
        if (map["liked_song_ids"] !is JsonArray) map["liked_song_ids"] = JsonArray(emptyList())
        if (map["playlists"] !is JsonArray) map["playlists"] = JsonArray(emptyList())
        map["explicit_content"] = JsonPrimitive(local.explicitContent == TRUE)
        map["quality"] = JsonPrimitive(local.quality)
        map["download_quality"] = JsonPrimitive(local.downloadQuality)
        map["now_playing_style"] = JsonPrimitive(local.nowPlayingStyle)
        map["lyrics_style"] = JsonPrimitive(local.lyricsStyle)
        map["theme_color_source"] = JsonPrimitive(local.themeColorSource)
        map["custom_theme_color"] = JsonPrimitive(local.customThemeColor)
        map["liquid_glass"] = JsonPrimitive(local.liquidGlass == TRUE)
        map["preferred_audio_language"] = JsonPrimitive(local.preferredAudioLanguage)
        map["youtube_subtitle_language"] = JsonPrimitive(local.youtubeSubtitleLanguage)
        return JsonObject(map)
    }

    private companion object {
        const val TAG = "KillmonAccount"
        const val KEY_LOCAL_EPOCH = "killmon_prefs_local_epoch"
        const val KEY_SERVER_UPDATED_AT = "killmon_prefs_server_updated_at"
        const val KEY_PREFS_DOC = "killmon_prefs_document"
        const val SESSION_EXPIRED = "La sesión caducó. Vuelve a iniciar sesión."
    }
}

private data class LocalPrefs(
    val language: String,
    val region: String,
    val theme: String,
    val themeColorSource: String,
    val customThemeColor: String,
    val explicitContent: String,
    val quality: String,
    val downloadQuality: String,
    val nowPlayingStyle: String,
    val lyricsStyle: String,
    val liquidGlass: String,
    val preferredAudioLanguage: String,
    val youtubeSubtitleLanguage: String,
)

private sealed interface Authed<out T> {
    data class Ok<T>(
        val value: T,
    ) : Authed<T>

    data class Err(
        val failure: ApiOutcome.Failure,
    ) : Authed<Nothing>

    data object SignedOut : Authed<Nothing>
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.bool(key: String): Boolean? {
    val raw = (this[key] as? JsonPrimitive)?.contentOrNull?.lowercase() ?: return null
    return when (raw) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

@OptIn(ExperimentalTime::class)
private fun parseUpdatedAt(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    value.toLongOrNull()?.let { raw ->
        return if (raw < 10_000_000_000L) raw * 1000 else raw
    }
    return runCatching { Instant.parse(value).toEpochMilliseconds() }.getOrNull()
}

internal fun killmonErrorMessage(failure: ApiOutcome.Failure): String =
    when (failure.error) {
        "email_taken" -> "Ese correo ya está registrado."
        "invalid_credentials" -> "Correo o contraseña incorrectos."
        "invalid_refresh_token" -> "La sesión caducó. Vuelve a iniciar sesión."
        "invalid_password" -> "La contraseña no es correcta."
        "conflict" -> "Las preferencias del servidor son más recientes."
        else ->
            when (failure.status) {
                400 ->
                    failure.message?.takeIf { it.isNotBlank() }
                        ?: "Revisa los datos. La contraseña debe tener entre 8 y 128 caracteres."
                401 -> "Correo o contraseña incorrectos."
                403 -> failure.message?.takeIf { it.isNotBlank() } ?: "No se pudo completar la acción."
                429 -> "Demasiados intentos. Espera un momento e inténtalo de nuevo."
                else ->
                    failure.message?.takeIf { it.isNotBlank() }
                        ?: "No se pudo conectar con Killmon. Revisa tu conexión."
            }
    }
