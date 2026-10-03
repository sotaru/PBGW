package app.pikminbloom.gps.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.pikminbloom.gps.R
import app.pikminbloom.gps.BuildConfig
import app.pikminbloom.gps.data.MapProvider
import app.pikminbloom.gps.data.PatrolPhase
import app.pikminbloom.gps.data.PatrolMode
import app.pikminbloom.gps.data.PatrolState
import app.pikminbloom.gps.data.Prefs
import app.pikminbloom.gps.service.RealGpsStepsService
import app.pikminbloom.gps.service.RealStepsPhase
import app.pikminbloom.gps.service.RealStepsState
import app.pikminbloom.gps.data.TravelMode
import app.pikminbloom.gps.data.JoystickSpeeds
import app.pikminbloom.gps.data.Waypoint
import app.pikminbloom.gps.data.WaypointStore
import app.pikminbloom.gps.databinding.ActivityMainBinding
import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.geo.LatLng
import app.pikminbloom.gps.geo.GpsMotion
import app.pikminbloom.gps.mock.MockLocationController
import app.pikminbloom.gps.route.PatrolPlanner
import app.pikminbloom.gps.route.SpiralRoute
import app.pikminbloom.gps.route.forCollection
import app.pikminbloom.gps.service.PatrolCheckpoint
import app.pikminbloom.gps.service.PatrolEvent
import app.pikminbloom.gps.service.PatrolService
import app.pikminbloom.gps.steps.StepInjector
import app.pikminbloom.gps.vision.FlowerScanPlan
import app.pikminbloom.gps.vision.FlowerScanner
import app.pikminbloom.gps.vision.ScreenCaptureService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.MapEventsOverlay
import kotlin.coroutines.resume

/**
 * Map + control screen: shows the Big Flowers, the planned route and the simulated walk, and drives
 * [PatrolService] through the pre-flight checks described in docs/PLAN.md §3.2.
 */
class MainActivity : AppCompatActivity(), MapEventsReceiver {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var store: WaypointStore
    private lateinit var mock: MockLocationController
    private lateinit var steps: StepInjector
    private lateinit var overlays: PatrolMapOverlays
    private lateinit var osmOverlays: MapOverlays
    private var googleLayer: GoogleMapLayer? = null
    private var mapProvider = MapProvider.OPENSTREETMAP
    private var lastCenteredPosition = FALLBACK_CENTER
    private var googleSavedState: Bundle? = null

    private lateinit var locationLauncher: ActivityResultLauncher<String>
    private lateinit var notificationLauncher: ActivityResultLauncher<String>
    private lateinit var healthLauncher: ActivityResultLauncher<Set<String>>
    private lateinit var importLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var exportJsonLauncher: ActivityResultLauncher<String>
    private lateinit var exportGpxLauncher: ActivityResultLauncher<String>
    private lateinit var overlayLauncher: ActivityResultLauncher<Intent>
    private lateinit var scanLauncher: ActivityResultLauncher<Intent>

    /** Set by the 「先開始巡邏」 branch of the scan flow: continue to the consent once the walk begins. */
    private var scanAfterPatrolStart = false

    /** Set when the overlay's 掃描 button opened us (EXTRA_START_SCAN); handled in onResume. */
    private var pendingScanRequest = false
    private var scanResultsDialog: androidx.appcompat.app.AlertDialog? = null
    private var joystickSpeedDialog: androidx.appcompat.app.AlertDialog? = null

    private var locationCallback: ((Boolean) -> Unit)? = null
    private var notificationCallback: ((Boolean) -> Unit)? = null
    private var healthCallback: ((Set<String>) -> Unit)? = null

    private val trail = ArrayList<GeoPoint>(TRAIL_LIMIT)
    private var trailLast: LatLng? = null
    private var lastBearing = 0f
    private var currentHome: LatLng? = null
    private var detailsExpanded = false
    private var centeredOnRealPosition = false

    /** Last phase we reacted to, so the floating bar is only started/stopped on a real transition. */
    private var overlayPhase: PatrolPhase? = null

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        prefs = Prefs(this)
        store = WaypointStore.get(this)
        mock = MockLocationController(this)
        steps = StepInjector(this)
        currentHome = prefs.customHome ?: prefs.home

        registerLaunchers()
        detailsExpanded = savedInstanceState?.getBoolean("details_expanded") ?: false
        renderDetailToggle()
        applyInsets()
        setupMap()
        binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> sizeControlPanel() }
        binding.topChrome.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> sizeControlPanel(); updateGoogleInsets() }
        binding.bottomCard.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateGoogleInsets() }
        googleSavedState = savedInstanceState?.getBundle("google_map_state")
        if (prefs.mapProvider == MapProvider.GOOGLE) switchMap(MapProvider.GOOGLE)
        setupButtons()
        collectFlows()

        maybeShowDisclaimer()
        handleScanIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleScanIntent(intent)
    }

    private fun handleScanIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_START_SCAN, false) == true) {
            intent.removeExtra(EXTRA_START_SCAN)
            pendingScanRequest = true
        }
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        googleLayer?.onResume()
        currentHome = homeForDisplay(PatrolService.state.value)
        rebuildOverlays()
        render(PatrolService.state.value)
        maybeOfferResume()
        if (pendingScanRequest) {
            pendingScanRequest = false
            startScanFlow()
        } else {
            maybeShowScanResults()
        }
    }

    private var resumeDialogShown = false

    /**
     * The previous patrol died without a clean stop (thermal kill, crash). The game is still
     * parked at the checkpoint position and the stale providers are deliberately left in place,
     * so whatever the user picks here can start from that exact spot with nothing jumping.
     */
    private fun maybeOfferResume() {
        if (resumeDialogShown || PatrolService.isRunning) return
        val cp = PatrolCheckpoint.resumable(this) ?: return
        resumeDialogShown = true
        val wpName = when (cp.mode) {
            PatrolMode.SPIRAL -> getString(R.string.target_spiral)
            PatrolMode.HOLD -> getString(R.string.target_hold)
            PatrolMode.WAYPOINTS -> store.routeList().firstOrNull { it.id == cp.routeId }?.name ?: store.activeRouteName()
        }
        val msg = getString(
            R.string.dlg_resume_msg,
            ageText(cp.ageMs),
            wpName,
            distanceText(cp.distanceWalkedM),
            cp.home?.let { distanceText(GeoMath.distanceM(cp.position, it)) } ?: getString(R.string.home_unknown),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dlg_resume_title)
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton(R.string.action_resume_patrol) { _, _ ->
                lifecycleScope.launch {
                    if (preflight(requireWaypoints = cp.mode == PatrolMode.WAYPOINTS, checkHealth = cp.mode != PatrolMode.HOLD)) PatrolService.resumeFromCheckpoint(this@MainActivity, thenReturnHome = false)
                    else resumeDialogShown = false
                }
            }
            .setNeutralButton(R.string.action_resume_go_home) { _, _ ->
                lifecycleScope.launch {
                    if (cp.home != null && preflight(requireWaypoints = false)) PatrolService.resumeFromCheckpoint(this@MainActivity, thenReturnHome = true)
                    else resumeDialogShown = false
                }
            }
            .setNegativeButton(R.string.action_discard_checkpoint) { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.dlg_discard_title)
                    .setMessage(R.string.dlg_discard_msg)
                    .setPositiveButton(R.string.action_discard_checkpoint) { _, _ ->
                        PatrolCheckpoint.clear(this)
                        // Now the stale providers really are stale: hand the game back to real GPS.
                        mock.stop()
                        toast(getString(R.string.toast_checkpoint_discarded))
                    }
                    .setNegativeButton(R.string.action_cancel) { _, _ -> resumeDialogShown = false }
                    .show()
            }
            .show().apply { getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).isEnabled = cp.home != null }
    }

    private fun ageText(ms: Long): String {
        val minutes = ms / 60_000L
        return if (minutes < 60) getString(R.string.fmt_minutes_ago, minutes)
        else getString(R.string.fmt_hours_ago, minutes / 60)
    }

    override fun onPause() {
        googleLayer?.onPause()
        binding.map.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        joystickSpeedDialog?.dismiss()
        googleLayer?.onDestroy()
        runCatching { binding.map.onDetach() }
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        googleLayer?.onStart()
    }

    override fun onStop() {
        googleLayer?.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        googleLayer?.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        googleLayer?.let { outState.putBundle("google_map_state", it.saveState()) }
        outState.putBoolean("details_expanded", detailsExpanded)
        super.onSaveInstanceState(outState)
    }

    // ------------------------------------------------------------------ setup

    private fun registerLaunchers() {
        locationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            locationCallback?.invoke(granted)
            locationCallback = null
        }
        notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationCallback?.invoke(granted)
            notificationCallback = null
        }
        healthLauncher = registerForActivityResult(steps.permissionContract()) { granted ->
            healthCallback?.invoke(granted)
            healthCallback = null
        }
        importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importFrom(uri)
        }
        exportJsonLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument(MIME_JSON)) { uri ->
            if (uri != null) exportTo(uri) { store.exportJson() }
        }
        exportGpxLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument(MIME_GPX)) { uri ->
            if (uri != null) exportTo(uri) { store.exportGpx() }
        }
        // The overlay permission screen has no result; we just re-check when the user comes back.
        overlayLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Permissions.canDrawOverlays(this)) {
                prefs.overlayEnabled = true
                showOverlay()
            } else {
                toast(getString(R.string.toast_overlay_denied))
            }
        }
        // MediaProjection consent for the bird's-eye scan. The token in `data` is single-use and
        // short-lived, so the capture service is started right here with it.
        scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                onProjectionGranted(result.resultCode, data)
            } else {
                toast(getString(R.string.toast_scan_denied))
            }
        }
    }

    private fun applyInsets() {
        val margin = resources.getDimensionPixelSize(R.dimen.space_m)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topChrome.updatePadding(top = bars.top, left = bars.left, right = bars.right)
            (binding.bottomCard.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.bottomMargin = bars.bottom + margin
                lp.leftMargin = bars.left + margin
                lp.rightMargin = bars.right + margin
                binding.bottomCard.layoutParams = lp
            }
            binding.root.post { sizeControlPanel(); updateGoogleInsets() }
            insets
        }
    }

    private fun sizeControlPanel() {
        if (binding.root.width == 0 || binding.root.height == 0) return
        val landscape = binding.root.width > binding.root.height
        val lp = binding.bottomCard.layoutParams as ViewGroup.MarginLayoutParams
        val available = (binding.root.height - binding.topChrome.height - lp.bottomMargin - 16.dp()).coerceAtLeast(1)
        binding.panelScroll.maximumHeight = if (landscape) available else minOf((binding.root.height * 0.62f).toInt(), available)
        val width = minOf(if (landscape) (binding.root.width * 0.46f).toInt() else binding.root.width - lp.leftMargin - lp.rightMargin, 600.dp()).coerceAtLeast(1)
        val cardParams = lp as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
        val gravity = android.view.Gravity.BOTTOM or if (landscape) android.view.Gravity.RIGHT else android.view.Gravity.CENTER_HORIZONTAL
        if (lp.width != width || cardParams.gravity != gravity) {
            cardParams.width = width; cardParams.gravity = gravity; binding.bottomCard.layoutParams = cardParams
        }
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()

    private fun renderDetailToggle() {
        show(binding.detailContent, detailsExpanded)
        binding.btnDetails.setText(if (detailsExpanded) R.string.ui_collapse else R.string.ui_expand)
        binding.btnDetails.setIconResource(if (detailsExpanded) R.drawable.ic_arrow_downward else R.drawable.ic_arrow_upward)
        ViewCompat.setStateDescription(binding.btnDetails, getString(if (detailsExpanded) R.string.ui_collapse else R.string.ui_expand))
    }

    private fun setupMap() {
        binding.map.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setTilesScaledToDpi(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(DEFAULT_ZOOM)
        }
        osmOverlays = MapOverlays(this, binding.map, MapEventsOverlay(this), ::onWaypointTapped)
        overlays = osmOverlays
        rebuildOverlays()
        centerInitially()
    }

    private fun setupButtons() {
        binding.btnRoutes.setOnClickListener { showWaypointList() }
        binding.btnJump.setOnClickListener { showJumpSheet() }
        binding.btnTools.setOnClickListener { showToolsSheet() }
        binding.btnDetails.setOnClickListener {
            detailsExpanded = !detailsExpanded
            renderDetailToggle()
        }
        binding.btnStart.setOnClickListener { showPatrolModeDialog() }
        binding.btnPause.setOnClickListener { if (RealGpsStepsService.isRunning) RealGpsStepsService.pause(this) else PatrolService.pause(this) }
        binding.btnResume.setOnClickListener { if (RealGpsStepsService.isRunning) RealGpsStepsService.resume(this) else PatrolService.resume(this) }
        binding.btnHome.setOnClickListener { PatrolService.returnHome(this) }
        binding.btnStop.setOnClickListener { confirmStop() }
        binding.btnTravel.setOnClickListener { showTravelDialog() }
        binding.btnJoystick.setOnClickListener { toggleJoystick() }
    }

    /** A plain stop leaves the game at the fake position (looks like a teleport); suggest 回家 first. */
    private fun confirmStop() {
        if (RealGpsStepsService.isRunning) { RealGpsStepsService.stop(this); return }
        if (PatrolService.state.value.mode != PatrolMode.WAYPOINTS) {
            PatrolService.stop(this)
            return
        }
        val phase = PatrolService.state.value.phase
        if (phase == PatrolPhase.PARKED) {
            // Parked at the custom home: stopping is the one deliberate teleport back to real GPS.
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dlg_stop_parked_title)
                .setMessage(R.string.dlg_stop_parked_msg)
                .setPositiveButton(R.string.btn_stop) { _, _ -> PatrolService.stop(this) }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
            return
        }
        val movingOrPaused = phase == PatrolPhase.WALKING || phase == PatrolPhase.DWELLING ||
            phase == PatrolPhase.PAUSED || phase == PatrolPhase.MANUAL
        if (!movingOrPaused) {
            PatrolService.stop(this)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dlg_stop_title)
            .setMessage(R.string.dlg_stop_msg)
            .setPositiveButton(R.string.action_return_home_first) { _, _ -> PatrolService.returnHome(this) }
            .setNegativeButton(R.string.action_stop_anyway) { _, _ -> PatrolService.stop(this) }
            .setNeutralButton(R.string.action_cancel, null)
            .show()
    }

    private fun collectFlows() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    store.waypoints.collect {
                        rebuildOverlays()
                        render(PatrolService.state.value)
                    }
                }
                launch { PatrolService.state.collect { render(it) } }
                launch { RealGpsStepsService.state.collect { render(PatrolService.state.value) } }
                launch { PatrolService.events.collect { onEvent(it) } }
                launch { FlowerScanner.state.collect { renderScan(it) } }
                launch { PatrolService.travelOverride.collect { renderLiveControls() } }
                launch { PatrolService.joystick.map { it.enabled to it.speedMode }.distinctUntilChanged().collect { renderLiveControls() } }
            }
        }
    }

    // ------------------------------------------------------------------ live controls (移動方式 / 搖桿)

    private fun renderLiveControls() {
        show(binding.liveControls, !RealGpsStepsService.isRunning)
        show(binding.btnTravel, !RealGpsStepsService.isRunning)
        show(binding.btnJoystick, !RealGpsStepsService.isRunning)
        val mode = PatrolService.controlSpeedMode
        binding.btnTravel.text = travelLabel(mode, short = true)
        binding.btnTravel.setIconResource(if (mode?.countsSteps != false) R.drawable.ic_walk else R.drawable.ic_car)
        binding.btnJoystick.isChecked = PatrolService.joystick.value.enabled
    }

    private fun travelLabel(mode: TravelMode?, short: Boolean = false): String {
        if (mode == null) {
            return if (short) getString(R.string.travel_short_walk)
            else getString(R.string.travel_walk_configured, "%.1f".format(prefs.config().speedMps * 3.6))
        }
        val kmh = mode.speedKmh.toInt()
        return if (short) "${mode.label} $kmh km/h"
        else getString(if (mode.countsSteps) R.string.travel_item else R.string.travel_item_no_steps, mode.label, kmh)
    }

    /** 移動方式: pick the vehicle for the next leg. Applies immediately, before or during a patrol. */
    private fun showTravelDialog() {
        val manual = PatrolService.joystick.value.enabled
        val choices = if (manual) JoystickSpeeds.choices else PatrolService.OVERRIDE_CHOICES
        val current = PatrolService.controlSpeedMode
        // An AlertDialog shows either a message or a list, never both, so build the two by hand.
        val pad = resources.getDimensionPixelSize(R.dimen.space_xl)
        val column = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        column.addView(android.widget.TextView(this).apply {
            text = getString(if (manual) R.string.dlg_joystick_speed_msg else R.string.dlg_travel_msg)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setPadding(0, 0, 0, pad / 2)
        })
        val group = android.widget.RadioGroup(this)
        choices.forEachIndexed { i, mode ->
            group.addView(android.widget.RadioButton(this).apply {
                id = View.generateViewId()
                text = travelLabel(mode)
                tag = i
                isChecked = mode == current
                minHeight = resources.getDimensionPixelSize(R.dimen.overlay_handle)
            })
        }
        column.addView(group)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (manual) R.string.dlg_joystick_speed_title else R.string.dlg_travel_title)
            .setView(android.widget.ScrollView(this).apply { addView(column) })
            .setNegativeButton(R.string.action_cancel, null)
            .show()
        group.setOnCheckedChangeListener { g, checkedId ->
            val index = g.findViewById<View>(checkedId)?.tag as? Int ?: return@setOnCheckedChangeListener
            PatrolService.setTravelOverride(choices[index])
            toast(getString(R.string.snack_travel_changed, travelLabel(choices[index], short = true)))
            dialog.dismiss()
        }
    }

    /**
     * 搖桿 lives on the floating bar (it has to sit on top of the game), so the toggle here makes
     * sure the bar is up and then flips the pad. The service takes the walk over the moment a
     * patrol is running.
     */
    private fun toggleJoystick() {
        when {
            PatrolService.joystick.value.enabled -> {
                PatrolService.setJoystickEnabled(false)
                toast(getString(R.string.toast_joystick_off))
            }
            !Permissions.canDrawOverlays(this) -> {
                toast(getString(R.string.toast_joystick_need_overlay))
                toggleOverlay()
            }
            else -> {
                if (joystickSpeedDialog?.isShowing != true) {
                    joystickSpeedDialog = JoystickSpeedDialog.show(this,
                        onDismiss = { joystickSpeedDialog = null; renderLiveControls() }) { speedMode ->
                        if (!PatrolService.joystick.value.enabled) OverlayService.showJoystick(this, speedMode)
                    }
                }
            }
        }
        // The checkable button flipped itself on the tap; the flow is the truth.
        renderLiveControls()
    }

    // ------------------------------------------------------------------ map

    private fun centerInitially() {
        val state = PatrolService.state.value
        val known = state.position.takeIf { state.phase != PatrolPhase.IDLE }
            ?: prefs.customHome ?: prefs.home ?: prefs.lastPosition
        if (known != null) {
            centeredOnRealPosition = true
            center(known, resetZoom = true)
            return
        }
        center(FALLBACK_CENTER, resetZoom = true)
        if (!Permissions.hasFineLocation(this) || PatrolService.isRunning) return
        lifecycleScope.launch {
            val real = mock.currentRealLocation(REAL_FIX_TIMEOUT_MS)
            if (real != null && !centeredOnRealPosition) {
                centeredOnRealPosition = true
                prefs.lastPosition = real
                center(real, resetZoom = true)
            }
        }
    }

    private fun center(p: LatLng, resetZoom: Boolean = false, animate: Boolean = !resetZoom) {
        lastCenteredPosition = p
        if (mapProvider == MapProvider.GOOGLE) {
            googleLayer?.center(p, if (resetZoom) DEFAULT_ZOOM else null, animate = animate)
            return
        }
        val gp = GeoPoint(p.lat, p.lon)
        if (resetZoom) {
            binding.map.controller.setZoom(DEFAULT_ZOOM)
            binding.map.controller.setCenter(gp)
        } else if (animate) {
            binding.map.controller.animateTo(gp)
        } else {
            binding.map.controller.setCenter(gp)
        }
    }

    private fun mapCenter(): LatLng = if (mapProvider == MapProvider.GOOGLE) googleLayer!!.currentCenter()
        else binding.map.mapCenter.let { LatLng(it.latitude, it.longitude) }

    private fun showMapProviderDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_map_provider)
            .setSingleChoiceItems(arrayOf("OpenStreetMap", "Google Maps"), mapProvider.ordinal) { dialog, index ->
                switchMap(MapProvider.entries[index])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun switchMap(provider: MapProvider) {
        if (provider == mapProvider) return
        if (provider == MapProvider.GOOGLE && !BuildConfig.HAS_GOOGLE_MAPS_KEY) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.google_maps_not_ready)
                .setMessage(R.string.google_maps_key_needed).setPositiveButton(R.string.action_ok, null).show()
            return
        }
        if (provider == MapProvider.GOOGLE && com.google.android.gms.common.GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(this) != com.google.android.gms.common.ConnectionResult.SUCCESS) {
            toast(getString(R.string.google_maps_services_needed))
            return
        }
        // OSM cannot report its requested center until its first layout has completed.
        val currentCenter = if (mapProvider == MapProvider.OPENSTREETMAP && !binding.map.isLaidOut) {
            lastCenteredPosition
        } else mapCenter()
        val zoom = if (mapProvider == MapProvider.GOOGLE) googleLayer!!.currentZoom() else binding.map.zoomLevelDouble
        if (provider == MapProvider.GOOGLE) {
            if (googleLayer == null) {
                try {
                    googleLayer = GoogleMapLayer(this, binding.googleMapContainer, googleSavedState, ::onWaypointTapped) {
                        longPressHelper(GeoPoint(it.lat, it.lon))
                    }
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) googleLayer?.onStart()
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) googleLayer?.onResume()
                } catch (t: Exception) {
                    binding.googleMapContainer.removeAllViews()
                    toast(getString(R.string.google_maps_services_needed))
                    return
                }
            }
            googleLayer!!.center(currentCenter, zoom, animate = false)
            updateGoogleInsets()
            overlays = googleLayer!!
        } else {
            binding.map.controller.setZoom(zoom)
            binding.map.controller.setCenter(GeoPoint(currentCenter.lat, currentCenter.lon))
            overlays = osmOverlays
        }
        mapProvider = provider
        prefs.mapProvider = provider
        binding.googleMapContainer.visibility = if (provider == MapProvider.GOOGLE) View.VISIBLE else View.GONE
        binding.map.visibility = if (provider == MapProvider.OPENSTREETMAP) View.VISIBLE else View.GONE
        updateGoogleInsets()
        rebuildOverlays()
        overlays.updateTrail(trail)
        overlays.updatePosition(PatrolService.state.value.position, lastBearing)
        val found = FlowerScanner.state.value.foundFlowers
        overlays.updateScanned(found.map { it.waypoint.latLng }, found.map { it.waypoint.name })
    }

    private fun updateGoogleInsets() {
        val top = binding.topChrome.height
        val landscape = binding.root.width > binding.root.height
        val cardParams = binding.bottomCard.layoutParams as ViewGroup.MarginLayoutParams
        val bottom = if (landscape) cardParams.bottomMargin else (binding.root.height - binding.bottomCard.top).coerceAtLeast(0)
        val right = if (landscape) (binding.root.width - binding.bottomCard.left).coerceAtLeast(0) else 0
        googleLayer?.setContentInsets(top, bottom, right)
        binding.mapCrosshair.translationY = (top - bottom) / 2f
        binding.mapCrosshair.translationX = -right / 2f
        // OSM has no Google-style content padding. Give it the same visible map viewport so
        // the crosshair stays usable when the control panel expands.
        val lp = binding.map.layoutParams as ViewGroup.MarginLayoutParams
        if (lp.topMargin != top || lp.bottomMargin != bottom || lp.rightMargin != right) {
            val previous = if (binding.map.isLaidOut) binding.map.mapCenter.let { GeoPoint(it.latitude, it.longitude) }
                else GeoPoint(lastCenteredPosition.lat, lastCenteredPosition.lon)
            lp.topMargin = top
            lp.bottomMargin = bottom
            lp.rightMargin = right
            binding.map.layoutParams = lp
            binding.map.doOnLayout { binding.map.controller.setCenter(previous) }
        }
    }

    private var overlayGeneration = 0L

    private fun rebuildOverlays() {
        val waypoints = store.load()
        val home = currentHome
        val generation = ++overlayGeneration
        // Draw markers/circles immediately; the route preview (planner) is computed off the main thread.
        overlays.rebuildStatic(waypoints, home, emptyList())
        val state = PatrolService.state.value
        if (state.mode == PatrolMode.HOLD || (waypoints.isEmpty() && state.mode != PatrolMode.SPIRAL)) return
        lifecycleScope.launch {
            val route = withContext(Dispatchers.Default) {
                val spiralCenter = state.spiralCenter
                if (state.mode == PatrolMode.SPIRAL && spiralCenter != null) {
                    val route = SpiralRoute(spiralCenter, lineWidthM = state.spiralLineWidthM, spacingM = state.spiralSpacingM)
                    val plan = route.nextPlan()
                    listOf(GeoPoint(spiralCenter.lat, spiralCenter.lon)) + plan.segments.map { GeoPoint(it.to.lat, it.to.lon) }
                } else plannedRoute(waypoints, home)
            }
            if (generation == overlayGeneration && route.isNotEmpty()) {
                overlays.rebuildStatic(waypoints, home, route)
            }
        }
    }

    /** The route the planner would walk on lap 0, used purely as a preview polyline. */
    private fun plannedRoute(waypoints: List<Waypoint>, home: LatLng?): List<GeoPoint> {
        if (waypoints.isEmpty()) return emptyList()
        val collection = store.activeRoute()?.collectOnce == true
        val config = prefs.config().forCollection(collection)
        val state = PatrolService.state.value
        val start = state.position?.takeIf { state.phase != PatrolPhase.IDLE } ?: home ?: waypoints.first().latLng
        val plan = runCatching {
            PatrolPlanner.planLap(
                start = start,
                waypoints = waypoints,
                config = config,
                order = PatrolPlanner.orderFor(0, waypoints.size, config.loopMode),
            )
        }.getOrNull() ?: return emptyList()
        if (plan.isEmpty) return emptyList()
        val points = ArrayList<GeoPoint>(plan.segments.size + 1)
        points.add(GeoPoint(plan.segments.first().from.lat, plan.segments.first().from.lon))
        plan.segments.forEach { points.add(GeoPoint(it.to.lat, it.to.lon)) }
        if (collection && home != null) points.add(GeoPoint(home.lat, home.lon))
        return points
    }

    override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false

    override fun longPressHelper(p: GeoPoint?): Boolean {
        if (p == null) return false
        val centerPosition = mapCenter()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dlg_map_actions)
            .setItems(arrayOf(getString(R.string.action_jump_map_center), getString(R.string.action_jump_pressed), getString(R.string.action_add_flower))) { _, which ->
                when (which) {
                    0 -> jumpTo(centerPosition)
                    1 -> jumpTo(LatLng(p.latitude, p.longitude))
                    2 -> WaypointDialogs.showEditor(this, prefs, null, p.latitude, p.longitude, store.load().size + 1) { store.add(it) }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
        return true
    }

    private fun onWaypointTapped(index: Int) {
        WaypointDialogs.showMarkerActions(this, store, prefs, index, running = PatrolService.isRunning) { startHereOrGoTo(it) }
    }

    /** Idle: start the patrol at that flower. Running: make it the next target (立刻前往). */
    private fun startHereOrGoTo(index: Int) {
        if (PatrolService.isRunning) {
            PatrolService.goTo(this, index)
            store.load().getOrNull(index)?.let { toast(getString(R.string.toast_go_now, it.name)) }
        } else {
            startPatrol(index)
        }
    }

    // ------------------------------------------------------------------ state rendering

    private fun render(state: PatrolState) {
        val realSteps = RealGpsStepsService.state.value
        if (!PatrolService.isRunning && realSteps.sessionId != 0L) {
            renderRealSteps(realSteps)
            return
        }
        val home = homeForDisplay(state)
        if (home != currentHome || renderedMode != state.mode || renderedSpiralCenter != state.spiralCenter || renderedSpiralWidth != state.spiralLineWidthM) {
            currentHome = home
            renderedMode = state.mode
            renderedSpiralCenter = state.spiralCenter
            renderedSpiralWidth = state.spiralLineWidthM
            rebuildOverlays()
        }
        renderStatus(state)
        renderLiveControls()
        renderButtons(state.phase)
        renderPosition(state)
        syncOverlay(state.phase)
        continueScanAfterStart(state.phase)
    }

    private var renderedMode: PatrolMode? = null

    private var realStepsCenteredSession = 0L
    private fun renderRealSteps(state: RealStepsState) {
        renderedMode = null // Restore route markers when the next normal patrol starts.
        binding.tvPhase.setText(when (state.phase) {
            RealStepsPhase.COMPLETED -> R.string.real_steps_complete
            RealStepsPhase.STOPPED -> R.string.real_steps_stopped
            RealStepsPhase.PAUSED -> R.string.real_steps_paused
            RealStepsPhase.FINISHING -> R.string.real_steps_finishing
            else -> R.string.real_steps_title
        })
        binding.tvSummary.text = getString(R.string.ui_real_summary, state.written, state.target, state.pending)
        binding.tvDetails.text = listOf(
            getString(R.string.real_steps_written, state.written, state.target),
            getString(R.string.real_steps_generated, state.generated, state.pending),
            getString(R.string.real_steps_rate_status, state.rate),
            getString(R.string.real_steps_jitter_status, state.jitterPct, state.currentRate),
            getString(when (state.motion) {
                GpsMotion.MOVING -> R.string.real_steps_motion_moving
                GpsMotion.STATIONARY -> R.string.real_steps_motion_stationary
                GpsMotion.UNKNOWN -> R.string.real_steps_motion_unknown
            }),
            getString(if (state.position == null) R.string.real_steps_gps_waiting else R.string.real_steps_gps_ready),
        ).joinToString("\n")
        binding.tvError.visibility = if (state.error.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.tvError.text = state.error.orEmpty()
        show(binding.btnStart, !state.active)
        show(binding.btnPause, state.phase == RealStepsPhase.RUNNING)
        show(binding.btnResume, state.phase == RealStepsPhase.PAUSED)
        binding.btnResume.setText(R.string.btn_resume)
        show(binding.btnStop, state.active)
        show(binding.btnHome, false)
        renderLiveControls()
        overlays.rebuildStatic(emptyList(), null, emptyList())
        overlays.updateTrail(emptyList())
        overlays.updatePosition(state.position, 0f)
        if (state.position != null && realStepsCenteredSession != state.sessionId) {
            realStepsCenteredSession = state.sessionId
            center(state.position, resetZoom = true)
        }
    }
    private var renderedSpiralCenter: LatLng? = null
    private var renderedSpiralWidth: Double? = null

    // ------------------------------------------------------------------ bird's-eye scan

    /**
     * Menu entry 「掃描俯瞰模式大花」. The scan needs a patrol in progress (its walk is the ruler
     * that calibrates the map scale) and a MediaProjection consent, which only an Activity can ask
     * for — so this is the one place the whole flow starts, and the overlay button routes here.
     */
    private fun startScanFlow() {
        val scan = FlowerScanner.state.value
        if (FlowerScanner.isRunning) {
            val b = MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dlg_scan_running_title)
                .setMessage(getString(R.string.dlg_scan_running_msg, scanStatusLine(scan)))
                .setNegativeButton(R.string.action_scan_stop) { _, _ ->
                    FlowerScanner.stop(this)
                    toast(getString(R.string.toast_scan_stopped))
                }
                .setNeutralButton(R.string.action_cancel, null)
            if (FlowerScanner.found.isNotEmpty()) {
                b.setPositiveButton(R.string.action_scan_view_results) { _, _ -> showScanResults() }
            }
            b.show()
            return
        }
        if (scan is FlowerScanner.ScanState.Done && scan.found.isNotEmpty()) {
            showScanResults()
            return
        }
        val phase = PatrolService.state.value.phase
        if (phase == PatrolPhase.IDLE || phase == PatrolPhase.STOPPING) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dlg_scan_need_patrol_title)
                .setMessage(R.string.dlg_scan_need_patrol_msg)
                .setPositiveButton(R.string.action_scan_start_patrol) { _, _ ->
                    scanAfterPatrolStart = true
                    startPatrol(0)
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
            return
        }
        showScanExplanation()
    }

    /** The 「先開始巡邏」 branch: once the walk is under way, pick the flow up at the explanation. */
    private fun continueScanAfterStart(phase: PatrolPhase) {
        if (!scanAfterPatrolStart) return
        when (phase) {
            PatrolPhase.WALKING, PatrolPhase.DWELLING, PatrolPhase.PAUSED -> {
                scanAfterPatrolStart = false
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) showScanExplanation()
            }
            PatrolPhase.IDLE -> scanAfterPatrolStart = false   // the start failed
            else -> Unit
        }
    }

    private fun showScanExplanation() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dlg_scan_title)
            .setMessage(R.string.dlg_scan_msg)
            .setPositiveButton(R.string.action_scan_start) { _, _ -> requestProjection() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun requestProjection() {
        val pm = getSystemService(MediaProjectionManager::class.java)
        val intent = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Whole display only: a "single app" capture would show us the game and nothing
                // else, which is fine, but the option confuses the flow and defaults differently
                // across OEM builds.
                pm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                pm.createScreenCaptureIntent()
            }
        } catch (t: Throwable) {
            android.util.Log.w(PatrolService.TAG, "createScreenCaptureIntent failed", t)
            toast(getString(R.string.toast_scan_denied))
            return
        }
        runCatching { scanLauncher.launch(intent) }
            .onFailure {
                android.util.Log.w(PatrolService.TAG, "projection consent launch failed", it)
                toast(getString(R.string.toast_scan_denied))
            }
    }

    private fun onProjectionGranted(resultCode: Int, data: Intent) {
        ScreenCaptureService.start(this, resultCode, data)
        // The service goes foreground and creates its virtual display asynchronously.
        lifecycleScope.launch {
            val ok = withTimeoutOrNull(SCAN_START_TIMEOUT_MS) { ScreenCaptureService.isRunning.first { it } } != null
            if (!ok) {
                toast(ScreenCaptureService.lastError ?: getString(R.string.toast_scan_denied))
                return@launch
            }
            FlowerScanner.start(this@MainActivity)
            toast(getString(R.string.toast_scan_started))
            offerToLaunchGame()
        }
    }

    /**
     * Order matters: Pikmin Bloom blanks its own surface (black in every capture) when it notices
     * a capture display appearing while it is running, but not when it is launched after the
     * projection already exists. So the game is opened from here, after the capture has started.
     */
    private fun offerToLaunchGame() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val b = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dlg_scan_launch_title)
            .setMessage(R.string.dlg_scan_launch_msg)
            .setNegativeButton(R.string.action_scan_launch_later, null)
        if (Permissions.isInstalled(this, PIKMIN_PACKAGE)) {
            b.setPositiveButton(R.string.action_scan_launch_game) { _, _ -> Permissions.openApp(this, PIKMIN_PACKAGE) }
        }
        b.show()
    }

    private fun renderScan(scan: FlowerScanner.ScanState) {
        val found = scan.foundFlowers
        overlays.updateScanned(found.map { it.waypoint.latLng }, found.map { it.waypoint.name })
        if (scan is FlowerScanner.ScanState.WaitingForBirdsEye && scan.blank) maybeExplainBlankCapture()
    }

    private var blankDialog: androidx.appcompat.app.AlertDialog? = null

    /**
     * The capture is black although the screen looks fine: the game blanked itself when the
     * projection appeared. One truncated line on the floating bar cannot explain that, so say it
     * properly here, with the relaunch button that fixes it.
     */
    private fun maybeExplainBlankCapture() {
        if (blankDialog?.isShowing == true || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val b = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dlg_scan_blank_title)
            .setMessage(R.string.dlg_scan_blank_msg)
            .setNegativeButton(R.string.action_scan_launch_later, null)
            .setOnDismissListener { blankDialog = null }
        if (Permissions.isInstalled(this, PIKMIN_PACKAGE)) {
            b.setPositiveButton(R.string.action_scan_launch_game) { _, _ -> Permissions.openApp(this, PIKMIN_PACKAGE) }
        }
        blankDialog = b.show()
    }

    /** Results dialog, shown on resume whenever the scan has found more than the user has seen. */
    private fun maybeShowScanResults() {
        val found = FlowerScanner.found
        if (found.isEmpty() || found.size <= FlowerScanner.reviewedCount) return
        if (scanResultsDialog?.isShowing == true) return
        showScanResults()
    }

    private fun showScanResults() {
        val found = FlowerScanner.found
        if (found.isEmpty()) {
            toast(getString(R.string.toast_scan_no_results))
            return
        }
        FlowerScanner.reviewedCount = found.size
        val labels = found.map { f ->
            getString(
                R.string.scan_result_item,
                f.waypoint.name,
                distanceText(f.rangeM),
                getString(if (f.stemFound) R.string.scan_confidence_high else R.string.scan_confidence_low),
            )
        }.toTypedArray()
        val checked = BooleanArray(found.size) { true }
        scanResultsDialog?.dismiss()
        scanResultsDialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dlg_scan_results_title, found.size))
            .setMultiChoiceItems(labels, checked) { _, index, isChecked -> checked[index] = isChecked }
            .setPositiveButton(R.string.action_scan_add_current) { _, _ -> addScanned(found, checked, newRoute = false) }
            .setNeutralButton(R.string.action_scan_new_route) { _, _ -> addScanned(found, checked, newRoute = true) }
            .setNegativeButton(R.string.action_cancel, null)
            .setOnDismissListener { scanResultsDialog = null }
            .show()
    }

    private fun addScanned(found: List<FlowerScanPlan.PlannedFlower>, checked: BooleanArray, newRoute: Boolean) {
        val selected = found.filterIndexed { i, _ -> checked.getOrElse(i) { false } }.map { it.waypoint }
        if (selected.isEmpty()) {
            toast(getString(R.string.toast_scan_nothing_selected))
            return
        }
        if (newRoute) store.createRoute(getString(R.string.scan_route_name))
        val existing = store.load()
        val added = ArrayList<Waypoint>()
        for (wp in selected) {
            val dup = (existing + added).any { GeoMath.distanceM(it.latLng, wp.latLng) <= SCAN_DEDUPE_M }
            if (!dup) {
                added += wp.copy(
                    id = UUID.randomUUID().toString(),
                    radiusM = prefs.defaultRadiusM,
                    dwellSec = prefs.defaultDwellSec,
                )
            }
        }
        store.save(existing + added)
        toast(getString(R.string.toast_scan_added, added.size))
        if (!FlowerScanner.isRunning) FlowerScanner.discardResults()
    }

    private fun scanStatusLine(scan: FlowerScanner.ScanState): String = when (scan) {
        FlowerScanner.ScanState.Idle -> getString(R.string.ovl_stopped)
        FlowerScanner.ScanState.NeedProjection -> getString(R.string.scan_status_need_projection)
        is FlowerScanner.ScanState.WaitingForBirdsEye -> scan.reason
        is FlowerScanner.ScanState.Calibrating -> getString(R.string.scan_status_calibrating, scan.metresSoFar.toInt())
        is FlowerScanner.ScanState.Scanning -> getString(R.string.scan_status_scanning, scan.found.size)
        is FlowerScanner.ScanState.Done -> getString(R.string.scan_status_done, scan.found.size)
        is FlowerScanner.ScanState.Error -> getString(R.string.scan_status_error, scan.message)
    }

    /**
     * Starts the floating control bar when a patrol begins and takes it down when it ends. The
     * service itself never touches the UI, so this lives here; when this activity is not visible the
     * overlay hides itself shortly after the phase goes back to IDLE.
     */
    private fun syncOverlay(phase: PatrolPhase) {
        val previous = overlayPhase
        overlayPhase = phase
        if (previous == phase) return
        when {
            phase != PatrolPhase.IDLE ->
                if (prefs.overlayEnabled && Permissions.canDrawOverlays(this)) {
                    OverlayService.startAutomatically(this, newPatrol = previous == PatrolPhase.IDLE || phase == PatrolPhase.STARTING)
                }

            previous != null && !prefs.overlayPinned -> OverlayService.stop(this)
        }
    }

    /** Menu toggle: show the bar right now (pinned, so it stays after the patrol) or hide it. */
    private fun toggleOverlay() {
        if (OverlayService.isRunning) {
            OverlayService.dismiss(this)
            toast(getString(R.string.toast_overlay_hidden))
            return
        }
        if (!Permissions.canDrawOverlays(this)) {
            lifecycleScope.launch {
                val go = ask(
                    R.string.dlg_overlay_permission_title, R.string.dlg_overlay_permission_msg,
                    R.string.action_open_overlay_settings,
                )
                if (go) {
                    runCatching { overlayLauncher.launch(Permissions.overlayPermissionIntent(this@MainActivity)) }
                        .onFailure { Permissions.openOverlaySettings(this@MainActivity) }
                }
            }
            return
        }
        showOverlay()
    }

    private fun showOverlay() {
        OverlayService.start(this, pinned = true)
        toast(getString(R.string.toast_overlay_shown))
    }

    private fun renderStatus(state: PatrolState) {
        binding.tvPhase.text = if (state.mode == PatrolMode.SPIRAL && state.phase == PatrolPhase.WALKING) getString(R.string.phase_spiral) else phaseText(state.phase)

        binding.tvSummary.text = when {
            state.phase == PatrolPhase.IDLE -> getString(R.string.ui_route_summary, store.activeRouteName(), store.load().size) + "\n" + getString(R.string.ui_idle_hint)
            state.currentWaypointName != null -> getString(R.string.ui_summary_target, state.currentWaypointName, distanceText(state.distanceToTargetM)) + "\n" +
                getString(R.string.ui_walk_metric, distanceText(state.distanceWalkedM)) + " · " + getString(R.string.ui_steps_metric, state.sessionSteps)
            else -> getString(R.string.ui_walk_metric, distanceText(state.distanceWalkedM)) + " · " + getString(R.string.ui_steps_metric, state.sessionSteps)
        }
        val lines = ArrayList<String>(6)
        val name = state.currentWaypointName
        if (state.phase == PatrolPhase.IDLE || name == null) {
            lines += getString(R.string.status_no_target)
            lines += getString(R.string.status_waypoint_count, store.load().size)
            val custom = prefs.customHome
            val h = currentHome
            lines += when {
                custom != null -> getString(R.string.status_home_custom, custom.toString())
                h == null -> getString(R.string.status_home_none)
                else -> getString(R.string.status_home_set, h.toString())
            }
        } else {
            lines += getString(R.string.status_target, name, distanceText(state.distanceToTargetM))
            if (state.homeIsCustom) lines += getString(R.string.status_home_custom, state.home.toString())
        }
        state.position?.let { lines += getString(R.string.status_position, it.toString()) }
        if (state.mode == PatrolMode.SPIRAL) {
            lines += getString(R.string.status_spiral_radius, distanceText(state.spiralRadiusM))
            lines += getString(R.string.status_spiral_width, distanceText(state.spiralLineWidthM), distanceText(state.spiralSpacingM))
        }
        lines += getString(R.string.status_walked, distanceText(state.distanceWalkedM))
        lines += getString(R.string.status_session_steps, state.sessionSteps)
        lines += getString(R.string.status_today_steps, state.stepsWrittenToday)
        lines += getString(R.string.status_speed, "%.1f".format(state.speedMps * 3.6))
        binding.tvDetails.text = lines.joinToString("\n")

        val error = state.lastError
        binding.tvError.visibility = if (error.isNullOrBlank()) View.GONE else View.VISIBLE
        if (!error.isNullOrBlank()) binding.tvError.text = getString(R.string.status_error, error)
    }

    private fun renderButtons(phase: PatrolPhase) {
        val moving = phase == PatrolPhase.WALKING || phase == PatrolPhase.DWELLING || phase == PatrolPhase.MANUAL
        show(binding.btnStart, phase == PatrolPhase.IDLE || phase == PatrolPhase.HOLDING)
        show(binding.btnPause, moving)
        show(binding.btnResume, phase == PatrolPhase.PAUSED || phase == PatrolPhase.PARKED)
        binding.btnResume.setText(if (phase == PatrolPhase.PARKED) R.string.btn_resume_lap else R.string.btn_resume)
        show(binding.btnHome, PatrolService.state.value.home != null && (moving || phase == PatrolPhase.PAUSED || phase == PatrolPhase.HOLDING))
        // 停止 stays available while STARTING so a slow GPS fix can be aborted.
        show(binding.btnStop, phase != PatrolPhase.IDLE && phase != PatrolPhase.STOPPING)
    }

    private fun renderPosition(state: PatrolState) {
        val p = state.position
        if (state.phase == PatrolPhase.IDLE || p == null) {
            if (trail.isNotEmpty()) {
                trail.clear()
                trailLast = null
                overlays.updateTrail(trail)
            }
            overlays.updatePosition(null, 0f)
            return
        }
        val previous = trailLast
        if (previous == null || GeoMath.distanceM(previous, p) >= TRAIL_MIN_STEP_M) {
            if (previous != null) lastBearing = GeoMath.bearingDeg(previous, p).toFloat()
            trailLast = p
            trail.add(GeoPoint(p.lat, p.lon))
            while (trail.size > TRAIL_LIMIT) trail.removeAt(0)
            overlays.updateTrail(trail)
        }
        overlays.updatePosition(p, lastBearing)
    }

    private fun phaseText(phase: PatrolPhase): String = getString(
        when (phase) {
            PatrolPhase.IDLE -> R.string.phase_idle
            PatrolPhase.STARTING -> R.string.phase_starting
            PatrolPhase.WALKING -> R.string.phase_walking
            PatrolPhase.DWELLING -> R.string.phase_dwelling
            PatrolPhase.PAUSED -> R.string.phase_paused
            PatrolPhase.RETURNING_HOME -> R.string.phase_returning
            PatrolPhase.STOPPING -> R.string.phase_stopping
            PatrolPhase.PARKED -> R.string.phase_parked
            PatrolPhase.MANUAL -> R.string.phase_manual
            PatrolPhase.HOLDING -> R.string.phase_holding
        }
    )

    private fun onEvent(event: PatrolEvent) {
        when (event) {
            is PatrolEvent.Error -> snack(event.message, Snackbar.LENGTH_LONG)
            is PatrolEvent.ArrivedAtWaypoint ->
                snack(getString(R.string.snack_arrived, event.name), Snackbar.LENGTH_SHORT)
            is PatrolEvent.ReturnedHome ->
                snack(getString(R.string.snack_returned_home), Snackbar.LENGTH_LONG)
            is PatrolEvent.Resumed ->
                snack(getString(R.string.snack_resumed, ageText(event.checkpointAgeMs)), Snackbar.LENGTH_LONG)
            is PatrolEvent.ParkedAtHome -> snack(getString(R.string.snack_parked), Snackbar.LENGTH_LONG)
            is PatrolEvent.TravelModeChanged ->
                if (event.automatic) snack(getString(R.string.snack_travel_auto_walk), Snackbar.LENGTH_SHORT)
            is PatrolEvent.ConfigChanged ->
                snack(getString(R.string.snack_speed_changed, "%.1f".format(event.speedKmh)), Snackbar.LENGTH_SHORT)
            is PatrolEvent.Replanned -> snack(getString(R.string.snack_replanned), Snackbar.LENGTH_SHORT)
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_my_location -> { goToMyLocation(); true }
        R.id.action_waypoint_list -> { showWaypointList(); true }
        R.id.action_overlay -> { toggleOverlay(); true }
        R.id.action_scan_flowers -> { startScanFlow(); true }
        R.id.action_setup -> { startActivity(Intent(this, SetupActivity::class.java)); true }
        R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        R.id.action_home -> { showHomeDialog(); true }
        R.id.action_jump_coords -> { promptJumpCoords(); true }
        R.id.action_spiral -> { startSpiral(); true }
        R.id.action_real_steps -> { startRealGpsSteps(); true }
        R.id.action_map_provider -> { showMapProviderDialog(); true }
        R.id.action_import -> { importLauncher.launch(arrayOf(MIME_ANY)); true }
        R.id.action_paste_coordinates -> { showCoordinatePaste(); true }
        R.id.action_export_json -> { exportJsonLauncher.launch(getString(R.string.export_json_filename)); true }
        R.id.action_export_gpx -> { exportGpxLauncher.launch(getString(R.string.export_gpx_filename)); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun showCoordinatePaste() {
        fun canApply(): Boolean {
            if (PatrolService.isRunning || RealGpsStepsService.isRunning || PatrolCheckpoint.resumable(this) != null) {
                toast(getString(R.string.collection_busy))
                return false
            }
            return true
        }
        if (!canApply()) return
        CoordinatePasteDialog.show(this) { name, home, flowers ->
            if (!canApply()) return@show false
            try {
                store.createCollectionRoute(name, flowers.map { point ->
                    Waypoint(UUID.randomUUID().toString(), point.name, point.position.lat, point.position.lon,
                        radiusM = prefs.defaultRadiusM, dwellSec = 0)
                })
                setCustomHome(home)
                rebuildOverlays()
                true
            } catch (e: Exception) {
                toast(getString(R.string.collection_save_failed))
                false
            }
        }
    }

    private fun showWaypointList() {
        WaypointDialogs.showList(
            activity = this,
            store = store,
            prefs = prefs,
            onFocus = { center(LatLng(it.lat, it.lon)) },
            onStartHere = { startHereOrGoTo(it) },
            onAddRequested = { addWaypointAtMapCenter() },
        )
    }

    private fun addWaypointAtMapCenter() {
        val center = mapCenter()
        WaypointDialogs.showEditor(
            this, prefs, null, center.lat, center.lon, store.load().size + 1,
        ) { store.add(it) }
    }

    private fun showPatrolModeDialog() {
        ActionSheet.show(this, R.string.ui_mode_help, R.string.ui_toolbar_subtitle, listOf(
            ActionSheet.Item(R.string.mode_waypoints, R.drawable.ic_flower, R.string.ui_mode_route_desc) { startPatrol(0) },
            ActionSheet.Item(R.string.mode_spiral, R.drawable.ic_walk, R.string.ui_mode_spiral_desc) { startSpiral() },
            ActionSheet.Item(R.string.real_steps_title, R.drawable.ic_my_location, R.string.ui_mode_steps_desc) { startRealGpsSteps() },
        ))
    }

    private fun showJumpSheet() {
        ActionSheet.show(this, R.string.ui_jump, R.string.ui_jump_desc, listOf(
            ActionSheet.Item(R.string.action_jump_map_center, R.drawable.ic_map_crosshair, R.string.ui_jump_center_desc) { jumpTo(mapCenter()) },
            ActionSheet.Item(R.string.menu_jump_coords, R.drawable.ic_navigation, R.string.ui_jump_coords_desc) { promptJumpCoords() },
            ActionSheet.Item(R.string.menu_home, R.drawable.ic_home) { showHomeDialog() },
        ))
    }

    private fun showToolsSheet() {
        ActionSheet.show(this, R.string.ui_tools, R.string.ui_tools_desc, listOf(
            ActionSheet.Item(R.string.ui_section_routes),
            ActionSheet.Item(R.string.collection_title, R.drawable.ic_import, R.string.ui_paste_desc) { showCoordinatePaste() },
            ActionSheet.Item(R.string.menu_scan_flowers, R.drawable.ic_scan) { startScanFlow() },
            ActionSheet.Item(R.string.menu_import, R.drawable.ic_import) { importLauncher.launch(arrayOf(MIME_ANY)) },
            ActionSheet.Item(R.string.menu_export_json, R.drawable.ic_export) { exportJsonLauncher.launch(getString(R.string.export_json_filename)) },
            ActionSheet.Item(R.string.menu_export_gpx, R.drawable.ic_export) { exportGpxLauncher.launch(getString(R.string.export_gpx_filename)) },
            ActionSheet.Item(R.string.ui_section_map),
            ActionSheet.Item(R.string.menu_my_location, R.drawable.ic_my_location) { goToMyLocation() },
            ActionSheet.Item(R.string.menu_map_provider, R.drawable.ic_navigation) { showMapProviderDialog() },
            ActionSheet.Item(R.string.menu_overlay, R.drawable.ic_gamepad) { toggleOverlay() },
            ActionSheet.Item(R.string.ui_section_setup),
            ActionSheet.Item(R.string.menu_setup, R.drawable.ic_check, R.string.ui_setup_entry_desc) { startActivity(Intent(this, SetupActivity::class.java)) },
            ActionSheet.Item(R.string.menu_settings, R.drawable.ic_settings) { startActivity(Intent(this, SettingsActivity::class.java)) },
        ))
    }

    private fun startRealGpsSteps() {
        if (PatrolService.isRunning || RealGpsStepsService.isRunning) {
            toast(getString(R.string.real_steps_busy))
            return
        }
        RealGpsStepsDialog.show(this, prefs) { target, rate ->
            lifecycleScope.launch {
                if (!Permissions.hasFineLocation(this@MainActivity)) {
                    val grant = ask(R.string.dlg_need_location_title, R.string.dlg_need_location_msg, R.string.action_grant)
                    if (!grant || !requestLocationPermission()) return@launch
                }
                if (Build.VERSION.SDK_INT >= 33 && !Permissions.hasNotifications(this@MainActivity)) {
                    if (ask(R.string.dlg_need_notification_title, R.string.dlg_need_notification_msg,
                            R.string.action_grant, R.string.action_continue)) requestNotificationPermission()
                }
                if (!steps.isAvailable) {
                    ask(R.string.dlg_hc_unavailable_title, R.string.real_steps_health_required, R.string.action_ok, null)
                    return@launch
                }
                if (!steps.grantedPermissions().containsAll(steps.stepPermissions)) {
                    if (!ask(R.string.dlg_hc_permission_title, R.string.real_steps_health_required, R.string.action_grant) ||
                        !requestHealthPermissions(steps.stepPermissions)) return@launch
                }
                if (PatrolService.isRunning || RealGpsStepsService.isRunning) {
                    toast(getString(R.string.real_steps_busy))
                    return@launch
                }
                RealGpsStepsService.start(this@MainActivity, target, rate)
            }
        }
    }

    private fun startSpiral() {
        if (RealGpsStepsService.isRunning) { toast(getString(R.string.real_steps_busy)); return }
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine()
            setText(prefs.spiralLineWidthM.toString())
            setSelectAllOnFocus(true)
        }
        val pad = resources.getDimensionPixelSize(R.dimen.space_xl)
        val column = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(android.widget.TextView(this@MainActivity).apply { setText(R.string.spiral_width_explanation) })
            addView(UiForms.field(this@MainActivity, getString(R.string.ui_spiral_label), input))
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.spiral_width_title)
            .setView(column)
            .setPositiveButton(R.string.btn_start, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val width = SpiralRoute.parseLineWidth(input.text.toString())
                if (width == null) {
                    input.error = getString(R.string.spiral_width_invalid)
                } else {
                    dialog.dismiss()
                    launchSpiral(width)
                }
            }
        }
        dialog.show()
    }

    private fun launchSpiral(lineWidthM: Double) {
        lifecycleScope.launch {
            if (preflight(requireWaypoints = false)) PatrolService.startSpiral(this@MainActivity, lineWidthM)
        }
    }

    private fun jumpTo(position: LatLng) {
        lifecycleScope.launch {
            if (!preflight(requireWaypoints = false, checkHealth = false)) return@launch
            PatrolService.teleport(this@MainActivity, position)
            trail.clear()
            trailLast = null
            overlays.updateTrail(trail)
            center(position)
        }
    }

    private fun promptJumpCoords() {
        val input = android.widget.EditText(this).apply {
            setHint(R.string.jump_coords_hint)
            setSingleLine()
        }
        val pad = resources.getDimensionPixelSize(R.dimen.space_xl)
        val box = android.widget.FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(UiForms.field(this@MainActivity, getString(R.string.ui_coordinate_label), input)) }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_jump_coords)
            .setView(box)
            .setPositiveButton(R.string.action_jump, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val position = LatLng.parse(input.text.toString())
                if (position == null) input.error = getString(R.string.jump_coords_invalid)
                else { dialog.dismiss(); jumpTo(position) }
            }
        }
        dialog.show()
    }

    // ------------------------------------------------------------------ custom home (家的位置)

    /** Idle state retains the previous patrol's home; it must not override a newly saved choice. */
    private fun homeForDisplay(state: PatrolState): LatLng? =
        if (state.phase == PatrolPhase.IDLE) prefs.customHome ?: prefs.home ?: state.home
        else state.home ?: prefs.customHome ?: prefs.home

    /**
     * 家的位置: by default home is always a fresh real fix (see [startPatrol]); this lets the user
     * pin a home somewhere else instead - the "stay in Japan for a while" case - which the service
     * then starts from and parks at.
     */
    private fun showHomeDialog() {
        if (PatrolService.isRunning) {
            toast(getString(R.string.toast_home_locked))
            return
        }
        val custom = prefs.customHome
        val b = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dlg_home_title)
            .setMessage(
                if (custom == null) getString(R.string.dlg_home_msg_real)
                else getString(R.string.dlg_home_msg_custom, custom.toString()),
            )
            .setPositiveButton(R.string.action_home_map_center) { _, _ ->
                setCustomHome(mapCenter())
            }
            .setNeutralButton(R.string.action_home_enter_coords) { _, _ -> promptHomeCoords() }
        if (custom != null) {
            b.setNegativeButton(R.string.action_home_clear) { _, _ -> setCustomHome(null) }
        } else {
            b.setNegativeButton(R.string.action_cancel, null)
        }
        b.show()
    }

    private fun promptHomeCoords() {
        val input = android.widget.EditText(this).apply {
            setHint(R.string.dlg_home_coords_hint)
            setSingleLine()
            prefs.customHome?.let { setText("%.6f, %.6f".format(it.lat, it.lon)) }
        }
        val pad = resources.getDimensionPixelSize(R.dimen.space_xl)
        val box = android.widget.FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(UiForms.field(this@MainActivity, getString(R.string.ui_coordinate_label), input)) }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_home_enter_coords)
            .setView(box)
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val position = LatLng.parse(input.text.toString())
                if (position == null) input.error = getString(R.string.toast_home_coords_invalid)
                else { setCustomHome(position); dialog.dismiss() }
            }
        }
        dialog.show()
    }

    private fun setCustomHome(p: LatLng?) {
        if (PatrolService.isRunning) {
            toast(getString(R.string.toast_home_locked))
            return
        }
        prefs.customHome = p
        currentHome = homeForDisplay(PatrolService.state.value)
        rebuildOverlays()
        render(PatrolService.state.value)
        if (p != null) {
            // A pending startup GPS lookup must not recenter the map after the user's choice.
            centeredOnRealPosition = true
            center(p, animate = false)
            toast(getString(R.string.toast_home_custom_set, p.toString()))
        } else {
            toast(getString(R.string.toast_home_custom_cleared))
        }
    }

    private fun goToMyLocation() {
        val state = PatrolService.state.value
        val simulated = state.position
        if (state.phase != PatrolPhase.IDLE && simulated != null) {
            center(simulated)
            return
        }
        if (!Permissions.hasFineLocation(this)) {
            lifecycleScope.launch { requestLocationPermission() }
            return
        }
        toast(getString(R.string.toast_locating))
        lifecycleScope.launch {
            val real = mock.currentRealLocation(REAL_FIX_TIMEOUT_MS)
            if (real == null) {
                toast(getString(R.string.toast_location_unavailable))
            } else {
                prefs.lastPosition = real
                centeredOnRealPosition = true
                center(real)
            }
        }
    }

    // ------------------------------------------------------------------ import / export

    private fun importFrom(uri: Uri) {
        val existing = store.load().size
        if (existing > 0) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dlg_import_replace_title)
                .setMessage(getString(R.string.dlg_import_replace_msg, existing))
                .setPositiveButton(R.string.action_replace) { _, _ -> importNow(uri) }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        } else {
            importNow(uri)
        }
    }

    private fun importNow(uri: Uri) {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                toast(getString(R.string.toast_import_failed)); return@launch
            }
            val count = withContext(Dispatchers.Default) {
                runCatching {
                    if (text.contains(GPX_MARKER, ignoreCase = true)) {
                        store.importGpx(text, prefs.defaultRadiusM, prefs.defaultDwellSec)
                    } else {
                        store.importJson(text)
                    }
                }.getOrDefault(0)
            }
            if (count > 0) toast(getString(R.string.toast_import_done, count))
            else toast(getString(R.string.toast_import_failed))
        }
    }

    private fun exportTo(uri: Uri, content: () -> String) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { it.write(content().toByteArray()) } != null
                }.getOrDefault(false)
            }
            toast(getString(if (ok) R.string.toast_export_done else R.string.toast_export_failed))
        }
    }

    // ------------------------------------------------------------------ pre-flight + start

    private fun startPatrol(startAtIndex: Int) {
        if (PatrolService.isRunning && PatrolService.state.value.phase != PatrolPhase.HOLDING) return
        // An unhandled checkpoint means the game is still parked at a crash point; a fresh start
        // would teleport it home. Route the user back to the resume choice instead.
        if (!PatrolService.isRunning && PatrolCheckpoint.resumable(this) != null) {
            resumeDialogShown = false
            maybeOfferResume()
            return
        }
        lifecycleScope.launch {
            if (!preflight()) return@launch
            // Home is ALWAYS a fresh fix, never a stored one. It is where the patrol walks back to
            // and where the mock providers hand control back to the real GPS, so it has to be where
            // the user is now; people move between sessions, and reusing this morning's home would
            // drop the game avatar there and look like a teleport. Passing null makes PatrolService
            // take the fix itself. (Prefs.home is still written, but only to draw the map marker.)
            // The one exception is a home the user pinned on purpose (Prefs.customHome, 家的位置):
            // the service starts from it and parks there instead of releasing the mock.
            PatrolService.start(this@MainActivity, null, startAtIndex)
        }
    }

    /** Runs every check in order, offering the fix for each. Returns false when the patrol must not start. */
    private suspend fun preflight(requireWaypoints: Boolean = true, checkHealth: Boolean = true): Boolean {
        if (RealGpsStepsService.isRunning) { toast(getString(R.string.real_steps_busy)); return false }
        // 1. Precise location — mandatory (the service captures "home" from the real GPS).
        if (!Permissions.hasFineLocation(this)) {
            val go = ask(R.string.dlg_need_location_title, R.string.dlg_need_location_msg, R.string.action_grant)
            if (!go || !requestLocationPermission()) {
                toast(getString(R.string.toast_location_denied)); return false
            }
        }

        // 2. Notifications — optional, the foreground service runs either way.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Permissions.hasNotifications(this)) {
            val go = ask(
                R.string.dlg_need_notification_title, R.string.dlg_need_notification_msg,
                R.string.action_grant, R.string.action_continue,
            )
            if (go) requestNotificationPermission()
        }

        // 3. This app must be the selected mock location app.
        if (!mock.isMockAppSelected()) {
            val go = ask(
                R.string.dlg_need_mock_title, R.string.dlg_need_mock_msg,
                R.string.action_open_developer_options,
            )
            if (go) mock.openMockAppPicker(this)
            return false
        }

        // 4. At least one Big Flower.
        if (requireWaypoints && store.load().isEmpty()) {
            ask(R.string.dlg_no_waypoints_title, R.string.dlg_no_waypoints_msg, R.string.action_ok, null)
            return false
        }

        // 5. Health Connect, only when step injection is on.
        if (checkHealth && prefs.config().injectSteps) {
            if (!steps.isAvailable) {
                return ask(
                    R.string.dlg_hc_unavailable_title, R.string.dlg_hc_unavailable_msg,
                    R.string.action_continue,
                )
            }
            if (!steps.hasPermissions()) {
                val grant = ask(
                    R.string.dlg_hc_permission_title, R.string.dlg_hc_permission_msg,
                    R.string.action_grant, R.string.action_continue_without_steps,
                )
                if (!grant) return true
                if (!requestHealthPermissions()) {
                    return ask(
                        R.string.dlg_hc_permission_title, R.string.dlg_hc_permission_msg,
                        R.string.action_continue_without_steps,
                    )
                }
            }
        }
        return true
    }

    /** Reuses a recent home (so we do not need a fresh fix) or returns null to let the service locate. */

    private fun maybeShowDisclaimer() {
        if (prefs.disclaimerAccepted) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dlg_disclaimer_title)
            .setMessage(R.string.dlg_disclaimer_msg)
            .setCancelable(false)
            .setPositiveButton(R.string.action_accept) { _, _ -> prefs.disclaimerAccepted = true }
            .setNegativeButton(R.string.action_exit) { _, _ -> finish() }
            .show()
    }

    // ------------------------------------------------------------------ suspend helpers

    private suspend fun requestLocationPermission(): Boolean = suspendCancellableCoroutine { cont ->
        locationCallback = { granted -> if (cont.isActive) cont.resume(granted) }
        locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private suspend fun requestNotificationPermission(): Boolean = suspendCancellableCoroutine { cont ->
        notificationCallback = { granted -> if (cont.isActive) cont.resume(granted) }
        notificationLauncher.launch(NOTIFICATION_PERMISSION)
    }

    private suspend fun requestHealthPermissions(required: Set<String> = steps.requiredPermissions): Boolean = suspendCancellableCoroutine { cont ->
        healthCallback = { granted -> if (cont.isActive) cont.resume(granted.containsAll(required)) }
        healthLauncher.launch(required)
    }

    private suspend fun ask(
        @StringRes titleRes: Int,
        @StringRes messageRes: Int,
        @StringRes positiveRes: Int,
        @StringRes negativeRes: Int? = R.string.action_cancel,
    ): Boolean = ask(titleRes, getString(messageRes), positiveRes, negativeRes)

    private suspend fun ask(
        @StringRes titleRes: Int,
        message: String,
        @StringRes positiveRes: Int,
        @StringRes negativeRes: Int? = R.string.action_cancel,
    ): Boolean = suspendCancellableCoroutine { cont ->
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setMessage(message)
            .setPositiveButton(positiveRes) { _, _ -> if (cont.isActive) cont.resume(true) }
            .setOnDismissListener { if (cont.isActive) cont.resume(false) }
        if (negativeRes != null) builder.setNegativeButton(negativeRes, null)
        val dialog = builder.create()
        cont.invokeOnCancellation { runCatching { dialog.dismiss() } }
        dialog.show()
    }

    // ------------------------------------------------------------------ small helpers

    private fun show(view: View, visible: Boolean) {
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun distanceText(metres: Double): String =
        if (metres >= 1000.0) getString(R.string.fmt_distance_km, metres / 1000.0)
        else getString(R.string.fmt_distance_m, metres)

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun snack(text: String, duration: Int) {
        Snackbar.make(binding.root, text, duration).setAnchorView(binding.bottomCard).show()
    }

    companion object {
        private const val DEFAULT_ZOOM = 17.0
        private const val TRAIL_LIMIT = 2000
        private const val TRAIL_MIN_STEP_M = 1.0
        private const val REAL_FIX_TIMEOUT_MS = 15_000L
        private const val MIME_ANY = "*/*"
        private const val MIME_JSON = "application/json"
        private const val MIME_GPX = "application/gpx+xml"
        private const val GPX_MARKER = "<gpx"
        private const val NOTIFICATION_PERMISSION = "android.permission.POST_NOTIFICATIONS"
        private const val SCAN_START_TIMEOUT_MS = 5_000L
        private const val SCAN_DEDUPE_M = 20.0
        private const val PIKMIN_PACKAGE = "com.nianticlabs.pikmin"

        /** Intent extra from the overlay's 掃描 button: run the scan flow on resume. */
        const val EXTRA_START_SCAN = "start_scan"

        /** Taipei 101, used only when nothing at all is known yet. */
        private val FALLBACK_CENTER = LatLng(25.0330, 121.5654)
    }
}
