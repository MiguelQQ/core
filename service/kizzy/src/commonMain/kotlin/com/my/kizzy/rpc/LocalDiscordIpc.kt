package com.my.kizzy.rpc

import com.maxrave.common.DiscordIpcActivity

/**
 * Local Discord app (named pipe on Windows). Null unless the saved token is the IPC sentinel
 * and this process can open the pipe. A real user token keeps using the gateway in [KizzyRPC].
 */
internal interface LocalDiscordIpc {
    fun isActive(): Boolean

    fun close()

    fun update(
        activity: DiscordIpcActivity,
        stillCurrent: () -> Boolean = { true },
    ): Boolean
}

internal expect fun localDiscordIpcOrNull(token: String): LocalDiscordIpc?
