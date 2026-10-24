package com.maxrave.data.killmon

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class StoredKillmonSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMs: Long,
    val userId: String,
    val email: String,
    val displayName: String,
    val createdAt: String,
)

internal interface KillmonTokenStore {
    suspend fun load(): StoredKillmonSession?

    suspend fun save(session: StoredKillmonSession)

    suspend fun clear()
}

internal object KillmonSessionCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(session: StoredKillmonSession): String = json.encodeToString(session)

    fun decode(raw: String): StoredKillmonSession? =
        runCatching { json.decodeFromString<StoredKillmonSession>(raw) }.getOrNull()
}

internal expect fun createKillmonTokenStore(): KillmonTokenStore
