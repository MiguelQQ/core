package com.maxrave.domain.data.model.killmon

data class KillmonUser(
    val id: String,
    val email: String,
    val displayName: String,
    val createdAt: String,
)

data class KillmonSession(
    val user: KillmonUser,
)

sealed interface KillmonAuthResult {
    data object Success : KillmonAuthResult

    data class Error(
        val message: String,
    ) : KillmonAuthResult
}
