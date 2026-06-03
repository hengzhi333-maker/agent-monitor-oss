package com.agentmonitor.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.concurrent.thread

class HostStore(context: Context) {
    private val prefs = context.getSharedPreferences("hosts", Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }
    private val alias = "agent_monitor_hosts"

    fun load(): List<HostConfig> {
        val raw = prefs.getString("list", null) ?: return emptyList()
        val plain = decryptIfNeeded(raw)
        val decoded = decodeHosts(plain)
        val hosts = dedupeHosts(decoded)
        if (hosts.isNotEmpty() && (!raw.startsWith("v2:") || hosts != decoded)) {
            migrateLegacyHosts(hosts)
        }
        return hosts
    }

    fun save(hosts: List<HostConfig>) {
        val normalized = dedupeHosts(hosts)
        prefs.edit().putString("list", encrypt(json.encodeToString(normalized))).apply()
    }

    fun exportPlain(): String = json.encodeToString(load())

    fun exportBackup(password: String): String {
        val plain = exportPlain()
        return if (password.isBlank()) plain else BackupCrypto.encrypt(plain, password)
    }

    fun importPlain(raw: String, merge: Boolean = true): List<HostConfig> {
        val incoming = dedupeHosts(
            decodeHosts(raw).filter { it.address.isNotBlank() && it.port in 1..65535 },
            preferIncoming = true
        )
        val next = if (merge) {
            dedupeHosts(load() + incoming, preferIncoming = true)
        } else {
            incoming
        }
        save(next)
        return next
    }

    fun importBackup(raw: String, password: String, merge: Boolean = true): List<HostConfig> {
        return importPlain(BackupCrypto.decryptIfNeeded(raw, password), merge)
    }

    fun upsert(host: HostConfig): List<HostConfig> {
        val normalized = normalizeHost(host)
        val list = load().toMutableList()
        val idx = list.indexOfFirst { it.id == normalized.id || it.sameLogicalHost(normalized) }
        if (idx >= 0) {
            list[idx] = mergeHosts(list[idx], normalized, preferIncoming = true)
        } else {
            list.add(normalized)
        }
        save(list)
        return load()
    }

    fun cleanupUsbHosts(): List<HostConfig> {
        val hosts = load()
        val nonUsb = hosts.filterNot { it.isUsb }
        val cleaned = hosts.filterNot { host ->
            host.isUsb && nonUsb.any { it.sameLogicalHost(host) }
        }
        val next = dedupeHosts(cleaned)
        save(next)
        return next
    }

    fun remove(id: String): List<HostConfig> {
        val list = load().filterNot { it.id == id }
        save(list)
        return list
    }

    private fun dedupeHosts(input: List<HostConfig>, preferIncoming: Boolean = false): List<HostConfig> {
        val out = mutableListOf<HostConfig>()
        input.map(::normalizeHost).forEach { host ->
            val idx = out.indexOfFirst { it.sameLogicalHost(host) }
            if (idx >= 0) {
                out[idx] = mergeHosts(out[idx], host, preferIncoming)
            } else {
                out.add(host)
            }
        }
        return out
    }

    private fun mergeHosts(existing: HostConfig, incoming: HostConfig, preferIncoming: Boolean): HostConfig {
        val preferred = when {
            preferIncoming -> incoming
            existing.isUsb && !incoming.isUsb -> incoming
            !existing.isUsb && incoming.isUsb -> existing
            else -> incoming
        }
        val fallback = if (preferred === incoming) existing else incoming
        return preferred.copy(
            id = existing.id.ifBlank { preferred.id },
            name = preferred.name.ifBlank { fallback.name },
            token = preferred.token.ifBlank { fallback.token },
            group = preferred.group.ifBlank { fallback.group },
            pinned = preferred.pinned || fallback.pinned,
            identityKey = preferred.identityKey.ifBlank { fallback.identityKey }
        )
    }

    private fun normalizeHost(host: HostConfig): HostConfig {
        val parsed = parseAddress(host.address)
        val address = parsed.address.ifBlank { host.address.trim() }
        val name = host.name.trim().ifBlank { address.ifBlank { "Agent Monitor" } }
        val token = host.token.trim()
        return host.copy(
            id = host.id.trim().ifBlank { stableLocalId(name, address, token, host.identityKey) },
            name = name,
            address = address,
            port = parsed.port ?: host.port.takeIf { it in 1..65535 } ?: 8765,
            token = token,
            secure = host.secure || parsed.secure == true,
            group = host.group.trim(),
            identityKey = host.identityKey.trim()
        )
    }

    private fun decryptIfNeeded(raw: String): String {
        if (!raw.startsWith("v2:")) return raw
        return try {
            val parts = raw.split(":", limit = 3)
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipherText = Base64.decode(parts[2], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    private fun migrateLegacyHosts(hosts: List<HostConfig>) {
        thread(start = true, isDaemon = false, name = "AgentMonitorHostMigration") {
            try {
                val encrypted = encrypt(json.encodeToString(hosts))
                val ok = prefs.edit().putString("list", encrypted).commit()
                if (!ok) Log.w(TAG, "Host migration commit failed")
            } catch (e: Exception) {
                Log.w(TAG, "Host migration failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun decodeHosts(plain: String): List<HostConfig> {
        return try {
            json.decodeFromString<List<HostConfig>>(plain)
        } catch (e: Exception) {
            decodeLegacyHosts(plain)
        }
    }

    private fun decodeLegacyHosts(plain: String): List<HostConfig> {
        return try {
            json.parseToJsonElement(plain).jsonArray.mapNotNull { item ->
                val obj = item.jsonObject
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "Agent Monitor"
                val addressInput = obj["address"]?.jsonPrimitive?.contentOrNull
                    ?: obj["url"]?.jsonPrimitive?.contentOrNull
                    ?: obj["baseUrl"]?.jsonPrimitive?.contentOrNull
                    ?: return@mapNotNull null
                val parsed = parseAddress(addressInput)
                val token = obj["token"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val identity = obj["identityKey"]?.jsonPrimitive?.contentOrNull
                    ?: obj["hostId"]?.jsonPrimitive?.contentOrNull
                    ?: obj["daemonId"]?.jsonPrimitive?.contentOrNull
                    ?: ""
                HostConfig(
                    id = obj["id"]?.jsonPrimitive?.contentOrNull
                        ?: stableLocalId(name, parsed.address, token, identity),
                    name = name,
                    address = parsed.address,
                    port = obj["port"]?.jsonPrimitive?.intOrNull ?: parsed.port ?: 8765,
                    token = token,
                    secure = obj["secure"]?.jsonPrimitive?.booleanOrNull ?: parsed.secure ?: false,
                    group = obj["group"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    pinned = obj["pinned"]?.jsonPrimitive?.booleanOrNull ?: false,
                    identityKey = identity
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private data class ParsedAddress(
        val address: String,
        val port: Int?,
        val secure: Boolean?
    )

    private fun parseAddress(input: String): ParsedAddress {
        val trimmed = input.trim().trimEnd('/')
        if (!trimmed.contains("://")) {
            val parts = trimmed.split(":", limit = 2)
            val port = parts.getOrNull(1)?.toIntOrNull()
            return ParsedAddress(parts.firstOrNull().orEmpty().ifBlank { trimmed }, port, null)
        }

        return try {
            val uri = URI(trimmed.replaceFirst("ws://", "http://").replaceFirst("wss://", "https://"))
            ParsedAddress(
                address = uri.host ?: trimmed.substringAfter("://").substringBefore('/').substringBefore(':'),
                port = uri.port.takeIf { it > 0 },
                secure = uri.scheme.equals("https", ignoreCase = true)
            )
        } catch (e: Exception) {
            ParsedAddress(trimmed.substringAfter("://").substringBefore('/').substringBefore(':'), null, null)
        }
    }

    private fun stableLocalId(name: String, address: String, token: String, identity: String = ""): String {
        val basis = identity.ifBlank { "$name|$address|${token.takeLast(12)}" }
        return "host_${Integer.toUnsignedString(basis.hashCode(), 36)}"
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        return "v2:${Base64.encodeToString(iv, Base64.NO_WRAP)}:${Base64.encodeToString(cipherText, Base64.NO_WRAP)}"
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "HostStore"
    }
}
