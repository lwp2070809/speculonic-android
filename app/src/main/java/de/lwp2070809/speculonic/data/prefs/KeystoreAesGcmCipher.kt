package de.lwp2070809.speculonic.data.prefs

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 基于原生 AndroidKeyStore（AES-256-GCM/NoPadding）实现的对称加解密工具。
 * 密文编码格式：Base64(NO_WRAP) of [1-byte version(0x01) || 12-byte random IV || ciphertext + 16-byte GCM tag]。
 */
internal object KeystoreAesGcmCipher {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "speculonic_connection_master_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val FORMAT_VERSION: Byte = 0x01
    private const val IV_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val AES_KEY_SIZE_BITS = 256
    private const val HEADER_BYTES = 1 + IV_LENGTH_BYTES

    fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        val key = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        require(iv.size == IV_LENGTH_BYTES) { "Unexpected GCM IV length: ${iv.size}" }
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val payload = ByteArray(1 + iv.size + ciphertext.size)
        payload[0] = FORMAT_VERSION
        System.arraycopy(iv, 0, payload, 1, iv.size)
        System.arraycopy(ciphertext, 0, payload, 1 + iv.size, ciphertext.size)
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    fun decrypt(encodedCiphertext: String): String {
        if (encodedCiphertext.isEmpty()) return ""
        val payload = Base64.decode(encodedCiphertext, Base64.NO_WRAP)
        require(payload.size > HEADER_BYTES && payload[0] == FORMAT_VERSION) {
            "Corrupted or unsupported ciphertext format"
        }

        val iv = payload.copyOfRange(1, HEADER_BYTES)
        val ciphertext = payload.copyOfRange(HEADER_BYTES, payload.size)
        val key = getExistingSecretKey()
            ?: error("AndroidKeyStore master key missing for existing ciphertext")

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val plaintextBytes = cipher.doFinal(ciphertext)
        return String(plaintextBytes, Charsets.UTF_8)
    }

    fun deleteKeyQuietly() {
        runCatching {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
            keyStore.load(null)
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
        }
    }

    @Synchronized
    private fun getExistingSecretKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
        keyStore.load(null)
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    }

    @Synchronized
    private fun getOrCreateSecretKey(): SecretKey {
        getExistingSecretKey()?.let { return it }
        return try {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_SIZE_BITS)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        } catch (e: ProviderException) {
            throw java.security.GeneralSecurityException(e.message, e)
        }
    }
}
