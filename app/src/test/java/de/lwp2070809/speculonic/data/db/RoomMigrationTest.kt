package de.lwp2070809.speculonic.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Room 数据库版本演进与自动化表结构迁移安全测试。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoomMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    private fun getDbPath(name: String): String {
        return InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(name).absolutePath
    }

    @Test
    fun migrateAll_from15to24_succeeds() {
        val dbPath = getDbPath("migration-test-all")
        // 创建项目现存最早导出架构的数据库版本 (15)
        helper.createDatabase(dbPath, 15).apply {
            close()
        }

        // 自动连续执行所有版本迁移脚本并严格比对 24.json 导出的表结构与索引哈希
        val db = helper.runMigrationsAndValidate(dbPath, 24, true, *AppDatabase.ALL_MIGRATIONS)
        db.close()
    }

    @Test
    fun testMigration18To19_addsPublicColumn() {
        val dbPath = getDbPath("migration-test-18-19")
        // 创建版本 18 的数据库
        helper.createDatabase(dbPath, 18).apply {
            close()
        }

        // 执行 18 -> 19 迁移并校验表结构与 public 列
        val db = helper.runMigrationsAndValidate(dbPath, 19, true, AppDatabase.MIGRATION_18_19)
        db.close()
    }

    @Test
    fun testMigration22To23_addsIsTranscodedColumn() {
        val dbPath = getDbPath("migration-test-22-23")
        // 创建版本 22 的数据库
        helper.createDatabase(dbPath, 22).apply {
            close()
        }

        // 执行 22 -> 23 迁移并校验表结构与 isTranscoded 列
        val db = helper.runMigrationsAndValidate(dbPath, 23, true, AppDatabase.MIGRATION_22_23)
        db.close()
    }

    @Test
    fun testMigration23To24_createsAlbumArtistIdIndex() {
        val dbPath = getDbPath("migration-test-23-24")
        // 创建版本 23 的数据库
        helper.createDatabase(dbPath, 23).apply {
            close()
        }

        // 执行 23 -> 24 迁移并校验 index_albums_artistId 索引
        val db = helper.runMigrationsAndValidate(dbPath, 24, true, AppDatabase.MIGRATION_23_24)
        db.close()
    }
}
