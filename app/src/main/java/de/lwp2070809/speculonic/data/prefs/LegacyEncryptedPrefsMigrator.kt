package de.lwp2070809.speculonic.data.prefs

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyPermanentlyInvalidatedException
import com.google.crypto.tink.Aead
import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.DeterministicAead
import com.google.crypto.tink.InsecureSecretKeyAccess
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
import java.io.IOException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.AEADBadTagException

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
    private const val MIN_GCM_CIPHERTEXT_BYTES = 28
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
        if (encryptedResult is LegacyMigrationResult.Migrated) return encryptedResult
        val fallbackResult = readLegacyFallbackPrefs(appContext)
        return if (fallbackResult == LegacyMigrationResult.NonePresent) encryptedResult else fallbackResult
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

            val masterAead = lazy {
                val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (!keyStore.containsAlias(LEGACY_MASTER_KEY_ALIAS)) {
                    throw KeystoreAesGcmCipher.InvalidCredentialData("Missing legacy master key")
                }
                AndroidKeystoreKmsClient().getAead(MASTER_KEY_URI)
            }
            val keysetHandle = readLegacyKeyset(rawPrefs, KEY_KEYSET_ALIAS, masterAead)
            val valueKeysetHandle = readLegacyKeyset(rawPrefs, VALUE_KEYSET_ALIAS, masterAead)

            val daead = keysetHandle.getPrimitive(RegistryConfiguration.get(), DeterministicAead::class.java)
            val aead = valueKeysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)

            val url = decryptStringEntry(rawPrefs, daead, aead, KEY_SERVER_URL) ?: ""
            val user = decryptStringEntry(rawPrefs, daead, aead, KEY_USERNAME) ?: ""
            val pass = decryptStringEntry(rawPrefs, daead, aead, KEY_PASSWORD) ?: ""

            val credentials = LegacyMigrationResult.Migrated(url, user, pass)
            if (credentials.hasAnyCredential()) {
                LogManager.i("LegacyEncryptedPrefsMigrator: Successfully decrypted legacy credentials cleanly via Tink.")
                credentials
            } else {
                LegacyMigrationResult.NonePresent
            }
        } catch (e: Exception) {
            if (!isUnreadableLegacyData(e)) throw e
            LogManager.e("LegacyEncryptedPrefsMigrator: Failed decrypting legacy encrypted prefs: ${e.message}", e)
            LegacyMigrationResult.Corrupted
        }
    }

    private fun isUnreadableLegacyData(error: Exception): Boolean =
        generateSequence<Throwable>(error) { it.cause }.any {
            it is KeystoreAesGcmCipher.InvalidCredentialData ||
                it is AEADBadTagException || it is KeyPermanentlyInvalidatedException
        }

    private fun readLegacyFallbackPrefs(context: Context): LegacyMigrationResult {
        if (!prefsFileExists(context, LEGACY_FALLBACK_PREFS_NAME)) {
            return LegacyMigrationResult.NonePresent
        }
        val prefs = context.getSharedPreferences(LEGACY_FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
        return try {
            val credentials = LegacyMigrationResult.Migrated(
                prefs.getString(KEY_SERVER_URL, "").orEmpty(),
                prefs.getString(KEY_USERNAME, "").orEmpty(),
                prefs.getString(KEY_PASSWORD, "").orEmpty()
            )
            if (credentials.hasAnyCredential()) credentials else LegacyMigrationResult.NonePresent
        } catch (e: ClassCastException) {
            LogManager.w("LegacyEncryptedPrefsMigrator: Invalid plaintext fallback value type", e)
            LegacyMigrationResult.Corrupted
        }
    }

    private fun readLegacyKeyset(
        rawPrefs: SharedPreferences,
        keysetAlias: String,
        masterAead: Lazy<Aead>
    ): KeysetHandle {
        val hexKeyset = readEncryptedString(rawPrefs, keysetAlias)
            ?: throw KeystoreAesGcmCipher.InvalidCredentialData("Missing legacy keyset")
        val bytes = decodeStoredData("Invalid legacy keyset encoding") { Hex.decode(hexKeyset) }
        return try {
            parseLegacyKeyset(bytes, masterAead)
        } finally {
            bytes.fill(0)
        }
    }

    internal fun parseLegacyKeyset(bytes: ByteArray, masterAead: Lazy<Aead>): KeysetHandle {
        val envelope = try {
            BinaryKeysetReader.withBytes(bytes).readEncrypted()
        } catch (e: IOException) {
            throw KeystoreAesGcmCipher.InvalidCredentialData("Invalid legacy keyset data", e)
        }
        // Tink 1.8 wrote keyset_info on every wrapped keyset. Its Keystore fallback wrote
        // a raw Keyset instead, independently for preference names and values.
        if (!envelope.hasKeysetInfo()) {
            return parseUnwrappedKeyset(bytes)
        }
        if (envelope.encryptedKeyset.size() < MIN_GCM_CIPHERTEXT_BYTES) {
            throw KeystoreAesGcmCipher.InvalidCredentialData("Truncated encrypted legacy keyset")
        }
        val plaintext = masterAead.value.decrypt(envelope.encryptedKeyset.toByteArray(), ByteArray(0))
        return try {
            parseUnwrappedKeyset(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun parseUnwrappedKeyset(bytes: ByteArray): KeysetHandle = try {
        TinkProtoKeysetFormat.parseKeyset(bytes, InsecureSecretKeyAccess.get(), RegistryConfiguration.get())
    } catch (e: GeneralSecurityException) {
        throw KeystoreAesGcmCipher.InvalidCredentialData("Invalid unwrapped legacy keyset", e)
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
        val encodedValue = readEncryptedString(rawPrefs, encryptedKey) ?: return null
        return decryptLegacyValue(aead, encodedValue, encryptedKey)
    }

    private fun decryptLegacyValue(aead: Aead, encodedValue: String, encryptedKey: String): String? {
        val cipherBytes = decodeLegacyCiphertext(encodedValue)
        val plainPayload = try {
            // This primitive uses the decrypted software keyset. Keystore access happens earlier.
            aead.decrypt(cipherBytes, encryptedKey.toByteArray(Charsets.UTF_8))
        } catch (e: GeneralSecurityException) {
            throw KeystoreAesGcmCipher.InvalidCredentialData("Cannot authenticate legacy value", e)
        }
        return decodeLegacyString(plainPayload)
    }

    private fun readEncryptedString(prefs: SharedPreferences, key: String): String? = try {
        prefs.getString(key, null)
    } catch (e: ClassCastException) {
        throw KeystoreAesGcmCipher.InvalidCredentialData("Invalid encrypted preference type", e)
    }

    private fun decodeLegacyCiphertext(encoded: String): ByteArray {
        val bytes = decodeStoredData("Invalid legacy value encoding") {
            java.util.Base64.getDecoder().decode(encoded.filterNot(Char::isWhitespace))
        }
        if (bytes.size < MIN_GCM_CIPHERTEXT_BYTES) {
            throw KeystoreAesGcmCipher.InvalidCredentialData("Truncated legacy value ciphertext")
        }
        return bytes
    }

    private inline fun decodeStoredData(message: String, decode: () -> ByteArray): ByteArray = try {
        decode()
    } catch (e: IllegalArgumentException) {
        throw KeystoreAesGcmCipher.InvalidCredentialData(message, e)
    }

    private fun decodeLegacyString(plainPayload: ByteArray): String? {
        if (plainPayload.size < 2 * Int.SIZE_BYTES) {
            throw KeystoreAesGcmCipher.InvalidCredentialData("Truncated legacy string header")
        }
        val buffer = ByteBuffer.wrap(plainPayload)
        val typeId = buffer.getInt()
        if (typeId != ENCRYPTED_TYPE_STRING) {
            throw KeystoreAesGcmCipher.InvalidCredentialData("Invalid legacy string type")
        }

        val byteLength = buffer.getInt()
        if (byteLength < 0 || byteLength > buffer.remaining()) {
            throw KeystoreAesGcmCipher.InvalidCredentialData("Invalid legacy string length")
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
