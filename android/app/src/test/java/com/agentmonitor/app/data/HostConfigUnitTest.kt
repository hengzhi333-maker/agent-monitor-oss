package com.agentmonitor.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostConfigUnitTest {
    @Test
    fun usbAndTailscaleWithSameDaemonTokenAreOneLogicalHost() {
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
    fun identityKeyMatchesAcrossAddressAndTokenChanges() {
        val oldLan = HostConfig(
            id = "lan",
            name = "desktop",
            address = "192.168.1.20",
            token = "old-token",
            identityKey = "daemon_abc"
        )
        val newTailscale = HostConfig(
            id = "tailnet",
            name = "desktop",
            address = "workstation-tailnet",
            token = "new-token",
            identityKey = "daemon_abc"
        )

        assertTrue(oldLan.sameLogicalHost(newTailscale))
        assertEquals("LAN", oldLan.connectionLabel)
        assertEquals("Tailscale", newTailscale.connectionLabel)
    }

    @Test
    fun sameAddressWithDifferentTokensStaysSeparate() {
        val left = HostConfig(id = "a", name = "desktop", address = "100.64.0.10", token = "token-a")
        val right = HostConfig(id = "b", name = "desktop", address = "100.64.0.10", token = "token-b")

        assertFalse(left.sameLogicalHost(right))
    }
}

