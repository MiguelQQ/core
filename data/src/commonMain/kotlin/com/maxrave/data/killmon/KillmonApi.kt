package com.maxrave.data.killmon

import com.maxrave.ktorext.getEngine
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal class KillmonApi {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }

    private val client =
        HttpClient(getEngine()) {
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 20_000
                socketTimeoutMillis = 20_000
            }
            install(ContentNegotiation) {
                json(json)
            }
            expectSuccess = false
        }

    suspend fun login(
        email: String,
        password: String,
    ): ApiOutcome<AuthPayload> = postAuth("auth/login", LoginBody(email, password))

    suspend fun register(
        email: String,
        password: String,
        displayName: String,
    ): ApiOutcome<AuthPayload> = postAuth("auth/register", RegisterBody(email, password, displayName))

    suspend fun refresh(refreshToken: String): ApiOutcome<AuthPayload> =
        postAuth("auth/refresh", RefreshBody(refreshToken))

    suspend fun logout(refreshToken: String): ApiOutcome<Unit> {
        val response =
            client.post("$BASE/auth/logout") {
                contentType(ContentType.Application.Json)
                setBody(RefreshBody(refreshToken))
            }
        return if (response.status == HttpStatusCode.NoContent || response.status == HttpStatusCode.OK) {
            ApiOutcome.Success(Unit)
        } else {
            response.failure()
        }
    }

    suspend fun me(accessToken: String): ApiOutcome<UserPayload> {
        val response =
            client.get("$BASE/me") {
                header("Authorization", "Bearer $accessToken")
            }
        if (response.status != HttpStatusCode.OK) return response.failure()
        return try {
            ApiOutcome.Success(response.body<UserBody>().toPayload())
        } catch (_: Exception) {
            response.failure()
        }
    }

    suspend fun deleteMe(
        accessToken: String,
        password: String,
    ): ApiOutcome<Unit> {
        val response =
            client.delete("$BASE/me") {
                header("Authorization", "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody(DeleteBody(password))
            }
        return if (response.status == HttpStatusCode.NoContent || response.status == HttpStatusCode.OK) {
            ApiOutcome.Success(Unit)
        } else {
            response.failure()
        }
    }

    suspend fun getPreferences(accessToken: String): ApiOutcome<PrefsPayload> {
        val response =
            client.get("$BASE/me/preferences") {
                header("Authorization", "Bearer $accessToken")
            }
        if (response.status != HttpStatusCode.OK) return response.failure()
        return parsePrefs(response.bodyAsText())
    }

    suspend fun putPreferences(
        accessToken: String,
        preferences: JsonObject,
        updatedAt: String?,
    ): ApiOutcome<PrefsPayload> {
        val response =
            client.put("$BASE/me/preferences") {
                header("Authorization", "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody(PutPrefsBody(preferences, updatedAt))
            }
        val text = runCatching { response.bodyAsText() }.getOrDefault("")
        return when (response.status) {
            HttpStatusCode.OK -> parsePrefs(text)
            HttpStatusCode.Conflict -> {
                val parsed = parsePrefs(text)
                val body = (parsed as? ApiOutcome.Success)?.value
                val failure = response.failure(text)
                ApiOutcome.Failure(
                    status = HttpStatusCode.Conflict.value,
                    error = failure.error ?: "conflict",
                    message = failure.message,
                    preferences = body?.preferences,
                    updatedAt = body?.updatedAt,
                )
            }
            else -> response.failure(text)
        }
    }

    private suspend inline fun <reified B> postAuth(
        path: String,
        body: B,
    ): ApiOutcome<AuthPayload> {
        val response =
            client.post("$BASE/$path") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        if (response.status != HttpStatusCode.OK && response.status != HttpStatusCode.Created) {
            return response.failure()
        }
        return try {
            ApiOutcome.Success(response.body<AuthBody>().toPayload())
        } catch (_: Exception) {
            response.failure()
        }
    }

    suspend fun presence(
        accessToken: String,
        body: ConnectPresenceBody,
    ): ApiOutcome<ConnectWire> = authedJson("PUT", "me/devices/presence", accessToken, body)

    suspend fun transfer(
        accessToken: String,
        body: ConnectTransferBody,
    ): ApiOutcome<ConnectWire> = authedJson("POST", "me/devices/transfer", accessToken, body)

    suspend fun control(
        accessToken: String,
        body: ConnectControlBody,
    ): ApiOutcome<ConnectWire> = authedJson("POST", "me/devices/control", accessToken, body)

    suspend fun bye(
        accessToken: String,
        body: ConnectByeBody,
    ): ApiOutcome<ConnectWire> = authedJson("POST", "me/devices/bye", accessToken, body)

    private suspend inline fun <reified B> authedJson(
        method: String,
        path: String,
        accessToken: String,
        body: B,
    ): ApiOutcome<ConnectWire> {
        val response =
            when (method) {
                "PUT" ->
                    client.put("$BASE/$path") {
                        header("Authorization", "Bearer $accessToken")
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                else ->
                    client.post("$BASE/$path") {
                        header("Authorization", "Bearer $accessToken")
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
            }
        if (response.status != HttpStatusCode.OK) return response.failure()
        return try {
            ApiOutcome.Success(response.body())
        } catch (_: Exception) {
            response.failure()
        }
    }

    private fun parsePrefs(text: String): ApiOutcome<PrefsPayload> {
        val obj = runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull()
            ?: return ApiOutcome.Failure(0, null, text.ifBlank { null }, null, null)
        val prefs =
            (obj["preferences"] as? JsonObject)
                ?: JsonObject(emptyMap())
        return ApiOutcome.Success(PrefsPayload(preferences = prefs, updatedAt = obj.text("updated_at")))
    }

    private suspend fun HttpResponse.failure(cachedText: String? = null): ApiOutcome.Failure {
        val text = cachedText ?: runCatching { bodyAsText() }.getOrNull().orEmpty()
        val obj = runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull()
        return ApiOutcome.Failure(
            status = status.value,
            error = obj?.text("error"),
            message = obj?.text("message"),
            preferences = obj?.get("preferences") as? JsonObject,
            updatedAt = obj?.text("updated_at"),
        )
    }

    private fun JsonObject.text(key: String): String? {
        val element = this[key] as? JsonPrimitive ?: return null
        return element.contentOrNull
    }

    private companion object {
        const val BASE = "https://killmon.seriepokemon.online/api"
    }
}

internal sealed interface ApiOutcome<out T> {
    data class Success<T>(
        val value: T,
    ) : ApiOutcome<T>

    data class Failure(
        val status: Int,
        val error: String?,
        val message: String?,
        val preferences: JsonObject?,
        val updatedAt: String?,
    ) : ApiOutcome<Nothing>
}

internal data class AuthPayload(
    val user: UserPayload,
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
)

internal data class UserPayload(
    val id: String,
    val email: String,
    val displayName: String,
    val createdAt: String,
)

internal data class PrefsPayload(
    val preferences: JsonObject,
    val updatedAt: String?,
)

@Serializable
private data class LoginBody(
    val email: String,
    val password: String,
)

@Serializable
private data class RegisterBody(
    val email: String,
    val password: String,
    @SerialName("display_name")
    val displayName: String,
)

@Serializable
private data class RefreshBody(
    @SerialName("refresh_token")
    val refreshToken: String,
)

@Serializable
private data class DeleteBody(
    val password: String,
)

@Serializable
private data class PutPrefsBody(
    val preferences: JsonObject,
    @SerialName("updated_at")
    val updatedAt: String? = null,
)

@Serializable
private data class AuthBody(
    val user: UserBody,
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String,
    @SerialName("expires_in")
    val expiresIn: Long = 900,
) {
    fun toPayload() =
        AuthPayload(
            user = user.toPayload(),
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresIn = expiresIn,
        )
}

@Serializable
private data class UserBody(
    @Serializable(with = FlexibleStringSerializer::class)
    val id: String,
    val email: String,
    @SerialName("display_name")
    val displayName: String,
    @SerialName("created_at")
    val createdAt: String = "",
) {
    fun toPayload() =
        UserPayload(
            id = id,
            email = email,
            displayName = displayName,
            createdAt = createdAt,
        )
}

@Serializable
internal data class ConnectPresenceBody(
    @SerialName("device_id")
    val deviceId: String,
    val platform: String,
    @SerialName("display_name")
    val displayName: String,
    val playback: ConnectPlaybackBody? = null,
    val takeover: Boolean = false,
    @SerialName("ack_transfer")
    val ackTransfer: Long? = null,
    @SerialName("ack_control")
    val ackControl: Long? = null,
)

@Serializable
internal data class ConnectTransferBody(
    @SerialName("device_id")
    val deviceId: String,
    @SerialName("target_device_id")
    val targetDeviceId: String,
    val playback: ConnectPlaybackBody? = null,
)

@Serializable
internal data class ConnectControlBody(
    @SerialName("device_id")
    val deviceId: String,
    val action: String,
    @SerialName("position_ms")
    val positionMs: Long? = null,
)

@Serializable
internal data class ConnectByeBody(
    @SerialName("device_id")
    val deviceId: String,
)

@Serializable
internal data class ConnectPlaybackBody(
    val queue: List<ConnectTrackBody>,
    val index: Int,
    @SerialName("position_ms")
    val positionMs: Long,
    @SerialName("is_playing")
    val isPlaying: Boolean,
)

@Serializable
internal data class ConnectTrackBody(
    @SerialName("video_id")
    val videoId: String,
    val title: String = "",
    val artist: String = "",
    val thumbnail: String = "",
    @SerialName("duration_ms")
    val durationMs: Long = 0,
)

@Serializable
internal data class ConnectWire(
    val devices: List<ConnectDeviceWire> = emptyList(),
    val session: ConnectSessionWire? = null,
    val command: ConnectCommandWire? = null,
)

@Serializable
internal data class ConnectDeviceWire(
    @SerialName("device_id")
    val deviceId: String = "",
    val platform: String = "",
    @SerialName("display_name")
    val displayName: String = "",
    @SerialName("is_active")
    val isActive: Boolean = false,
    @SerialName("is_self")
    val isSelf: Boolean = false,
)

@Serializable
internal data class ConnectSessionWire(
    @SerialName("active_device_id")
    val activeDeviceId: String = "",
    val revision: Long = 0,
    @SerialName("is_playing")
    val isPlaying: Boolean = false,
    @SerialName("position_ms")
    val positionMs: Long = 0,
    val index: Int = 0,
    val queue: List<ConnectTrackBody> = emptyList(),
)

@Serializable
internal data class ConnectCommandWire(
    val type: String = "",
    val revision: Long = 0,
    val action: String? = null,
    @SerialName("position_ms")
    val positionMs: Long? = null,
    val index: Int? = null,
)

private object FlexibleStringSerializer : KSerializer<String> {
    override val descriptor = PrimitiveSerialDescriptor("FlexibleString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString()
        val element = jsonDecoder.decodeJsonElement()
        return (element as? JsonPrimitive)?.content ?: element.toString()
    }

    override fun serialize(
        encoder: Encoder,
        value: String,
    ) {
        encoder.encodeString(value)
    }
}
