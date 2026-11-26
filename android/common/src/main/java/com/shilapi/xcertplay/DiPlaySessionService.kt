package com.shilapi.xcertplay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.shilapi.xcertplay.host.R

/** Keeps an explicitly started connection alive when another car app is in the foreground. */
class DiPlaySessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            CarPlayBackgroundSession.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "CarPlay 连接状态", NotificationManager.IMPORTANCE_LOW))
            Notification.Builder(this, CHANNEL)
        } else {
            // Android 7.x has no notification channels; the priority stands in for the channel importance.
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
        }
        val open = PendingIntent.getActivity(this, 0, CarPlayScreen.intent(this), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, DiPlaySessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = builder
            .setSmallIcon(R.drawable.ic_diplay_notification)
            .setContentTitle("思骑CarPlay")
            .setContentText("无线 CarPlay 连接运行中")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "断开", stop).build()).build()
        if (!startForegroundSafely(notification)) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /**
     * On Android 14/15, starting a foreground service with MICROPHONE or LOCATION from background
     * or over the lockscreen throws [SecurityException]. Fall back cleanly to CONNECTED_DEVICE
     * instead of crashing the shared gateway process.
     */
    private fun startForegroundSafely(notification: Notification): Boolean {
        if (Build.VERSION.SDK_INT < 29) {
            startForeground(1, notification)
            return true
        }
        var fullTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val needMic = !AirPlayPersistence.loadCarBluetoothAudio(this)
        if (needMic && Build.VERSION.SDK_INT >= 30 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            fullTypes = fullTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        // Without it Android stops location updates while another car app covers CarPlay.
        if (AirPlayPersistence.loadLocationReportingEnabled(this) &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fullTypes = fullTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        val primary = runCatching {
            startForeground(1, notification, fullTypes)
        }
        if (primary.isSuccess) return true
        Log.w(TAG, "Full-type startForeground rejected (${primary.exceptionOrNull()?.javaClass?.simpleName}); falling back to CONNECTED_DEVICE")
        if (fullTypes != ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) {
            val connectedOnly = runCatching {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            }
            if (connectedOnly.isSuccess) return true
            Log.w(TAG, "CONNECTED_DEVICE startForeground rejected (${connectedOnly.exceptionOrNull()?.javaClass?.simpleName})")
        }
        if (Build.VERSION.SDK_INT < 34) {
            return runCatching { startForeground(1, notification) }.isSuccess
        }
        return false
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (SiQiRelayBridge.isVirtualScreenEnabled(this) && CarPlayBackgroundSession.hasSession()) {
            // Keep Scheme A offscreen virtual CarPlay alive when user removes the UI task card.
            return
        }
        // BYD's recents force-stops the package ~10 ms after removing the task: end guidance first.
        com.shilapi.xcertplay.hud.BydNavigationOutputs.endNow()
        CarPlayBackgroundSession.stop()
        stopSelf()
    }
    companion object {
        private const val TAG = "DiPlaySessionService"
        const val ACTION_STOP = "com.shihab.diplay.DISCONNECT"
        private const val CHANNEL = "diplay_connection"
    }
}
