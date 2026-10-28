package com.my.kizzy.rpc

import com.maxrave.common.DiscordDesktop
import com.maxrave.common.DiscordIpcActivity
import com.maxrave.common.WindowsDiscordIpcSession

internal actual fun localDiscordIpcOrNull(token: String): LocalDiscordIpc? {
    if (!DiscordDesktop.isWindowsIpcToken(token)) return null
    if (!WindowsDiscordIpcSession.isWindows()) return null
    return JvmWindowsDiscordIpc(DiscordDesktop.APPLICATION_ID)
}

private class JvmWindowsDiscordIpc(
    applicationId: String,
) : LocalDiscordIpc {
    private val session = WindowsDiscordIpcSession(applicationId)

    override fun isActive(): Boolean = session.isActive()

    override fun close() {
        session.close()
    }

    override fun update(
        activity: DiscordIpcActivity,
        stillCurrent: () -> Boolean,
    ): Boolean = session.setActivity(activity, stillCurrent)
}
