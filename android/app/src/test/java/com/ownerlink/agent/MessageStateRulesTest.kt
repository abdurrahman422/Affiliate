package com.ownerlink.agent

import com.ownerlink.agent.chat.MessageState
import com.ownerlink.agent.chat.MessageStateRules
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageStateRulesTest {
    @Test fun stateMovesForwardAndNeverRegresses() {
        assertTrue(MessageStateRules.canTransition(MessageState.PENDING, MessageState.SENDING))
        assertTrue(MessageStateRules.canTransition(MessageState.SENT, MessageState.DELIVERED))
        assertTrue(MessageStateRules.canTransition(MessageState.DELIVERED, MessageState.READ))
        assertFalse(MessageStateRules.canTransition(MessageState.READ, MessageState.SENT))
    }
    @Test fun failedMessageCanRetry() {
        assertTrue(MessageStateRules.canTransition(MessageState.FAILED, MessageState.PENDING))
    }
}
