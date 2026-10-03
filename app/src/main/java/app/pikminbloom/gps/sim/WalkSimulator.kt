package app.pikminbloom.gps.sim

import app.pikminbloom.gps.data.PatrolConfig
import app.pikminbloom.gps.data.TravelMode
import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.geo.LatLng
import app.pikminbloom.gps.route.PatrolPlan
import app.pikminbloom.gps.route.RouteSegment
import app.pikminbloom.gps.route.SegmentKind
import kotlin.math.min
import kotlin.random.Random

/** One simulated GPS fix. */
data class Sample(
    val position: LatLng,
    val speedMps: Double,
    val bearingDeg: Double,
    val accuracyM: Float,
    val altitudeM: Double,
    val distanceDeltaM: Double,
    val segmentIndex: Int,
    val waypointIndex: Int?,
    val kind: SegmentKind,
    /** Set exactly once per waypoint visit, on the sample that passes the arrival point. */
    val arrivedAtWaypoint: Int?,
    /** True on the sample that reaches the end of the loaded plan. */
    val lapFinished: Boolean,
    /** False on vehicle legs: distance still accrues, but no steps are written for it. */
    val countsSteps: Boolean = true,
    /** Progress along the plan in metres (exact, without lateral noise). */
    val progressM: Double = 0.0,
    /** Only the on-foot portion, even when this tick crosses a vehicle/walking boundary. */
    val stepDistanceDeltaM: Double = if (countsSteps) distanceDeltaM else 0.0,
)

/**
 * Moves along a [PatrolPlan] at walking speed and produces natural-looking fixes.
 *
 * Progress along the path is exact; the *reported* position carries a bounded random-walk lateral
 * noise (<= 1 m), the speed wobbles by +-[PatrolConfig.speedJitterPct] re-drawn every few seconds,
 * accuracy drifts slowly inside the configured band and altitude drifts a few decimetres.
 * Deterministic for a seeded [Random].
 *
 * Two things can change while a plan is being walked: the configured speed ([updateConfig]) and a
 * live vehicle override ([travelOverride]). Both apply from the next tick on; legs that declare
 * their own [RouteSegment.travelMode] (decor trips) keep it regardless.
 */
class WalkSimulator(
    config: PatrolConfig,
    private val random: Random = Random.Default,
) {
    private var config: PatrolConfig = config
    private var plan: PatrolPlan = PatrolPlan.EMPTY
    private var segIdx = 0
    private var distIntoSeg = 0.0
    private var progress = 0.0
    private var elapsedSec = 0.0

    private var targetSpeed = config.speedMps
    private var speedFactor = 1.0
    private var nextSpeedRedrawAt = 0.0

    private var accuracy = ((config.accuracyMinM + config.accuracyMaxM) / 2f)
    private var altitude = config.altitudeM
    private var noiseNorthM = 0.0
    private var noiseEastM = 0.0

    private var lastBearing = 0.0
    private var lastSample: Sample = idleSample(LatLng(0.0, 0.0))

    /** Exact position while the joystick steers ([advanceManual]); null whenever a plan is loaded. */
    private var manualPos: LatLng? = null

    /**
     * Vehicle override for every leg that has no travel mode of its own. Null walks at the
     * configured speed and counts steps; a vehicle moves at its speed and counts none.
     */
    var travelOverride: TravelMode? = null

    var finished: Boolean = true
        private set

    /** True after [advanceManual] until the next [load]: the loaded plan no longer describes where we are. */
    val inManual: Boolean get() = manualPos != null

    fun load(plan: PatrolPlan) {
        this.plan = plan
        segIdx = 0
        distIntoSeg = 0.0
        progress = 0.0
        manualPos = null
        finished = plan.isEmpty
        val start = plan.start ?: lastSample.position
        if (!plan.isEmpty) lastBearing = plan.segments[0].bearingDeg
        lastSample = idleSample(start).copy(
            segmentIndex = 0,
            waypointIndex = plan.segments.firstOrNull()?.waypointIndex,
            kind = plan.segments.firstOrNull()?.kind ?: SegmentKind.TRAVEL,
        )
    }

    /** Instant relocation has no travelled distance, steps or waypoint arrival. */
    fun relocate(position: LatLng) {
        plan = PatrolPlan.EMPTY
        segIdx = 0
        distIntoSeg = 0.0
        progress = 0.0
        manualPos = null
        noiseNorthM = 0.0
        noiseEastM = 0.0
        finished = true
        lastSample = idleSample(position)
    }

    fun exactCurrentPosition(): LatLng = exactPosition()

    /** Applies a settings change mid-walk; the new speed is used from the next tick on. */
    fun updateConfig(config: PatrolConfig) {
        this.config = config
        targetSpeed = config.speedMps
        accuracy = accuracy.coerceIn(config.accuracyMinM, config.accuracyMaxM)
    }

    fun setSpeed(mps: Double) {
        targetSpeed = mps.coerceIn(0.2, 200.0)
    }

    /** Last reported fix without moving (used while paused). Speed is reported as 0. */
    fun current(): Sample = lastSample.copy(
        speedMps = 0.0,
        distanceDeltaM = 0.0,
        stepDistanceDeltaM = 0.0,
        arrivedAtWaypoint = null,
        lapFinished = false,
    )

    /** Speed of a leg without its own travel mode, before jitter. */
    private val legDefaultSpeed: Double get() = travelOverride?.speedMps ?: targetSpeed

    private fun legSpeedOf(seg: RouteSegment): Double = seg.travelMode?.speedMps ?: legDefaultSpeed

    private fun legCountsSteps(seg: RouteSegment): Boolean =
        seg.travelMode?.countsSteps ?: (travelOverride?.countsSteps ?: true)

    private fun redrawJitter(dt: Double) {
        elapsedSec += dt
        if (elapsedSec >= nextSpeedRedrawAt) {
            val j = config.speedJitterPct / 100.0
            // Random.nextDouble(from, until) requires until > from, so 0 % jitter must be special-cased.
            speedFactor = if (j > 1e-9) 1.0 + random.nextDouble(-j, j) else 1.0
            nextSpeedRedrawAt = elapsedSec + random.nextDouble(3.0, 8.0)
        }
    }

    fun advance(dtSec: Double): Sample {
        val dt = dtSec.coerceIn(0.0, 5.0)
        redrawJitter(dt)
        drift()

        // Off-route after the joystick: the plan cannot be advanced from here, so hold position
        // until the caller loads a plan that starts where we are.
        if (finished || plan.isEmpty || manualPos != null) {
            lastSample = lastSample.copy(
                speedMps = 0.0,
                accuracyM = accuracy,
                altitudeM = altitude,
                distanceDeltaM = 0.0,
                stepDistanceDeltaM = 0.0,
                arrivedAtWaypoint = null,
                lapFinished = false,
                position = noisy(exactPosition()),
            )
            return lastSample
        }

        // Time, not distance, is what a tick actually spends. Legs can have very different speeds
        // (a highway leg followed by a walk), so crossing from one into the next mid-tick must
        // switch to the new leg's speed instead of finishing the tick at the old one.
        var remainingTime = dt
        var moved = 0.0
        var walked = 0.0
        var arrived: Int? = null
        var lapDone = false
        var lastLegSpeed = 0.0

        while (remainingTime > 1e-9 && !finished) {
            val seg = plan.segments[segIdx]
            // Never consume two arrival points in one tick: each arrival must be reported once.
            if (arrived != null && seg.arrivalAtEnd) break
            val legSpeed = (legSpeedOf(seg) * speedFactor).coerceAtLeast(1e-6)
            lastLegSpeed = legSpeed
            val left = seg.lengthM - distIntoSeg
            val step = min(left, legSpeed * remainingTime)
            distIntoSeg += step
            remainingTime -= step / legSpeed
            moved += step
            if (legCountsSteps(seg)) walked += step
            progress += step
            if (distIntoSeg >= seg.lengthM - 1e-9) {
                if (seg.arrivalAtEnd && seg.waypointIndex != null) arrived = seg.waypointIndex
                if (segIdx + 1 < plan.segments.size) {
                    segIdx++
                    distIntoSeg = 0.0
                    // A zero-length leg (only carries a flag) must not stall the loop.
                    if (plan.segments[segIdx].lengthM <= 1e-9) {
                        val s = plan.segments[segIdx]
                        if (s.arrivalAtEnd && s.waypointIndex != null) arrived = s.waypointIndex
                        if (segIdx + 1 < plan.segments.size) { segIdx++ } else { finished = true; lapDone = true }
                    }
                } else {
                    finished = true
                    lapDone = true
                }
            }
        }

        val seg = plan.segments[segIdx]
        if (seg.lengthM > 1e-9) lastBearing = seg.bearingDeg
        val exact = exactPosition()
        lastSample = Sample(
            position = noisy(exact),
            // Report the speed of the leg we ended the tick on, so a fix taken just after a
            // highway leg does not still claim 90 km/h while the avatar is walking.
            speedMps = if (finished) 0.0 else (legSpeedOf(seg) * speedFactor).takeIf { it > 0 } ?: lastLegSpeed,
            bearingDeg = lastBearing,
            accuracyM = accuracy,
            altitudeM = altitude,
            distanceDeltaM = moved,
            segmentIndex = segIdx,
            waypointIndex = seg.waypointIndex,
            kind = seg.kind,
            arrivedAtWaypoint = arrived,
            lapFinished = lapDone,
            progressM = progress,
            countsSteps = legCountsSteps(seg),
            stepDistanceDeltaM = walked,
        )
        return lastSample
    }

    /**
     * Joystick tick: moves [magnitude] (0..1) of the current speed along [bearingDeg] from wherever
     * we are, ignoring the loaded plan. The plan is left untouched; the caller re-plans from
     * [current] when the joystick is put away. An explicit [speedMode] is local to manual motion;
     * the automatic route's [travelOverride] stays unchanged. Null uses the configured walking speed.
     */
    fun advanceManual(
        dtSec: Double, bearingDeg: Double, magnitude: Double,
        speedMode: TravelMode? = travelOverride,
    ): Sample {
        val dt = dtSec.coerceIn(0.0, 5.0)
        redrawJitter(dt)
        drift()
        val from = manualPos ?: exactPosition()
        val speed = (speedMode?.speedMps ?: targetSpeed) * speedFactor * magnitude.coerceIn(0.0, 1.0)
        val step = speed * dt
        val to = if (step > 1e-6) GeoMath.destination(from, bearingDeg, step) else from
        manualPos = to
        if (step > 1e-6) lastBearing = GeoMath.normalizeBearing(bearingDeg)
        progress += step
        lastSample = Sample(
            position = noisy(to),
            speedMps = speed,
            bearingDeg = lastBearing,
            accuracyM = accuracy,
            altitudeM = altitude,
            distanceDeltaM = step,
            segmentIndex = segIdx,
            waypointIndex = null,
            kind = SegmentKind.TRAVEL,
            arrivedAtWaypoint = null,
            lapFinished = false,
            progressM = progress,
            countsSteps = speedMode?.countsSteps ?: true,
        )
        return lastSample
    }

    /**
     * The unfinished legs that belong to the waypoint we are currently on, the first one shortened
     * to start exactly where we are. Lets a re-plan keep an orbit (or a vehicle leg) in progress
     * instead of restarting it. Empty when nothing is loaded, the plan is finished, or the
     * joystick has taken over.
     */
    fun remainingLegsOfCurrentWaypoint(): List<RouteSegment> {
        if (finished || plan.isEmpty || manualPos != null) return emptyList()
        val cur = plan.segments[segIdx]
        val out = ArrayList<RouteSegment>()
        val here = exactPosition()
        if (GeoMath.distanceM(here, cur.to) > 0.5) out += cur.copy(from = here)
        for (i in segIdx + 1 until plan.segments.size) {
            val s = plan.segments[i]
            if (s.waypointIndex != cur.waypointIndex) break
            out += s
        }
        return out
    }

    private fun exactPosition(): LatLng {
        manualPos?.let { return it }
        if (plan.isEmpty) return lastSample.position
        val seg = plan.segments[segIdx]
        if (seg.lengthM <= 1e-9) return seg.to
        val f = (distIntoSeg / seg.lengthM).coerceIn(0.0, 1.0)
        return GeoMath.interpolate(seg.from, seg.to, f)
    }

    private fun drift() {
        // Bounded random walks: lateral noise <= 1 m, accuracy inside the band, altitude +-1 m.
        noiseNorthM = (noiseNorthM + random.nextDouble(-0.25, 0.25)).coerceIn(-1.0, 1.0)
        noiseEastM = (noiseEastM + random.nextDouble(-0.25, 0.25)).coerceIn(-1.0, 1.0)
        val mag = kotlin.math.hypot(noiseNorthM, noiseEastM)
        if (mag > 1.0) { noiseNorthM /= mag; noiseEastM /= mag }
        accuracy = (accuracy + random.nextDouble(-0.4, 0.4).toFloat())
            .coerceIn(config.accuracyMinM, config.accuracyMaxM)
        altitude = (altitude + random.nextDouble(-0.05, 0.05)).coerceIn(config.altitudeM - 1.0, config.altitudeM + 1.0)
    }

    private fun noisy(p: LatLng): LatLng = GeoMath.offsetMeters(p, noiseNorthM, noiseEastM)

    private fun idleSample(p: LatLng) = Sample(
        position = p,
        speedMps = 0.0,
        bearingDeg = lastBearing,
        accuracyM = accuracy,
        altitudeM = altitude,
        distanceDeltaM = 0.0,
        segmentIndex = 0,
        waypointIndex = null,
        kind = SegmentKind.TRAVEL,
        arrivedAtWaypoint = null,
        lapFinished = false,
        progressM = progress,
    )
}
