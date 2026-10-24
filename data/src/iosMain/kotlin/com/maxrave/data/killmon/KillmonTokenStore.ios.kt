package com.maxrave.data.killmon

import com.maxrave.data.io.fileDir
import com.maxrave.data.io.fileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath

/**
 * The iOS sandbox keeps the file private to the app. Android uses EncryptedSharedPreferences.
 */
internal actual fun createKillmonTokenStore(): KillmonTokenStore = IosKillmonTokenStore(fileDir())

private class IosKillmonTokenStore(
    directory: String,
) : KillmonTokenStore {
    private val path = "$directory/killmon_session.json".toPath()

    override suspend fun load(): StoredKillmonSession? =
        withContext(Dispatchers.IO) {
            val fs = fileSystem()
            if (!fs.exists(path)) return@withContext null
            runCatching { KillmonSessionCodec.decode(fs.read(path) { readUtf8() }) }.getOrNull()
        }

    override suspend fun save(session: StoredKillmonSession) {
        withContext(Dispatchers.IO) {
            val fs = fileSystem()
            fs.createDirectories(path.parent!!)
            fs.write(path) { writeUtf8(KillmonSessionCodec.encode(session)) }
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            fileSystem().delete(path, mustExist = false)
        }
    }
}
