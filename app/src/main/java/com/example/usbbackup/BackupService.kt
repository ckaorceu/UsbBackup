package com.example.usbbackup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.File

class BackupService : Service() {

    companion object {
        const val CHANNEL_ID = "backup_channel"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_SOURCE = "extra_source"
        const val EXTRA_DEST_URI = "extra_dest_uri"

        var isRunning = false
            private set
    }

    private val binder = LocalBinder()
    private var engine: BackupEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // UI 回调
    var progressListener: BackupProgressListener? = null

    inner class LocalBinder : Binder() {
        fun getService(): BackupService = this@BackupService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sourcePath = intent?.getStringExtra(EXTRA_SOURCE) ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        val destUriStr = intent.getStringExtra(EXTRA_DEST_URI) ?: run {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("准备备份..."))
        isRunning = true

        // 获取 WakeLock 防止备份过程中休眠
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UsbBackup::BackupLock").apply {
            acquire(30 * 60 * 1000L) // 最多 30 分钟
        }

        val sourceDir = File(sourcePath)
        val destUri = Uri.parse(destUriStr)

        Thread {
            engine = BackupEngine(this)
            engine!!.backup(sourceDir, destUri, object : BackupProgressListener {
                override fun onFileStart(name: String, index: Int, total: Int) {
                    updateNotification("($index/$total) $name")
                    progressListener?.onFileStart(name, index, total)
                }

                override fun onFileCopied(bytes: Long) {
                    progressListener?.onFileCopied(bytes)
                }

                override fun onSkipped(name: String) {
                    progressListener?.onSkipped(name)
                }

                override fun onError(name: String, message: String) {
                    progressListener?.onError(name, message)
                }

                override fun onFinished(copied: Int, skipped: Int, failed: Int, totalBytes: Long) {
                    val msg = "备份完成: 复制 $copied, 跳过 $skipped, 失败 $failed"
                    updateNotification(msg)
                    progressListener?.onFinished(copied, skipped, failed, totalBytes)
                    releaseAndStop()
                }
            })
        }.start()

        return START_NOT_STICKY
    }

    fun cancel() {
        engine?.cancelled = true
    }

    private fun releaseAndStop() {
        isRunning = false
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "备份服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "显示备份进度"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("USB 备份")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
