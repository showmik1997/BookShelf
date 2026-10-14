package com.example.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeyStoreManager(private val context: Context) {

    private val keyAlias = "bookshelf_openai_key_alias"
    private val keyStoreType = "AndroidKeyStore"
    private val transformation = "AES/GCM/NoPadding"
    private val prefsName = "bookshelf_encrypted_vault"
    private val prefApiKey = "enc_api_key"
    private val prefIv = "enc_api_iv"

    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    init {
        ensureKeyExists()
    }

    private fun ensureKeyExists() {
        val keyStore = KeyStore.getInstance(keyStoreType).apply { load(null) }
        if (!keyStore.containsAlias(keyAlias)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, keyStoreType)
            val spec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }
    }

    private fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(keyStoreType).apply { load(null) }
        return keyStore.getKey(keyAlias, null) as SecretKey
    }

    private val prefFallbackKey = "fallback_raw_key"

    fun saveApiKey(rawKey: String) {
        val trimmed = rawKey.trim()
        if (trimmed.isBlank() || trimmed == "MY_GEMINI_API_KEY" || trimmed == "YOUR_API_KEY_HERE") {
            prefs.edit().remove(prefApiKey).remove(prefIv).remove(prefFallbackKey).apply()
            return
        }

        // Always save fallback copy in private app storage so hardware keystore issues never wipe user's key
        prefs.edit().putString(prefFallbackKey, trimmed).apply()

        try {
            ensureKeyExists()
            val cipher = Cipher.getInstance(transformation)
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))

            val encBase64 = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
            val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)

            prefs.edit()
                .putString(prefApiKey, encBase64)
                .putString(prefIv, ivBase64)
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getApiKey(): String {
        val fallback = prefs.getString(prefFallbackKey, null)?.trim() ?: ""

        val encBase64 = prefs.getString(prefApiKey, null)
        val ivBase64 = prefs.getString(prefIv, null)

        if (encBase64.isNullOrBlank() || ivBase64.isNullOrBlank()) {
            return if (fallback != "MY_GEMINI_API_KEY") fallback else ""
        }

        return try {
            val encryptedBytes = Base64.decode(encBase64, Base64.NO_WRAP)
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)

            val cipher = Cipher.getInstance(transformation)
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)

            val decryptedBytes = cipher.doFinal(encryptedBytes)
            val key = String(decryptedBytes, Charsets.UTF_8).trim()
            if (key == "MY_GEMINI_API_KEY" || key == "YOUR_API_KEY_HERE") {
                clearApiKey()
                ""
            } else if (key.isNotBlank()) {
                key
            } else {
                fallback
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // If AndroidKeyStore decryption failed, return reliable fallback
            if (fallback != "MY_GEMINI_API_KEY") fallback else ""
        }
    }

    fun hasApiKey(): Boolean {
        return getApiKey().isNotBlank()
    }

    fun clearApiKey() {
        prefs.edit().remove(prefApiKey).remove(prefIv).remove(prefFallbackKey).apply()
    }
}
