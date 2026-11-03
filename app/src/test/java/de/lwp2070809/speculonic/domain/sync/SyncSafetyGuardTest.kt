package de.lwp2070809.speculonic.domain.sync

import de.lwp2070809.speculonic.data.PreferencesManager
import de.lwp2070809.speculonic.data.db.dao.MusicDao
import de.lwp2070809.speculonic.data.prefs.BluetoothCarPrefs
import de.lwp2070809.speculonic.data.prefs.CacheSyncPrefs
import de.lwp2070809.speculonic.data.prefs.ConnectionPrefs
import de.lwp2070809.speculonic.data.prefs.GeneralSystemPrefs
import de.lwp2070809.speculonic.data.prefs.PlaybackPrefs
import de.lwp2070809.speculonic.domain.repository.AuthManager
import de.lwp2070809.speculonic.domain.repository.EntityMapper
import de.lwp2070809.speculonic.domain.repository.SafetyGuardException
import de.lwp2070809.speculonic.domain.repository.ServerCapabilities
import de.lwp2070809.speculonic.domain.repository.SyncManager
import de.lwp2070809.speculonic.network.api.SubsonicService
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 离线元数据同步防误删熔断保护算法 (Safety Guard) 单元测试。
 */
class SyncSafetyGuardTest {

    class FakeMusicDaoHandler(val songsCount: Int) : InvocationHandler {
        var deleteSongsNotInTempCalled = false
        var deleteAlbumsNotInTempCalled = false
        var deleteArtistsNotInTempCalled = false
        var clearSyncTempIdsCalled = false
        var repairAndCountCalled = false

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            return when (method.name) {
                "getSongsCount" -> songsCount
                "deleteSongsNotInTemp" -> {
                    deleteSongsNotInTempCalled = true
                    0
                }
                "deleteAlbumsNotInTemp" -> {
                    deleteAlbumsNotInTempCalled = true
                    0
                }
                "deleteArtistsNotInTemp" -> {
                    deleteArtistsNotInTempCalled = true
                    0
                }
                "clearSyncTempIds" -> {
                    clearSyncTempIdsCalled = true
                    Unit
                }
                "repairAndCount" -> {
                    repairAndCountCalled = true
                    songsCount
                }
                "getAllSongsMetadata", "getAlbums", "getArtists" -> emptyList<Any>()
                "insertArtists", "insertAlbums", "insertSongs", "insertSyncTempIds" -> Unit
                else -> {
                    val returnType = method.returnType
                    when {
                        returnType == java.lang.Integer.TYPE || returnType == Integer::class.java -> 0
                        returnType == java.lang.Long.TYPE || returnType == java.lang.Long::class.java -> 0L
                        returnType == java.lang.Boolean.TYPE || returnType == java.lang.Boolean::class.java -> false
                        returnType == java.util.List::class.java -> emptyList<Any>()
                        returnType == kotlinx.coroutines.flow.Flow::class.java -> flowOf(emptyList<Any>())
                        else -> emptyList<Any>()
                    }
                }
            }
        }
    }

    private fun createFakeMusicDao(songsCount: Int): Pair<MusicDao, FakeMusicDaoHandler> {
        val handler = FakeMusicDaoHandler(songsCount)
        val dao = Proxy.newProxyInstance(
            MusicDao::class.java.classLoader,
            arrayOf(MusicDao::class.java),
            handler
        ) as MusicDao
        return dao to handler
    }

    private fun createDummySubsonicResponseJson(songCount: Int): String {
        val songListJson = (1..songCount).joinToString(",") { i ->
            """{"id":"song_$i","title":"Song $i","albumId":"al_$i","artistId":"ar_$i","parent":"dir_$i"}"""
        }
        return """
        {
            "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "type": "speculonic-test",
                "serverVersion": "1.0",
                "openSubsonic": true,
                "searchResult3": {
                    "song": [$songListJson]
                }
            }
        }
        """.trimIndent()
    }

    private fun createMockSubsonicService(responseJson: String): SubsonicService {
        return Proxy.newProxyInstance(
            SubsonicService::class.java.classLoader,
            arrayOf(SubsonicService::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "search3Stream" -> {
                    responseJson.toResponseBody("application/json".toMediaType())
                }
                else -> null
            }
        } as SubsonicService
    }

    private fun createDummyPreferencesManager(): PreferencesManager {
        val cacheSyncPrefs = Proxy.newProxyInstance(
            CacheSyncPrefs::class.java.classLoader,
            arrayOf(CacheSyncPrefs::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getLastSyncTime" -> flowOf(0L)
                "getServerLastModified" -> flowOf(0L)
                "saveSyncProgress", "saveSyncError" -> null
                else -> null
            }
        } as CacheSyncPrefs

        val connectionPrefs = Proxy.newProxyInstance(
            ConnectionPrefs::class.java.classLoader,
            arrayOf(ConnectionPrefs::class.java)
        ) { _, _, _ -> null } as ConnectionPrefs

        val playbackPrefs = Proxy.newProxyInstance(
            PlaybackPrefs::class.java.classLoader,
            arrayOf(PlaybackPrefs::class.java)
        ) { _, _, _ -> null } as PlaybackPrefs

        val bluetoothCarPrefs = Proxy.newProxyInstance(
            BluetoothCarPrefs::class.java.classLoader,
            arrayOf(BluetoothCarPrefs::class.java)
        ) { _, _, _ -> null } as BluetoothCarPrefs

        val generalSystemPrefs = Proxy.newProxyInstance(
            GeneralSystemPrefs::class.java.classLoader,
            arrayOf(GeneralSystemPrefs::class.java)
        ) { _, _, _ -> null } as GeneralSystemPrefs

        return PreferencesManager(
            connectionPrefs,
            playbackPrefs,
            bluetoothCarPrefs,
            cacheSyncPrefs,
            generalSystemPrefs
        )
    }

    @Test
    fun safetyGuardTriggersWhenServerSongsFarBelowLocalThreshold() = runBlocking {
        // 本地基数 100 首 (>= 10 首最低门槛)，服务端仅返回 10 首 (< 70 首熔断线)
        val (fakeDao, handler) = createFakeMusicDao(songsCount = 100)
        val json = createDummySubsonicResponseJson(songCount = 10)
        val api = createMockSubsonicService(json)
        val auth = AuthManager("test_user", "test_pass".toCharArray())
        val pref = createDummyPreferencesManager()
        val syncManager = SyncManager(api, fakeDao, auth, pref, EntityMapper)

        val capabilities = ServerCapabilities(
            type = "navidrome",
            isOpenSubsonic = true,
            serverVersion = "0.50.0"
        )

        try {
            syncManager.syncAllData(
                serverCapabilities = capabilities,
                forceRefresh = true,
                ignoreSafetyGuard = false,
                hasLocalData = true,
                onSyncComplete = { _, _ -> }
            )
            fail("必须抛出 SafetyGuardException 熔断异常")
        } catch (e: SafetyGuardException) {
            assertTrue(e.message?.contains("安全保护触发") == true)
        }

        // 断言：熔断发生时，严禁调用物理删除方法，本地数据必须完整保留
        assertFalse("熔断触发时绝不得调用 deleteSongsNotInTemp", handler.deleteSongsNotInTempCalled)
        assertFalse("熔断触发时绝不得调用 deleteAlbumsNotInTemp", handler.deleteAlbumsNotInTempCalled)
        assertFalse("熔断触发时绝不得调用 deleteArtistsNotInTemp", handler.deleteArtistsNotInTempCalled)
        assertTrue("熔断触发时必须清理临时表以保持洁净", handler.clearSyncTempIdsCalled)
    }

    @Test
    fun safetyGuardBypassedWhenIgnoreSafetyGuardIsTrue() = runBlocking {
        // 本地 100 首，服务端 10 首，但用户或业务显式指定 ignoreSafetyGuard = true (例如全量重置重建)
        val (fakeDao, handler) = createFakeMusicDao(songsCount = 100)
        val json = createDummySubsonicResponseJson(songCount = 10)
        val api = createMockSubsonicService(json)
        val auth = AuthManager("test_user", "test_pass".toCharArray())
        val pref = createDummyPreferencesManager()
        val syncManager = SyncManager(api, fakeDao, auth, pref, EntityMapper)

        val capabilities = ServerCapabilities(
            type = "navidrome",
            isOpenSubsonic = true,
            serverVersion = "0.50.0"
        )

        var syncCompleted = false
        syncManager.syncAllData(
            serverCapabilities = capabilities,
            forceRefresh = true,
            ignoreSafetyGuard = true,
            hasLocalData = true,
            onSyncComplete = { _, _ -> syncCompleted = true }
        )

        assertTrue("在忽略熔断策略下同步应正常完成", syncCompleted)
        assertTrue("允许执行 Surgical Deletion 删除服务端已移除的差集", handler.deleteSongsNotInTempCalled)
    }

    @Test
    fun safetyGuardNotTriggeredWhenLocalBaseBelowMinimum() = runBlocking {
        // 本地基数只有 5 首 (< 10 首最低防线)，即使服务端只有 1 首，也不应误触发熔断
        val (fakeDao, handler) = createFakeMusicDao(songsCount = 5)
        val json = createDummySubsonicResponseJson(songCount = 1)
        val api = createMockSubsonicService(json)
        val auth = AuthManager("test_user", "test_pass".toCharArray())
        val pref = createDummyPreferencesManager()
        val syncManager = SyncManager(api, fakeDao, auth, pref, EntityMapper)

        val capabilities = ServerCapabilities(
            type = "navidrome",
            isOpenSubsonic = true,
            serverVersion = "0.50.0"
        )

        var syncCompleted = false
        syncManager.syncAllData(
            serverCapabilities = capabilities,
            forceRefresh = true,
            ignoreSafetyGuard = false,
            hasLocalData = true,
            onSyncComplete = { _, _ -> syncCompleted = true }
        )

        assertTrue("基数未达最低门槛时不触发熔断，同步正常完成", syncCompleted)
        assertTrue("允许执行 Surgical 差集清理", handler.deleteSongsNotInTempCalled)
    }
}
