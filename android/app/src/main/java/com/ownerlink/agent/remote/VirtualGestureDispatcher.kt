package com.ownerlink.agent.remote

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.ownerlink.agent.accessibility.OwnerLinkAccessibilityService
import com.ownerlink.agent.network.int
import com.ownerlink.agent.network.optDouble
import com.ownerlink.agent.network.str
import kotlinx.serialization.json.JsonObject

object VirtualGestureDispatcher {
    fun dispatch(payload: JsonObject, displayId: Int, width: Int, height: Int): Pair<Boolean, String?> {
        val service = OwnerLinkAccessibilityService.instance
            ?: return false to "Enable Affiliate remote workspace control in Android Accessibility settings"
        return when ((payload.str("gesture") ?: "").uppercase()) {
            "TAP" -> stroke(service, displayId, point(payload.optDouble("x"), width), point(payload.optDouble("y"), height), null, null, 50)
            "LONG_PRESS" -> stroke(service, displayId, point(payload.optDouble("x"), width), point(payload.optDouble("y"), height), null, null, (payload.int("duration_ms") ?: 700).coerceIn(400, 2000).toLong())
            "SWIPE" -> stroke(service, displayId, point(payload.optDouble("x1"), width), point(payload.optDouble("y1"), height), point(payload.optDouble("x2"), width), point(payload.optDouble("y2"), height), (payload.int("duration_ms") ?: 350).coerceIn(80, 1200).toLong())
            else -> false to "Only tap, long-press and swipe are supported on the isolated display"
        }
    }

    private fun point(value: Double?, size: Int) = ((value ?: 0.0).coerceIn(0.0, 1.0) * size).toFloat()

    private fun stroke(
        service: android.accessibilityservice.AccessibilityService,
        displayId: Int,
        x1: Float,
        y1: Float,
        x2: Float?,
        y2: Float?,
        duration: Long,
    ): Pair<Boolean, String?> {
        val path = Path().apply { moveTo(x1, y1); if (x2 != null && y2 != null) lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .setDisplayId(displayId)
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration.coerceAtLeast(1)))
            .build()
        return if (service.dispatchGesture(gesture, null, null)) true to null
        else false to "Android rejected input on the secondary display"
    }
}
