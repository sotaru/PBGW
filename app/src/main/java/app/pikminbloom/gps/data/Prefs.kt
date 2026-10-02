package app.pikminbloom.gps.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import app.pikminbloom.gps.geo.LatLng
import app.pikminbloom.gps.vision.Calibration

/**
 * Settings + small persisted state. Keys match `res/xml/preferences.xml`; numeric preferences are
 * stored as Strings (EditTextPreference) so the settings screen can edit them directly.
 */
class Prefs(context: Context) {

    val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    fun config(): PatrolConfig {
        val d = PatrolConfig()
        return PatrolConfig(
            // km/h in the UI, m/s internally. Pikmin Bloom stops planting somewhere around
            // 15-20 km/h, so 20 km/h (5.56 m/s) is the highest value we allow.
            speedMps = (str(KEY_SPEED_KMH, d.speedMps * 3.6) / 3.6).coerceIn(0.3, MAX_SPEED_MPS),
            speedJitterPct = str(KEY_SPEED_JITTER_PCT, d.speedJitterPct).coerceIn(0.0, 30.0),
            strideM = (str(KEY_STRIDE_CM, d.strideM * 100) / 100.0).coerceIn(0.4, 1.2),
            loopMode = enum(KEY_LOOP_MODE, d.loopMode),
            orbitAtWaypoints = sp.getBoolean(KEY_ORBIT, d.orbitAtWaypoints),
            injectSteps = sp.getBoolean(KEY_INJECT_STEPS, d.injectSteps),
            stepFlushIntervalSec = str(KEY_STEP_FLUSH_SEC, d.stepFlushIntervalSec.toDouble()).toInt().coerceIn(20, 600),
            dailyStepCap = str(KEY_DAILY_STEP_CAP, d.dailyStepCap.toDouble()).toLong().coerceIn(0, 200_000),
            maxCadenceSpm = str(KEY_MAX_CADENCE, d.maxCadenceSpm.toDouble()).toInt().coerceIn(0, 1000),
            accuracyMinM = str(KEY_ACC_MIN, d.accuracyMinM.toDouble()).toFloat().coerceIn(1f, 30f),
            accuracyMaxM = str(KEY_ACC_MAX, d.accuracyMaxM.toDouble()).toFloat().coerceIn(1f, 50f),
            altitudeM = str(KEY_ALTITUDE, d.altitudeM).coerceIn(-100.0, 4000.0),
            notifyOnArrival = sp.getBoolean(KEY_NOTIFY_ARRIVAL, d.notifyOnArrival),
            vibrateOnArrival = sp.getBoolean(KEY_VIBRATE_ARRIVAL, d.vibrateOnArrival),
            arrivalAlertMinGapSec = str(KEY_ALERT_GAP_SEC, d.arrivalAlertMinGapSec.toDouble()).toInt().coerceIn(0, 3600),
            autoReturnAfterLaps = str(KEY_AUTO_RETURN_LAPS, d.autoReturnAfterLaps.toDouble()).toInt().coerceIn(0, 999),
            returnMode = enum(KEY_RETURN_MODE, d.returnMode),
            mockNetworkProvider = sp.getBoolean(KEY_MOCK_NETWORK, d.mockNetworkProvider),
            mockFusedProvider = sp.getBoolean(KEY_MOCK_FUSED, d.mockFusedProvider),
            useFlpMockMode = sp.getBoolean(KEY_FLP_MOCK, d.useFlpMockMode),
            tickMs = d.tickMs,
        ).let { c -> if (c.accuracyMaxM < c.accuracyMinM) c.copy(accuracyMaxM = c.accuracyMinM) else c }
    }

    var defaultRadiusM: Double
        get() = str(KEY_DEFAULT_RADIUS, Waypoint.DEFAULT_RADIUS_M).coerceIn(8.0, 40.0)
        set(v) = sp.edit { putString(KEY_DEFAULT_RADIUS, v.toString()) }

    var defaultDwellSec: Int
        get() = str(KEY_DEFAULT_DWELL, Waypoint.DEFAULT_DWELL_SEC.toDouble()).toInt().coerceIn(0, 1800)
        set(v) = sp.edit { putString(KEY_DEFAULT_DWELL, v.toString()) }

    var home: LatLng?
        get() {
            if (!sp.contains(KEY_HOME_LAT)) return null
            val lat = java.lang.Double.longBitsToDouble(sp.getLong(KEY_HOME_LAT, 0))
            val lon = java.lang.Double.longBitsToDouble(sp.getLong(KEY_HOME_LON, 0))
            return runCatching { LatLng(lat, lon) }.getOrNull()
        }

        set(v) = sp.edit {
            if (v == null) {
                remove(KEY_HOME_LAT); remove(KEY_HOME_LON); remove(KEY_HOME_SAVED_AT)
            } else {
                putLong(KEY_HOME_LAT, java.lang.Double.doubleToRawLongBits(v.lat))
                putLong(KEY_HOME_LON, java.lang.Double.doubleToRawLongBits(v.lon))
                putLong(KEY_HOME_SAVED_AT, System.currentTimeMillis())
            }
        }

    var mapProvider: MapProvider
        get() = enum("map_provider", MapProvider.OPENSTREETMAP)
        set(value) = sp.edit { putString("map_provider", value.name) }

    var spiralLineWidthM: Double
        get() = str("spiral_line_width_m", 40.0).coerceIn(1.0, 1000.0)
        set(value) = sp.edit { putString("spiral_line_width_m", value.toString()) }

    /**
     * A home the user chose on the map instead of where the phone really is (「我近期想要待在
     * 日本到處溜搭」). While set, every patrol starts here and 回家 walks back here and parks with
     * the mock still on; only 停止 returns the game to the real GPS. Null = the normal behaviour,
     * where home is always a fresh real fix.
     */
    var customHome: LatLng?
        get() {
            if (!sp.contains(KEY_CUSTOM_HOME_LAT)) return null
            val lat = java.lang.Double.longBitsToDouble(sp.getLong(KEY_CUSTOM_HOME_LAT, 0))
            val lon = java.lang.Double.longBitsToDouble(sp.getLong(KEY_CUSTOM_HOME_LON, 0))
            return runCatching { LatLng(lat, lon) }.getOrNull()
        }
        set(v) = sp.edit {
            if (v == null) {
                remove(KEY_CUSTOM_HOME_LAT); remove(KEY_CUSTOM_HOME_LON)
            } else {
                putLong(KEY_CUSTOM_HOME_LAT, java.lang.Double.doubleToRawLongBits(v.lat))
                putLong(KEY_CUSTOM_HOME_LON, java.lang.Double.doubleToRawLongBits(v.lon))
            }
        }

    val homeSavedAtMs: Long get() = sp.getLong(KEY_HOME_SAVED_AT, 0L)
    val savedHomeAgeMs: Long get() = if (homeSavedAtMs == 0L) Long.MAX_VALUE else System.currentTimeMillis() - homeSavedAtMs

    var lastPosition: LatLng?
        get() {
            if (!sp.contains(KEY_LAST_LAT)) return null
            val lat = java.lang.Double.longBitsToDouble(sp.getLong(KEY_LAST_LAT, 0))
            val lon = java.lang.Double.longBitsToDouble(sp.getLong(KEY_LAST_LON, 0))
            return runCatching { LatLng(lat, lon) }.getOrNull()
        }
        set(v) = sp.edit {
            if (v == null) { remove(KEY_LAST_LAT); remove(KEY_LAST_LON) } else {
                putLong(KEY_LAST_LAT, java.lang.Double.doubleToRawLongBits(v.lat))
                putLong(KEY_LAST_LON, java.lang.Double.doubleToRawLongBits(v.lon))
            }
        }

    var disclaimerAccepted: Boolean
        get() = sp.getBoolean(KEY_DISCLAIMER, false)
        set(v) = sp.edit { putBoolean(KEY_DISCLAIMER, v) }

    /** Show the floating control bar (ui/OverlayService) automatically while a patrol runs. */
    var overlayEnabled: Boolean
        get() = sp.getBoolean(KEY_OVERLAY_ENABLED, false)
        set(v) = sp.edit { putBoolean(KEY_OVERLAY_ENABLED, v) }

    /** Pinned overlays stay on screen when the patrol goes back to IDLE (long-press the handle). */
    var overlayPinned: Boolean
        get() = sp.getBoolean(KEY_OVERLAY_PINNED, false)
        set(v) = sp.edit { putBoolean(KEY_OVERLAY_PINNED, v) }

    /** Last position of the floating window in pixels; [OVERLAY_UNSET] means "never moved". */
    var overlayX: Int
        get() = sp.getInt(KEY_OVERLAY_X, OVERLAY_UNSET)
        set(v) = sp.edit { putInt(KEY_OVERLAY_X, v) }

    var overlayY: Int
        get() = sp.getInt(KEY_OVERLAY_Y, OVERLAY_UNSET)
        set(v) = sp.edit { putInt(KEY_OVERLAY_Y, v) }

    /**
     * The last successful bird's-eye scan calibration (vision/FlowerScanner). A second scan at the
     * same map zoom can adopt it after a short check instead of walking the full 40 m baseline.
     */
    var scanCalibration: Calibration?
        get() {
            if (!sp.contains(KEY_SCAN_CAL_MPP)) return null
            val mpp = java.lang.Double.longBitsToDouble(sp.getLong(KEY_SCAN_CAL_MPP, 0))
            val north = java.lang.Double.longBitsToDouble(sp.getLong(KEY_SCAN_CAL_NORTH, 0))
            return Calibration(mpp, north).takeIf { it.isPlausible() }
        }
        set(v) = sp.edit {
            if (v == null) {
                remove(KEY_SCAN_CAL_MPP); remove(KEY_SCAN_CAL_NORTH); remove(KEY_SCAN_CAL_SAVED_AT)
            } else {
                putLong(KEY_SCAN_CAL_MPP, java.lang.Double.doubleToRawLongBits(v.metresPerPixel))
                putLong(KEY_SCAN_CAL_NORTH, java.lang.Double.doubleToRawLongBits(v.screenNorthDeg))
                putLong(KEY_SCAN_CAL_SAVED_AT, System.currentTimeMillis())
            }
        }

    val scanCalibrationSavedAtMs: Long get() = sp.getLong(KEY_SCAN_CAL_SAVED_AT, 0L)

    // ------------------------------------------------------------------ decor hunt (ui/DecorHunt)

    /** The [Decor] picked last time, preselected in the picker. */
    var lastDecor: Decor?
        get() = sp.getString(KEY_LAST_DECOR, null)?.let { Decor.byName(it) }
        set(v) = sp.edit { if (v == null) remove(KEY_LAST_DECOR) else putString(KEY_LAST_DECOR, v.name) }

    /** How the user last chose to cover the leg to a decor place. */
    var tripTravelMode: TravelMode
        get() = enum(KEY_TRIP_TRAVEL_MODE, TravelMode.WALK)
        set(v) = sp.edit { putString(KEY_TRIP_TRAVEL_MODE, v.name) }

    /** Wander radius around the destination as typed (not clamped to the waypoint limit). */
    var tripWanderRadiusM: Double
        get() = sp.getFloat(KEY_TRIP_WANDER_RADIUS, DEFAULT_TRIP_WANDER_RADIUS_M.toFloat()).toDouble().coerceIn(1.0, 500.0)
        set(v) = sp.edit { putFloat(KEY_TRIP_WANDER_RADIUS, v.toFloat()) }

    var tripWanderMin: Int
        get() = sp.getInt(KEY_TRIP_WANDER_MIN, DEFAULT_TRIP_WANDER_MIN).coerceIn(0, 30)
        set(v) = sp.edit { putInt(KEY_TRIP_WANDER_MIN, v) }

    private fun str(key: String, default: Double): Double =
        sp.getString(key, null)?.trim()?.toDoubleOrNull() ?: default

    private inline fun <reified E : Enum<E>> enum(key: String, default: E): E {
        val raw = sp.getString(key, null) ?: return default
        return enumValues<E>().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: default
    }

    companion object {
        /** 20 km/h. Above roughly this speed Pikmin Bloom stops planting flowers. */
        const val MAX_SPEED_MPS = 20.0 / 3.6

        const val KEY_SPEED_KMH = "speed_kmh"
        const val KEY_ORBIT = "orbit_at_waypoints"
        const val KEY_SPEED_JITTER_PCT = "speed_jitter_pct"
        const val KEY_STRIDE_CM = "stride_cm"
        const val KEY_LOOP_MODE = "loop_mode"
        const val KEY_INJECT_STEPS = "inject_steps"
        const val KEY_STEP_FLUSH_SEC = "step_flush_sec"
        const val KEY_DAILY_STEP_CAP = "daily_step_cap"
        const val KEY_MAX_CADENCE = "max_cadence_spm"
        const val KEY_ACC_MIN = "accuracy_min_m"
        const val KEY_ACC_MAX = "accuracy_max_m"
        const val KEY_ALTITUDE = "altitude_m"
        const val KEY_NOTIFY_ARRIVAL = "notify_on_arrival"
        const val KEY_VIBRATE_ARRIVAL = "vibrate_on_arrival"
        const val KEY_ALERT_GAP_SEC = "alert_gap_sec"
        const val KEY_AUTO_RETURN_LAPS = "auto_return_after_laps"
        const val KEY_RETURN_MODE = "return_mode"
        const val KEY_MOCK_NETWORK = "mock_network"
        const val KEY_MOCK_FUSED = "mock_fused"
        const val KEY_FLP_MOCK = "flp_mock_mode"
        const val KEY_DEFAULT_RADIUS = "default_radius_m"
        const val KEY_DEFAULT_DWELL = "default_dwell_sec"
        const val KEY_HOME_LAT = "home_lat"
        const val KEY_HOME_LON = "home_lon"
        const val KEY_HOME_SAVED_AT = "home_saved_at"
        const val KEY_CUSTOM_HOME_LAT = "custom_home_lat"
        const val KEY_CUSTOM_HOME_LON = "custom_home_lon"
        const val KEY_LAST_LAT = "last_lat"
        const val KEY_LAST_LON = "last_lon"
        const val KEY_DISCLAIMER = "disclaimer_accepted"
        const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        const val KEY_OVERLAY_PINNED = "overlay_pinned"
        const val KEY_OVERLAY_X = "overlay_x"
        const val KEY_OVERLAY_Y = "overlay_y"
        const val KEY_SCAN_CAL_MPP = "scan_cal_metres_per_pixel"
        const val KEY_SCAN_CAL_NORTH = "scan_cal_screen_north_deg"
        const val KEY_SCAN_CAL_SAVED_AT = "scan_cal_saved_at"
        const val KEY_LAST_DECOR = "last_decor"
        const val KEY_TRIP_TRAVEL_MODE = "trip_travel_mode"
        const val KEY_TRIP_WANDER_RADIUS = "trip_wander_radius_m"
        const val KEY_TRIP_WANDER_MIN = "trip_wander_min"

        /** Sentinel for [overlayX] / [overlayY] meaning "use the default placement". */
        const val OVERLAY_UNSET = Int.MIN_VALUE

        /** Matches the defaults of `PatrolPlanner.planTripTo` (60 m, 15 min). */
        const val DEFAULT_TRIP_WANDER_RADIUS_M = 60.0
        const val DEFAULT_TRIP_WANDER_MIN = 15
    }
}
