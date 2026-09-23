package com.ownerlink.agent.remote

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.ownerlink.agent.accessibility.OwnerLinkAccessibilityService
import com.ownerlink.agent.network.BinaryFrames
import com.ownerlink.agent.network.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Experimental secondary-display workspace. It does not mirror or take over the physical display:
 * only activities explicitly launched onto this app-owned display are captured.
 */
class VirtualWorkspaceManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val deviceId: String,
    private val sendJson: (String) -> Unit,
    private val sendBytes: (ByteArray) -> Unit,
) {
    private val width = 720
    private val height = 1600
    private val density = 320
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var projection: MediaProjection? = null
    private var streamJob: Job? = null
    private var sequence = 0
    private val streamId = UUID(0, 1)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Synchronized
    fun start(fps: Int = 1, quality: Int = 60): JsonObject {
        display?.let { existing ->
            return state(true, existing.display.displayId, "Remote workspace already running")
        }
        check(context.packageManager.hasSystemFeature("android.software.activities_on_secondary_displays")) {
            "This phone does not support activities on secondary displays"
        }
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        val activeProjection = VirtualProjectionPermission.create(context)
            ?: run {
                imageReader.close()
                error("Open Affiliate Settings and tap Prepare Remote Workspace on the phone first")
            }
        activeProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                synchronized(this@VirtualWorkspaceManager) {
                    streamJob?.cancel(); streamJob = null
                    display?.release(); display = null
                    reader?.close(); reader = null
                    projection = null
                    VirtualProjectionPermission.clear()
                }
                status("CONSENT_ENDED", "Android ended the Remote Workspace permission", null)
            }
        }, mainHandler)
        val virtualDisplay = activeProjection.createVirtualDisplay(
            "Affiliate Remote Workspace",
            width,
            height,
            density,
            flags,
            imageReader.surface,
            null,
            null,
        ) ?: run {
            imageReader.close()
            activeProjection.stop()
            error("Android rejected the secondary display")
        }
        projection = activeProjection
        reader = imageReader
        display = virtualDisplay
        launchHome()
        startFrames(fps.coerceIn(1, 2), quality.coerceIn(35, 80))
        status("READY", "Remote workspace started", virtualDisplay.display.displayId)
        return state(true, virtualDisplay.display.displayId, "Remote workspace started")
    }

    @Synchronized
    fun stop(): JsonObject {
        streamJob?.cancel()
        streamJob = null
        display?.release()
        display = null
        reader?.close()
        reader = null
        projection?.stop()
        projection = null
        VirtualProjectionPermission.clear()
        status("STOPPED", "Remote workspace stopped", null)
        return state(false, null, "Remote workspace stopped")
    }

    fun listApps(): JsonObject {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = context.packageManager.queryIntentActivities(launcher, 0)
            .asSequence()
            .map {
                buildJsonObject {
                    put("package", it.activityInfo.packageName)
                    put("label", it.loadLabel(context.packageManager).toString())
                }
            }
            .distinctBy { it["package"].toString() }
            .sortedBy { it["label"].toString().lowercase() }
            .toList()
        return buildJsonObject { put("apps", JsonArray(apps)) }
    }

    fun launchPackage(packageName: String): JsonObject {
        val active = display ?: error("Start the remote workspace first")
        val allowed = listLaunchablePackages()
        require(packageName in allowed) { "App is not launchable on this phone" }
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: error("App has no launcher activity")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        context.startActivity(intent, activityOptions(active.display.displayId))
        status("APP_LAUNCHED", "App opened in remote workspace", active.display.displayId)
        return buildJsonObject { put("launched", true); put("package", packageName) }
    }

    fun launchHome(): JsonObject {
        val active = display ?: error("Start the remote workspace first")
        val intent = Intent(context, WorkspaceHomeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        context.startActivity(intent, activityOptions(active.display.displayId))
        return buildJsonObject { put("home", true) }
    }

    fun gesture(payload: JsonObject): Pair<Boolean, String?> {
        val active = display ?: return false to "Start the remote workspace first"
        return VirtualGestureDispatcher.dispatch(payload, active.display.displayId, width, height)
    }

    fun isRunning(): Boolean = display != null

    fun displayId(): Int? = display?.display?.displayId

    private fun startFrames(fps: Int, quality: Int) {
        streamJob?.cancel()
        streamJob = scope.launch {
            val interval = 1000L / fps
            delay(350)
            while (isActive && display != null) {
                val frame = capture(quality)
                if (frame != null) {
                    sequence += 1
                    val payload = BinaryFrames.packScreen(width, height, 0, frame)
                    sendBytes(BinaryFrames.frame(BinaryFrames.KIND_WORKSPACE, streamId, sequence, payload, last = true))
                }
                delay(interval)
            }
        }
    }

    private fun capture(quality: Int): ByteArray? {
        val image = reader?.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes.first()
            val rowPadding = plane.rowStride - plane.pixelStride * width
            val paddedWidth = width + rowPadding / plane.pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            plane.buffer.rewind()
            padded.copyPixelsFromBuffer(plane.buffer)
            val cropped = if (paddedWidth == width) padded else Bitmap.createBitmap(padded, 0, 0, width, height)
            val out = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (cropped !== padded) cropped.recycle()
            padded.recycle()
            out.toByteArray()
        } finally {
            image.close()
        }
    }

    private fun activityOptions(displayId: Int): BundleCompat = BundleCompat(
        ActivityOptions.makeBasic().apply { launchDisplayId = displayId }.toBundle(),
    )

    private fun listLaunchablePackages(): Set<String> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(launcher, 0).map { it.activityInfo.packageName }.toSet()
    }

    private fun status(code: String, message: String, displayId: Int?) {
        sendJson(
            Protocol.envelope(
                Protocol.VIRTUAL_WORKSPACE_STATUS,
                deviceId,
                buildJsonObject {
                    put("code", code)
                    put("message", message)
                    if (displayId != null) put("display_id", displayId)
                },
            ),
        )
    }

    private fun state(running: Boolean, displayId: Int?, message: String) = buildJsonObject {
        put("running", running)
        put("supported", context.packageManager.hasSystemFeature("android.software.activities_on_secondary_displays"))
        put("android", Build.VERSION.SDK_INT)
        if (displayId != null) put("display_id", displayId)
        put("message", message)
    }

    /** Keeps the Android Bundle overload explicit and easy to unit-replace. */
    private data class BundleCompat(val value: android.os.Bundle)

    private fun Context.startActivity(intent: Intent, options: BundleCompat) {
        startActivity(intent, options.value)
    }
}
