package app.pikminbloom.gps.ui

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import app.pikminbloom.gps.R
import app.pikminbloom.gps.data.PatrolPhase
import app.pikminbloom.gps.data.PatrolState
import app.pikminbloom.gps.data.Prefs
import app.pikminbloom.gps.data.TravelMode
import app.pikminbloom.gps.databinding.OverlayBarBinding
import app.pikminbloom.gps.databinding.OverlayJoystickBinding
import app.pikminbloom.gps.service.PatrolEvent
import app.pikminbloom.gps.service.PatrolNotifications
import app.pikminbloom.gps.service.PatrolService
import app.pikminbloom.gps.vision.FlowerScanner
import app.pikminbloom.gps.vision.ScreenCaptureService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/**
 * The floating control bar (懸浮視窗) drawn on top of Pikmin Bloom.
 *
 * Deliberately **not** a foreground service: [PatrolService] already holds the one foreground
 * service of this app, and this one only lives while the patrol (or the user) wants it to. It is
 * started from the UI ([MainActivity] / [SettingsActivity]) and stopped the same way, or by itself
 * shortly after the patrol goes back to [PatrolPhase.IDLE].
 *
 * Collapsed it is just a round, semi-transparent handle whose colour is the patrol phase. Tapping
 * the handle expands a compact bar with one line of live status and 暫停/繼續 · 回家 · 停止 ·
 * 開啟主畫面. Dragging the handle moves the window (clamped to the display); a long press pins it so
 * it survives the end of a patrol.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: Prefs
    private lateinit var params: WindowManager.LayoutParams

    private var binding: OverlayBarBinding? = null
    private var joystick: OverlayJoystickBinding? = null
    private lateinit var joystickParams: WindowManager.LayoutParams
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private var expanded = false
    private var pinned = false
    private var touchSlop = 24

    private var lastPhase: PatrolPhase? = null
    private var lastRenderMs = 0L
    private var flashText: String? = null
    private var flashUntilMs = 0L
    private var lastArrivalIndex = -1
    private var lastArrivalMs = 0L

    // drag state
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false
    private var longPressed = false

    private val hideRunnable = Runnable {
        Log.i(TAG, "overlay auto-hide (patrol idle)")
        stopSelf()
    }

    private val longPressRunnable = Runnable {
        longPressed = true
        togglePinned()
    }

    /** Redraws once a flash message expires; the state alone may not tick again (StateFlow dedupes). */
    private val renderRunnable = Runnable { render(PatrolService.state.value) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        prefs = Prefs(this)
        pinned = prefs.overlayPinned
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        if (!Permissions.canDrawOverlays(this)) {
            Log.w(TAG, "overlay permission missing, not showing the floating bar")
            stopSelf()
            return
        }
        if (!addOverlayView()) {
            stopSelf()
            return
        }
        isRunning = true
        collectPatrol()
        render(PatrolService.state.value)
        onPhaseChanged(PatrolService.state.value)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (binding == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.getBooleanExtra(EXTRA_SHOW_JOYSTICK, false) == true) {
            if (addJoystickWindow()) PatrolService.setJoystickEnabled(true)
            setExpanded(true)
        }
        if (intent?.hasExtra(EXTRA_PINNED) == true) {
            pinned = intent.getBooleanExtra(EXTRA_PINNED, false)
            prefs.overlayPinned = pinned
            if (pinned) {
                handler.removeCallbacks(hideRunnable)
                // Shown by hand from the app: open it expanded so the user sees what appeared.
                setExpanded(true)
            } else {
                scheduleIdleHideIfNeeded(PatrolService.state.value.phase)
            }
        }
        render(PatrolService.state.value)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        binding?.let { b ->
            runCatching { windowManager.removeViewImmediate(b.root) }
                .onFailure { Log.w(TAG, "removeViewImmediate failed", it) }
        }
        binding = null
        removeJoystickWindow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ window

    private fun addOverlayView(): Boolean = try {
        // An overlay has no activity theme; wrap the service context so the layout resolves ?attr/*.
        val themed = ContextThemeWrapper(this, R.style.Theme_PikminGps)
        val b = OverlayBarBinding.inflate(LayoutInflater.from(themed))

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val (screenW, screenH) = screenSize()
            x = prefs.overlayX.takeIf { it != Prefs.OVERLAY_UNSET } ?: dp(DEFAULT_MARGIN_DP)
            y = prefs.overlayY.takeIf { it != Prefs.OVERLAY_UNSET } ?: (screenH / 3)
            x = x.coerceIn(0, max(0, screenW - dp(HANDLE_DP)))
            y = y.coerceIn(0, max(0, screenH - dp(HANDLE_DP)))
        }

        windowManager.addView(b.root, params)
        binding = b
        wire(b)
        b.root.post { clampAndApply() }
        Log.i(TAG, "overlay shown at ${params.x},${params.y} (pinned=$pinned)")
        true
    } catch (t: Throwable) {
        // SecurityException when the permission was revoked between the check and addView,
        // BadTokenException on some OEM builds that block background overlays.
        Log.w(TAG, "cannot add the overlay window", t)
        false
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun wire(b: OverlayBarBinding) {
        // FLAG_WATCH_OUTSIDE_TOUCH: a tap anywhere else collapses the bar (the touch still reaches
        // the app below, this is only a notification).
        b.root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                setExpanded(false)
                true
            } else {
                false
            }
        }
        b.handle.setOnTouchListener { _, event -> onHandleTouch(event) }

        b.btnToggle.setOnClickListener {
            val p = PatrolService.state.value.phase
            if (p == PatrolPhase.PAUSED || p == PatrolPhase.PARKED) PatrolService.resume(this)
            else PatrolService.pause(this)
        }
        b.btnHome.setOnClickListener { PatrolService.returnHome(this) }
        b.btnStop.setOnClickListener { PatrolService.stop(this) }
        b.btnScan.setOnClickListener { onScanClicked() }
        b.btnVehicle.setOnClickListener { onVehicleClicked() }
        b.btnJoystick.setOnClickListener { onJoystickClicked() }
        b.btnOpen.setOnClickListener { openMainActivity() }
    }

    /** Cycles the vehicle override; the status line says which one is now in force. */
    private fun onVehicleClicked() {
        val next = PatrolService.cycleTravelOverride()
        flash(getString(R.string.ovl_travel_flash, travelLabel(next)))
    }

    private fun travelLabel(mode: TravelMode?): String =
        if (mode == null) getString(R.string.travel_short_walk) else "${mode.label} ${mode.speedKmh.toInt()} km/h"

    /**
     * 搖桿 toggle: shows the pad (a second overlay window) and hands the walk over to it. Without
     * a patrol it only shows the pad; the service picks it up the moment a patrol starts.
     */
    private fun onJoystickClicked() {
        if (PatrolService.joystick.value.enabled) {
            PatrolService.setJoystickEnabled(false)
            removeJoystickWindow()
            toast(R.string.toast_joystick_off)
        } else {
            if (!addJoystickWindow()) return
            PatrolService.setJoystickEnabled(true)
            toast(if (PatrolService.isRunning) R.string.toast_joystick_on else R.string.toast_joystick_need_patrol)
        }
        render(PatrolService.state.value)
    }

    /** The pad sits at the bottom-left corner, clear of the game's own bottom-centre buttons. */
    private fun addJoystickWindow(): Boolean {
        if (joystick != null) return true
        return try {
            val themed = ContextThemeWrapper(this, R.style.Theme_PikminGps)
            val j = OverlayJoystickBinding.inflate(LayoutInflater.from(themed))
            val size = dp(JOYSTICK_DP)
            joystickParams = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                x = dp(JOYSTICK_MARGIN_DP)
                y = dp(JOYSTICK_MARGIN_DP * 6)
            }
            j.pad.onSteer = { bearing, magnitude -> PatrolService.steer(bearing, magnitude) }
            windowManager.addView(j.root, joystickParams)
            joystick = j
            true
        } catch (t: Throwable) {
            Log.w(TAG, "cannot add the joystick window", t)
            false
        }
    }

    private fun removeJoystickWindow() {
        joystick?.let { j ->
            runCatching { windowManager.removeViewImmediate(j.root) }
                .onFailure { Log.w(TAG, "removeViewImmediate(joystick) failed", it) }
        }
        joystick = null
    }

    /**
     * 掃描 toggle. The projection consent can only be obtained by an Activity, so without one the
     * button hands over to MainActivity, which runs the explanation + consent flow and comes back.
     */
    private fun onScanClicked() {
        when {
            FlowerScanner.isRunning -> {
                FlowerScanner.stop(this)
                toast(R.string.toast_scan_stopped)
            }
            ScreenCaptureService.isRunning.value -> FlowerScanner.start(this)
            else -> openMainActivity(startScan = true)
        }
    }

    /** Drag with a slop threshold so a tap still expands/collapses; long press toggles the pin. */
    private fun onHandleTouch(event: MotionEvent): Boolean = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            downRawX = event.rawX
            downRawY = event.rawY
            startX = params.x
            startY = params.y
            dragging = false
            longPressed = false
            handler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            true
        }

        MotionEvent.ACTION_MOVE -> {
            val dx = event.rawX - downRawX
            val dy = event.rawY - downRawY
            if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                dragging = true
                handler.removeCallbacks(longPressRunnable)
            }
            if (dragging) {
                params.x = (startX + dx).toInt()
                params.y = (startY + dy).toInt()
                clampAndApply()
            }
            true
        }

        MotionEvent.ACTION_UP -> {
            handler.removeCallbacks(longPressRunnable)
            when {
                dragging -> savePosition()
                longPressed -> Unit
                else -> setExpanded(!expanded)
            }
            true
        }

        MotionEvent.ACTION_CANCEL -> {
            handler.removeCallbacks(longPressRunnable)
            if (dragging) savePosition()
            true
        }

        else -> false
    }

    private fun clampAndApply() {
        val b = binding ?: return
        val (screenW, screenH) = screenSize()
        val w = if (b.root.width > 0) b.root.width else dp(HANDLE_DP)
        val h = if (b.root.height > 0) b.root.height else dp(HANDLE_DP)
        params.x = params.x.coerceIn(0, max(0, screenW - w))
        params.y = params.y.coerceIn(0, max(0, screenH - h))
        runCatching { windowManager.updateViewLayout(b.root, params) }
            .onFailure { Log.w(TAG, "updateViewLayout failed", it) }
    }

    private fun savePosition() {
        prefs.overlayX = params.x
        prefs.overlayY = params.y
    }

    private fun setExpanded(value: Boolean) {
        val b = binding ?: return
        if (expanded == value) return
        expanded = value
        b.bar.visibility = if (value) View.VISIBLE else View.GONE
        render(PatrolService.state.value)
        // The window just changed width; re-clamp once it has been measured again.
        b.root.post { clampAndApply() }
    }

    private fun togglePinned() {
        pinned = !pinned
        prefs.overlayPinned = pinned
        if (pinned) handler.removeCallbacks(hideRunnable)
        else scheduleIdleHideIfNeeded(PatrolService.state.value.phase)
        toast(if (pinned) R.string.toast_overlay_pinned else R.string.toast_overlay_unpinned)
    }

    private fun openMainActivity(startScan: Boolean = false) {
        // Allowed from the background because we hold SYSTEM_ALERT_WINDOW.
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (startScan) intent.putExtra(MainActivity.EXTRA_START_SCAN, true)
        runCatching { startActivity(intent) }.onFailure { Log.w(TAG, "cannot open MainActivity", it) }
        setExpanded(false)
    }

    // ------------------------------------------------------------------ state

    private fun collectPatrol() {
        scope.launch {
            PatrolService.state.collect { state ->
                val now = SystemClock.uptimeMillis()
                val phaseChanged = state.phase != lastPhase
                if (phaseChanged) onPhaseChanged(state)
                // The service ticks at 1 Hz; never redraw faster than twice a second anyway.
                if (phaseChanged || now - lastRenderMs >= MIN_RENDER_INTERVAL_MS) render(state)
            }
        }
        scope.launch {
            PatrolService.events.collect { event ->
                when (event) {
                    is PatrolEvent.ArrivedAtWaypoint -> onArrived(event)

                    is PatrolEvent.ReturnedHome -> flash(getString(R.string.ovl_returned_home))
                    is PatrolEvent.ParkedAtHome -> { flash(getString(R.string.snack_parked)); setExpanded(true) }
                    is PatrolEvent.TravelModeChanged ->
                        flash(if (event.automatic) getString(R.string.snack_travel_auto_walk) else getString(R.string.ovl_travel_flash, travelLabel(event.mode)))
                    is PatrolEvent.ConfigChanged -> flash(getString(R.string.snack_speed_changed, "%.1f".format(event.speedKmh)))
                    is PatrolEvent.Replanned -> flash(getString(R.string.snack_replanned))
                    is PatrolEvent.Error -> flash(event.message)
                    else -> Unit
                }
            }
        }
        scope.launch { PatrolService.travelOverride.collect { render(PatrolService.state.value) } }
        scope.launch {
            PatrolService.joystick.map { it.enabled }.distinctUntilChanged().collect { enabled ->
                // The service drops the joystick on 回家 / 停止; take the pad down with it.
                if (!enabled) removeJoystickWindow()
                render(PatrolService.state.value)
            }
        }
        scope.launch {
            FlowerScanner.state.collect { scan ->
                when (scan) {
                    // Terminal states are announced once, then the line goes back to patrol status.
                    is FlowerScanner.ScanState.Done -> flash(getString(R.string.scan_status_done, scan.found.size))
                    is FlowerScanner.ScanState.Error -> flash(getString(R.string.scan_status_error, scan.message))
                    // The one waiting state the user must act on: make sure the line is visible.
                    is FlowerScanner.ScanState.WaitingForBirdsEye -> if (scan.blank) setExpanded(true)
                    is FlowerScanner.ScanState.Scanning ->
                        if (scan.found.size > lastAnnouncedFound) {
                            lastAnnouncedFound = scan.found.size
                            setExpanded(true)
                        }
                    else -> Unit
                }
                render(PatrolService.state.value)
            }
        }
    }

    private var lastAnnouncedFound = 0

    /**
     * A single-waypoint LOOP patrol re-arrives at the same flower on every tick, so only announce
     * the same waypoint again after [ARRIVAL_REPEAT_MS] — otherwise the bar would never close.
     */
    private fun onArrived(event: PatrolEvent.ArrivedAtWaypoint) {
        val now = SystemClock.uptimeMillis()
        if (event.index == lastArrivalIndex && now - lastArrivalMs < ARRIVAL_REPEAT_MS) return
        lastArrivalIndex = event.index
        lastArrivalMs = now
        flash(getString(R.string.ovl_arrived, event.name))
        setExpanded(true)
    }

    private fun onPhaseChanged(state: PatrolState) {
        val previous = lastPhase
        lastPhase = state.phase
        if (state.phase == PatrolPhase.IDLE) {
            // Show a short 已停止 before disappearing, so the user sees why the bar went away.
            if (previous != null && previous != PatrolPhase.IDLE) setExpanded(true)
            scheduleIdleHideIfNeeded(state.phase)
        } else {
            handler.removeCallbacks(hideRunnable)
        }
    }

    private fun scheduleIdleHideIfNeeded(phase: PatrolPhase) {
        handler.removeCallbacks(hideRunnable)
        if (phase == PatrolPhase.IDLE && !pinned) handler.postDelayed(hideRunnable, IDLE_HIDE_MS)
    }

    private fun flash(text: String) {
        flashText = text
        flashUntilMs = SystemClock.uptimeMillis() + FLASH_MS
        handler.removeCallbacks(renderRunnable)
        handler.postDelayed(renderRunnable, FLASH_MS + 50L)
        render(PatrolService.state.value)
    }

    // ------------------------------------------------------------------ rendering

    private fun render(state: PatrolState) {
        val b = binding ?: return
        lastRenderMs = SystemClock.uptimeMillis()

        val color = ContextCompat.getColor(this, phaseColor(state))
        b.handle.backgroundTintList = ColorStateList.valueOf(color)
        b.status.setTextColor(color)

        if (!expanded) return

        val scan = FlowerScanner.state.value
        b.status.text = flashText?.takeIf { SystemClock.uptimeMillis() < flashUntilMs }
            ?: scanStatusText(scan)?.let { "$it${getString(R.string.ovl_separator)}${getString(phaseLabel(state.phase))}" }
            ?: statusText(state)

        val paused = state.phase == PatrolPhase.PAUSED || state.phase == PatrolPhase.PARKED
        val moving = state.phase == PatrolPhase.WALKING || state.phase == PatrolPhase.DWELLING || state.phase == PatrolPhase.MANUAL
        b.btnToggle.setImageResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause)
        b.btnToggle.contentDescription =
            getString(if (paused) R.string.ovl_cd_resume else R.string.ovl_cd_pause)
        enable(b.btnToggle, moving || paused)
        enable(b.btnHome, state.home != null && (moving || state.phase == PatrolPhase.PAUSED || state.phase == PatrolPhase.HOLDING))
        // Vehicle icon shows what is in force; joystick icon lights up while the pad is out.
        val vehicle = PatrolService.travelOverride.value
        b.btnVehicle.setImageResource(if (vehicle == null) R.drawable.ic_walk else R.drawable.ic_car)
        b.btnVehicle.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (vehicle == null) R.color.overlay_icon else R.color.overlay_phase_paused),
        )
        val pad = PatrolService.joystick.value.enabled
        b.btnJoystick.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (pad) R.color.overlay_phase_manual else R.color.overlay_icon),
        )
        enable(b.btnStop, state.phase != PatrolPhase.IDLE && state.phase != PatrolPhase.STOPPING)
        // The scan button reads as "on" while a scan runs; it is always tappable because without a
        // projection it simply opens the app to ask for one.
        val scanning = FlowerScanner.isRunning
        b.btnScan.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (scanning) R.color.overlay_phase_paused else R.color.overlay_icon),
        )
        b.btnScan.alpha = if (scanning || scan.isActive) 1f else 0.7f
    }

    /** The scan's one-liner while a scan is in progress (校準中 12 m / 已找到 3 朵 / 請切到俯瞰模式), else null. */
    private fun scanStatusText(scan: FlowerScanner.ScanState): String? = when (scan) {
        is FlowerScanner.ScanState.WaitingForBirdsEye -> scan.reason
        is FlowerScanner.ScanState.Calibrating -> getString(R.string.scan_status_calibrating, scan.metresSoFar.toInt())
        is FlowerScanner.ScanState.Scanning -> getString(R.string.scan_status_scanning, scan.found.size)
        FlowerScanner.ScanState.NeedProjection -> getString(R.string.scan_status_need_projection)
        else -> null
    }

    /** One short line: 階段 · 目標 + 距離 · 本次步數. It sits on top of a game, so keep it tiny. */
    private fun statusText(state: PatrolState): String {
        if (state.phase == PatrolPhase.IDLE) return getString(R.string.ovl_stopped)
        val parts = ArrayList<String>(3)
        parts += getString(phaseLabel(state.phase))
        val name = state.currentWaypointName
        parts += if (name.isNullOrBlank()) {
            PatrolNotifications.formatDistance(state.distanceWalkedM)
        } else {
            getString(R.string.ovl_target, name, PatrolNotifications.formatDistance(state.distanceToTargetM))
        }
        parts += getString(R.string.ovl_steps, state.sessionSteps)
        return parts.joinToString(getString(R.string.ovl_separator))
    }

    private fun phaseLabel(phase: PatrolPhase): Int = when (phase) {
        PatrolPhase.IDLE -> R.string.ovl_phase_idle
        PatrolPhase.STARTING -> R.string.ovl_phase_starting
        PatrolPhase.WALKING -> R.string.ovl_phase_walking
        PatrolPhase.DWELLING -> R.string.ovl_phase_dwelling
        PatrolPhase.PAUSED -> R.string.ovl_phase_paused
        PatrolPhase.RETURNING_HOME -> R.string.ovl_phase_returning
        PatrolPhase.STOPPING -> R.string.ovl_phase_stopping
        PatrolPhase.PARKED -> R.string.ovl_phase_parked
        PatrolPhase.MANUAL -> R.string.ovl_phase_manual
        PatrolPhase.HOLDING -> R.string.phase_holding
    }

    @ColorRes
    private fun phaseColor(state: PatrolState): Int = when {
        !state.lastError.isNullOrBlank() -> R.color.overlay_phase_error
        state.phase == PatrolPhase.PAUSED -> R.color.overlay_phase_paused
        state.phase == PatrolPhase.PARKED -> R.color.overlay_phase_parked
        state.phase == PatrolPhase.MANUAL -> R.color.overlay_phase_manual
        state.phase == PatrolPhase.RETURNING_HOME -> R.color.overlay_phase_returning
        state.phase == PatrolPhase.IDLE || state.phase == PatrolPhase.STOPPING -> R.color.overlay_phase_idle
        else -> R.color.overlay_phase_normal
    }

    private fun enable(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.35f
    }

    // ------------------------------------------------------------------ helpers

    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "PikminGPS"
        private const val EXTRA_PINNED = "pinned"
        private const val EXTRA_SHOW_JOYSTICK = "show_joystick"
        private const val MIN_RENDER_INTERVAL_MS = 500L
        private const val IDLE_HIDE_MS = 3_000L
        private const val FLASH_MS = 8_000L
        private const val ARRIVAL_REPEAT_MS = 60_000L
        private const val HANDLE_DP = 48
        private const val JOYSTICK_DP = 150
        private const val JOYSTICK_MARGIN_DP = 16
        private const val DEFAULT_MARGIN_DP = 8

        /** Process-local; the UI uses it to render a "show / hide" toggle. */
        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * Shows the bar. [pinned] = true keeps it on screen after the patrol stops (manual toggle);
         * null leaves the stored pin state alone. Does nothing without the overlay permission.
         */
        fun start(context: Context, pinned: Boolean? = null) {
            if (!Permissions.canDrawOverlays(context)) {
                Log.w(TAG, "overlay not started: no SYSTEM_ALERT_WINDOW permission")
                return
            }
            val intent = Intent(context, OverlayService::class.java)
            if (pinned != null) intent.putExtra(EXTRA_PINNED, pinned)
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "startService(OverlayService) failed", it) }
        }

        /** Puts the joystick pad up (starting the bar if needed). No-op without the overlay permission. */
        fun showJoystick(context: Context) {
            if (!Permissions.canDrawOverlays(context)) return
            // Pinned as well: an idle bar would otherwise hide itself (and the pad) three seconds later.
            val intent = Intent(context, OverlayService::class.java).putExtra(EXTRA_SHOW_JOYSTICK, true).putExtra(EXTRA_PINNED, true)
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "startService(OverlayService, joystick) failed", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, OverlayService::class.java)) }
                .onFailure { Log.w(TAG, "stopService(OverlayService) failed", it) }
        }
    }
}
