package com.maxrave.data.killmon

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatform.getKoin

internal actual fun createKillmonTokenStore(): KillmonTokenStore = AndroidKillmonTokenStore(getKoin().get())

private class AndroidKillmonTokenStore(
    context: Context,
) : KillmonTokenStore {
    private val prefs: SharedPreferences =
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    override suspend fun load(): StoredKillmonSession? =
        withContext(Dispatchers.IO) {
            prefs.getString(KEY_SESSION, null)?.let(KillmonSessionCodec::decode)
        }

    override suspend fun save(session: StoredKillmonSession) {
        withContext(Dispatchers.IO) {
            prefs.edit().putString(KEY_SESSION, KillmonSessionCodec.encode(session)).commit()
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            prefs.edit().remove(KEY_SESSION).commit()
        }
    }

    private companion object {
        const val FILE_NAME = "killmon_account"
        const val KEY_SESSION = "session"
    }
}
