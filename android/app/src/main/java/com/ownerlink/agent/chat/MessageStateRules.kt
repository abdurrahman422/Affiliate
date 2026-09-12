package com.ownerlink.agent.chat

object MessageStateRules {
    private val rank = mapOf(MessageState.PENDING to 0, MessageState.SENDING to 1, MessageState.SENT to 2, MessageState.DELIVERED to 3, MessageState.READ to 4)
    fun canTransition(current: MessageState, next: MessageState): Boolean {
        if (current == next) return true
        if (next == MessageState.FAILED) return current != MessageState.READ
        if (current == MessageState.FAILED) return next == MessageState.PENDING || next == MessageState.SENDING
        return (rank[next] ?: -1) >= (rank[current] ?: -1)
    }
}
