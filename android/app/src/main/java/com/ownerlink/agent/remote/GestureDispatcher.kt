package com.ownerlink.agent.remote

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.accessibilityservice.AccessibilityService
import android.view.WindowManager
import com.ownerlink.agent.accessibility.OwnerLinkAccessibilityService
import kotlinx.serialization.json.JsonObject
import com.ownerlink.agent.network.int
import com.ownerlink.agent.network.optDouble
import com.ownerlink.agent.network.str

object GestureDispatcher {
    fun dispatch(payload: JsonObject): Pair<Boolean, String?> {
        val service = OwnerLinkAccessibilityService.instance
            ?: return false to "Enable the OwnerLink accessibility service on the phone."
        val gesture = (payload.str("gesture") ?: "").uppercase()
        val (w, h) = displaySize(service)
        return when (gesture) {
            "TAP" -> {
                val x = nx(payload.optDouble("x"), w)
                val y = ny(payload.optDouble("y"), h)
                stroke(service, x, y, x, y, 50)
            }
            "LONG_PRESS" -> {
                val x = nx(payload.optDouble("x"), w)
                val y = ny(payload.optDouble("y"), h)
                val dur = (payload.int("duration_ms") ?: 700).coerceIn(400, 2000)
                stroke(service, x, y, x, y, dur.toLong())
            }
            "SWIPE" -> {
                val x1 = nx(payload.optDouble("x1"), w)
                val y1 = ny(payload.optDouble("y1"), h)
                val x2 = nx(payload.optDouble("x2"), w)
                val y2 = ny(payload.optDouble("y2"), h)
                val dur = (payload.int("duration_ms") ?: 350).coerceIn(80, 1200)
                stroke(service, x1, y1, x2, y2, dur.toLong())
            }
            "SCROLL_UP" -> stroke(service, w * 0.5f, h * 0.72f, w * 0.5f, h * 0.32f, 350)
            "SCROLL_DOWN" -> stroke(service, w * 0.5f, h * 0.32f, w * 0.5f, h * 0.72f, 350)
            "BACK" -> global(service, AccessibilityService.GLOBAL_ACTION_BACK)
            "HOME" -> global(service, AccessibilityService.GLOBAL_ACTION_HOME)
            "RECENTS" -> global(service, AccessibilityService.GLOBAL_ACTION_RECENTS)
            else -> false to "Unknown gesture"
        }
    }

    private fun displaySize(service: AccessibilityService): Pair<Int, Int> {
        val metrics = service.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        return metrics.width() to metrics.height()
    }

    private fun nx(v: Double?, width: Int): Float = ((v ?: 0.0).coerceIn(0.0, 1.0) * width).toFloat()
    private fun ny(v: Double?, height: Int): Float = ((v ?: 0.0).coerceIn(0.0, 1.0) * height).toFloat()

    private fun stroke(
        service: AccessibilityService,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        duration: Long,
    ): Pair<Boolean, String?> {
        val path = Path().apply {
            moveTo(x1, y1)
            if (x1 != x2 || y1 != y2) lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration.coerceAtLeast(1))
        val ok = service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        return if (ok) true to null else false to "Gesture was not accepted"
    }

    private fun global(service: AccessibilityService, action: Int): Pair<Boolean, String?> {
        val ok = service.performGlobalAction(action)
        return if (ok) true to null else false to "Global action is not available"
    }
}
