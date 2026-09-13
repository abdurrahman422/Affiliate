package com.ownerlink.agent.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class CallLogItem(
    val id: String,
    val number: String,
    val displayName: String?,
    val direction: String,
    val timestamp: Long,
    val durationSeconds: Long,
)

object CallLogRules {
    fun direction(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "incoming"
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        CallLog.Calls.MISSED_TYPE -> "missed"
        else -> "other"
    }

    fun page(items: List<CallLogItem>, cursor: Int, limit: Int): Pair<List<CallLogItem>, Int?> {
        val start = cursor.coerceAtLeast(0).coerceAtMost(items.size)
        val safeLimit = limit.coerceIn(1, 100)
        val result = items.drop(start).take(safeLimit)
        val next = (start + result.size).takeIf { it < items.size }
        return result to next
    }
}

class CallLogRepository(private val context: Context) {
    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    fun list(cursor: Int, limit: Int, filter: String?): kotlinx.serialization.json.JsonObject {
        if (!hasPermission()) return buildJsonObject {
            put("ok", false); put("code", "CALL_LOG_PERMISSION_REQUIRED")
            put("message", "Call Log permission is required on this device.")
        }
        val allowedFilter = filter?.lowercase()?.takeIf { it in setOf("incoming", "outgoing", "missed") }
        val rows = mutableListOf<CallLogItem>()
        val projection = arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION)
        context.contentResolver.query(CallLog.Calls.CONTENT_URI, projection, null, null, "${CallLog.Calls.DATE} DESC")?.use { c ->
            val id = c.getColumnIndexOrThrow(CallLog.Calls._ID)
            val number = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val name = c.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
            val type = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val date = c.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val duration = c.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            while (c.moveToNext()) {
                val direction = CallLogRules.direction(c.getInt(type))
                if (allowedFilter == null || allowedFilter == direction) rows += CallLogItem(
                    c.getLong(id).toString(), c.getString(number).orEmpty(), c.getString(name), direction,
                    c.getLong(date), c.getLong(duration),
                )
            }
        }
        val (page, next) = CallLogRules.page(rows, cursor, limit)
        return buildJsonObject {
            put("ok", true)
            put("items", JsonArray(page.map { item -> buildJsonObject {
                put("id", item.id); put("number", item.number)
                item.displayName?.let { put("display_name", it) }
                put("direction", item.direction); put("timestamp", item.timestamp); put("duration_seconds", item.durationSeconds)
            } }))
            next?.let { put("next_cursor", it.toString()) }
        }
    }
}
