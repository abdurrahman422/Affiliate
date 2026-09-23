package com.ownerlink.agent.remote

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import com.ownerlink.agent.service.ConnectionService

class ScreenMirrorConsentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    @Deprecated("Activity result retained for Android 12 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CAPTURE && ScreenProjectionPermission.grant(this, resultCode, data)) {
            ConnectionService.enableProjection(this)
            Toast.makeText(this, "Remote Screen is ready", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Screen sharing permission was not granted", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    companion object { private const val REQUEST_CAPTURE = 9042 }
}
