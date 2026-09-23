package com.ownerlink.agent.remote

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager

/** Process-local Android screen-sharing grant. It is intentionally never persisted. */
object VirtualProjectionPermission {
    @Volatile private var resultCode: Int? = null
    @Volatile private var resultData: Intent? = null

    fun save(code: Int, data: Intent) {
        require(code == Activity.RESULT_OK)
        resultCode = code
        resultData = Intent(data)
    }

    fun clear() {
        resultCode = null
        resultData = null
    }

    fun isGranted(): Boolean = resultCode == Activity.RESULT_OK && resultData != null

    fun create(context: Context): MediaProjection? {
        val code = resultCode ?: return null
        val data = resultData ?: return null
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        return manager.getMediaProjection(code, Intent(data))
    }
}
