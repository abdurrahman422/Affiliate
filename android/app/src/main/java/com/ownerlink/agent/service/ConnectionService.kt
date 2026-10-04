package com.ownerlink.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ownerlink.agent.MainActivity
import com.ownerlink.agent.OwnerLinkApp
import com.ownerlink.agent.R
import com.ownerlink.agent.util.NetworkMonitor

class ConnectionService : Service() {
    private var networkMonitor: NetworkMonitor? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val app = application as OwnerLinkApp
        networkMonitor = NetworkMonitor(this) {
            app.socket.retryNow()
        }.also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val app = application as OwnerLinkApp
            app.store.serviceWanted = false
            app.socket.disconnect(user = true)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            val locationGranted = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val locationType = if (locationGranted) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or locationType
            startForeground(NOTIF_ID, notification, type)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        val app = application as OwnerLinkApp
        app.store.serviceWanted = true
        app.socket.connect()
        return START_STICKY
    }

    override fun onDestroy() {
        networkMonitor?.stop()
        super.onDestroy()
    }

    private fun createChannel() {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "Privacy Chat connection", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_privatchat_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.ownerlink.agent.STOP"
        private const val CHANNEL = "ownerlink_remote"
        private const val NOTIF_ID = 42

        fun start(context: Context) {
            val app = context.applicationContext as OwnerLinkApp
            val intent = Intent(context, ConnectionService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                android.util.Log.e("OwnerLink", "foreground service start failed", e)
                app.socket.connect()
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ConnectionService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }


    }
}
