package com.agentmonitor.app.data

import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private const val BackupFormat = "agent-monitor.hosts.encrypted.v1"
private const val BackupKdf = "PBKDF2WithHmacSHA256"
private const val BackupIterations = 120_000

@Serializable
private data class EncryptedHostBackup(
    val format: String = BackupFormat,
    val kdf: String = BackupKdf,
    val iterations: Int = BackupIterations,
    val salt: String = "",
    val iv: String = "",
    val cipherText: String = ""
)

object BackupCrypto {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val random = SecureRandom()

    fun encrypt(plain: String, password: String): String {
        require(password.isNotBlank()) { "Backup password is required." }
        val salt = randomBytes(16)
        val iv = randomBytes(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return json.encodeToString(
            EncryptedHostBackup(
                salt = salt.b64(),
                iv = iv.b64(),
                cipherText = cipherText.b64()
            )
        )
    }

    fun decryptIfNeeded(raw: String, password: String): String {
        val trimmed = raw.trim()
        if (!trimmed.contains(BackupFormat)) return raw
        if (password.isBlank()) throw IllegalArgumentException("Backup password is required.")
        val payload = json.decodeFromString<EncryptedHostBackup>(trimmed)
        if (payload.format != BackupFormat || payload.kdf != BackupKdf) {
            throw IllegalArgumentException("Unsupported encrypted backup format.")
        }
        val salt = payload.salt.fromB64()
        val iv = payload.iv.fromB64()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            deriveKey(password, salt, payload.iterations),
            GCMParameterSpec(128, iv)
        )
        return String(cipher.doFinal(payload.cipherText.fromB64()), Charsets.UTF_8)
    }

    fun isEncrypted(raw: String): Boolean = raw.trim().contains(BackupFormat)

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int = BackupIterations): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations.coerceAtLeast(60_000), 256)
        val key = SecretKeyFactory.getInstance(BackupKdf).generateSecret(spec).encoded
        return SecretKeySpec(key, "AES")
    }

    private fun randomBytes(size: Int) = ByteArray(size).also { random.nextBytes(it) }

    private fun ByteArray.b64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.fromB64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)
}
