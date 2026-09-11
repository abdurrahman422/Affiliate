package com.ownerlink.agent

import com.ownerlink.agent.network.SocketLifecycle
import com.ownerlink.agent.network.SocketStatus
import com.ownerlink.agent.network.DeviceHandshake
import com.ownerlink.agent.network.requiredHandshake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocketLifecycleTest {
    @Test fun paired_state_selects_auth_and_new_phone_selects_unpaired_hello() {
        assertEquals(DeviceHandshake.UNPAIRED_HELLO, requiredHandshake(false, null))
        assertEquals(DeviceHandshake.UNPAIRED_HELLO, requiredHandshake(true, null))
        assertEquals(DeviceHandshake.DEVICE_AUTH, requiredHandshake(true, "durable-token"))
    }

    @Test fun repeated_connect_while_connecting_or_connected_creates_no_second_attempt() {
        val lifecycle = SocketLifecycle()
        val first = lifecycle.connectRequested()!!
        assertNull(lifecycle.connectRequested())
        assertTrue(lifecycle.opened(first))
        assertEquals(SocketStatus.CONNECTED, lifecycle.status)
        assertNull(lifecycle.connectRequested())
    }

    @Test fun failure_schedules_one_reconnect_and_success_cancels_it() {
        val lifecycle = SocketLifecycle()
        val first = lifecycle.connectRequested()!!
        assertEquals(1_000L, lifecycle.terminated(first))
        assertTrue(lifecycle.isReconnectPending())
        assertNull(lifecycle.terminated(first))
        val second = lifecycle.reconnectFired()!!
        assertTrue(lifecycle.opened(second))
        assertFalse(lifecycle.isReconnectPending())
        assertEquals(SocketStatus.CONNECTED, lifecycle.status)
    }

    @Test fun stale_callback_is_ignored_after_disconnect() {
        val lifecycle = SocketLifecycle()
        val first = lifecycle.connectRequested()!!
        lifecycle.disconnect()
        assertFalse(lifecycle.opened(first))
        assertNull(lifecycle.terminated(first))
    }
}
