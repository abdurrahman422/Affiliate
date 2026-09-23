package com.ownerlink.agent.remote

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager

/** Process-local, user-approved physical-screen capture grant. Never persisted. */
object ScreenProjectionPermission {
    @Volatile private var active: MediaProjection? = null

    fun grant(context: Context, resultCode: Int, data: Intent?): Boolean {
        if (resultCode != Activity.RESULT_OK || data == null) return false
        active?.stop()
        active = (context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
            .getMediaProjection(resultCode, data)
        return active != null
    }

    fun get(): MediaProjection? = active
    fun isGranted(): Boolean = active != null

    fun clear(projection: MediaProjection? = null) {
        if (projection == null || active === projection) active = null
    }
}
