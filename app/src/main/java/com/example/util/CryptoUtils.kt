package com.example.util

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

object CryptoUtils {
    private const val ALGORITHM = "AES/CBC/PKCS5Padding"
    
    // Stable salt for key derivation, ensuring persistent offline decryption
    private val secretKeySpec: SecretKeySpec by lazy {
        val saltBytes = "DoxonEnterpriseSecurityArchitecture2026KeySalt".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
        val hashedKey = digest.digest(saltBytes)
        SecretKeySpec(hashedKey, "AES")
    }
    
    private val ivSpec = IvParameterSpec(ByteArray(16)) // Stable vector for consistent SQLite queries

    /**
     * Encrypt a string using AES-256 at rest
     */
    fun encrypt(text: String): String {
        if (text.isEmpty()) return text
        return try {
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivSpec)
            val encryptedBytes = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(encryptedBytes, Base64.DEFAULT)
        } catch (e: Exception) {
            e.printStackTrace()
            text
        }
    }

    /**
     * Decrypt an AES-256 encrypted string
     */
    fun decrypt(encryptedText: String): String {
        if (encryptedText.isEmpty()) return encryptedText
        return try {
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivSpec)
            val decodedBytes = Base64.decode(encryptedText, Base64.DEFAULT)
            String(cipher.doFinal(decodedBytes), Charsets.UTF_8)
        } catch (e: Exception) {
            // Return raw if it wasn't encrypted or if decryption fails
            encryptedText
        }
    }

    /**
     * Non-reversible cryptographic hash to access long-term cross-chat memories
     */
    fun hashKeyNonReversible(key: String): String {
        if (key.isEmpty()) return key
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(key.toByteArray(Charsets.UTF_8))
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            e.printStackTrace()
            key
        }
    }
}
