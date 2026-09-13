package com.ownerlink.agent.affiliate

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ownerlink.agent.R

class AffiliateNotifications(private val context: Context) {
    fun show(event: String, message: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Affiliate task updates", NotificationManager.IMPORTANCE_DEFAULT))
        val title = when (event) {
            "TASK_PUBLISHED" -> "New task available"
            "REVISION_REQUESTED" -> "Revision requested"
            "COIN_CREDITED" -> "Coins credited"
            else -> "Task updated"
        }
        manager.notify(event.hashCode(), NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_privatchat_notification)
            .setContentTitle(title).setContentText(message).setAutoCancel(true).build())
    }
    companion object { private const val CHANNEL = "affiliate_tasks" }
}
