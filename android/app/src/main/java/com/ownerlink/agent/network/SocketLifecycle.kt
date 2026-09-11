package com.ownerlink.agent.network

/** Pure, synchronized state machine for one physical device socket. */
enum class SocketStatus { DISCONNECTED, CONNECTING, CONNECTED }

class SocketLifecycle {
    var status: SocketStatus = SocketStatus.DISCONNECTED
        private set
    private var attempt = 0L
    private var reconnectPending = false
    private var backoffMs = 1_000L

    fun connectRequested(): Long? {
        if (status != SocketStatus.DISCONNECTED) return null
        reconnectPending = false
        status = SocketStatus.CONNECTING
        return ++attempt
    }

    fun opened(id: Long): Boolean {
        if (id != attempt || status != SocketStatus.CONNECTING) return false
        status = SocketStatus.CONNECTED
        reconnectPending = false
        backoffMs = 1_000L
        return true
    }

    fun terminated(id: Long): Long? {
        if (id != attempt || status == SocketStatus.DISCONNECTED || reconnectPending) return null
        status = SocketStatus.DISCONNECTED
        reconnectPending = true
        val delay = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        return delay
    }

    fun reconnectFired(): Long? {
        if (!reconnectPending) return null
        reconnectPending = false
        return connectRequested()
    }

    fun retryNow(): Long? {
        if (status != SocketStatus.DISCONNECTED) return null
        reconnectPending = false
        return connectRequested()
    }

    fun disconnect() {
        attempt++
        status = SocketStatus.DISCONNECTED
        reconnectPending = false
    }

    fun isCurrent(id: Long): Boolean = id == attempt
    fun isReconnectPending(): Boolean = reconnectPending
}
