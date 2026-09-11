package com.ownerlink.agent.remote

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.view.Display
import com.ownerlink.agent.accessibility.OwnerLinkAccessibilityService
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume

sealed class CaptureResult {
    data class Frame(val jpeg: ByteArray, val width: Int, val height: Int, val rotation: Int) : CaptureResult()
    data class Failed(val code: String, val message: String) : CaptureResult()
}

object ScreenshotCapture {
    private val executor = Executors.newSingleThreadExecutor()

    suspend fun capture(maxWidth: Int = 720, quality: Int = 55): CaptureResult {
        val service = OwnerLinkAccessibilityService.instance
            ?: return CaptureResult.Failed("ACCESSIBILITY_DISABLED", "Enable the OwnerLink accessibility service on the phone.")
        return suspendCancellableCoroutine { cont ->
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        try {
                            val hw = screenshot.hardwareBuffer
                            val wrapped = Bitmap.wrapHardwareBuffer(hw, screenshot.colorSpace)
                            val software = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                            wrapped?.recycle()
                            hw.close()
                            if (software == null) {
                                if (cont.isActive) cont.resume(CaptureResult.Failed("PROTECTED_SCREEN", "Protected screen cannot be captured."))
                                return
                            }
                            val scaled = scale(software, maxWidth)
                            val width = scaled.width
                            val height = scaled.height
                            val rotation = service.display?.rotation ?: 0
                            val out = ByteArrayOutputStream()
                            scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(30, 80), out)
                            if (scaled !== software) scaled.recycle()
                            software.recycle()
                            if (cont.isActive) {
                                cont.resume(CaptureResult.Frame(out.toByteArray(), width, height, rotation))
                            }
                        } catch (e: Exception) {
                            if (cont.isActive) cont.resume(CaptureResult.Failed("ERROR", e.message ?: "screenshot failed"))
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        if (!cont.isActive) return
                        val mapped = mapError(errorCode)
                        cont.resume(CaptureResult.Failed(mapped.first, mapped.second))
                    }
                },
            )
        }
    }

    private fun scale(src: Bitmap, maxWidth: Int): Bitmap {
        if (src.width <= maxWidth) return src
        val h = src.height * maxWidth / src.width
        return Bitmap.createScaledBitmap(src, maxWidth, h, true)
    }

    private fun mapError(code: Int): Pair<String, String> {
        // ERROR_TAKE_SCREENSHOT_SECURE_WINDOW = 6 on API 34.
        return when (code) {
            3 -> "RATE_LIMITED" to "Screenshot rate limited"
            6 -> "PROTECTED_SCREEN" to "Protected screen cannot be captured."
            2 -> "ACCESSIBILITY_DISABLED" to "Accessibility screenshot permission is not available."
            else -> "ERROR" to "Screenshot failed ($code)"
        }
    }
}
