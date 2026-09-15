package com.yomitanmobile.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yomitanmobile.MainActivity
import com.yomitanmobile.data.download.DictionaryDownloadManager
import com.yomitanmobile.data.download.DownloadPhase
import com.yomitanmobile.data.download.QueueState
import com.yomitanmobile.data.repository.FrequencyRecomputer
import com.yomitanmobile.util.LocaleHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the process alive while dictionaries install or frequency ranks are
 * recomputed.
 *
 * The work itself already runs on the application scope, so leaving a screen
 * does not stop it — but leaving the APP did: a minimised app with no
 * foreground component is the first thing Android freezes or kills, and a
 * 100 MB dictionary import takes minutes. This service does no work of its
 * own. It watches the download queue and the recomputer, holds a foreground
 * notification (with progress) and a partial wake lock while either has
 * something to do, and stops itself the moment both are idle.
 */
@AndroidEntryPoint
class BackgroundWorkService : Service() {

    @Inject lateinit var downloadManager: DictionaryDownloadManager
    @Inject lateinit var frequencyRecomputer: FrequencyRecomputer

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var lastStartId = 0
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Must happen on every start: startForegroundService() gives the
        // service a few seconds to call it or the app is killed.
        if (!goForeground(buildNotification(Status.Idle))) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        acquireWakeLock()
        if (watcher == null) watcher = serviceScope.launch { watch() }
        return START_NOT_STICKY
    }

    private suspend fun watch() {
        combine(
            downloadManager.queue,
            downloadManager.currentDownload,
            frequencyRecomputer.isRunning
        ) { queue, current, recomputing ->
            val active = queue.filter { it.state == QueueState.WAITING || it.state == QueueState.RUNNING }
            when {
                active.isNotEmpty() -> {
                    val running = active.firstOrNull { it.state == QueueState.RUNNING }
                    val progress = current?.takeIf { it.dictionaryId == running?.info?.id }
                    Status.Installing(
                        name = running?.info?.name ?: active.first().info.name,
                        waiting = active.count { it.state == QueueState.WAITING },
                        importing = progress?.phase == DownloadPhase.IMPORTING,
                        percent = progress?.takeIf { it.totalBytes > 0 }
                            ?.let { (it.progressPercent * 100).toInt().coerceIn(0, 100) }
                    )
                }
                recomputing -> Status.Recomputing
                else -> Status.Idle
            }
        }.distinctUntilChanged().collect { status ->
            if (status == Status.Idle) {
                // Only stops when no newer start arrived meanwhile; if one
                // did, its work is already visible in the flows above.
                if (stopSelfResult(lastStartId)) return@collect
            } else {
                notificationManager().notify(NOTIFICATION_ID, buildNotification(status))
            }
        }
    }

    /**
     * Android 15 caps dataSync services at six hours a day. The work keeps
     * running on the application scope; only the foreground guarantee ends.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        watcher = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    private fun goForeground(notification: android.app.Notification): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )
        true
    } catch (e: Exception) {
        // ForegroundServiceStartNotAllowedException and friends: the work
        // still runs, it just is not protected from being frozen.
        Log.w(TAG, "Could not enter the foreground", e)
        false
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YomitanMobile:background-work").apply {
            setReferenceCounted(false)
            // A ceiling, not an expected duration: released in onDestroy as
            // soon as the work is done.
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private sealed interface Status {
        data object Idle : Status
        data object Recomputing : Status
        data class Installing(
            val name: String,
            val waiting: Int,
            val importing: Boolean,
            val percent: Int?
        ) : Status
    }

    private fun tr(pl: String, en: String) = LocaleHelper.tr(resources.configuration, pl, en)

    private fun buildNotification(status: Status): android.app.Notification {
        ensureChannel()
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        when (status) {
            is Status.Installing -> {
                val phase = if (status.importing) tr("Importowanie", "Importing") else tr("Pobieranie", "Downloading")
                builder.setContentTitle("$phase: ${status.name}")
                if (status.waiting > 0) {
                    builder.setContentText(tr("W kolejce: ${status.waiting}", "Queued: ${status.waiting}"))
                }
                if (status.percent != null) {
                    builder.setProgress(100, status.percent, false)
                } else {
                    builder.setProgress(0, 0, true)
                }
            }
            Status.Recomputing -> builder
                .setContentTitle(tr("Przeliczanie rankingów częstotliwości…", "Recomputing frequency ranks…"))
                .setProgress(0, 0, true)
            Status.Idle -> builder
                .setContentTitle(tr("Praca w tle…", "Working in the background…"))
                .setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = notificationManager()
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                tr("Instalowanie słowników", "Dictionary installs"),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    companion object {
        private const val TAG = "BackgroundWorkService"
        private const val CHANNEL_ID = "background_work"
        private const val NOTIFICATION_ID = 4711
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60 * 60 * 1000

        /** Safe to call often and from anywhere the app is in the foreground. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, BackgroundWorkService::class.java)
                )
            } catch (e: Exception) {
                // Started from the background (e.g. an import finishing while
                // minimised): not allowed, and not needed — the work is
                // already under way.
                Log.w(TAG, "Could not start the background work service", e)
            }
        }
    }
}
