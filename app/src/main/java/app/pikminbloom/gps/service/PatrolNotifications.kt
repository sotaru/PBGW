package app.pikminbloom.gps.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import app.pikminbloom.gps.PikminGpsApp
import app.pikminbloom.gps.R
import app.pikminbloom.gps.data.PatrolPhase
import app.pikminbloom.gps.data.PatrolState
import app.pikminbloom.gps.ui.MainActivity

/** Builds the ongoing patrol notification and the one-shot event notifications. */
class PatrolNotifications(private val ctx: Context) {

    private val nm = ctx.getSystemService(NotificationManager::class.java)

    fun ongoing(state: PatrolState): Notification {
        val title = when (state.phase) {
            PatrolPhase.IDLE -> ctx.getString(R.string.svc_phase_idle)
            PatrolPhase.STARTING -> ctx.getString(R.string.svc_phase_starting)
            PatrolPhase.WALKING -> ctx.getString(R.string.svc_phase_walking, state.currentWaypointName ?: "…")
            PatrolPhase.DWELLING -> ctx.getString(R.string.svc_phase_dwelling, state.currentWaypointName ?: "…")
            PatrolPhase.PAUSED -> ctx.getString(R.string.svc_phase_paused)
            PatrolPhase.RETURNING_HOME -> ctx.getString(R.string.svc_phase_returning, formatDistance(state.distanceToTargetM))
            PatrolPhase.STOPPING -> ctx.getString(R.string.svc_phase_stopping)
            PatrolPhase.PARKED -> ctx.getString(R.string.svc_phase_parked)
            PatrolPhase.MANUAL -> ctx.getString(R.string.svc_phase_manual)
            PatrolPhase.HOLDING -> ctx.getString(R.string.phase_holding)
        }
        val vehicle = state.travelOverride?.let { " · ${it.label} ${it.speedKmh.toInt()} km/h" }.orEmpty()
        val text = ctx.getString(
            R.string.svc_status_line,
            formatDistance(state.distanceWalkedM),
            state.sessionSteps,
            state.stepsWrittenToday,
        ) + vehicle
        val b = NotificationCompat.Builder(ctx, PikminGpsApp.CHANNEL_PATROL)
            .setSmallIcon(R.drawable.ic_flower)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp())
        when (state.phase) {
            PatrolPhase.PAUSED, PatrolPhase.PARKED -> b.addAction(0, ctx.getString(R.string.svc_action_resume), service(PatrolService.ACTION_RESUME))
            PatrolPhase.WALKING, PatrolPhase.DWELLING, PatrolPhase.MANUAL -> b.addAction(0, ctx.getString(R.string.svc_action_pause), service(PatrolService.ACTION_PAUSE))
            else -> Unit
        }
        if (state.home != null && state.phase != PatrolPhase.RETURNING_HOME && state.phase != PatrolPhase.STOPPING && state.phase != PatrolPhase.PARKED) {
            b.addAction(0, ctx.getString(R.string.svc_action_home), service(PatrolService.ACTION_RETURN_HOME))
        }
        b.addAction(0, ctx.getString(R.string.svc_action_stop), service(PatrolService.ACTION_STOP))
        return b.build()
    }

    /** @param vibrate false keeps the alert silent; the vibration motor is a real battery cost. */
    fun arrived(name: String, vibrate: Boolean) {
        notifyEvent(
            ID_ARRIVED,
            ctx.getString(R.string.svc_arrived_title, name),
            ctx.getString(R.string.svc_arrived_text),
        )
        if (vibrate) vibrate(longArrayOf(0, 220))
    }

    fun returnedHome(vibrate: Boolean = true) {
        notifyEvent(ID_HOME, ctx.getString(R.string.svc_home_title), ctx.getString(R.string.svc_home_text))
        // Arriving home happens once per session, so a short double buzz is affordable.
        if (vibrate) vibrate(longArrayOf(0, 200, 120, 200))
    }

    /** Reached the user-chosen home; the mock is still on, which the text must make clear. */
    fun parkedAtHome(vibrate: Boolean) {
        notifyEvent(ID_HOME, ctx.getString(R.string.svc_parked_title), ctx.getString(R.string.svc_parked_text))
        if (vibrate) vibrate(longArrayOf(0, 200, 120, 200))
    }

    fun error(message: String) {
        notifyEvent(ID_ERROR, ctx.getString(R.string.svc_error_title), message)
    }

    fun cancelOngoing() = nm.cancel(ID_ONGOING)

    private fun notifyEvent(id: Int, title: String, text: String) {
        val n = NotificationCompat.Builder(ctx, PikminGpsApp.CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_flower)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp())
            .build()
        runCatching { nm.notify(id, n) }
    }

    private fun vibrate(pattern: LongArray) {
        try {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
            }
            v?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Throwable) {
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun service(action: String): PendingIntent = PendingIntent.getForegroundService(
        ctx, action.hashCode(),
        Intent(ctx, PatrolService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ID_ONGOING = 1
        const val ID_ARRIVED = 2
        const val ID_HOME = 3
        const val ID_ERROR = 4

        fun formatDistance(m: Double): String =
            if (m >= 1000) "%.2f km".format(m / 1000) else "%.0f m".format(m)
    }
}
