package com.maxrave.domain.repository

import com.maxrave.domain.data.model.killmon.KillmonAuthResult
import com.maxrave.domain.data.model.killmon.KillmonSession
import kotlinx.coroutines.flow.StateFlow

/**
 * Killmon account. The app waits until [ready] is true before showing Home. A null [session]
 * after that means the user must sign in.
 */
interface KillmonAccountRepository {
    /** False until the stored session has been read and, if present, checked once. */
    val ready: StateFlow<Boolean>

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
