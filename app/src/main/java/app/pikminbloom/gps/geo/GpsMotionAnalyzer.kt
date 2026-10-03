package app.pikminbloom.gps.geo

enum class GpsMotion { UNKNOWN, MOVING, STATIONARY }

data class GpsMotionReading(
    val motion: GpsMotion = GpsMotion.UNKNOWN,
    val speedMps: Double? = null,
    val accuracyM: Double? = null,
)

/** Real GPS motion estimate. Accuracy bounds, confirmation and expiry suppress GPS drift. */
class GpsMotionAnalyzer {
    data class Fix(
        val position: LatLng,
        val elapsedMs: Long,
        val accuracyM: Double,
        val speedMps: Double? = null,
        val speedAccuracyMps: Double? = null,
        val mocked: Boolean = false,
    )

    private val fixes = ArrayDeque<Fix>()
    private var lastSeenMs: Long? = null
    private var lastReliableMs: Long? = null
    private var candidate = GpsMotion.UNKNOWN
    private var candidateSinceMs = 0L
    private var reading = GpsMotionReading()

    fun addFix(fix: Fix, nowMs: Long = fix.elapsedMs): GpsMotionReading {
        // Never use cached, mocked or reordered positions to determine current movement.
        if (fix.mocked || fix.elapsedMs < 0 || fix.elapsedMs > nowMs || nowMs - fix.elapsedMs > STALE_MS ||
            (lastSeenMs != null && fix.elapsedMs <= lastSeenMs!!)) return snapshot(nowMs)
        if (lastReliableMs != null && fix.elapsedMs - lastReliableMs!! > STALE_MS) reset()
        lastSeenMs = fix.elapsedMs
        if (!fix.accuracyM.isFinite() || fix.accuracyM !in 0.0..MAX_ACCURACY_M) {
            clearMotion()
            reading = GpsMotionReading(accuracyM = fix.accuracyM.takeIf { it.isFinite() && it >= 0 })
            return reading
        }
        lastReliableMs = fix.elapsedMs
        fixes.addLast(fix)
        while (fixes.isNotEmpty() && fix.elapsedMs - fixes.first().elapsedMs > WINDOW_MS) fixes.removeFirst()

        val speed = fix.speedMps?.takeIf { it.isFinite() && it >= 0 }
        val error = fix.speedAccuracyMps?.takeIf { it.isFinite() && it in 0.0..MAX_SPEED_ERROR_MPS }
        val speedMotion = if (speed != null && error != null) when {
            speed - error >= MOVING_MPS -> GpsMotion.MOVING
            speed + error <= STATIONARY_MPS -> GpsMotion.STATIONARY
            else -> GpsMotion.UNKNOWN
        } else GpsMotion.UNKNOWN
        val coordinateMotion = coordinateMotion(fix)
        // Prefer trustworthy GNSS speed; otherwise compare positions over a longer window.
        val next = if (speedMotion != GpsMotion.UNKNOWN) speedMotion else coordinateMotion
        if (next == GpsMotion.UNKNOWN) {
            candidate = GpsMotion.UNKNOWN
            reading = GpsMotionReading(accuracyM = fix.accuracyM)
        } else {
            if (next != candidate) {
                candidate = next
                candidateSinceMs = fix.elapsedMs
            }
            val confirmed = fix.elapsedMs - candidateSinceMs >= CONFIRM_MS
            reading = GpsMotionReading(
                motion = if (confirmed) next else reading.motion,
                speedMps = if (speedMotion != GpsMotion.UNKNOWN) speed else null,
                accuracyM = fix.accuracyM,
            )
        }
        return reading
    }

    private fun coordinateMotion(latest: Fix): GpsMotion {
        val anchor = fixes.first()
        val spanSec = (latest.elapsedMs - anchor.elapsedMs) / 1000.0
        if (spanSec < 10.0) return GpsMotion.UNKNOWN
        val displacement = GeoMath.distanceM(anchor.position, latest.position)
        val noise = maxOf(3.0, anchor.accuracyM + latest.accuracyM)
        if (displacement > noise && displacement / spanSec >= MOVING_MPS) return GpsMotion.MOVING
        // Check the entire window, so returning to the starting point does not imply a stop.
        val withinNoise = fixes.all {
            GeoMath.distanceM(anchor.position, it.position) <= maxOf(2.0, (anchor.accuracyM + it.accuracyM) / 2)
        }
        return if (spanSec >= 15.0 && withinNoise) GpsMotion.STATIONARY else GpsMotion.UNKNOWN
    }

    fun snapshot(nowMs: Long): GpsMotionReading {
        if (lastReliableMs == null || nowMs - lastReliableMs!! > STALE_MS || nowMs < lastReliableMs!!) {
            clearMotion()
        }
        return reading
    }

    fun reset() {
        lastSeenMs = null
        clearMotion()
    }

    private fun clearMotion() {
        fixes.clear()
        lastReliableMs = null
        candidate = GpsMotion.UNKNOWN
        reading = GpsMotionReading()
    }

    companion object {
        private const val STALE_MS = 15_000L
        private const val WINDOW_MS = 20_000L
        private const val CONFIRM_MS = 3000L
        private const val MAX_ACCURACY_M = 25.0
        private const val MAX_SPEED_ERROR_MPS = 0.5
        private const val MOVING_MPS = 0.5
        private const val STATIONARY_MPS = 0.3
    }
}
