package com.agentmonitor.app

import com.agentmonitor.app.data.HostConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostConfigTest {
    @Test
    fun sameDaemonAcrossUsbAndTailscaleIsOneLogicalHost() {
        val usb = HostConfig(
            id = "usb-old",
            name = "workstation",
            address = "127.0.0.1",
            token = "shared-token"
        )
        val tailscale = HostConfig(
            id = "tailscale-new",
            name = "workstation",
            address = "100.64.0.10",
            token = "shared-token"
        )

        assertTrue(usb.sameLogicalHost(tailscale))
        assertTrue(tailscale.sameLogicalHost(usb))
        assertEquals("USB", usb.connectionLabel)
        assertEquals("Tailscale", tailscale.connectionLabel)
    }

    @Test
    fun stableIdentityMatchesEvenWhenAddressChanges() {
        val lan = HostConfig(
            id = "lan",
            name = "desktop",
            address = "192.168.1.20",
            token = "token-a",
            identityKey = "daemon_abc"
        )
        val tailnet = lan.copy(
            id = "tailnet",
            address = "workstation-tailnet",
            token = "token-b"
        )

        assertTrue(lan.sameLogicalHost(tailnet))
        assertEquals("LAN", lan.connectionLabel)
        assertEquals("Tailscale", tailnet.connectionLabel)
    }

    @Test
    fun differentTokensAndIdentitiesStaySeparate() {
        val left = HostConfig(id = "a", name = "desktop", address = "100.64.0.10", token = "token-a")
        val right = HostConfig(id = "b", name = "desktop", address = "100.64.0.10", token = "token-b")

        assertFalse(left.sameLogicalHost(right))
    }
}

