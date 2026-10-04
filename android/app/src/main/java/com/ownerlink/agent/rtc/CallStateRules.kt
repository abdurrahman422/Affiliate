package com.ownerlink.agent.rtc

object CallStateRules {
    fun canTransition(from: CallPhase, to: CallPhase): Boolean = when (from) {
        CallPhase.IDLE, CallPhase.ENDED, CallPhase.FAILED -> to in setOf(CallPhase.OUTGOING, CallPhase.INCOMING)
        CallPhase.OUTGOING -> to in setOf(CallPhase.CONNECTING, CallPhase.ENDED, CallPhase.FAILED)
        CallPhase.INCOMING -> to in setOf(CallPhase.CONNECTING, CallPhase.ENDED, CallPhase.FAILED)
        CallPhase.CONNECTING -> to in setOf(CallPhase.ACTIVE, CallPhase.RECONNECTING, CallPhase.ENDED, CallPhase.FAILED)
        CallPhase.RECONNECTING -> to in setOf(CallPhase.CONNECTING, CallPhase.ACTIVE, CallPhase.ENDED, CallPhase.FAILED)
        CallPhase.ACTIVE -> to in setOf(CallPhase.RECONNECTING, CallPhase.ENDED, CallPhase.FAILED)
    }
}
