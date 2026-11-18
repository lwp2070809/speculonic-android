package de.lwp2070809.speculonic.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.google.crypto.tink.Aead
import com.google.crypto.tink.DeterministicAead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.daead.DeterministicAeadConfig
import com.google.crypto.tink.integration.android.AndroidKeystoreKmsClient
import com.google.crypto.tink.subtle.Base64
import com.google.crypto.tink.subtle.Hex
import de.lwp2070809.speculonic.util.LogManager
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore

/**
 * 仅用于从旧版 EncryptedSharedPreferences (speculonic_secure_prefs) 一次性只读迁出历史凭据。
 * 完成过渡版本迁移并删除旧 XML 后，可在后续版本连同 tink-android 依赖整体移除。
 */
internal object LegacyEncryptedPrefsMigrator {

    private const val LEGACY_PREFS_NAME = "speculonic_secure_prefs"
    private const val LEGACY_FALLBACK_PREFS_NAME = "speculonic_secure_prefs_fallback"
    private const val KEY_KEYSET_ALIAS = "__androidx_security_crypto_encrypted_prefs_key_keyset__"
    private const val VALUE_KEYSET_ALIAS = "__androidx_security_crypto_encrypted_prefs_value_keyset__"
    private const val MASTER_KEY_URI = "android-keystore://_androidx_security_master_key_"
    private const val LEGACY_MASTER_KEY_ALIAS = "_androidx_security_master_key_"
    private const val ENCRYPTED_TYPE_STRING = 0
    private const val NULL_SENTINEL = "__NULL__"

    private const val KEY_SERVER_URL = "enc_server_url"
    private const val KEY_USERNAME = "enc_username"
    private const val KEY_PASSWORD = "enc_password"

    sealed interface LegacyMigrationResult {
        data object NonePresent : LegacyMigrationResult
        data class Migrated(val serverUrl: String, val username: String, val password: String) : LegacyMigrationResult
        data object Corrupted : LegacyMigrationResult
    }

    fun readLegacyCredentials(context: Context): LegacyMigrationResult {
        val appContext = context.applicationContext
        val encryptedResult = readLegacyEncryptedPrefs(appContext)
        val fallbackResult = readLegacyFallbackPrefs(appContext)

        return when {
            encryptedResult is LegacyMigrationResult.Migrated && encryptedResult.hasAnyCredential() -> encryptedResult
            fallbackResult is LegacyMigrationResult.Migrated -> fallbackResult
            encryptedResult is LegacyMigrationResult.Corrupted -> LegacyMigrationResult.Corrupted
            else -> LegacyMigrationResult.NonePresent
        }
    }

    fun cleanupLegacyStorage(context: Context) {
        cleanupLegacySharedPrefsFiles(context.applicationContext)
    }

    private fun readLegacyEncryptedPrefs(context: Context): LegacyMigrationResult {
        if (!prefsFileExists(context, LEGACY_PREFS_NAME)) {
            return LegacyMigrationResult.NonePresent
        }
        val rawPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        if (!rawPrefs.contains(KEY_KEYSET_ALIAS) || !rawPrefs.contains(VALUE_KEYSET_ALIAS)) {
            val hasOtherEntries = rawPrefs.all.keys.any { it != KEY_KEYSET_ALIAS && it != VALUE_KEYSET_ALIAS }
            return if (hasOtherEntries) LegacyMigrationResult.Corrupted else LegacyMigrationResult.NonePresent
        }

        return try {
            DeterministicAeadConfig.register()
            AeadConfig.register()

            val masterAead = AndroidKeystoreKmsClient().getAead(MASTER_KEY_URI)
            val keysetHandle = parseEncryptedKeyset(rawPrefs, KEY_KEYSET_ALIAS, masterAead)
            val valueKeysetHandle = parseEncryptedKeyset(rawPrefs, VALUE_KEYSET_ALIAS, masterAead)

            val daead = keysetHandle.getPrimitive(RegistryConfiguration.get(), DeterministicAead::class.java)
            val aead = valueKeysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)

            val url = decryptStringEntry(rawPrefs, daead, aead, KEY_SERVER_URL) ?: ""
            val user = decryptStringEntry(rawPrefs, daead, aead, KEY_USERNAME) ?: ""
            val pass = decryptStringEntry(rawPrefs, daead, aead, KEY_PASSWORD) ?: ""

            if (url.isNotEmpty() || user.isNotEmpty() || pass.isNotEmpty()) {
                LogManager.i("LegacyEncryptedPrefsMigrator: Successfully decrypted legacy credentials cleanly via Tink.")
                LegacyMigrationResult.Migrated(url, user, pass)
            } else {
                LegacyMigrationResult.NonePresent
            }
        } catch (e: Exception) {
            LogManager.e("LegacyEncryptedPrefsMigrator: Failed decrypting legacy encrypted prefs: ${e.message}", e)
            LegacyMigrationResult.Corrupted
        }
    }

    private fun readLegacyFallbackPrefs(context: Context): LegacyMigrationResult {
        if (!prefsFileExists(context, LEGACY_FALLBACK_PREFS_NAME)) {
            return LegacyMigrationResult.NonePresent
        }
        val prefs = context.getSharedPreferences(LEGACY_FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
        val url = prefs.getString(KEY_SERVER_URL, "") ?: ""
        val user = prefs.getString(KEY_USERNAME, "") ?: ""
        val pass = prefs.getString(KEY_PASSWORD, "") ?: ""
        return if (url.isNotEmpty() || user.isNotEmpty() || pass.isNotEmpty()) {
            LogManager.w("LegacyEncryptedPrefsMigrator: Upgrading historical plaintext fallback credentials into Keystore store.")
            LegacyMigrationResult.Migrated(url, user, pass)
        } else {
            LegacyMigrationResult.NonePresent
        }
    }

    private fun parseEncryptedKeyset(
        rawPrefs: SharedPreferences,
        keysetAlias: String,
        masterAead: Aead
    ): KeysetHandle {
        val hexKeyset = rawPrefs.getString(keysetAlias, null)
            ?: error("Missing encrypted keyset: $keysetAlias")
        return TinkProtoKeysetFormat.parseEncryptedKeyset(
            Hex.decode(hexKeyset),
            masterAead,
            ByteArray(0),
            RegistryConfiguration.get()
        )
    }

    private fun decryptStringEntry(
        rawPrefs: SharedPreferences,
        daead: DeterministicAead,
        aead: Aead,
        plainKey: String
    ): String? {
        val encryptedKeyBytes = daead.encryptDeterministically(
            plainKey.toByteArray(Charsets.UTF_8),
            LEGACY_PREFS_NAME.toByteArray(Charsets.UTF_8)
        )
        val encryptedKey = Base64.encode(encryptedKeyBytes)
        val encodedValue = rawPrefs.getString(encryptedKey, null) ?: return null

        val cipherBytes = Base64.decode(encodedValue, Base64.DEFAULT)
        val plainPayload = aead.decrypt(cipherBytes, encryptedKey.toByteArray(Charsets.UTF_8))
        val buffer = ByteBuffer.wrap(plainPayload)
        val typeId = buffer.getInt()
        if (typeId != ENCRYPTED_TYPE_STRING) return null

        val byteLength = buffer.getInt()
        if (byteLength < 0 || byteLength > buffer.remaining()) {
            error("Corrupted encrypted string length: $byteLength")
        }
        val strBytes = ByteArray(byteLength)
        buffer.get(strBytes)
        val decoded = String(strBytes, Charsets.UTF_8)
        return if (decoded == NULL_SENTINEL) null else decoded
    }

    private fun cleanupLegacySharedPrefsFiles(context: Context) {
        runCatching { context.deleteSharedPreferences(LEGACY_PREFS_NAME) }
        runCatching { context.deleteSharedPreferences(LEGACY_FALLBACK_PREFS_NAME) }
        runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            if (keyStore.containsAlias(LEGACY_MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(LEGACY_MASTER_KEY_ALIAS)
            }
        }
    }

    private fun prefsFileExists(context: Context, prefsName: String): Boolean {
        val prefsDir = File(context.dataDir, "shared_prefs")
        return File(prefsDir, "$prefsName.xml").exists()
    }

    private fun LegacyMigrationResult.Migrated.hasAnyCredential(): Boolean {
        return serverUrl.isNotEmpty() || username.isNotEmpty() || password.isNotEmpty()
    }
}
