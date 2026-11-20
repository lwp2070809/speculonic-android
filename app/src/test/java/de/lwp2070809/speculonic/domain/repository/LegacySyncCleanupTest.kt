package de.lwp2070809.speculonic.domain.repository

import android.app.Application
import androidx.room.Room
import de.lwp2070809.speculonic.data.PreferencesManager
import de.lwp2070809.speculonic.data.db.AppDatabase
import de.lwp2070809.speculonic.data.prefs.BluetoothCarPrefsImpl
import de.lwp2070809.speculonic.data.prefs.CacheSyncPrefsImpl
import de.lwp2070809.speculonic.data.prefs.ConnectionPrefsImpl
import de.lwp2070809.speculonic.data.prefs.GeneralSystemPrefsImpl
import de.lwp2070809.speculonic.data.prefs.PlaybackPrefsImpl
import de.lwp2070809.speculonic.network.api.SubsonicService
import de.lwp2070809.speculonic.network.model.Song
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class LegacySyncCleanupTest {
    @Test
    fun conflictingAlbumListProtectsSmallLibraryAndConfirmedEmptyLibraryCanBeCleared() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val prefs = PreferencesManager(
            ConnectionPrefsImpl(context), PlaybackPrefsImpl(context), BluetoothCarPrefsImpl(context),
            CacheSyncPrefsImpl(context), GeneralSystemPrefsImpl(context)
        )
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.musicDao()
            val cached = EntityMapper.songToEntity(Song(id = "s", title = "Cached"))
                .copy(localUri = "file:/cached.mp3", isFullyCached = true)
            dao.insertSongs(listOf(cached))
            suspend fun sync(albums: String) {
                val executor = LegacySyncExecutor(api(albums), dao, AuthManager("", charArrayOf()), prefs, EntityMapper) { it() }
                executor.execute(
                    SyncExecutionParams(true, 0L, 0L, null) { _, _ -> },
                    verifySafetyGuard = { _, _, _, _ -> },
                    performSurgicalCleanup = { _, _ -> dao.deleteSongsNotInTemp() }
                )
            }
            assertThrows(IOException::class.java) {
                runBlocking { sync("""[{"id":"a","name":"Album","songCount":1}]""") }
            }
            assertEquals(cached, dao.getSongById("s"))
            sync("[]")
            assertEquals(0, dao.getSongsCount())
        } finally {
            db.close()
        }
    }

    private fun api(albums: String): SubsonicService {
        val jsonType = "application/json".toMediaType()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val fields = when (request.url.pathSegments.last()) {
                "getIndexes" -> """"indexes":{}"""
                "getAlbumList2" -> """"albumList2":{"album":$albums}"""
                else -> error("Unexpected request ${request.url.encodedPath}")
            }
            val body = """{"subsonic-response":{"status":"ok","version":"1.16.1",$fields}}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody(jsonType)).build()
        }.build()
        return Retrofit.Builder().baseUrl("https://library.example/").client(client)
            .addConverterFactory(Json.asConverterFactory(jsonType)).build().create(SubsonicService::class.java)
    }
}
