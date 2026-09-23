package com.ownerlink.agent.network

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

object BinaryFrames {
    val MAGIC = byteArrayOf('O'.code.toByte(), 'L'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte())
    const val KIND_FILE = 1
    const val KIND_SCREEN = 2
    const val KIND_THUMB = 3
    const val KIND_WORKSPACE = 4
    const val FLAG_LAST = 0x01
    private const val HEADER = 4 + 1 + 16 + 4 + 1

    fun uuidBytes(id: UUID): ByteArray {
        val buf = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        buf.putLong(id.mostSignificantBits)
        buf.putLong(id.leastSignificantBits)
        return buf.array()
    }

    fun uuidFromString(value: String): UUID = UUID.fromString(value)

    fun frame(kind: Int, transferId: UUID, seq: Int, payload: ByteArray, last: Boolean = false): ByteArray {
        val flags = if (last) FLAG_LAST else 0
        val out = ByteBuffer.allocate(HEADER + payload.size).order(ByteOrder.BIG_ENDIAN)
        out.put(MAGIC)
        out.put(kind.toByte())
        out.put(uuidBytes(transferId))
        out.putInt(seq)
        out.put(flags.toByte())
        out.put(payload)
        return out.array()
    }

    fun packScreen(width: Int, height: Int, rotation: Int, jpeg: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(6 + jpeg.size).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(width.toShort())
        buf.putShort(height.toShort())
        buf.put(rotation.toByte())
        buf.put(0)
        buf.put(jpeg)
        return buf.array()
    }

    fun parse(data: ByteArray): Parsed {
        require(data.size >= HEADER) { "short frame" }
        require(data.copyOfRange(0, 4).contentEquals(MAGIC)) { "bad magic" }
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        buf.position(4)
        val kind = buf.get().toInt() and 0xff
        val hi = buf.long
        val lo = buf.long
        val seq = buf.int
        val flags = buf.get().toInt() and 0xff
        val payload = data.copyOfRange(HEADER, data.size)
        return Parsed(kind, UUID(hi, lo), seq, flags, payload)
    }

    data class Parsed(
        val kind: Int,
        val transferId: UUID,
        val seq: Int,
        val flags: Int,
        val payload: ByteArray,
    )
}
