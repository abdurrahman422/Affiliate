package com.ownerlink.agent.remote

import com.ownerlink.agent.network.BinaryFrames
import com.ownerlink.agent.network.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class ScreenStreamer(
    private val scope: CoroutineScope,
    private val deviceId: String,
    private val sendJson: (String) -> Unit,
    private val sendBytes: (ByteArray) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val immediate = AtomicBoolean(false)
    private var job: Job? = null
    private var seq = 0
    private val streamId = UUID(0, 0)

    fun start(fps: Int, quality: Int, maxWidth: Int) {
        // Accessibility screenshots are captured directly into RAM and relayed without files.
        // Calls are strictly sequential, so a slow device naturally runs below the 4 FPS ceiling.
        val interval = (1000L / fps.coerceIn(1, 4))
        if (running.getAndSet(true)) {
            requestImmediate()
            return
        }
        job = scope.launch {
            var backoff = interval
            while (isActive && running.get()) {
                when (val result = ScreenshotCapture.capture(maxWidth, quality)) {
                    is CaptureResult.Frame -> {
                        backoff = interval
                        seq += 1
                        val payload = BinaryFrames.packScreen(result.width, result.height, result.rotation, result.jpeg)
                        sendBytes(BinaryFrames.frame(BinaryFrames.KIND_SCREEN, streamId, seq, payload, last = true))
                    }
                    is CaptureResult.Failed -> {
                        sendJson(
                            Protocol.envelope(
                                Protocol.SCREEN_STATUS,
                                deviceId,
                                buildJsonObject {
                                    put("code", result.code)
                                    put("message", result.message)
                                },
                            ),
                        )
                        if (result.code == "RATE_LIMITED") backoff = (backoff * 2).coerceAtMost(4000)
                        if (result.code == "PROTECTED_SCREEN") delay(1000)
                    }
                }
                if (immediate.compareAndSet(true, false)) continue
                delay(backoff)
            }
        }
    }

    fun requestImmediate() {
        immediate.set(true)
    }

    fun stop() {
        running.set(false)
        job?.cancel()
        job = null
    }

    suspend fun sendOne(quality: Int, maxWidth: Int) {
        when (val result = ScreenshotCapture.capture(maxWidth, quality)) {
            is CaptureResult.Frame -> {
                seq += 1
                val payload = BinaryFrames.packScreen(result.width, result.height, result.rotation, result.jpeg)
                sendBytes(BinaryFrames.frame(BinaryFrames.KIND_SCREEN, streamId, seq, payload, last = true))
            }
            is CaptureResult.Failed -> {
                sendJson(
                    Protocol.envelope(
                        Protocol.ERROR,
                        deviceId,
                        buildJsonObject {
                            put("code", result.code)
                            put("message", result.message)
                        },
                    ),
                )
            }
        }
    }
}
