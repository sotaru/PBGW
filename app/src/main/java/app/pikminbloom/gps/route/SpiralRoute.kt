package app.pikminbloom.gps.route

import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.geo.LatLng
import kotlin.math.PI
import kotlin.math.sqrt

/** Each turn expands by the covered line width plus clearance for curvature and GPS noise. */
class SpiralRoute(
    val center: LatLng,
    val lineWidthM: Double = DEFAULT_LINE_WIDTH_M,
    angleRad: Double = 0.0,
    val spacingM: Double = spacingForWidth(lineWidthM),
) {
    init {
        require(lineWidthM.isFinite() && lineWidthM > 0.0)
        require(spacingM.isFinite() && spacingM > 0.0)
        require(angleRad.isFinite() && angleRad >= 0.0)
    }

    var angleRad: Double = angleRad
        private set

    private val growth get() = spacingM / (2.0 * PI)

    fun pointAt(angle: Double): LatLng =
        GeoMath.destination(center, Math.toDegrees(angle), growth * angle)

    /** Invert r = a * theta to recover progress at a checkpoint between chunk boundaries. */
    fun angleAt(position: LatLng): Double = GeoMath.distanceM(center, position) / growth

    fun nextPlan(from: LatLng = pointAt(angleRad)): PatrolPlan {
        var cursor = from
        val segments = ArrayList<RouteSegment>(CHUNK_SEGMENTS)
        repeat(CHUNK_SEGMENTS) {
            // About 5 m per leg; cap the turn near the center to keep the curve smooth.
            angleRad += (5.0 / (growth * sqrt(1.0 + angleRad * angleRad))).coerceAtMost(0.12)
            val next = pointAt(angleRad)
            segments += RouteSegment(cursor, next, null, SegmentKind.TRAVEL)
            cursor = next
        }
        return PatrolPlan.of(segments)
    }

    companion object {
        const val DEFAULT_LINE_WIDTH_M = 40.0
        const val CHUNK_SEGMENTS = 128
        fun parseLineWidth(text: String?): Double? = text?.trim()?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it in 1.0..1000.0 }

        /** A run's temporary choice takes precedence without changing the saved default. */
        fun resolveLineWidth(requested: Double?, default: Double): Double =
            requested?.takeIf { it.isFinite() && it in 1.0..1000.0 }
                ?: default.takeIf { it.isFinite() && it in 1.0..1000.0 }
                ?: DEFAULT_LINE_WIDTH_M

        fun spacingForWidth(widthM: Double): Double = widthM + maxOf(3.0, widthM * 0.10)
    }
}
