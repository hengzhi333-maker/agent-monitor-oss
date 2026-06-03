package com.agentmonitor.app.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.agentmonitor.app.AgentMonitorApp
import com.agentmonitor.app.MainActivity
import com.agentmonitor.app.R
import com.agentmonitor.app.data.Alert
import java.util.concurrent.atomic.AtomicInteger

class AlertNotifier(private val context: Context) {
    private val nextId = AtomicInteger(1000)

    fun show(alert: Alert) {
        if (!canPostNotifications(context)) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = alert.title.ifBlank { "Agent alert" }
        val body = alert.body.ifBlank { alert.agent.ifBlank { "Status changed" } }
        val priority = if (alert.level == "error") {
            NotificationCompat.PRIORITY_HIGH
        } else {
            NotificationCompat.PRIORITY_DEFAULT
        }

        val notification = NotificationCompat.Builder(context, AgentMonitorApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(priority)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(nextId.incrementAndGet(), notification)
        } catch (_: SecurityException) {
            // Permission can change while the app is running; keep monitoring alive.
        }
    }

    companion object {
        const val FOREGROUND_ID = 1001

        fun foregroundNotification(context: Context, text: String): Notification {
            return NotificationCompat.Builder(context, AgentMonitorApp.CHANNEL_SERVICE)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Agent Monitor is monitoring")
                .setContentText(text)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()
        }

        fun updateForeground(context: Context, text: String) {
            try {
                val manager = context.getSystemService(NotificationManager::class.java)
                manager.notify(FOREGROUND_ID, foregroundNotification(context, text))
            } catch (_: SecurityException) {
                // Notification permission can be revoked while the foreground service keeps running.
            }
        }

        private fun canPostNotifications(context: Context): Boolean {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
        }
    }
}
