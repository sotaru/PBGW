package app.pikminbloom.gps.data

import app.pikminbloom.gps.geo.LatLng

/**
 * A Big Flower (巨大花朵) the patrol should visit.
 *
 * @param radiusM  radius of the circle the walker wanders inside once it arrives (Pikmin Bloom counts
 *                 flowers planted within 40 m of a Big Flower; default 30 m keeps us safely inside).
 * @param dwellSec seconds to keep wandering inside the circle before moving on (0 = pass through only).
 */
data class Waypoint(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val radiusM: Double = DEFAULT_RADIUS_M,
    val dwellSec: Int = DEFAULT_DWELL_SEC,
) {
    val latLng: LatLng get() = LatLng(lat, lon)

    companion object {
        const val DEFAULT_RADIUS_M = 30.0
        const val DEFAULT_DWELL_SEC = 120
    }
}

enum class LoopMode { LOOP, PINGPONG, ONCE }

/**
 * How to cover a leg of a journey.
 *
 * Long legs are the problem this solves. Walking 30 km at walking speed takes six hours, and
 * "walking" it at 20 km/h is a speed no human sustains. Covering it at a vehicle speed instead is
 * both faster and more ordinary-looking, and it is honest about steps: nobody takes steps while
 * driving, so [countsSteps] is false for every vehicle and no step data is written for those legs.
 *
 * Pikmin Bloom stops planting flowers somewhere around 15-20 km/h, so vehicle legs plant nothing
 * either. That is expected: the point of a vehicle leg is to *arrive*, and the walking starts there.
 */
enum class TravelMode(val speedKmh: Double, val countsSteps: Boolean, val label: String) {
    WALK(4.7, true, "步行"),
    BRISK(7.0, true, "快走"),
    RUN(10.0, true, "慢跑"),
    BIKE(18.0, false, "腳踏車"),
    CAR(45.0, false, "汽機車"),
    HIGHWAY(90.0, false, "高速公路"),
    PLANE(600.0, false, "飛機"),
    ;

    val speedMps: Double get() = speedKmh / 3.6

    companion object {
        /** Sensible mode for a leg of [distanceM], so the user does not have to think about it. */
        fun suggestFor(distanceM: Double): TravelMode = when {
            distanceM < 1_500 -> WALK
            distanceM < 15_000 -> CAR
            distanceM < 300_000 -> HIGHWAY
            else -> PLANE
        }
    }
}

enum class ReturnMode { WALK, TELEPORT }

enum class PatrolMode { WAYPOINTS, SPIRAL, HOLD }
enum class MapProvider { OPENSTREETMAP, GOOGLE }

/** All tunables, persisted by Prefs. Defaults are chosen to look like a normal walk (~4.7 km/h). */
data class PatrolConfig(
    val speedMps: Double = 1.3,
    val speedJitterPct: Double = 10.0,
    val strideM: Double = 0.70,
    val loopMode: LoopMode = LoopMode.LOOP,
    /**
     * When false the patrol only walks THROUGH each Big Flower's circle and moves straight on to the
     * next one, ignoring [Waypoint.dwellSec]. Turn it on only when you want to farm one flower's
     * 40 m circle (planting toward the 300-flower bloom).
     */
    val orbitAtWaypoints: Boolean = false,
    val injectSteps: Boolean = true,
    val stepFlushIntervalSec: Int = 60,
    val dailyStepCap: Long = 50_000,
    /**
     * Ceiling on how many steps per minute may be written, whatever the simulated speed says.
     * 0 disables it. Distance / stride alone produces impossible cadences at high speed: 20 km/h
     * with a 0.7 m stride is ~476 steps per minute, where a fast human runner is 180-200.
     */
    val maxCadenceSpm: Int = 0,
    val accuracyMinM: Float = 3f,
    val accuracyMaxM: Float = 9f,
    val altitudeM: Double = 20.0,
    val notifyOnArrival: Boolean = true,
    /**
     * Buzz as well as post the arrival notification. Off by default: the vibration motor is one of
     * the few things on the phone that costs more power than the GPS work this app already does,
     * and a multi-flower loop would fire it constantly.
     */
    val vibrateOnArrival: Boolean = false,
    /** Never alert more often than this, however many flowers are passed. */
    val arrivalAlertMinGapSec: Int = 60,
    /** Walk home automatically after this many completed laps. 0 disables it. */
    val autoReturnAfterLaps: Int = 0,
    val returnMode: ReturnMode = ReturnMode.WALK,
    val mockNetworkProvider: Boolean = true,
    val mockFusedProvider: Boolean = true,
    val useFlpMockMode: Boolean = true,
    val tickMs: Long = 1000,
)

enum class PatrolPhase {
    IDLE,
    STARTING,        // capturing real position, enabling mock providers
    WALKING,         // moving between waypoints
    DWELLING,        // wandering inside a waypoint circle
    PAUSED,          // position frozen, mock still active
    RETURNING_HOME,  // walking (or teleporting) back to the real position
    STOPPING,        // removing mock providers
    /**
     * Back at a user-chosen home (Prefs.customHome) with the mock still on. Only 停止 hands the game
     * back to the real GPS, because a custom home is by definition not where the phone is.
     */
    PARKED,
    /** The floating joystick steers; the route is suspended and resumes from wherever this ends. */
    MANUAL,
    /** A quick jump holds this position until another movement command or stop. */
    HOLDING,
}

/** Snapshot published by PatrolService (StateFlow) for the UI and notification. */
data class PatrolState(
    val mode: PatrolMode = PatrolMode.WAYPOINTS,
    val spiralCenter: LatLng? = null,
    val spiralRadiusM: Double = 0.0,
    val spiralLineWidthM: Double = 40.0,
    val spiralSpacingM: Double = 44.0,
    val phase: PatrolPhase = PatrolPhase.IDLE,
    val position: LatLng? = null,
    val home: LatLng? = null,
    val currentWaypointIndex: Int = -1,
    val currentWaypointName: String? = null,
    val distanceToTargetM: Double = 0.0,
    val distanceWalkedM: Double = 0.0,
    val sessionSteps: Long = 0,
    val stepsWrittenToday: Long = 0,
    val speedMps: Double = 0.0,
    val startedAtMs: Long = 0,
    val lapsCompleted: Int = 0,
    val lastError: String? = null,
    val mockAppSelected: Boolean = false,
    val healthConnectReady: Boolean = false,
    /** Live vehicle override (PatrolService.setTravelOverride); null = the configured walking speed. */
    val travelOverride: TravelMode? = null,
    /** True when this session's home is the user-chosen one (Prefs.customHome), not a real fix. */
    val homeIsCustom: Boolean = false,
)
