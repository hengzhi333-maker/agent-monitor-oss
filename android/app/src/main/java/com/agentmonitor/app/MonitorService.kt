package com.agentmonitor.app

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.agentmonitor.app.notifications.AlertNotifier
import com.agentmonitor.app.ui.ConnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        MonitorEngine.init(applicationContext)
        MonitorEngine.start()
        startForegroundCompat(statusText())

        watchJob = scope.launch {
            MonitorEngine.ui
                .map { state -> state.hosts.count { it.connection == ConnState.Online } to state.hosts.size }
                .distinctUntilChanged()
                .collect { (online, total) ->
                    AlertNotifier.updateForeground(applicationContext, "$online/$total hosts online")
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MonitorEngine.start()
        return START_STICKY
    }

    override fun onDestroy() {
        watchJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun statusText(): String {
        val total = MonitorEngine.hosts().size
        val online = MonitorEngine.onlineCount()
        return "$online/$total hosts online"
    }

    private fun startForegroundCompat(text: String) {
        val notification = AlertNotifier.foregroundNotification(this, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                AlertNotifier.FOREGROUND_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(AlertNotifier.FOREGROUND_ID, notification)
        }
    }
}
