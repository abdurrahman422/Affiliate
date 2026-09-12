package com.ownerlink.agent

import com.ownerlink.agent.rtc.CallPhase
import com.ownerlink.agent.rtc.CallStateRules
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallStateRulesTest {
    @Test fun acceptedCallHasValidLifecycle() {
        assertTrue(CallStateRules.canTransition(CallPhase.IDLE, CallPhase.INCOMING))
        assertTrue(CallStateRules.canTransition(CallPhase.INCOMING, CallPhase.CONNECTING))
        assertTrue(CallStateRules.canTransition(CallPhase.CONNECTING, CallPhase.ACTIVE))
        assertTrue(CallStateRules.canTransition(CallPhase.ACTIVE, CallPhase.ENDED))
    }
    @Test fun endedCallCannotBecomeActive() { assertFalse(CallStateRules.canTransition(CallPhase.ENDED, CallPhase.ACTIVE)) }
    @Test fun activeCallCanRecoverAfterNetworkSwitch() {
        assertTrue(CallStateRules.canTransition(CallPhase.ACTIVE, CallPhase.RECONNECTING))
        assertTrue(CallStateRules.canTransition(CallPhase.RECONNECTING, CallPhase.ACTIVE))
    }
    @Test fun terminalCallCannotBeReactivatedByStaleAccept() {
        assertFalse(CallStateRules.canTransition(CallPhase.ENDED, CallPhase.CONNECTING))
        assertFalse(CallStateRules.canTransition(CallPhase.FAILED, CallPhase.ACTIVE))
    }
}
