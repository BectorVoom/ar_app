package com.example.arspatialpinning.platform.media

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

class RecordingService : Service() {
    private val notificationFactory by lazy { RecordingNotificationFactory(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                notificationFactory.ensureChannel()
                val notification = notificationFactory.createForegroundNotification()
                startForeground(
                    RecordingNotificationFactory.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
                setForegroundReady(true)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        setForegroundReady(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "com.example.arspatialpinning.action.START_RECORDING_SERVICE"
        private val foregroundReadyFlag = AtomicBoolean(false)

        fun start(context: Context) {
            setForegroundReady(false)
            val intent = Intent(context, RecordingService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            setForegroundReady(false)
            context.stopService(Intent(context, RecordingService::class.java))
        }

        fun isForegroundReady(): Boolean = foregroundReadyFlag.get()

        private fun setForegroundReady(value: Boolean) {
            foregroundReadyFlag.set(value)
        }
    }
}
