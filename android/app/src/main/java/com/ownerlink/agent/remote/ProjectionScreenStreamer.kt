package com.ownerlink.agent.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import com.ownerlink.agent.network.BinaryFrames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Mirrors the physical display into JPEG frames after explicit Android consent. */
class ProjectionScreenStreamer(
    context: Context,
    private val scope: CoroutineScope,
    private val sendBytes: (ByteArray) -> Unit,
) {
    private val metrics = context.resources.displayMetrics
    private val sourceWidth = metrics.widthPixels.coerceAtLeast(1)
    private val sourceHeight = metrics.heightPixels.coerceAtLeast(1)
    private val density = metrics.densityDpi
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var projection: MediaProjection? = null
    private var job: Job? = null
    private var sequence = 0
    private val immediate = AtomicBoolean(false)
    private val streamId = UUID(0, 0)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Synchronized fun start(fps: Int, quality: Int, maxWidth: Int): Boolean {
        val active = ScreenProjectionPermission.get() ?: return false
        if (display != null) { immediate.set(true); return true }
        val outputWidth = minOf(sourceWidth, maxWidth.coerceAtLeast(320))
        val outputHeight = (sourceHeight.toLong() * outputWidth / sourceWidth).toInt().coerceAtLeast(1)
        val imageReader = ImageReader.newInstance(outputWidth, outputHeight, PixelFormat.RGBA_8888, 3)
        active.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                synchronized(this@ProjectionScreenStreamer) { releaseDisplay(); projection = null }
                ScreenProjectionPermission.clear(active)
            }
        }, mainHandler)
        val virtualDisplay = active.createVirtualDisplay(
            "Affiliate Screen Mirror", outputWidth, outputHeight, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, null,
        ) ?: run { imageReader.close(); return false }
        projection = active
        reader = imageReader
        display = virtualDisplay
        job = scope.launch {
            val interval = 1000L / fps.coerceIn(1, 6)
            delay(300)
            while (isActive && display != null) {
                capture(outputWidth, outputHeight, quality)?.let { jpeg ->
                    sequence += 1
                    val payload = BinaryFrames.packScreen(outputWidth, outputHeight, 0, jpeg)
                    sendBytes(BinaryFrames.frame(BinaryFrames.KIND_SCREEN, streamId, sequence, payload, last = true))
                }
                if (!immediate.compareAndSet(true, false)) delay(interval)
            }
        }
        return true
    }

    fun requestImmediate() { immediate.set(true) }

    @Synchronized fun stop() { releaseDisplay() }

    private fun releaseDisplay() {
        job?.cancel(); job = null
        display?.release(); display = null
        reader?.close(); reader = null
    }

    private fun capture(width: Int, height: Int, quality: Int): ByteArray? {
        val image = reader?.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes.first()
            val paddedWidth = width + (plane.rowStride - plane.pixelStride * width) / plane.pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            plane.buffer.rewind()
            padded.copyPixelsFromBuffer(plane.buffer)
            val frame = if (paddedWidth == width) padded else Bitmap.createBitmap(padded, 0, 0, width, height)
            val out = ByteArrayOutputStream()
            val encoded = frame.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(35, 80), out)
            if (frame !== padded) frame.recycle()
            padded.recycle()
            if (encoded) out.toByteArray() else null
        } finally { image.close() }
    }
}
