package com.ownerlink.agent

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ownerlink.agent.network.Protocol
import com.ownerlink.agent.network.str
import com.ownerlink.agent.rtc.PeerRtcManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RtcLoopbackTest {
    @Test fun realWebRtcDataChannelConnectsTwoPeers() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val peers = arrayOfNulls<PeerRtcManager>(2)
        val ready = CountDownLatch(1); val received = CountDownLatch(1); var value = ""
        fun relay(target: Int, from: String, raw: String): Boolean {
            val root = Protocol.json.parseToJsonElement(raw).jsonObject
            val original = root["payload"]?.jsonObject ?: JsonObject(emptyMap())
            val forwarded = buildJsonObject { original.forEach { (k, v) -> if (k != "to") put(k, v) }; put("from", from) }
            peers[target]?.handle(root.str("type") ?: return false, forwarded) { it }
            return true
        }
        peers[0] = PeerRtcManager(context, "alice-000", scope, { relay(1, "alice-000", it) }, { _, _ -> }, { ready.countDown() })
        peers[1] = PeerRtcManager(context, "bob-00000", scope, { relay(0, "bob-00000", it) }, { _, text -> value = text; received.countDown() }, { })
        try {
            peers[0]!!.ensureDataSession("bob-00000")
            assertTrue("DataChannel did not open", ready.await(15, TimeUnit.SECONDS))
            assertTrue(peers[0]!!.sendData("bob-00000", "hello-webrtc"))
            assertTrue("Peer did not receive data", received.await(5, TimeUnit.SECONDS))
            assertEquals("hello-webrtc", value)
        } finally {
            peers.forEach { it?.shutdown() }
        }
    }
}
