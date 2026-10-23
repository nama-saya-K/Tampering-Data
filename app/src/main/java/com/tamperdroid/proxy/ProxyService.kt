package com.tamperdroid.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.tamperdroid.R

class ProxyService : Service() {

    companion object {
        const val EXTRA_PORT = "port"
        const val CHANNEL_ID = "tamperdroid_proxy"
        const val NOTIF_ID = 1
        @Volatile var isRunning = false
            private set
    }

    private var server: ProxyServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra(EXTRA_PORT, 8888) ?: 8888
        CertAuthority.init(applicationContext)

        createChannelIfNeeded()
        startForeground(NOTIF_ID, buildNotification(port))

        server = ProxyServer(port)
        server?.start()
        isRunning = true
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        isRunning = false
        super.onDestroy()
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Proxy running", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun buildNotification(port: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TamperDroid proxy running")
            .setContentText("Listening on 127.0.0.1:$port")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }
}
