package de.lwp2070809.speculonic.data.prefs

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import de.lwp2070809.speculonic.data.dataStore
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class ServerIdentityTest {
    private val context = RuntimeEnvironment.getApplication()
    @get:Rule val directory = TemporaryFolder()
    private val secureStore by lazy {
        PreferenceDataStoreFactory.create { File(directory.root, "secure.preferences_pb") }
    }

    @Test
    fun unreadableSecureStoreFailsTheIdentityReadAndCanBeRetried() = runBlocking {
        val store = context.dataStore
        val oldUrl = "https://old.example"
        store.edit {
            it[stringPreferencesKey("server_url")] = oldUrl
            it[stringPreferencesKey("username")] = "alice"
        }
        val readable = AtomicBoolean(false)
        val unavailable = object : DataStore<Preferences> by secureStore {
            override val data: Flow<Preferences> = flow {
                if (!readable.get()) throw IOException("Storage unavailable")
                secureStore.data.collect { emit(it) }
            }
        }
        val prefs = ConnectionPrefsImpl(context, settingsStore = store, secureDataStore = unavailable)
        assertThrows(IOException::class.java) { runBlocking { prefs.readServerIdentity() } }
        readable.set(true)
        assertEquals(oldUrl to "alice", prefs.readServerIdentity())
    }

    @Test
    fun logoutSurvivesFailureBetweenSecureWriteAndLegacyCleanup() = runBlocking {
        val store = context.dataStore
        val urlKey = stringPreferencesKey("server_url")
        store.edit { it[urlKey] = "https://old.example" }
        val blockedCleanup = object : DataStore<Preferences> by store {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                throw IOException("Interrupted before legacy cleanup")
        }
        val prefs = ConnectionPrefsImpl(context, settingsStore = blockedCleanup, secureDataStore = secureStore)
        assertThrows(IOException::class.java) { runBlocking { prefs.saveServerSettings("", "", "") } }
        assertEquals("https://old.example", store.data.first()[urlKey])
        val restarted = ConnectionPrefsImpl(context, settingsStore = store, secureDataStore = secureStore)
        assertEquals("" to "", restarted.readServerIdentity())
    }
}
