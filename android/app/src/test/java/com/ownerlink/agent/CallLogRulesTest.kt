package com.ownerlink.agent

import android.provider.CallLog
import com.ownerlink.agent.calllog.CallLogItem
import com.ownerlink.agent.calllog.CallLogRules
import org.junit.Assert.*
import org.junit.Test

class CallLogRulesTest {
    @Test fun parsesSupportedDirections() {
        assertEquals("incoming", CallLogRules.direction(CallLog.Calls.INCOMING_TYPE))
        assertEquals("outgoing", CallLogRules.direction(CallLog.Calls.OUTGOING_TYPE))
        assertEquals("missed", CallLogRules.direction(CallLog.Calls.MISSED_TYPE))
    }

    @Test fun paginationPreservesNewestOrderAndMetadata() {
        val items = (5 downTo 1).map { CallLogItem(it.toString(), "+880$it", null, "incoming", it * 1000L, it * 5L) }
        val (first, next) = CallLogRules.page(items, 0, 2)
        val (second, finalCursor) = CallLogRules.page(items, next!!, 3)
        assertEquals(listOf("5", "4"), first.map { it.id })
        assertEquals(5000L, first.first().timestamp)
        assertEquals(25L, first.first().durationSeconds)
        assertEquals(listOf("3", "2", "1"), second.map { it.id })
        assertNull(finalCursor)
    }
}
