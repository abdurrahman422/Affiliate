package com.ownerlink.agent

import com.ownerlink.agent.network.Protocol
import com.ownerlink.agent.rtc.IceServerConfig
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IceServerConfigTest {
    @Test fun parses_stun_udp_tcp_and_tls_turn() {
        val payload = Protocol.json.parseToJsonElement("""{"ice_servers":[{"urls":["stun:s.example:3478"]},{"urls":["turn:t.example:3478?transport=udp","turn:t.example:3478?transport=tcp","turns:t.example:5349"],"username":"1000:a","credential":"temporary"}]}""").jsonObject
        val result = IceServerConfig.parse(payload)
        assertEquals(2, result.size)
        assertEquals("temporary", result[1].credential)
        assertTrue(result[1].urls.last().startsWith("turns:"))
    }

    @Test fun rejects_non_ice_urls_and_malformed_payload() {
        assertTrue(IceServerConfig.parse(Protocol.json.parseToJsonElement("""{"ice_servers":[{"urls":["https://bad"]}]}""").jsonObject).isEmpty())
        assertTrue(IceServerConfig.parse(Protocol.json.parseToJsonElement("""{}""").jsonObject).isEmpty())
    }
}
