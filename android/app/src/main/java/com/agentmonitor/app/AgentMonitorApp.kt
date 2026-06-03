package com.agentmonitor.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class AgentMonitorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MonitorEngine.init(applicationContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val alerts = NotificationChannel(
                CHANNEL_ALERTS,
                "Agent alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Agent offline and service health alerts" }
            val service = NotificationChannel(
                CHANNEL_SERVICE,
                "Background monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps daemon connections alive for alerts" }
            manager.createNotificationChannel(alerts)
            manager.createNotificationChannel(service)
        }
    }

    companion object {
        const val CHANNEL_ALERTS = "alerts"
        const val CHANNEL_SERVICE = "service"
    }
}
