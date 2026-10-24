package com.maxrave.data.killmon

import com.maxrave.data.io.fileDir
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual fun createKillmonTokenStore(): KillmonTokenStore = JvmKillmonTokenStore(fileDir())

/**
 * AES-GCM file next to the desktop settings. Android uses EncryptedSharedPreferences; this is the
 * desktop equivalent so the refresh token is not sitting in plaintext.
 */
private class JvmKillmonTokenStore(
    directory: String,
) : KillmonTokenStore {
    private val dir = File(directory).apply { mkdirs() }
    private val keyFile = File(dir, "killmon_session.key")
    private val dataFile = File(dir, "killmon_session.bin")

    override suspend fun load(): StoredKillmonSession? =
        withContext(Dispatchers.IO) {
            if (!dataFile.exists()) return@withContext null
            runCatching {
                val payload = dataFile.readBytes()
                if (payload.size <= GCM_IV_LENGTH) return@runCatching null
                val iv = payload.copyOfRange(0, GCM_IV_LENGTH)
                val cipherText = payload.copyOfRange(GCM_IV_LENGTH, payload.size)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
                KillmonSessionCodec.decode(cipher.doFinal(cipherText).decodeToString())
            }.getOrNull()
        }

    override suspend fun save(session: StoredKillmonSession) {
        withContext(Dispatchers.IO) {
            val iv = ByteArray(GCM_IV_LENGTH).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv))
            val encrypted = cipher.doFinal(KillmonSessionCodec.encode(session).encodeToByteArray())
            dataFile.writeBytes(iv + encrypted)
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            dataFile.delete()
        }
    }

    private fun key(): SecretKey {
        if (!keyFile.exists() || keyFile.length() != 32L) {
            keyFile.writeBytes(ByteArray(32).also { SecureRandom().nextBytes(it) })
            keyFile.setReadable(false, false)
            keyFile.setReadable(true, true)
            keyFile.setWritable(false, false)
            keyFile.setWritable(true, true)
        }
        return SecretKeySpec(keyFile.readBytes(), "AES")
    }

    private companion object {
        const val GCM_IV_LENGTH = 12
    }
}
