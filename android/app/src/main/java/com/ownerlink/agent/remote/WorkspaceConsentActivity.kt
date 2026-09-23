package com.ownerlink.agent.remote

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import com.ownerlink.agent.service.ConnectionService

/** Displays Android's own consent UI; Affiliate never bypasses or auto-accepts it. */
class WorkspaceConsentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    @Deprecated("Android result callback retained for Android 11/12 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CAPTURE && resultCode == RESULT_OK && data != null) {
            VirtualProjectionPermission.save(resultCode, data)
            ConnectionService.enableProjection(this)
            Toast.makeText(this, "Remote Workspace prepared. Keep Affiliate running.", Toast.LENGTH_LONG).show()
        } else {
            VirtualProjectionPermission.clear()
            Toast.makeText(this, "Remote Workspace permission was not granted.", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    companion object { private const val REQUEST_CAPTURE = 7102 }
}
