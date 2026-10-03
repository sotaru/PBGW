package app.pikminbloom.gps.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.pikminbloom.gps.PikminGpsApp
import app.pikminbloom.gps.R
import app.pikminbloom.gps.data.Prefs
import app.pikminbloom.gps.geo.LatLng
import app.pikminbloom.gps.geo.GpsMotion
import app.pikminbloom.gps.geo.GpsMotionAnalyzer
import app.pikminbloom.gps.mock.MockLocationController
import app.pikminbloom.gps.steps.StepFlushProgress
import app.pikminbloom.gps.steps.StepInjector
import app.pikminbloom.gps.steps.StepWriteOutbox
import app.pikminbloom.gps.steps.TimedStepCounter
import app.pikminbloom.gps.ui.MainActivity
import app.pikminbloom.gps.ui.OverlayService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant
import java.time.ZoneId

enum class RealStepsPhase { IDLE, STARTING, RUNNING, PAUSED, FINISHING, COMPLETED, STOPPED }

data class RealStepsState(
    val phase: RealStepsPhase = RealStepsPhase.IDLE,
    val sessionId: Long = 0,
    val target: Long = 0,
    val rate: Int = 0,
    val generated: Long = 0,
    val written: Long = 0,
    val pending: Long = 0,
    val position: LatLng? = null,
    val error: String? = null,
    val jitterPct: Double = 0.0,
    val currentRate: Double = 0.0,
    val motion: GpsMotion = GpsMotion.UNKNOWN,
    val gpsAccuracyM: Double? = null,
) {
    val active: Boolean get() = phase in setOf(RealStepsPhase.STARTING, RealStepsPhase.RUNNING, RealStepsPhase.PAUSED, RealStepsPhase.FINISHING)
}

/** Time-based, user-entered steps. Never installs or pushes a mock GPS provider. */
class RealGpsStepsService : LifecycleService() {
    private lateinit var injector: StepInjector
    private lateinit var prefs: Prefs
    private lateinit var counter: TimedStepCounter
    private val lock = Mutex()
    // Both modes drain the same durable queue, while service guards prevent concurrent writers.
    private val outbox by lazy { StepWriteOutbox(File(noBackupFilesDir, "step_outbox.json")) }
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var queued = 0L
    private var windowStart = Instant.now()
    private var lastTick = 0L
    private var lastFlush = 0L
    private var lastNotification = 0L
    private var capReached = false
    private val motionAnalyzer = GpsMotionAnalyzer()
    private val locationManager by lazy { getSystemService(LocationManager::class.java) }
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val mocked = if (Build.VERSION.SDK_INT >= 31) location.isMock else {
                @Suppress("DEPRECATION") location.isFromMockProvider
            }
            if (!mocked && isRunning) {
                val point = LatLng(location.latitude, location.longitude)
                val movement = motionAnalyzer.addFix(GpsMotionAnalyzer.Fix(
                    position = point,
                    elapsedMs = location.elapsedRealtimeNanos / 1_000_000,
                    accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else Double.NaN,
                    speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
                    speedAccuracyMps = if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond.toDouble() else null,
                ), SystemClock.elapsedRealtime())
                _state.update { it.copy(position = point, motion = movement.motion, gpsAccuracyM = movement.accuracyM) }
                prefs.lastPosition = point
            }
        }
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) {
            motionAnalyzer.reset()
            _state.update { it.copy(position = null, motion = GpsMotion.UNKNOWN, gpsAccuracyM = null) }
        }
        @Deprecated("Deprecated in Android")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    override fun onCreate() {
        super.onCreate()
        injector = StepInjector(this)
        prefs = Prefs(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == START) {
            val target = intent.getLongExtra("target", 0)
            val rate = intent.getIntExtra("rate", 0)
            if (PatrolService.isRunning || target !in 1..TimedStepCounter.MAX_TARGET || rate !in 1..TimedStepCounter.MAX_RATE ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                stopSelf()
                return START_NOT_STICKY
            }
            if (!foreground()) return START_NOT_STICKY
            if (isRunning) return START_NOT_STICKY
            _state.value = RealStepsState(RealStepsPhase.STARTING, System.currentTimeMillis(), target, rate)
            loop = lifecycleScope.launch {
                lock.withLock {
                    try {
                        check(injector.isAvailable && injector.grantedPermissions().containsAll(injector.stepPermissions)) {
                            getString(R.string.real_steps_health_required)
                        }
                        val config = prefs.config()
                        val maxCadence = config.maxCadenceSpm
                        check(maxCadence == 0 || rate <= maxCadence) { getString(R.string.real_steps_cadence_limit, maxCadence) }
                        // Discharge this app's old test providers, including after a process death.
                        MockLocationController(this@RealGpsStepsService).releaseForRealLocation()
                        PatrolCheckpoint.clear(this@RealGpsStepsService)
                        PatrolService.setJoystickEnabled(false)
                        OverlayService.stop(this@RealGpsStepsService)
                        counter = TimedStepCounter(target, rate, config.speedJitterPct, maxCadence)
                        counter.paused = intent.getBooleanExtra("start_paused", false)
                        acquireWakeLock()
                        startRealGps()
                        queued = 0
                        windowStart = Instant.now()
                        lastTick = SystemClock.elapsedRealtime()
                        lastFlush = lastTick
                        _state.update { it.copy(phase = if (counter.paused) RealStepsPhase.PAUSED else RealStepsPhase.RUNNING,
                            jitterPct = counter.jitterPct, currentRate = counter.currentRate) }
                        flush(force = true)
                    } catch (e: Exception) {
                        end(getString(R.string.real_steps_start_failed, e.message.orEmpty()))
                    }
                }
                while (isActive && isRunning) {
                    delay(1000)
                    lock.withLock {
                        try {
                            tick()
                        } catch (e: Exception) {
                            _state.update { it.copy(error = e.message ?: getString(R.string.steps_pending_retry)) }
                        }
                    }
                }
            }
        } else if (isRunning) {
            lifecycleScope.launch {
                lock.withLock {
                    if (!::counter.isInitialized) return@withLock
                    when (intent?.action) {
                        PAUSE -> if (state.value.phase == RealStepsPhase.RUNNING) {
                            accrue()
                            counter.paused = true
                            _state.update { it.copy(phase = RealStepsPhase.PAUSED) }
                        }
                        RESUME -> if (state.value.phase == RealStepsPhase.PAUSED) {
                            lastTick = SystemClock.elapsedRealtime()
                            counter.paused = false
                            _state.update { it.copy(phase = RealStepsPhase.RUNNING) }
                        }
                        STOP -> {
                            accrue()
                            runCatching { flush(force = true) }.onFailure { _state.update { s -> s.copy(error = it.message) } }
                            end()
                        }
                    }
                    updateNotification()
                }
            }
        } else stopSelf()
        return START_NOT_STICKY
    }

    private fun accrue() {
        val now = SystemClock.elapsedRealtime()
        val config = prefs.config()
        val movement = motionAnalyzer.snapshot(now)
        counter.updateRateVariation(config.speedJitterPct, config.maxCadenceSpm, movement.motion)
        if (state.value.phase == RealStepsPhase.RUNNING) counter.advance((now - lastTick).coerceIn(0, 3000))
        lastTick = now
        _state.update { it.copy(generated = counter.generated, pending = counter.generated - it.written,
            jitterPct = counter.jitterPct, currentRate = counter.currentRate,
            motion = movement.motion, gpsAccuracyM = movement.accuracyM) }
    }

    private suspend fun tick() {
        if (!isRunning) return
        accrue()
        if (counter.complete) _state.update { it.copy(phase = RealStepsPhase.FINISHING) }
        flush(force = counter.complete && counter.generated > queued)
        when {
            capReached -> end(getString(R.string.real_steps_daily_limit))
            state.value.written >= state.value.target -> end(completed = true)
        }
        if (isRunning && SystemClock.elapsedRealtime() - lastNotification >= 5000) updateNotification()
    }

    private suspend fun flush(force: Boolean) {
        val elapsed = SystemClock.elapsedRealtime()
        if (!force && elapsed - lastFlush < prefs.config().stepFlushIntervalSec * 1000L) return
        lastFlush = elapsed
        val now = Instant.now()
        val count = counter.generated - queued
        if (count > 0) {
            val start = if (windowStart < now) windowStart else now.minusMillis(1)
            val id = "real-${state.value.sessionId}-${start.toEpochMilli()}"
            outbox.enqueue(StepWriteOutbox.windows(id, start, now, count, 0.0, ZoneId.systemDefault()),
                StepFlushProgress(state.value.sessionId, counter.generated.toDouble(), counter.generated, now.toEpochMilli()))
            queued = counter.generated
        }
        windowStart = now
        val completed = outbox.drain(prefs.config().dailyStepCap, injector::stepsWrittenByUsOn) { batch ->
            injector.write(Instant.ofEpochMilli(batch.startMs), Instant.ofEpochMilli(batch.endMs),
                batch.acceptedSteps!!, batch.acceptedDistanceM, batch.id, batch.zone, manualEntry = batch.id.startsWith("real-"))
        }
        val ours = completed.filter { it.id.startsWith("real-${state.value.sessionId}-") }
        capReached = capReached || ours.any { it.acceptedSteps!! < it.steps }
        _state.update {
            it.copy(written = it.written + ours.sumOf { b -> b.acceptedSteps ?: 0L },
                pending = outbox.pending.filter { b -> b.id.startsWith("real-${it.sessionId}-") }.sumOf { b -> b.acceptedSteps ?: b.steps },
                error = if (outbox.hasPending) getString(R.string.steps_pending_retry) else null)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRealGps() {
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000, 0f, listener, Looper.getMainLooper())
    }

    private fun end(error: String? = null, completed: Boolean = false) {
        _state.update { it.copy(phase = if (completed) RealStepsPhase.COMPLETED else RealStepsPhase.STOPPED, error = error ?: it.error) }
        runCatching { locationManager.removeUpdates(listener) }
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (completed) {
            getSystemService(NotificationManager::class.java).notify(ID_COMPLETE,
                NotificationCompat.Builder(this, PikminGpsApp.CHANNEL_EVENTS).setSmallIcon(R.drawable.ic_walk)
                    .setContentTitle(getString(R.string.real_steps_complete))
                    .setContentText(getString(R.string.real_steps_written, state.value.written, state.value.target))
                    .setContentIntent(openApp()).setAutoCancel(true).build())
        }
        stopSelf()
    }

    private fun foreground(): Boolean = try {
        ServiceCompat.startForeground(this, ID_ONGOING, notification(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        true
    } catch (e: Exception) {
        _state.update { it.copy(phase = RealStepsPhase.STOPPED, error = e.message) }
        stopSelf()
        false
    }

    private fun notification(): Notification {
        val s = state.value
        return NotificationCompat.Builder(this, PikminGpsApp.CHANNEL_PATROL)
            .setSmallIcon(R.drawable.ic_walk).setContentTitle(getString(R.string.real_steps_title))
            .setContentText(getString(R.string.real_steps_written, s.written, s.target))
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setContentIntent(openApp())
            .addAction(0, getString(if (s.phase == RealStepsPhase.PAUSED) R.string.btn_resume else R.string.btn_pause),
                actionIntent(if (s.phase == RealStepsPhase.PAUSED) RESUME else PAUSE))
            .addAction(0, getString(R.string.btn_stop), actionIntent(STOP)).build()
    }

    private fun updateNotification() {
        lastNotification = SystemClock.elapsedRealtime()
        if (isRunning) getSystemService(NotificationManager::class.java).notify(ID_ONGOING, notification())
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(this, ID_ONGOING,
        Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun actionIntent(action: String): PendingIntent = PendingIntent.getService(this, action.hashCode(),
        Intent(this, RealGpsStepsService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PBGW:real-steps").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
    }
    private fun releaseWakeLock() { runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }; wakeLock = null }

    override fun onDestroy() {
        loop?.cancel()
        runCatching { locationManager.removeUpdates(listener) }
        releaseWakeLock()
        if (isRunning) _state.update { it.copy(phase = RealStepsPhase.STOPPED, error = getString(R.string.real_steps_interrupted)) }
        super.onDestroy()
    }

    companion object {
        private const val START = "app.pikminbloom.gps.realsteps.START"
        private const val PAUSE = "app.pikminbloom.gps.realsteps.PAUSE"
        private const val RESUME = "app.pikminbloom.gps.realsteps.RESUME"
        private const val STOP = "app.pikminbloom.gps.realsteps.STOP"
        private const val ID_ONGOING = 5
        private const val ID_COMPLETE = 6
        private val _state = MutableStateFlow(RealStepsState())
        val state: StateFlow<RealStepsState> = _state
        val isRunning: Boolean get() = state.value.active
        fun start(context: Context, target: Long, rate: Int, startPaused: Boolean = false) {
            require(target in 1..TimedStepCounter.MAX_TARGET && rate in 1..TimedStepCounter.MAX_RATE)
            ContextCompat.startForegroundService(context, Intent(context, RealGpsStepsService::class.java).setAction(START)
                .putExtra("target", target).putExtra("rate", rate).putExtra("start_paused", startPaused))
        }
        fun pause(context: Context) = send(context, PAUSE)
        fun resume(context: Context) = send(context, RESUME)
        fun stop(context: Context) = send(context, STOP)
        private fun send(context: Context, action: String) { context.startService(Intent(context, RealGpsStepsService::class.java).setAction(action)) }
    }
}
