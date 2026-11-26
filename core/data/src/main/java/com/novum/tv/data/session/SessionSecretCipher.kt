package com.novum.tv.data.session

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Encrypts account secrets before they are written to Preferences DataStore. */
interface SessionSecretCipher {
    fun encrypt(plaintext: String): String

    fun decrypt(ciphertext: String): String?

    fun isEncrypted(value: String): Boolean
}

/** AES-GCM backed by a non-exportable AES key stored in Android Keystore. */
@Singleton
class AndroidKeyStoreSessionSecretCipher @Inject constructor() : SessionSecretCipher {

    @Synchronized
    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.iv + cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        return ENCRYPTED_PREFIX + Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    @Synchronized
    override fun decrypt(ciphertext: String): String? {
        if (!isEncrypted(ciphertext)) return null
        return runCatching {
            val encrypted = Base64.decode(ciphertext.removePrefix(ENCRYPTED_PREFIX), Base64.NO_WRAP)
            require(encrypted.size > IV_LENGTH_BYTES)
            val iv = encrypted.copyOfRange(0, IV_LENGTH_BYTES)
            val payload = encrypted.copyOfRange(IV_LENGTH_BYTES, encrypted.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(payload), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    override fun isEncrypted(value: String): Boolean = value.startsWith(ENCRYPTED_PREFIX)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "novum_session_secrets_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val ENCRYPTED_PREFIX = "enc:v1:"
        const val IV_LENGTH_BYTES = 12
        const val TAG_LENGTH_BITS = 128
    }
}
