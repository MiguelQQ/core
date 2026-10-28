import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.maxrave.data.dataStore.JamServerPreferencesMigration
import com.maxrave.data.dataStore.RegionalPreferencesMigration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import okio.Path.Companion.toPath

fun createDataStore(
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    producePath: () -> String,
): DataStore<Preferences> {
    return PreferenceDataStoreFactory.createWithPath(
        migrations = listOf(RegionalPreferencesMigration(), JamServerPreferencesMigration()),
        scope = scope,
        produceFile = { producePath().toPath() },
    )
}
