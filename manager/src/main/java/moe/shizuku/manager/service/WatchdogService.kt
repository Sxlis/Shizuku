package moe.shizuku.manager.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import moe.shizuku.manager.R
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.receiver.ShizukuReceiverStarter
import moe.shizuku.manager.utils.SettingsPage
import moe.shizuku.manager.utils.ShizukuStateMachine
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class WatchdogService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var crashCheckJob: Job? = null

    /**
     * True from the moment a crash is seen until the recovery attempt ends, so that only one
     * attempt runs at a time.
     */
    @Volatile
    private var recoveryInProgress = false

    /**
     * True once the grace period expired and a restart was actually requested. A server that
     * comes back before that is a blip (toggling wireless debugging restarts adbd) and must
     * stay invisible, so only an attempt that got this far is reported.
     */
    @Volatile
    private var restartRequested = false

    private val stateListener: (ShizukuStateMachine.State) -> Unit = { state ->
        when (state) {
            ShizukuStateMachine.State.CRASHED -> handleCrashed()

            ShizukuStateMachine.State.RUNNING -> {
                // Reported here instead of from the coroutine: ShizukuStateMachine's own
                // binder received listener reaches this branch first and cancels the
                // coroutine, so code after set(RUNNING) in there may never run.
                val recovered = recoveryInProgress && restartRequested
                recoveryInProgress = false
                restartRequested = false
                crashCheckJob?.cancel()
                if (recovered) showRecoveredNotification()
            }

            ShizukuStateMachine.State.STOPPED -> {
                // Stopped on purpose: never reported.
                recoveryInProgress = false
                restartRequested = false
                crashCheckJob?.cancel()
            }

            // STARTING / STOPPING: a start or a stop is still in flight.
            else -> Unit
        }
    }

    private fun handleCrashed() {
        if (recoveryInProgress) return

        recoveryInProgress = true
        restartRequested = false
        crashCheckJob?.cancel()
        crashCheckJob = scope.launch {
            try {
                // Toggling wireless debugging restarts adbd, which kills the server and brings
                // it back within a second, so give it a moment before spending a real attempt.
                delay(RECOVERY_GRACE_MS)

                if (Shizuku.pingBinder()) {
                    // Back on its own: no restart was requested, so the state listener stays
                    // silent.
                    ShizukuStateMachine.set(ShizukuStateMachine.State.RUNNING)
                    return@launch
                }

                restartRequested = true
                val startResult = ShizukuReceiverStarter.start(applicationContext)

                // Watch the recovery for a bounded window. The notification for a successful
                // restart is posted by the state listener, not from here.
                val deadline = System.currentTimeMillis() + RECOVERY_TIMEOUT_MS
                while (System.currentTimeMillis() < deadline) {
                    delay(RECOVERY_POLL_MS)

                    if (Shizuku.pingBinder()) {
                        ShizukuStateMachine.set(ShizukuStateMachine.State.RUNNING)
                        return@launch
                    }

                    if (ShizukuStateMachine.get() == ShizukuStateMachine.State.STOPPED) {
                        // The user stopped it on purpose while we were waiting.
                        return@launch
                    }
                }

                if (Shizuku.pingBinder()) {
                    ShizukuStateMachine.set(ShizukuStateMachine.State.RUNNING)
                } else if (ShizukuStateMachine.get() != ShizukuStateMachine.State.STOPPED &&
                    shouldReportFailure(startResult)
                ) {
                    showFailureNotification()
                }
            } finally {
                recoveryInProgress = false
                restartRequested = false
            }
        }
    }

    /**
     * A restart in ADB mode is carried out by [ShizukuReceiverStarter]'s background worker, which
     * keeps retrying (Wi-Fi off, device locked, ...) and reports through its own notification, so
     * the watchdog running out of patience is not a failure the user has to act on. Nothing is
     * reported either when a more specific notification was already posted, or when no start was
     * attempted at all.
     */
    private fun shouldReportFailure(result: ShizukuReceiverStarter.StartResult): Boolean =
        when (result) {
            ShizukuReceiverStarter.StartResult.ROOT_STARTED,
            ShizukuReceiverStarter.StartResult.UNSUPPORTED -> true

            ShizukuReceiverStarter.StartResult.ADB_ENQUEUED,
            ShizukuReceiverStarter.StartResult.NO_PERMISSION,
            ShizukuReceiverStarter.StartResult.SKIPPED -> false
        }

    override fun onCreate() {
        super.onCreate()
        isRunning.set(true)
        ShizukuStateMachine.addListener(stateListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_STOP_SERVICE") {
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID_WATCHDOG,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(
                NOTIFICATION_ID_WATCHDOG,
                buildNotification()
            )
        }
        return START_STICKY
    }

    override fun onDestroy() {
        crashCheckJob?.cancel()
        scope.cancel()
        ShizukuStateMachine.removeListener(stateListener)
        isRunning.set(false)
        ShizukuSettings.setWatchdog(applicationContext, false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val channelId = "shizuku_watchdog"
        val channelName = "Watchdog"

        val channel = NotificationChannel(
            channelId,
            channelName,
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or 
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        val launchPendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, WatchdogService::class.java).apply {
            action = "ACTION_STOP_SERVICE"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.watchdog_running))
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentIntent(launchPendingIntent)
            .addAction(
                R.drawable.ic_close_24,
                getString(R.string.watchdog_turn_off),
                stopPendingIntent
            )
            .setOngoing(true)
            .build()
    }

    /** The service crashed and the watchdog brought it back. */
    private fun showRecoveredNotification() {
        showWatchdogNotification(
            R.string.watchdog_shizuku_crashed_title,
            R.string.watchdog_shizuku_crashed_text,
        )
    }

    /** The service stopped and the automatic restart did not work. */
    private fun showFailureNotification() {
        showWatchdogNotification(
            R.string.watchdog_shizuku_failed_title,
            R.string.watchdog_shizuku_failed_text,
        )
    }

    private fun showWatchdogNotification(titleRes: Int, textRes: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CRASH_CHANNEL_ID,
            "Crash Reports",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        nm.createNotificationChannel(channel)

        val learnMoreIntent = Intent(Intent.ACTION_VIEW).apply {
            setData(Uri.parse("https://github.com/thedjchi/Shizuku/wiki#shizuku-keeps-stopping-randomly"))
        }
        val learnMorePendingIntent = PendingIntent.getActivity(
            this, 0, learnMoreIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val disableIntent = SettingsPage.Notifications.NotificationChannel.buildIntent(applicationContext)
        val disablePendingIntent = PendingIntent.getActivity(
            this, 0, disableIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CRASH_CHANNEL_ID)
            .setContentTitle(getString(titleRes))
            .setContentText(getString(textRes))
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentIntent(learnMorePendingIntent)
            .setAutoCancel(true)
            .addAction(0, getString(R.string.watchdog_shizuku_crashed_action_turn_off_alerts), disablePendingIntent)
            .build()

        nm.notify(NOTIFICATION_ID_CRASH, notification)
    }

    companion object {
        private const val TAG = "ShizukuWatchdog"
        private const val NOTIFICATION_ID_WATCHDOG = 1001
        private const val NOTIFICATION_ID_CRASH = 1002

        /** Time allowed for the server to come back by itself before a recovery is started. */
        private const val RECOVERY_GRACE_MS = 2000L

        /** How long the automatic restart gets before the failure is reported. */
        private const val RECOVERY_TIMEOUT_MS = 15_000L

        private const val RECOVERY_POLL_MS = 1000L
        const val CRASH_CHANNEL_ID = "crash_reports"

        private val isRunning = AtomicBoolean(false)

        @JvmStatic
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, WatchdogService::class.java))
            } catch (e: Exception) {
                Log.e("ShizukuApplication", "Failed to start WatchdogService: ${e.message}" )
            }
        }

        @JvmStatic
        fun stop(context: Context) {
            context.stopService(Intent(context, WatchdogService::class.java))
        }

        @JvmStatic
        fun isRunning(): Boolean = isRunning.get()
    }
}