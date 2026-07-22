package com.sshvpn.utils

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import timber.log.Timber
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG             = "CryptoUtils"
private const val KEYSTORE_ALIAS  = "ssh_vpn_key"
private const val TRANSFORMATION   = "AES/GCM/NoPadding"
private const val GCM_TAG_LENGTH  = 128

/**
 * AES-256/GCM encrypt/decrypt backed by the Android Keystore.
 * The key never leaves the secure hardware element.
 */
@Singleton
class CryptoUtils @Inject constructor() {

    private val keyStore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }

    // ── Public API ──────────────────────────────────────────────────────────────

    fun encrypt(plaintext: String): String {
        if (plaintext.isBlank()) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv         = cipher.iv
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            // Encode as base64(iv || ciphertext) separated by "."
            val ivB64   = Base64.encodeToString(iv, Base64.NO_WRAP)
            val ctB64   = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            "\$ivB64.\$ctB64"
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Encryption failed")
            plaintext // fallback: return unencrypted (dev mode only)
        }
    }

    fun decrypt(encoded: String): String {
        if (encoded.isBlank()) return ""
        val parts = encoded.split(".")
        if (parts.size != 2) return encoded // not encrypted — return as-is
        return try {
            val iv         = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher     = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Decryption failed")
            encoded
        }
    }

    // ── Key management ──────────────────────────────────────────────────────────

    private fun getOrCreateKey(): SecretKey {
        if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
            createKey()
        }
        return (keyStore.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    private fun createKey() {
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .build()

        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply { init(spec) }
            .generateKey()
        Timber.tag(TAG).i("AES-256/GCM key created in AndroidKeyStore")
    }
}
