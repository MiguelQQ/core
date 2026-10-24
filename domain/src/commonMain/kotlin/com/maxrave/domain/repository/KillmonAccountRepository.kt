package com.maxrave.domain.repository

import com.maxrave.domain.data.model.killmon.KillmonAuthResult
import com.maxrave.domain.data.model.killmon.KillmonSession
import kotlinx.coroutines.flow.StateFlow

/**
 * Optional Killmon account. The app stays usable when [session] is null; nothing asks for a
 * login at startup.
 */
interface KillmonAccountRepository {
    val session: StateFlow<KillmonSession?>

    suspend fun login(
        email: String,
        password: String,
    ): KillmonAuthResult

    suspend fun register(
        email: String,
        password: String,
        displayName: String,
    ): KillmonAuthResult

    suspend fun logout()

    suspend fun deleteAccount(password: String): KillmonAuthResult
}
