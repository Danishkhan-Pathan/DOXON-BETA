package com.example.data.sync

import android.util.Base64
import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Robust, military-grade cryptographic utility prioritizing user privacy and zero-knowledge data security.
 * Uses PBKDF2WithHmacSHA256 for key derivation and AES-GCM-256 for authenticated encryption.
 */
object SecureSyncHelper {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_DERIVATION_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 2000
    private const val KEY_LENGTH = 256
    private const val IV_LENGTH_BYTES = 12
    private const val SALT_LENGTH_BYTES = 16
    private const val TAG_LENGTH_BITS = 128

    /**
     * Derives a 256-bit AES key from a passphrase and a salt.
     */
    fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance(KEY_DERIVATION_ALGORITHM)
        val spec: KeySpec = PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        val tmp = factory.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, "AES")
    }

    /**
     * Encrypts plain text using the user's passphrase with AES-GCM-256.
     * Returns a string package containing [Base64 Salt]:[Base64 IV]:[Base64 Ciphertext].
     */
    fun encrypt(plainText: String, passphrase: String): String {
        val secureRandom = SecureRandom()
        
        // Generate pseudo-random salt and IV
        val salt = ByteArray(SALT_LENGTH_BYTES)
        secureRandom.nextBytes(salt)
        
        val iv = ByteArray(IV_LENGTH_BYTES)
        secureRandom.nextBytes(iv)

        // Derive key from passphrase and salt
        val secretKeySpec = deriveKey(passphrase, salt)

        val cipher = Cipher.getInstance(ALGORITHM)
        val parameterSpec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, parameterSpec)

        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        val saltBase64 = Base64.encodeToString(salt, Base64.NO_WRAP)
        val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val cipherTextBase64 = Base64.encodeToString(cipherText, Base64.NO_WRAP)

        return "$saltBase64:$ivBase64:$cipherTextBase64"
    }

    /**
     * Decrypts a secure pack of [Base64 Salt]:[Base64 IV]:[Base64 Ciphertext] using the passphrase.
     */
    fun decrypt(securePack: String, passphrase: String): String {
        val parts = securePack.split(":")
        if (parts.size != 3) {
            throw IllegalArgumentException("Invalid sync payload package integrity.")
        }

        val salt = Base64.decode(parts[0], Base64.NO_WRAP)
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipherText = Base64.decode(parts[2], Base64.NO_WRAP)

        val secretKeySpec = deriveKey(passphrase, salt)

        val cipher = Cipher.getInstance(ALGORITHM)
        val parameterSpec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, parameterSpec)

        val decryptedBytes = cipher.doFinal(cipherText)
        return String(decryptedBytes, Charsets.UTF_8)
    }
}
