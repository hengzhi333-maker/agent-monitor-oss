package com.agentmonitor.app.data

import kotlinx.serialization.Serializable

@Serializable
data class HostConfig(
    val id: String,
    val name: String,
    val address: String,
    val port: Int = 8765,
    val token: String = "",
    val secure: Boolean = false,
    val group: String = "",
    val pinned: Boolean = false,
    val identityKey: String = ""
) {
    val baseUrl: String get() = "${if (secure) "https" else "http"}://$address:$port"
    val wsUrl: String get() = "${if (secure) "wss" else "ws"}://$address:$port/ws"
    val normalizedAddress: String get() = normalizeAddressKey(address)
    val isUsb: Boolean get() = normalizedAddress == "127.0.0.1" || normalizedAddress == "localhost"
    val isTailscale: Boolean get() = looksLikeTailscaleAddress(normalizedAddress) ||
        (!isUsb && !normalizedAddress.contains(".") && normalizedAddress.isNotBlank())
    val connectionLabel: String get() = when {
        isUsb -> "USB"
        isTailscale -> "Tailscale"
        looksLikeLanAddress(normalizedAddress) -> "LAN"
        else -> "Remote"
    }
    val displayEndpoint: String get() = "$connectionLabel $address:$port"

    fun sameLogicalHost(other: HostConfig): Boolean {
        if (id.isNotBlank() && id == other.id) return true
        val leftIdentity = normalizedIdentity()
        val rightIdentity = other.normalizedIdentity()
        if (leftIdentity.isNotBlank() && leftIdentity == rightIdentity) return true
        if (token.isNotBlank() && token == other.token) {
            if (port == other.port && name.trim().equals(other.name.trim(), ignoreCase = true)) return true
            if (isUsb != other.isUsb) return true
            if (normalizedAddress == other.normalizedAddress && port == other.port) return true
        }
        return normalizedAddress == other.normalizedAddress &&
            port == other.port &&
            token.isNotBlank() &&
            token == other.token
    }

    fun normalizedIdentity(): String = identityKey.trim().lowercase()

    companion object {
        fun normalizeAddressKey(value: String): String =
            value.trim()
                .removePrefix("http://")
                .removePrefix("https://")
                .removePrefix("ws://")
                .removePrefix("wss://")
                .substringBefore("/")
                .substringBefore(":")
                .lowercase()

        fun looksLikeTailscaleAddress(value: String): Boolean {
            val parts = value.split('.').mapNotNull { it.toIntOrNull() }
            return parts.size == 4 && parts[0] == 100 && parts[1] in 64..127
        }

        fun looksLikeLanAddress(value: String): Boolean {
            val parts = value.split('.').mapNotNull { it.toIntOrNull() }
            if (parts.size != 4) return false
            return parts[0] == 10 ||
                (parts[0] == 172 && parts[1] in 16..31) ||
                (parts[0] == 192 && parts[1] == 168)
        }
    }
}
