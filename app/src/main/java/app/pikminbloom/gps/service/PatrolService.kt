package app.pikminbloom.gps.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.pikminbloom.gps.R
import app.pikminbloom.gps.data.PatrolConfig
import app.pikminbloom.gps.data.JoystickSpeeds
import app.pikminbloom.gps.data.PatrolMode
import app.pikminbloom.gps.data.PatrolPhase
import app.pikminbloom.gps.data.PatrolState
import app.pikminbloom.gps.data.Prefs
import app.pikminbloom.gps.data.ReturnMode
import app.pikminbloom.gps.data.TravelMode
import app.pikminbloom.gps.data.Waypoint
import app.pikminbloom.gps.data.WaypointStore
import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.geo.LatLng
import app.pikminbloom.gps.mock.MockLocationController
import app.pikminbloom.gps.mock.MockNotAllowedException
import app.pikminbloom.gps.route.PatrolPlan
import app.pikminbloom.gps.route.PatrolPlanner
import app.pikminbloom.gps.route.RouteSegment
import app.pikminbloom.gps.route.SegmentKind
import app.pikminbloom.gps.route.SpiralRoute
import app.pikminbloom.gps.sim.Sample
import app.pikminbloom.gps.sim.ManualInputBuffer
import app.pikminbloom.gps.sim.WalkSimulator
import app.pikminbloom.gps.steps.StepInjector
import app.pikminbloom.gps.steps.StepFlushProgress
import app.pikminbloom.gps.steps.StepWriteOutbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.floor

/**
 * Foreground service that runs the whole patrol: captures the real position ("home"), installs the
 * mock providers, ticks [WalkSimulator] once a second (100 ms in manual mode), flushes steps and walks
 * back home on request.
 *
 * Everything the user can change while walking is honoured live: settings (speed), the waypoint
 * list (re-planned from where we are), a vehicle override ([setTravelOverride]) and the floating
 * joystick ([joystick]).
 *
 * Threading: everything that touches the simulator, the plan or the step accounting runs on the
 * single-threaded [engine] dispatcher. Control actions arriving on the main thread are posted there.
 */
class PatrolService : LifecycleService() {

    private lateinit var prefs: Prefs
    private lateinit var store: WaypointStore
    private lateinit var mock: MockLocationController
    private lateinit var steps: StepInjector
    private lateinit var notifications: PatrolNotifications

    private val engine = Executors.newSingleThreadExecutor { r -> Thread(r, "PikminGPS-engine") }.asCoroutineDispatcher()

    private var config = PatrolConfig()
    private var waypoints: List<Waypoint> = emptyList()
    private var sim = WalkSimulator(config)
    private var plan: PatrolPlan = PatrolPlan.EMPTY
    private var lap = 0
    private var home: LatLng? = null
    private var mode = PatrolMode.WAYPOINTS
    private var spiral: SpiralRoute? = null
    private var manualSpeedMode: TravelMode? = null

    /** True when [home] is the user's chosen one: 回家 parks there instead of releasing the mock. */
    private var parkAtHome = false

    /** Ids of the waypoints already visited (or skipped) in the current lap; a re-plan leaves them out. */
    private val doneThisLap = HashSet<String>()

    /** A waypoint edit arrived mid-vehicle-leg / mid-orbit; apply it once that leg is over. */
    private var replanPending = false

    private var tickJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastTickElapsedMs = 0L
    private var lastNotificationMs = 0L

    // Step accounting (engine thread only).
    private var stepsAccrued = 0.0
    private var stepsFlushed = 0L
    private var distanceSinceFlush = 0.0
    private var flushWindowStart: Instant = Instant.now()
    private var stepsWrittenToday = 0L
    private var stepsDay = LocalDate.now()
    private var lastStepFlushAttemptMs = 0L
    private val stepOutbox by lazy { StepWriteOutbox(File(noBackupFilesDir, "step_outbox.json")) }
    private var settleTicks = 0
    private var lastArrivalAlertMs = 0L
    private var lastCheckpointMs = 0L

    /** Set (on the engine thread) to make the tick loop exit and run the matching finish sequence. */
    private sealed class Finish {
        data object Stop : Finish()
        data object ReturnedHome : Finish()
        data class Failed(val message: String) : Finish()
    }
    @Volatile private var pendingFinish: Finish? = null
    @Volatile private var stopRequested = false

    /** Strong reference: SharedPreferences only holds listeners weakly. */
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in LIVE_CONFIG_KEYS) lifecycleScope.launch(engine) { onConfigChanged(key) }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        store = WaypointStore.get(this)
        mock = MockLocationController(this)
        steps = StepInjector(this)
        notifications = PatrolNotifications(this)
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)

        // Live inputs. Each collector runs on the engine thread, so it can touch the simulator.
        lifecycleScope.launch(engine) { store.waypoints.collect { onWaypointsChanged(it) } }
        lifecycleScope.launch(engine) { _travelOverride.collect { onTravelOverrideChanged(it) } }
        lifecycleScope.launch(engine) {
            _joystick.map { it.enabled to it.speedMode }.distinctUntilChanged().collect { (enabled, speedMode) ->
                onJoystickToggled(enabled)
                if (enabled && state.value.phase == PatrolPhase.MANUAL && manualSpeedMode != speedMode) {
                    advanceManualTo(SystemClock.elapsedRealtime())
                    manualSpeedMode = speedMode
                }
                _state.update { it.copy(travelOverride = if (enabled) speedMode else _travelOverride.value) }
                lastNotificationMs = 0
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        if (RealGpsStepsService.isRunning) {
            // Satisfy any foreground-start obligation, then reject all mock-location actions.
            if (action in setOf(ACTION_START, ACTION_TELEPORT, ACTION_SPIRAL, ACTION_RESUME_CHECKPOINT)) goForeground()
            _events.tryEmit(PatrolEvent.Error(getString(R.string.real_steps_busy)))
            stopSelf()
            return START_NOT_STICKY
        }
        if (action == ACTION_START || action == ACTION_TELEPORT || action == ACTION_SPIRAL) {
            handleStart(intent)
            return START_NOT_STICKY
        }
        if (action == ACTION_RESUME_CHECKPOINT) {
            handleResumeCheckpoint(intent.getBooleanExtra(EXTRA_THEN_RETURN_HOME, false))
            return START_NOT_STICKY
        }
        if (state.value.phase == PatrolPhase.IDLE) {
            // Stale notification action / nothing running: discharge the start obligation and go away.
            stopSelf()
            return START_NOT_STICKY
        }
        when (action) {
            ACTION_PAUSE -> lifecycleScope.launch(engine) { setPaused(true) }
            ACTION_RESUME -> lifecycleScope.launch(engine) { setPaused(false) }
            ACTION_RETURN_HOME -> lifecycleScope.launch(engine) { beginReturnHome() }
            ACTION_STOP -> lifecycleScope.launch(engine) { requestStop() }
            ACTION_SKIP_WAYPOINT -> lifecycleScope.launch(engine) { skipWaypoint() }
            ACTION_GO_TO -> {
                val index = intent.getIntExtra(EXTRA_WAYPOINT_INDEX, -1)
                lifecycleScope.launch(engine) { goToWaypoint(index) }
            }
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ start

    private fun handleStart(intent: Intent) {
        val requestedMode = when (intent.action) {
            ACTION_TELEPORT -> PatrolMode.HOLD
            ACTION_SPIRAL -> PatrolMode.SPIRAL
            else -> PatrolMode.WAYPOINTS
        }
        val requestedSpiralWidth = SpiralRoute.resolveLineWidth(
            if (intent.hasExtra(EXTRA_SPIRAL_LINE_WIDTH)) intent.getDoubleExtra(EXTRA_SPIRAL_LINE_WIDTH, Double.NaN) else null,
            prefs.spiralLineWidthM,
        )
        val destination = if (requestedMode == PatrolMode.HOLD) runCatching {
            require(intent.hasExtra(EXTRA_POSITION_LAT) && intent.hasExtra(EXTRA_POSITION_LON))
            LatLng(intent.getDoubleExtra(EXTRA_POSITION_LAT, Double.NaN), intent.getDoubleExtra(EXTRA_POSITION_LON, Double.NaN))
        }.getOrNull() else null
        if (requestedMode == PatrolMode.HOLD && destination == null) { stopSelf(); return }
        // Location permission must exist before startForeground(type = location) on API 34+.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            _state.update { it.copy(lastError = getString(R.string.svc_err_no_location_permission)) }
            _events.tryEmit(PatrolEvent.Error(getString(R.string.svc_err_no_location_permission)))
            notifications.error(getString(R.string.svc_err_no_location_permission))
            stopSelf()
            return
        }
        // Always satisfy startForegroundService(), even for a duplicate start.
        if (!goForeground()) return
        if (state.value.phase != PatrolPhase.IDLE) {
            lifecycleScope.launch(engine) {
                val phase = state.value.phase
                if (phase == PatrolPhase.STARTING || phase == PatrolPhase.STOPPING || pendingFinish != null) return@launch
                if (requestedMode == PatrolMode.WAYPOINTS && phase != PatrolPhase.HOLDING) return@launch
                val from = destination ?: sim.current().position
                selectMovement(requestedMode, from, intent.getIntExtra(EXTRA_START_AT_INDEX, 0), requestedSpiralWidth)
            }
            return
        }
        _state.value = PatrolState(phase = PatrolPhase.STARTING, mockAppSelected = mock.isMockAppSelected(), travelOverride = _travelOverride.value)
        stopRequested = false
        pendingFinish = null

        // A fresh start removes the stale providers and takes a real fix, which is exactly the
        // crash-point -> real-position teleport a checkpoint exists to prevent. Refuse until the
        // user has chosen resume / go home / discard in the UI.
        val checkpoint = PatrolCheckpoint.resumable(this)
        if (checkpoint != null && requestedMode == PatrolMode.WAYPOINTS) { failNow(getString(R.string.svc_err_checkpoint_pending)); return }

        config = prefs.config()
        waypoints = store.load()
        if (requestedMode == PatrolMode.WAYPOINTS && waypoints.isEmpty()) { failNow(getString(R.string.svc_err_no_waypoints)); return }
        if (!mock.isMockAppSelected()) { failNow(getString(R.string.svc_err_not_mock_app)); return }

        val overrideHome = if (intent.hasExtra(EXTRA_HOME_LAT) && intent.hasExtra(EXTRA_HOME_LON)) {
            runCatching { LatLng(intent.getDoubleExtra(EXTRA_HOME_LAT, 0.0), intent.getDoubleExtra(EXTRA_HOME_LON, 0.0)) }.getOrNull()
        } else null
        // The user's chosen home (「待在日本」) wins over a real fix; an explicit override (debug
        // driver) wins over both but keeps the ordinary "release the mock at home" ending.
        val customHome = if (overrideHome == null) prefs.customHome else null
        val startIndex = intent.getIntExtra(EXTRA_START_AT_INDEX, 0).coerceIn(0, (waypoints.size - 1).coerceAtLeast(0))
        acquireWakeLock()

        lifecycleScope.launch(engine) {
            try {
                // A crashed/killed previous run may have left test providers installed; they would
                // make every "real" fix look mocked, so clear them before capturing home.
                val parked = if (requestedMode != PatrolMode.WAYPOINTS) mock.lastParkedMockFix() else null
                if (requestedMode == PatrolMode.WAYPOINTS) mock.stop()
                val real = if (requestedMode == PatrolMode.WAYPOINTS && overrideHome == null && customHome == null) {
                    mock.currentRealLocation(20_000)
                } else if (requestedMode != PatrolMode.WAYPOINTS && parked == null && checkpoint == null && customHome == null &&
                    (requestedMode == PatrolMode.SPIRAL || prefs.home == null)) {
                    mock.currentRealLocation(if (destination != null) 1_000 else 20_000)
                } else null
                val h = if (requestedMode == PatrolMode.WAYPOINTS) overrideHome ?: customHome ?: real
                    else checkpoint?.home ?: customHome ?: prefs.home ?: real
                val from = destination ?: parked ?: checkpoint?.position ?: customHome ?: real ?: h
                if (stopRequested) { failNow(getString(R.string.svc_phase_stopping), silent = true); return@launch }
                if (from == null) { failNow(getString(R.string.svc_err_no_home)); return@launch }
                home = h
                parkAtHome = customHome != null
                if (h != null && requestedMode == PatrolMode.WAYPOINTS) prefs.home = h
                stepsWrittenToday = if (config.injectSteps && steps.isAvailable) steps.stepsWrittenByUsToday() else 0L
                if (stopRequested) { failNow(getString(R.string.svc_phase_stopping), silent = true); return@launch }
                mock.start(config, keepExisting = requestedMode != PatrolMode.WAYPOINTS)
                sim = WalkSimulator(config).also { it.travelOverride = _travelOverride.value }
                lap = 0
                stepsAccrued = 0.0; stepsFlushed = 0; distanceSinceFlush = 0.0
                flushWindowStart = Instant.now()
                _state.update {
                    it.copy(
                        home = h, position = from, startedAtMs = System.currentTimeMillis(),
                        stepsWrittenToday = stepsWrittenToday, mockAppSelected = true,
                        healthConnectReady = steps.isAvailable, lastError = null,
                        homeIsCustom = parkAtHome,
                    )
                }
                selectMovement(requestedMode, from, startIndex, requestedSpiralWidth)
                maybeFlushSteps(force = true)
                Log.i(TAG, "started at $h (custom home: $parkAtHome, override: ${_travelOverride.value})")
                lastNotificationMs = 0
                // The joystick may already be up (toggled before 開始); take it into account now.
                if (_joystick.value.enabled) enterManual()
                startTicking()
            } catch (e: MockNotAllowedException) {
                failNow(e.message ?: getString(R.string.svc_err_not_mock_app))
            } catch (t: Throwable) {
                Log.e(TAG, "start failed", t)
                failNow(t.message ?: "start failed")
            }
        }
    }

    /** Engine-only mode switch. Relocations never enter the distance/step accounting path. */
    private fun selectMovement(
        nextMode: PatrolMode, from: LatLng, startIndex: Int = 0,
        lineWidthM: Double = spiral?.lineWidthM ?: prefs.spiralLineWidthM,
    ) {
        if (nextMode == PatrolMode.WAYPOINTS && waypoints.isEmpty()) return
        mode = nextMode
        spiral = if (mode == PatrolMode.SPIRAL) SpiralRoute(from, lineWidthM = lineWidthM) else null
        replanPending = false
        doneThisLap.clear()
        manualInput.stop()
        _joystick.value = JoystickInput()
        sim.relocate(from)
        when (mode) {
            PatrolMode.HOLD -> { plan = PatrolPlan.EMPTY; planWaypoints = emptyList() }
            PatrolMode.SPIRAL -> loadPlan(spiral!!.nextPlan(from))
            PatrolMode.WAYPOINTS -> { lap = 0; loadLap(from, startIndex.coerceIn(0, waypoints.lastIndex)) }
        }
        mock.pushRaw(from, altitudeM = config.altitudeM)
        val first = plan.segments.firstOrNull()
        _state.update {
            it.copy(mode = mode, phase = if (mode == PatrolMode.HOLD) PatrolPhase.HOLDING else phaseFor(first?.kind ?: SegmentKind.TRAVEL),
                position = from, speedMps = 0.0, travelOverride = _travelOverride.value,
                spiralCenter = spiral?.center, spiralRadiusM = 0.0,
                spiralLineWidthM = spiral?.lineWidthM ?: prefs.spiralLineWidthM,
                spiralSpacingM = spiral?.spacingM ?: SpiralRoute.spacingForWidth(prefs.spiralLineWidthM),
                currentWaypointIndex = first?.waypointIndex ?: -1,
                currentWaypointName = when (mode) {
                    PatrolMode.HOLD -> getString(R.string.target_hold)
                    PatrolMode.SPIRAL -> getString(R.string.target_spiral)
                    PatrolMode.WAYPOINTS -> first?.waypointIndex?.let { i -> waypoints.getOrNull(i)?.name }
                }, distanceToTargetM = 0.0, lastError = null)
        }
        lastTickElapsedMs = SystemClock.elapsedRealtime()
        lastNotificationMs = 0
        writeCheckpoint()
        Log.i(TAG, "movement=$mode at $from, home=$home")
    }

    // ------------------------------------------------------------------ resume after a process death

    /**
     * Picks a patrol up from its [PatrolCheckpoint] after the process was killed (thermal, OOM,
     * crash). The game is still parked at the checkpoint position, so the first thing done after
     * installing the providers is to push that exact position again: nothing on screen moves.
     *
     * Home is the checkpoint's home, not a fresh fix. A fresh fix is impossible here anyway (the
     * stale providers are still masking the real GPS and removing them first is the teleport we
     * are avoiding), and a kill happens minutes into a session, not hours, so the user has not
     * moved. If they have, they can discard the checkpoint and start fresh instead.
     */
    private fun handleResumeCheckpoint(thenReturnHome: Boolean) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            _events.tryEmit(PatrolEvent.Error(getString(R.string.svc_err_no_location_permission)))
            stopSelf(); return
        }
        if (!goForeground()) return
        if (state.value.phase != PatrolPhase.IDLE) { Log.i(TAG, "resume ignored: already ${state.value.phase}"); return }
        val cp = PatrolCheckpoint.resumable(this) ?: run { failNow(getString(R.string.svc_err_no_checkpoint)); return }

        _state.value = PatrolState(phase = PatrolPhase.STARTING, mockAppSelected = mock.isMockAppSelected(), travelOverride = _travelOverride.value)
        stopRequested = false
        pendingFinish = null
        config = prefs.config()
        if (cp.routeId.isNotBlank()) store.switchTo(cp.routeId)
        waypoints = store.load()
        if (!mock.isMockAppSelected()) { failNow(getString(R.string.svc_err_not_mock_app)); return }
        acquireWakeLock()

        lifecycleScope.launch(engine) {
            try {
                // The checkpoint is up to a few seconds behind where the last fix actually left the
                // player. If the system still holds that fix, resume from it and nothing moves at all.
                val parked = mock.lastParkedMockFix()
                    ?.takeIf { GeoMath.distanceM(it, cp.position) <= 100.0 }
                val resumeAt = parked ?: cp.position
                if (parked != null) Log.i(TAG, "resuming from the parked fix $parked (checkpoint was ${cp.position})")

                // Replace the stale providers in place and immediately re-assert the parked position.
                mock.start(config, keepExisting = true)
                mock.pushRaw(resumeAt, accuracyM = 5f, altitudeM = config.altitudeM)

                home = cp.home
                // A session that started from the chosen home still parks there at the end.
                parkAtHome = cp.home?.let { h -> prefs.customHome?.let { GeoMath.distanceM(it, h) < 1.0 } } ?: false
                sim = WalkSimulator(config).also { it.travelOverride = _travelOverride.value }
                lap = cp.lap
                stepsAccrued = cp.stepsAccrued
                stepsFlushed = cp.stepsFlushed
                distanceSinceFlush = cp.distanceSinceFlush
                flushWindowStart = Instant.ofEpochMilli(cp.flushWindowStartMs)
                stepsWrittenToday = cp.stepsWrittenToday
                // A kill may have happened after the durable outbox commit but before the checkpoint.
                stepOutbox.progress?.takeIf { it.sessionId == cp.startedAtMs && it.flushed > stepsFlushed }?.let {
                    stepsAccrued = maxOf(stepsAccrued, it.accrued)
                    stepsFlushed = it.flushed
                    distanceSinceFlush = 0.0
                    flushWindowStart = Instant.ofEpochMilli(it.endMs)
                }
                lastArrivalAlertMs = 0L
                doneThisLap.clear()

                mode = cp.mode
                spiral = if (mode == PatrolMode.SPIRAL && cp.spiralCenter != null) {
                    SpiralRoute(cp.spiralCenter, lineWidthM = cp.spiralLineWidthM, angleRad = cp.spiralAngleRad, spacingM = cp.spiralSpacingM)
                } else null
                val goHome = cp.home != null && (thenReturnHome || cp.phase == PatrolPhase.RETURNING_HOME || cp.phase == PatrolPhase.PARKED ||
                    (mode == PatrolMode.WAYPOINTS && waypoints.isEmpty()))
                val hold = !goHome && (mode == PatrolMode.HOLD || (mode == PatrolMode.WAYPOINTS && waypoints.isEmpty()))
                if (goHome) {
                    loadPlan(PatrolPlanner.planReturnHome(resumeAt, cp.home!!, routeTravelMode.takeIf { it != TravelMode.WALK }))
                    settleTicks = 0
                } else if (hold) {
                    mode = PatrolMode.HOLD
                    plan = PatrolPlan.EMPTY
                    sim.relocate(resumeAt)
                } else if (mode == PatrolMode.SPIRAL) {
                    if (spiral == null) spiral = SpiralRoute(resumeAt, lineWidthM = prefs.spiralLineWidthM)
                    loadPlan(spiral!!.nextPlan(resumeAt))
                } else {
                    // Continue with the remaining waypoints of the interrupted lap, from where we are.
                    val order = PatrolPlanner.orderFor(lap, waypoints.size, config.loopMode)
                    val pos = order.indexOf(cp.targetWaypointIndex.coerceIn(0, waypoints.size - 1))
                    val remaining = if (pos >= 0) order.drop(pos) else order
                    if (pos > 0) order.take(pos).forEach { i -> waypoints.getOrNull(i)?.let { doneThisLap += it.id } }
                    loadPlan(PatrolPlanner.planLap(resumeAt, waypoints, config, remaining.ifEmpty { order }, lap))
                }

                _state.update {
                    it.copy(
                        phase = when {
                            goHome -> PatrolPhase.RETURNING_HOME
                            hold -> PatrolPhase.HOLDING
                            cp.phase == PatrolPhase.PAUSED -> PatrolPhase.PAUSED
                            else -> phaseFor(plan.segments.firstOrNull()?.kind ?: SegmentKind.TRAVEL)
                        },
                        mode = mode, spiralCenter = spiral?.center,
                        spiralLineWidthM = spiral?.lineWidthM ?: prefs.spiralLineWidthM,
                        spiralSpacingM = spiral?.spacingM ?: SpiralRoute.spacingForWidth(prefs.spiralLineWidthM),
                        spiralRadiusM = spiral?.let { GeoMath.distanceM(it.center, resumeAt) } ?: 0.0,
                        home = cp.home, position = resumeAt, startedAtMs = cp.startedAtMs,
                        distanceWalkedM = cp.distanceWalkedM, sessionSteps = floor(stepsAccrued).toLong(),
                        stepsWrittenToday = stepsWrittenToday, lapsCompleted = cp.lapsCompleted,
                        mockAppSelected = true, healthConnectReady = steps.isAvailable, lastError = null,
                        currentWaypointIndex = if (goHome || mode != PatrolMode.WAYPOINTS) -1 else cp.targetWaypointIndex,
                        currentWaypointName = when {
                            goHome -> getString(R.string.svc_target_home)
                            hold -> getString(R.string.target_hold)
                            mode == PatrolMode.SPIRAL -> getString(R.string.target_spiral)
                            else -> waypoints.getOrNull(cp.targetWaypointIndex)?.name
                        },
                        distanceToTargetM = if (goHome) GeoMath.distanceM(resumeAt, cp.home!!) else 0.0,
                        homeIsCustom = parkAtHome,
                    )
                }
                Log.i(TAG, "resumed from checkpoint (${cp.ageMs / 1000}s old) at $resumeAt, goHome=$goHome")
                _events.tryEmit(PatrolEvent.Resumed(cp.ageMs))
                lastNotificationMs = 0
                startTicking()
            } catch (e: MockNotAllowedException) {
                failNow(e.message ?: getString(R.string.svc_err_not_mock_app))
            } catch (t: Throwable) {
                Log.e(TAG, "resume failed", t)
                failNow(t.message ?: "resume failed")
            }
        }
    }

    /** Snapshot of everything a resume needs. Engine thread only. */
    private fun writeCheckpoint() {
        val s = state.value
        val h = home
        val p = s.position ?: return
        if (s.phase == PatrolPhase.IDLE || s.phase == PatrolPhase.STOPPING || s.phase == PatrolPhase.STARTING) return
        PatrolCheckpoint.save(
            this,
            PatrolCheckpoint(
                savedAtMs = System.currentTimeMillis(),
                startedAtMs = s.startedAtMs,
                home = h,
                position = p,
                routeId = store.activeRouteId.value,
                lap = lap,
                targetWaypointIndex = s.currentWaypointIndex.coerceAtLeast(0),
                phase = s.phase,
                distanceWalkedM = s.distanceWalkedM,
                stepsAccrued = stepsAccrued,
                stepsFlushed = stepsFlushed,
                flushWindowStartMs = flushWindowStart.toEpochMilli(),
                distanceSinceFlush = distanceSinceFlush,
                stepsWrittenToday = stepsWrittenToday,
                lapsCompleted = s.lapsCompleted,
                mode = mode,
                spiralCenter = spiral?.center,
                spiralAngleRad = spiral?.angleAt(sim.exactCurrentPosition()) ?: 0.0,
                spiralSpacingM = spiral?.spacingM ?: SpiralRoute.spacingForWidth(prefs.spiralLineWidthM),
                spiralLineWidthM = spiral?.lineWidthM ?: prefs.spiralLineWidthM,
            ),
        )
    }

    private fun goForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, PatrolNotifications.ID_ONGOING, notifications.ongoing(state.value), type)
        true
    } catch (t: Throwable) {
        Log.e(TAG, "startForeground failed", t)
        // A location-type foreground service may only be started while the app is "eligible",
        // i.e. with a visible activity. Anything else (an adb broadcast, a stale notification
        // action) lands here, and the raw platform message is useless to a user.
        val friendly = if (t is SecurityException || t.javaClass.simpleName.contains("ForegroundServiceStartNotAllowed")) {
            getString(R.string.svc_err_background_start)
        } else {
            t.message ?: "startForeground failed"
        }
        failNow(friendly)
        false
    }

    /** The active route's travel mode; WALK for an ordinary patrol. */
    private val routeTravelMode: TravelMode
        get() = store.activeRoute()?.travelMode ?: TravelMode.WALK

    private fun loadLap(from: LatLng, startIndex: Int = 0) {
        var order = PatrolPlanner.orderFor(lap, waypoints.size, config.loopMode)
        doneThisLap.clear()
        replanPending = false
        if (lap == 0 && startIndex > 0) {
            // 從這裡開始: the ones before it are not visited this lap, and a re-plan must not add them back.
            order.take(order.indexOf(startIndex).coerceAtLeast(0)).forEach { i -> waypoints.getOrNull(i)?.let { doneThisLap += it.id } }
            order = order.drop(startIndex)
        }

        val mode = routeTravelMode
        val first = order.firstOrNull()?.let { waypoints.getOrNull(it) }
        val next = if (lap == 0 && mode != TravelMode.WALK && first != null) {
            // A trip: cover the (possibly long) leg to the first place at vehicle speed - no steps,
            // no planting - then wander it on foot for its dwell time regardless of the global
            // orbit setting, because that walk is the whole point of going there. Any further
            // waypoints are walked as usual from where the wander ends.
            val trip = PatrolPlanner.planTripTo(
                from, first.latLng, config, mode,
                wanderRadiusM = first.radiusM,
                wanderSec = first.dwellSec.coerceAtLeast(60),
            )
            val rest = order.drop(1)
            if (rest.isEmpty()) trip else {
                val tail = PatrolPlanner.planLap(trip.end ?: first.latLng, waypoints, config, rest, lap)
                PatrolPlan.of(trip.segments + tail.segments)
            }
        } else {
            PatrolPlanner.planLap(from, waypoints, config, order, lap)
        }
        loadPlan(next)
        Log.i(TAG, "lap $lap loaded: ${plan.segments.size} segments, ${"%.0f".format(plan.totalLengthM)} m, mode=$mode")
    }

    // ------------------------------------------------------------------ tick loop (engine thread)

    private fun startTicking() {
        tickJob?.cancel()
        lastTickElapsedMs = SystemClock.elapsedRealtime()
        tickJob = lifecycleScope.launch(engine) {
            while (isActive && pendingFinish == null) {
                try {
                    tick()
                } catch (t: Throwable) {
                    Log.e(TAG, "tick failed", t)
                    _state.update { it.copy(lastError = t.message) }
                }
                if (pendingFinish != null) break
                // Manual motion is responsive; ordinary patrols keep their existing GPS cadence.
                withTimeoutOrNull(if (state.value.phase == PatrolPhase.MANUAL) MANUAL_TICK_MS else config.tickMs) {
                    manualWake.receive()
                }
            }
            val finish = pendingFinish ?: return@launch
            withContext(NonCancellable) { runFinish(finish) }
        }
    }

    private suspend fun tick() {
        val nowMs = SystemClock.elapsedRealtime()
        val dt = ((nowMs - lastTickElapsedMs) / 1000.0).coerceIn(0.0, 3.0)
        lastTickElapsedMs = nowMs

        when (state.value.phase) {
            PatrolPhase.PAUSED, PatrolPhase.HOLDING -> mock.push(sim.current())
            PatrolPhase.PARKED -> {
                // Keep the game fed with fixes at home; a silent provider reads as "GPS lost".
                val h = home ?: run { pendingFinish = Finish.Stop; return }
                mock.pushRaw(h, accuracyM = 5f, altitudeM = config.altitudeM)
            }
            PatrolPhase.MANUAL -> {
                advanceManualTo(nowMs)
            }
            PatrolPhase.WALKING, PatrolPhase.DWELLING -> {
                val s = sim.advance(dt)
                mock.push(s)
                account(s)
                if (s.arrivedAtWaypoint != null) onArrived(s.arrivedAtWaypoint)
                _state.update {
                    it.copy(
                        phase = phaseFor(s.kind), position = s.position, speedMps = s.speedMps,
                        currentWaypointIndex = s.waypointIndex ?: it.currentWaypointIndex,
                        currentWaypointName = s.waypointIndex?.let { i -> waypoints.getOrNull(i)?.name } ?: it.currentWaypointName,
                        distanceToTargetM = distanceToTarget(s),
                        spiralRadiusM = spiral?.let { route -> GeoMath.distanceM(route.center, s.position) } ?: 0.0,
                    )
                }
                if (s.lapFinished) {
                    onLapFinished(s)
                } else if (replanPending && s.kind == SegmentKind.TRAVEL && s.countsSteps) {
                    // The orbit / vehicle leg that was in progress when the edit came in is over.
                    replanPending = false
                    replanRemaining(reason = "deferred edit")
                }
            }
            PatrolPhase.RETURNING_HOME -> {
                val h = home ?: run { pendingFinish = Finish.Stop; return }
                if (config.returnMode == ReturnMode.TELEPORT || sim.finished) {
                    mock.pushRaw(h, accuracyM = 5f, altitudeM = config.altitudeM)
                    _state.update { it.copy(position = h, speedMps = 0.0, distanceToTargetM = 0.0) }
                    settleTicks++
                    if (settleTicks >= SETTLE_TICKS) {
                        if (parkAtHome) park(h) else pendingFinish = Finish.ReturnedHome
                    }
                } else {
                    val s = sim.advance(dt)
                    mock.push(s)
                    account(s)
                    _state.update {
                        it.copy(position = s.position, speedMps = s.speedMps, distanceToTargetM = GeoMath.distanceM(s.position, h))
                    }
                }
            }
            else -> Unit
        }

        if (pendingFinish == null) maybeFlushSteps(force = false)
        maybeUpdateNotification()

        // Cheap insurance against a thermal kill: a few hundred bytes every few seconds.
        if (nowMs - lastCheckpointMs >= CHECKPOINT_INTERVAL_MS) {
            lastCheckpointMs = nowMs
            writeCheckpoint()
        }
    }

    /** Apply each recorded direction for its real duration, not for a whole tick. */
    private fun advanceManualTo(nowMs: Long) {
        var last: Sample? = null
        for (input in manualInput.consume(nowMs)) {
            val sample = sim.advanceManual(input.durationMs / 1000.0, input.bearingDeg, input.magnitude, manualSpeedMode)
            account(sample)
            last = sample
        }
        val sample = last ?: sim.current()
        mock.push(sample)
        _state.update {
            it.copy(position = sample.position, speedMps = sample.speedMps, distanceToTargetM = 0.0,
                spiralRadiusM = spiral?.let { route -> GeoMath.distanceM(route.center, sample.position) } ?: 0.0)
        }
    }

    /**
     * Home reached, but home is the user's chosen spot rather than where the phone is: releasing
     * the mock would drop the game back onto the real GPS, which may be another country. Stay put
     * with the mock on; 繼續 starts the next lap from here, 停止 is the deliberate way out.
     */
    private fun park(h: LatLng) {
        loadPlan(PatrolPlanner.planReturnHome(h, h))
        _state.update {
            it.copy(phase = PatrolPhase.PARKED, position = h, speedMps = 0.0, distanceToTargetM = 0.0,
                currentWaypointIndex = -1, currentWaypointName = getString(R.string.svc_target_home))
        }
        lastNotificationMs = 0
        notifications.parkedAtHome(vibrate = config.vibrateOnArrival)
        _events.tryEmit(PatrolEvent.ParkedAtHome)
        Log.i(TAG, "parked at the custom home $h; mock stays on")
    }

    private suspend fun runFinish(finish: Finish) {
        writeCheckpoint()
        _state.update { it.copy(phase = PatrolPhase.STOPPING) }
        var preserveCheckpoint = false
        runCatching { maybeFlushSteps(force = true) }.onFailure {
            preserveCheckpoint = true
            Log.w(TAG, "final flush failed; keeping checkpoint", it)
        }
        mock.stop()
        when (finish) {
            Finish.ReturnedHome -> {
                notifications.returnedHome(vibrate = config.vibrateOnArrival)
                _events.tryEmit(PatrolEvent.ReturnedHome)
            }
            Finish.Stop -> _events.tryEmit(PatrolEvent.Stopped)
            is Finish.Failed -> { _events.tryEmit(PatrolEvent.Error(finish.message)); notifications.error(finish.message) }
        }
        val healthError = if (preserveCheckpoint || runCatching { stepOutbox.hasPending }.getOrDefault(true)) getString(R.string.steps_pending_retry) else null
        withContext(Dispatchers.Main) { teardown((finish as? Finish.Failed)?.message ?: healthError, preserveCheckpoint) }
    }

    private fun account(s: Sample) {
        if (s.distanceDeltaM <= 0.0) return
        // Vehicle legs move the position but produce no steps; nobody walks while driving.
        if (s.stepDistanceDeltaM > 0.0) {
            stepsAccrued += s.stepDistanceDeltaM / config.strideM
            distanceSinceFlush += s.stepDistanceDeltaM
        }
        _state.update {
            it.copy(distanceWalkedM = it.distanceWalkedM + s.distanceDeltaM, sessionSteps = floor(stepsAccrued).toLong())
        }
    }

    private fun distanceToTarget(s: Sample): Double {
        val idx = s.waypointIndex ?: return 0.0
        val wp = waypoints.getOrNull(idx) ?: return 0.0
        return GeoMath.distanceM(s.position, wp.latLng)
    }

    private fun phaseFor(kind: SegmentKind) = if (kind == SegmentKind.ORBIT) PatrolPhase.DWELLING else PatrolPhase.WALKING

    private fun onArrived(index: Int) {
        val wp = waypoints.getOrNull(index)
        val name = wp?.name ?: "#${index + 1}"
        wp?.let { doneThisLap += it.id }
        Log.i(TAG, "arrived at $index ($name)")
        _events.tryEmit(PatrolEvent.ArrivedAtWaypoint(index, name))
        // A vehicle is for getting somewhere: it drops the player off at the first flower and the
        // walking (planting, steps) starts there. Otherwise a forgotten 汽車 would zoom round a
        // whole lap planting nothing.
        val vehicle = _travelOverride.value
        if (vehicle != null && !vehicle.countsSteps) {
            Log.i(TAG, "arrived by $vehicle → back to walking")
            autoDropPending = true
            _travelOverride.value = null   // the collector applies it (onTravelOverrideChanged)
        }
        if (!config.notifyOnArrival) return
        // Rate limit: passing several flowers in a row must not turn into a burst of buzzes.
        val now = SystemClock.elapsedRealtime()
        val gapMs = config.arrivalAlertMinGapSec * 1000L
        val quiet = lastArrivalAlertMs != 0L && now - lastArrivalAlertMs < gapMs
        lastArrivalAlertMs = now
        notifications.arrived(name, vibrate = config.vibrateOnArrival && !quiet)
    }

    private fun onLapFinished(s: Sample) {
        if (mode == PatrolMode.SPIRAL) {
            loadPlan(spiral!!.nextPlan(sim.exactCurrentPosition()))
            return
        }
        val finishedLap = lap
        val completed = finishedLap + 1
        _events.tryEmit(PatrolEvent.LapFinished(finishedLap))
        _state.update { it.copy(lapsCompleted = completed) }
        lap++

        val limit = config.autoReturnAfterLaps
        if (limit > 0 && completed >= limit) {
            Log.i(TAG, "completed $completed lap(s), limit $limit → returning home")
            beginReturnHome()
            return
        }
        val nextOrder = PatrolPlanner.orderFor(lap, waypoints.size, config.loopMode)
        if (nextOrder.isEmpty()) {
            Log.i(TAG, "route finished (ONCE) → returning home")
            beginReturnHome()
            return
        }
        loadLap(s.position)
        // A lap with nowhere to walk (one flower and no orbiting, or flowers on top of each other)
        // would "arrive" again on the very next tick and spin the notification once a second.
        if (plan.totalLengthM < MIN_LAP_M) {
            Log.i(TAG, "lap $lap is degenerate (${"%.1f".format(plan.totalLengthM)} m) → nothing left to walk, returning home")
            beginReturnHome()
        }
    }

    // ------------------------------------------------------------------ live changes (engine thread)

    /** A settings screen edit while walking: re-read everything and hand the simulator the new speed. */
    private fun onConfigChanged(key: String?) {
        if (state.value.phase == PatrolPhase.IDLE) return
        val fresh = prefs.config()
        if (fresh == config) return
        if (state.value.phase == PatrolPhase.MANUAL) advanceManualTo(SystemClock.elapsedRealtime())
        val speedChanged = fresh.speedMps != config.speedMps
        config = fresh
        sim.updateConfig(fresh)
        Log.i(TAG, "config changed ($key): speed ${"%.1f".format(fresh.speedMps * 3.6)} km/h, orbit=${fresh.orbitAtWaypoints}")
        if (speedChanged) _events.tryEmit(PatrolEvent.ConfigChanged(fresh.speedMps * 3.6))
        lastNotificationMs = 0
    }

    /**
     * The waypoint list (of the active route) changed while walking: keep the leg in progress if
     * it still makes sense, then continue with whatever is left of the lap, in the new order.
     */
    private fun onWaypointsChanged(list: List<Waypoint>) {
        val previous = waypoints
        waypoints = list
        if (mode != PatrolMode.WAYPOINTS) return
        val p = state.value.phase
        if (p != PatrolPhase.WALKING && p != PatrolPhase.DWELLING && p != PatrolPhase.PAUSED) return
        if (list == previous) return
        if (list.isEmpty()) {
            Log.i(TAG, "every waypoint removed while walking → returning home")
            beginReturnHome()
            return
        }
        val cur = sim.current()
        val curWp = cur.waypointIndex?.let { previous.getOrNull(it) }
        val midLeg = cur.kind == SegmentKind.ORBIT || !cur.countsSteps
        if (midLeg && curWp != null && curWp in list) {
            // Finish the orbit (or the drive) we are in the middle of, then take the new list up.
            replanPending = true
            Log.i(TAG, "waypoints edited (${previous.size} → ${list.size}); applying after the current leg")
            return
        }
        replanRemaining(reason = "waypoints edited (${previous.size} → ${list.size})")
    }

    /**
     * Re-plans the rest of the current lap from where we are: everything not yet visited this lap,
     * in route order, with [firstIndex] (立刻前往) moved to the front when given. The leg in
     * progress is kept when it belongs to a waypoint that still exists unchanged.
     */
    private fun replanRemaining(reason: String, firstIndex: Int? = null) {
        val cur = sim.current()
        val order = PatrolPlanner.orderFor(lap, waypoints.size, config.loopMode)
        var rest = order.filter { i -> waypoints.getOrNull(i)?.let { it.id !in doneThisLap } ?: false }
        if (firstIndex != null && firstIndex in waypoints.indices) {
            rest = listOf(firstIndex) + rest.filter { it != firstIndex }
        }

        // Keep the leg we are on if it is still for a waypoint that exists unchanged (its index may
        // have moved). Never for a 立刻前往 to somewhere else: that is an explicit change of target.
        val keep = ArrayList<RouteSegment>()
        var kept: Int? = null
        val oldIdx = cur.waypointIndex
        if (oldIdx != null) {
            val legs = sim.remainingLegsOfCurrentWaypoint()
            // The plan's index refers to the list it was made from; the same flower may now sit elsewhere.
            val wp = planWaypoints.getOrNull(oldIdx)
            val newIdx = if (wp != null) waypoints.indexOf(wp) else -1
            if (legs.isNotEmpty() && newIdx >= 0 && (firstIndex == null || firstIndex == newIdx)) {
                keep += legs.map { it.copy(waypointIndex = newIdx) }
                kept = newIdx
            }
        }
        if (kept != null) rest = rest.filter { it != kept }

        val start = keep.lastOrNull()?.to ?: cur.position
        val tail = if (rest.isEmpty()) PatrolPlan.EMPTY else PatrolPlanner.planLap(start, waypoints, config, rest, lap)
        val merged = PatrolPlan.of(keep + tail.segments)
        if (merged.isEmpty) {
            // Nothing left in this lap at all: treat it as finished, which loads the next one (or goes home).
            Log.i(TAG, "re-plan ($reason): nothing left → lap finished")
            onLapFinished(cur)
            return
        }
        replanPending = false
        loadPlan(merged)
        _state.update {
            val first = plan.segments.first()
            it.copy(
                phase = if (it.phase == PatrolPhase.PAUSED) it.phase else phaseFor(first.kind),
                currentWaypointIndex = first.waypointIndex ?: -1,
                currentWaypointName = first.waypointIndex?.let { i -> waypoints.getOrNull(i)?.name },
                distanceToTargetM = first.waypointIndex?.let { i -> waypoints.getOrNull(i)?.let { w -> GeoMath.distanceM(cur.position, w.latLng) } } ?: 0.0,
            )
        }
        lastNotificationMs = 0
        _events.tryEmit(PatrolEvent.Replanned(reason))
        Log.i(TAG, "re-planned ($reason): kept ${keep.size} leg(s), then ${rest.size} waypoint(s), ${"%.0f".format(plan.totalLengthM)} m")
    }

    /** The waypoint list the current [plan] was built from: its segment indices refer to this one. */
    private var planWaypoints: List<Waypoint> = emptyList()

    /** Every plan goes through here so [planWaypoints] always matches [plan]. */
    private fun loadPlan(p: PatrolPlan) {
        manualInput.stop()
        plan = p
        planWaypoints = waypoints
        sim.load(p)
    }

    /** 立刻前往: make [index] the next target, keeping the rest of the lap after it. */
    private fun goToWaypoint(index: Int) {
        val p = state.value.phase
        if (index !in waypoints.indices) return
        if (mode != PatrolMode.WAYPOINTS && p != PatrolPhase.STARTING && p != PatrolPhase.STOPPING) {
            selectMovement(PatrolMode.WAYPOINTS, sim.current().position, index)
            return
        }
        when (p) {
            PatrolPhase.WALKING, PatrolPhase.DWELLING, PatrolPhase.PAUSED -> {
                doneThisLap.remove(waypoints[index].id)
                replanRemaining(reason = "go to ${waypoints[index].name}", firstIndex = index)
            }
            PatrolPhase.PARKED, PatrolPhase.MANUAL -> {
                // Leave the parking spot / the joystick and walk the lap starting at that flower.
                if (p == PatrolPhase.MANUAL) {
                    advanceManualTo(SystemClock.elapsedRealtime())
                    manualInput.stop()
                    _joystick.update { it.copy(enabled = false) }
                }
                doneThisLap.clear()
                val order = PatrolPlanner.orderFor(lap, waypoints.size, config.loopMode)
                val rest = listOf(index) + order.filter { it != index }
                loadPlan(PatrolPlanner.planLap(sim.current().position, waypoints, config, rest, lap))
                _state.update {
                    it.copy(phase = phaseFor(plan.segments.first().kind), currentWaypointIndex = index,
                        currentWaypointName = waypoints[index].name)
                }
                lastNotificationMs = 0
                _events.tryEmit(PatrolEvent.Replanned("go to ${waypoints[index].name}"))
            }
            else -> Unit
        }
    }

    /** Set right before dropping the override on arrival, so the change is reported as automatic. */
    private var autoDropPending = false

    private fun onTravelOverrideChanged(mode: TravelMode?) {
        val automatic = autoDropPending && mode == null
        autoDropPending = false
        if (state.value.phase == PatrolPhase.IDLE) {
            _state.update { it.copy(travelOverride = mode) }
            return
        }
        applyTravelOverride(mode, automatic)
    }

    private fun applyTravelOverride(mode: TravelMode?, automatic: Boolean) {
        if (state.value.phase == PatrolPhase.MANUAL) advanceManualTo(SystemClock.elapsedRealtime())
        sim.travelOverride = mode
        _state.update { it.copy(travelOverride = if (_joystick.value.enabled) _joystick.value.speedMode else mode) }
        lastNotificationMs = 0
        _events.tryEmit(PatrolEvent.TravelModeChanged(mode, automatic))
        Log.i(TAG, "travel override → ${mode ?: "walk"} (${if (automatic) "auto" else "user"})")
    }

    /** Joystick shown / hidden. Taking over is immediate; giving back re-plans from wherever we ended up. */
    private fun onJoystickToggled(enabled: Boolean) {
        val p = state.value.phase
        if (enabled) {
            if (p == PatrolPhase.WALKING || p == PatrolPhase.DWELLING || p == PatrolPhase.PARKED || p == PatrolPhase.HOLDING) enterManual()
            // PAUSED keeps its frozen position; 繼續 goes to MANUAL because the joystick is up.
        } else if (p == PatrolPhase.MANUAL) {
            leaveManual()
        }
    }

    private fun enterManual() {
        val input = _joystick.value
        manualSpeedMode = input.speedMode
        manualInput.start(SystemClock.elapsedRealtime(), input.bearingDeg, if (input.enabled) input.magnitude else 0.0)
        _state.update {
            it.copy(phase = PatrolPhase.MANUAL, travelOverride = manualSpeedMode, currentWaypointIndex = -1,
                currentWaypointName = getString(R.string.svc_target_joystick), distanceToTargetM = 0.0)
        }
        lastNotificationMs = 0
        manualWake.trySend(Unit)
        Log.i(TAG, "joystick took over at ${sim.current().position}")
    }

    private fun leaveManual() {
        if (state.value.phase == PatrolPhase.MANUAL) advanceManualTo(SystemClock.elapsedRealtime())
        manualInput.stop()
        _state.update { it.copy(travelOverride = _travelOverride.value) }
        if (mode != PatrolMode.WAYPOINTS) {
            selectMovement(mode, sim.current().position)
            return
        }
        lastTickElapsedMs = SystemClock.elapsedRealtime()
        if (waypoints.isEmpty()) { beginReturnHome(); return }
        // Whatever was visited stays visited; the rest of the lap continues from here.
        val cur = sim.current()
        val order = PatrolPlanner.orderFor(lap, waypoints.size, config.loopMode)
        val rest = order.filter { i -> waypoints.getOrNull(i)?.let { it.id !in doneThisLap } ?: false }
        if (rest.isEmpty()) {
            _state.update { it.copy(phase = PatrolPhase.WALKING) }
            onLapFinished(cur)
            return
        }
        loadPlan(PatrolPlanner.planLap(cur.position, waypoints, config, rest, lap))
        val first = plan.segments.first()
        _state.update {
            it.copy(phase = phaseFor(first.kind), currentWaypointIndex = first.waypointIndex ?: -1,
                currentWaypointName = first.waypointIndex?.let { i -> waypoints.getOrNull(i)?.name })
        }
        lastNotificationMs = 0
        _events.tryEmit(PatrolEvent.Replanned("joystick off"))
        Log.i(TAG, "joystick put away at ${cur.position}; ${rest.size} waypoint(s) left in lap $lap")
    }

    // ------------------------------------------------------------------ steps (engine thread)

    private suspend fun maybeFlushSteps(force: Boolean) {
        val now = Instant.now()
        val elapsed = SystemClock.elapsedRealtime()
        val today = LocalDate.now()
        if (!force && today == stepsDay && elapsed - lastStepFlushAttemptMs < config.stepFlushIntervalSec * 1000L) return
        lastStepFlushAttemptMs = elapsed
        val pending = floor(stepsAccrued).toLong() - stepsFlushed
        if (pending > 0) {
            val start = if (flushWindowStart < now) flushWindowStart else now.minusMillis(1)
            val windowSec = Duration.between(start, now).seconds
            val cadenceRoom = if (config.maxCadenceSpm > 0) {
                (config.maxCadenceSpm * windowSec / 60.0).toLong().coerceAtLeast(1)
            } else Long.MAX_VALUE
            val count = if (config.injectSteps) minOf(pending, cadenceRoom) else 0L
            val windows = StepWriteOutbox.windows("${state.value.startedAtMs}-${start.toEpochMilli()}",
                start, now, count, distanceSinceFlush * (count.toDouble() / pending), ZoneId.systemDefault())
            // Never consume steps until the complete window is safely stored, independent of route cleanup.
            stepOutbox.enqueue(windows, StepFlushProgress(state.value.startedAtMs, stepsAccrued,
                floor(stepsAccrued).toLong(), now.toEpochMilli()))
            stepsFlushed += pending
            flushWindowStart = now
            distanceSinceFlush = 0.0
            writeCheckpoint()
        } else {
            flushWindowStart = now
            distanceSinceFlush = 0.0
        }
        val completed = if (config.injectSteps && steps.isAvailable) {
            stepOutbox.drain(config.dailyStepCap, steps::stepsWrittenByUsOn) { batch ->
                steps.write(Instant.ofEpochMilli(batch.startMs), Instant.ofEpochMilli(batch.endMs),
                    batch.acceptedSteps!!, batch.acceptedDistanceM, batch.id, batch.zone, manualEntry = batch.id.startsWith("real-"))
            }
        } else emptyList()
        // Read the current LOCAL day, including after midnight, resume, or deleting records in Settings.
        val current = if (config.injectSteps && steps.isAvailable) steps.stepsWrittenByUsOn(today, ZoneId.systemDefault()) else null
        if (current != null) stepsWrittenToday = current
        else if (today != stepsDay) stepsWrittenToday = 0L
        stepsDay = today
        val retryMessage = getString(R.string.steps_pending_retry)
        _state.update { it.copy(stepsWrittenToday = stepsWrittenToday,
            lastError = if (stepOutbox.hasPending && config.injectSteps) retryMessage
                else it.lastError.takeUnless { error -> error == retryMessage }) }
        completed.sumOf { it.acceptedSteps ?: 0L }.takeIf { it > 0 }?.let {
            _events.tryEmit(PatrolEvent.StepsWritten(it, stepsWrittenToday))
        }
    }

    // ------------------------------------------------------------------ control (engine thread)

    private fun setPaused(paused: Boolean) {
        val p = state.value.phase
        if (paused && (p == PatrolPhase.WALKING || p == PatrolPhase.DWELLING || p == PatrolPhase.MANUAL)) {
            if (p == PatrolPhase.MANUAL) advanceManualTo(SystemClock.elapsedRealtime())
            manualInput.stop()
            _state.update { it.copy(phase = PatrolPhase.PAUSED, speedMps = 0.0) }
        } else if (!paused && p == PatrolPhase.PAUSED) {
            when {
                _joystick.value.enabled -> enterManual()
                // Paused while the joystick had us off-route, and the joystick was put away since:
                // the old plan says nothing about where we are now, so pick the route up from here.
                sim.inManual -> leaveManual()
                else -> _state.update { it.copy(phase = phaseFor(sim.current().kind)) }
            }
        } else if (!paused && p == PatrolPhase.PARKED) {
            // 繼續 from the custom home: another lap, from here.
            val h = home ?: return
            if (mode != PatrolMode.WAYPOINTS) { selectMovement(mode, h); return }
            if (waypoints.isEmpty()) return
            if (_joystick.value.enabled) { enterManual(); return }
            loadLap(h)
            _state.update {
                it.copy(
                    phase = if (plan.isEmpty) PatrolPhase.WALKING else phaseFor(plan.segments[0].kind),
                    currentWaypointIndex = plan.segments.firstOrNull()?.waypointIndex ?: -1,
                    currentWaypointName = plan.segments.firstOrNull()?.waypointIndex?.let { i -> waypoints.getOrNull(i)?.name },
                )
            }
            Log.i(TAG, "left the parking spot for lap $lap")
        }
        lastNotificationMs = 0
    }

    private fun skipWaypoint() {
        val p = state.value.phase
        if (p != PatrolPhase.WALKING && p != PatrolPhase.DWELLING && p != PatrolPhase.PAUSED) return
        val cur = sim.current()
        val idx = cur.waypointIndex ?: return
        waypoints.getOrNull(idx)?.let { doneThisLap += it.id }
        val order = PatrolPlanner.orderFor(lap, waypoints.size, config.loopMode)
        val pos = order.indexOf(idx)
        val rest = if (pos >= 0) order.drop(pos + 1) else emptyList()
        if (rest.isEmpty()) { onLapFinished(cur); return }
        loadPlan(PatrolPlanner.planLap(cur.position, waypoints, config, rest, lap))
    }

    private fun beginReturnHome() {
        val p = state.value.phase
        if (p == PatrolPhase.IDLE || p == PatrolPhase.RETURNING_HOME || p == PatrolPhase.STOPPING || p == PatrolPhase.PARKED) return
        if (p == PatrolPhase.STARTING) { requestStop(); return }
        val h = home ?: run { _events.tryEmit(PatrolEvent.Error(getString(R.string.home_unknown))); return }
        if (p == PatrolPhase.MANUAL) {
            advanceManualTo(SystemClock.elapsedRealtime())
            manualInput.stop()
            _joystick.update { it.copy(enabled = false) }
        }
        val from = sim.current().position
        settleTicks = 0
        // A trip that was driven out is driven back: walking 20 km home would take hours, and the
        // route already declares how such legs are covered. Ordinary patrols still walk.
        val mode = routeTravelMode.takeIf { it != TravelMode.WALK }
        if (config.returnMode == ReturnMode.WALK) {
            loadPlan(PatrolPlanner.planReturnHome(from, h, mode))
        }
        _state.update {
            it.copy(phase = PatrolPhase.RETURNING_HOME, currentWaypointName = getString(R.string.svc_target_home),
                distanceToTargetM = GeoMath.distanceM(from, h))
        }
        lastNotificationMs = 0
        Log.i(TAG, "returning home (${config.returnMode}, ${mode ?: TravelMode.WALK}) from $from to $h, ${"%.0f".format(GeoMath.distanceM(from, h))} m")
    }

    private fun requestStop() {
        when (state.value.phase) {
            PatrolPhase.IDLE -> stopSelf()
            PatrolPhase.STARTING -> stopRequested = true      // start coroutine checks this at its next step
            PatrolPhase.STOPPING -> Unit
            else -> if (pendingFinish == null) {
                if (state.value.phase == PatrolPhase.MANUAL) advanceManualTo(SystemClock.elapsedRealtime())
                manualInput.stop()
                pendingFinish = Finish.Stop
                manualWake.trySend(Unit)
            }
        }
    }

    /** Immediate failure before the tick loop exists (start path). Safe from any thread. */
    private fun failNow(message: String, silent: Boolean = false) {
        Log.w(TAG, "fail: $message")
        _state.update { it.copy(phase = PatrolPhase.STOPPING, lastError = if (silent) null else message) }
        if (!silent) {
            _events.tryEmit(PatrolEvent.Error(message))
            notifications.error(message)
        }
        lifecycleScope.launch(engine) {
            tickJob?.cancel()
            mock.stop()
            withContext(Dispatchers.Main) { teardown(if (silent) null else message) }
        }
    }

    private fun teardown(keepError: String? = null, preserveCheckpoint: Boolean = false) {
        manualInput.stop()
        releaseWakeLock()
        // Every path through here is a clean exit: the game is (or is about to be) on real GPS,
        // so there is nothing to resume from.
        if (!preserveCheckpoint) PatrolCheckpoint.clear(this)
        val last = state.value
        prefs.lastPosition = last.position
        // A vehicle is a one-session thing; the next patrol starts on foot. Same for the joystick.
        _travelOverride.value = null
        _joystick.update { it.copy(enabled = false) }
        _state.value = PatrolState(
            home = last.home ?: prefs.home,
            lastError = keepError,
            stepsWrittenToday = last.stepsWrittenToday,
            mockAppSelected = mock.isMockAppSelected(),
            healthConnectReady = steps.isAvailable,
        )
        stopRequested = false
        pendingFinish = null
        replanPending = false
        doneThisLap.clear()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun maybeUpdateNotification() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotificationMs < NOTIFICATION_INTERVAL_MS) return
        lastNotificationMs = now
        val s = state.value
        if (s.phase == PatrolPhase.IDLE || s.phase == PatrolPhase.STOPPING) return
        runCatching {
            getSystemService(android.app.NotificationManager::class.java).notify(PatrolNotifications.ID_ONGOING, notifications.ongoing(s))
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PikminGPS:patrol").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        manualInput.stop()
        tickJob?.cancel()
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        if (state.value.phase != PatrolPhase.IDLE) {
            // Killed without a clean stop (task swipe, system kill): never leave mock providers behind.
            mock.stop()
            _state.value = PatrolState(home = prefs.home, mockAppSelected = mock.isMockAppSelected(), healthConnectReady = steps.isAvailable)
        }
        releaseWakeLock()
        engine.close()
        super.onDestroy()
    }

    /** The joystick's current input. [enabled] is the on/off switch; the vector is what the pad reports. */
    data class JoystickInput(
        val enabled: Boolean = false, val bearingDeg: Double = 0.0, val magnitude: Double = 0.0,
        val speedMode: TravelMode? = null,
    )

    companion object {
        const val TAG = "PikminGPS"
        private const val PKG = "app.pikminbloom.gps"
        const val ACTION_START = "$PKG.action.START"
        const val ACTION_TELEPORT = "$PKG.action.TELEPORT"
        const val ACTION_SPIRAL = "$PKG.action.SPIRAL"
        const val EXTRA_POSITION_LAT = "position_lat"
        private const val EXTRA_SPIRAL_LINE_WIDTH = "spiral_line_width_m"
        const val EXTRA_POSITION_LON = "position_lon"
        const val ACTION_PAUSE = "$PKG.action.PAUSE"
        const val ACTION_RESUME = "$PKG.action.RESUME"
        const val ACTION_RETURN_HOME = "$PKG.action.RETURN_HOME"
        const val ACTION_STOP = "$PKG.action.STOP"
        const val ACTION_SKIP_WAYPOINT = "$PKG.action.SKIP_WAYPOINT"
        const val ACTION_GO_TO = "$PKG.action.GO_TO"
        const val ACTION_RESUME_CHECKPOINT = "$PKG.action.RESUME_CHECKPOINT"
        const val EXTRA_THEN_RETURN_HOME = "then_return_home"
        const val EXTRA_HOME_LAT = "home_lat"
        const val EXTRA_HOME_LON = "home_lon"
        const val EXTRA_START_AT_INDEX = "start_at_index"
        const val EXTRA_WAYPOINT_INDEX = "waypoint_index"

        private const val NOTIFICATION_INTERVAL_MS = 5_000L
        private const val SETTLE_TICKS = 3

        /** Shorter than this and a lap has no walking in it, so repeating it would just spin. */
        private const val MIN_LAP_M = 5.0
        private const val CHECKPOINT_INTERVAL_MS = 5_000L

        /** Settings that matter while a patrol runs (everything Prefs.config() reads). */
        private val LIVE_CONFIG_KEYS = setOf(
            Prefs.KEY_SPEED_KMH, Prefs.KEY_SPEED_JITTER_PCT, Prefs.KEY_STRIDE_CM, Prefs.KEY_LOOP_MODE,
            Prefs.KEY_ORBIT, Prefs.KEY_INJECT_STEPS, Prefs.KEY_STEP_FLUSH_SEC, Prefs.KEY_DAILY_STEP_CAP,
            Prefs.KEY_MAX_CADENCE, Prefs.KEY_ACC_MIN, Prefs.KEY_ACC_MAX, Prefs.KEY_ALTITUDE,
            Prefs.KEY_NOTIFY_ARRIVAL, Prefs.KEY_VIBRATE_ARRIVAL, Prefs.KEY_ALERT_GAP_SEC,
            Prefs.KEY_AUTO_RETURN_LAPS, Prefs.KEY_RETURN_MODE,
        )

        private val _state = MutableStateFlow(PatrolState())
        val state: StateFlow<PatrolState> = _state
        private val _events = MutableSharedFlow<PatrolEvent>(extraBufferCapacity = 32)
        val events: SharedFlow<PatrolEvent> = _events

        /** Live vehicle override; null = walk at the configured speed. Survives until the patrol ends. */
        private val _travelOverride = MutableStateFlow<TravelMode?>(null)
        val travelOverride: StateFlow<TravelMode?> = _travelOverride

        private const val MANUAL_TICK_MS = 100L
        private val manualInput = ManualInputBuffer()
        private val manualWake = Channel<Unit>(Channel.CONFLATED)

        /** UI state; the timestamped buffer preserves brief gestures between engine ticks. */
        private val _joystick = MutableStateFlow(JoystickInput())
        val joystick: StateFlow<JoystickInput> = _joystick

        val isRunning: Boolean get() = _state.value.phase != PatrolPhase.IDLE

        /** The override choices offered in the UI, in cycling order. */
        val OVERRIDE_CHOICES: List<TravelMode?> = listOf(null, TravelMode.BIKE, TravelMode.CAR, TravelMode.HIGHWAY, TravelMode.PLANE)

        fun setTravelOverride(mode: TravelMode?) {
            if (_joystick.value.enabled) _joystick.update { it.copy(speedMode = mode) }
            else _travelOverride.value = mode
        }

        val controlSpeedMode: TravelMode?
            get() = if (_joystick.value.enabled) _joystick.value.speedMode else _travelOverride.value

        /** Next choice after the current one (overlay button cycles through them). */
        fun cycleTravelOverride(): TravelMode? {
            val choices = if (_joystick.value.enabled) JoystickSpeeds.choices else OVERRIDE_CHOICES
            val i = choices.indexOf(controlSpeedMode)
            val next = choices[(i + 1) % choices.size]
            setTravelOverride(next)
            return next
        }

        fun setJoystickEnabled(enabled: Boolean, speedMode: TravelMode? = null) {
            _joystick.update { if (enabled) it.copy(enabled = true, speedMode = speedMode) else JoystickInput() }
            val input = _joystick.value
            manualInput.update(SystemClock.elapsedRealtime(), input.bearingDeg, if (input.enabled) input.magnitude else 0.0)
        }

        /** Pad input; [magnitude] 0 = standing still. Only meaningful while enabled. */
        fun steer(bearingDeg: Double, magnitude: Double) {
            val bearing = if (bearingDeg.isFinite()) GeoMath.normalizeBearing(bearingDeg) else 0.0
            val amount = if (bearingDeg.isFinite() && magnitude.isFinite()) magnitude.coerceIn(0.0, 1.0) else 0.0
            _joystick.update { it.copy(bearingDeg = bearing, magnitude = amount) }
            manualInput.update(SystemClock.elapsedRealtime(), bearing, if (_joystick.value.enabled) amount else 0.0)
        }

        fun intent(context: Context, action: String): Intent =
            Intent(context, PatrolService::class.java).setAction(action)

        /** Starts the patrol; [homeOverride] reuses a known real position instead of a fresh fix. */
        fun start(context: Context, homeOverride: LatLng? = null, startAtIndex: Int = 0) {
            val i = intent(context, ACTION_START).putExtra(EXTRA_START_AT_INDEX, startAtIndex)
            if (homeOverride != null) i.putExtra(EXTRA_HOME_LAT, homeOverride.lat).putExtra(EXTRA_HOME_LON, homeOverride.lon)
            ContextCompat.startForegroundService(context, i)
        }

        fun teleport(context: Context, position: LatLng) {
            ContextCompat.startForegroundService(context, intent(context, ACTION_TELEPORT)
                .putExtra(EXTRA_POSITION_LAT, position.lat).putExtra(EXTRA_POSITION_LON, position.lon))
        }

        fun startSpiral(context: Context, lineWidthM: Double? = null) {
            val command = intent(context, ACTION_SPIRAL)
            if (lineWidthM != null) command.putExtra(EXTRA_SPIRAL_LINE_WIDTH, lineWidthM)
            ContextCompat.startForegroundService(context, command)
        }

        private fun send(context: Context, action: String, configure: (Intent.() -> Unit)? = null) {
            // The service is a running foreground service whenever these make sense; when it is not,
            // onStartCommand stops itself immediately, which also discharges the start obligation.
            val i = intent(context, action)
            configure?.invoke(i)
            runCatching { ContextCompat.startForegroundService(context, i) }
                .onFailure { Log.w(TAG, "send $action failed: ${it.message}") }
        }

        /** Continue an interrupted patrol from its checkpoint; must be called from a visible activity. */
        fun resumeFromCheckpoint(context: Context, thenReturnHome: Boolean) {
            val i = intent(context, ACTION_RESUME_CHECKPOINT).putExtra(EXTRA_THEN_RETURN_HOME, thenReturnHome)
            ContextCompat.startForegroundService(context, i)
        }

        fun pause(context: Context) = send(context, ACTION_PAUSE)
        fun resume(context: Context) = send(context, ACTION_RESUME)
        fun returnHome(context: Context) = send(context, ACTION_RETURN_HOME)
        fun stop(context: Context) = send(context, ACTION_STOP)
        fun skipWaypoint(context: Context) = send(context, ACTION_SKIP_WAYPOINT)

        /** 立刻前往: head for waypoint [index] of the active route next. */
        fun goTo(context: Context, index: Int) = send(context, ACTION_GO_TO) { putExtra(EXTRA_WAYPOINT_INDEX, index) }
    }
}
