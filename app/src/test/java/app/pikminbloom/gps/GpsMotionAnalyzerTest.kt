package app.pikminbloom.gps

import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.geo.GpsMotion
import app.pikminbloom.gps.geo.GpsMotionAnalyzer
import app.pikminbloom.gps.geo.LatLng
import org.junit.Assert.*
import org.junit.Test

class GpsMotionAnalyzerTest {
    private val origin = LatLng(25.0, 121.0)
    private fun fix(time: Long, distanceM: Double = 0.0, accuracy: Double = 3.0,
                    speed: Double? = null, speedError: Double? = null, mock: Boolean = false) =
        GpsMotionAnalyzer.Fix(GeoMath.destination(origin, 0.0, distanceM), time, accuracy, speed, speedError, mock)

    @Test fun accurateGpsSpeedRequiresConfirmationAndDetectsWalkingThenStopping() {
        val analyzer = GpsMotionAnalyzer()
        assertEquals(GpsMotion.UNKNOWN, analyzer.addFix(fix(0, speed = 1.2, speedError = 0.1)).motion)
        assertEquals(GpsMotion.MOVING, analyzer.addFix(fix(5000, 6.0, speed = 1.2, speedError = 0.1)).motion)
        analyzer.addFix(fix(10_000, 6.0, speed = 0.0, speedError = 0.1))
        assertEquals(GpsMotion.STATIONARY, analyzer.addFix(fix(15_000, 6.0, speed = 0.0, speedError = 0.1)).motion)
    }

    @Test fun coordinateOnlyMovementIsDetectedBeyondAccuracyBounds() {
        val analyzer = GpsMotionAnalyzer()
        for (second in 0L..20L step 5L) analyzer.addFix(fix(second * 1000, second.toDouble()))
        assertEquals(GpsMotion.MOVING, analyzer.snapshot(20_000).motion)
    }

    @Test fun smallStationaryCoordinateDriftDoesNotBecomeWalking() {
        val analyzer = GpsMotionAnalyzer()
        for (second in 0L..25L step 5L) analyzer.addFix(fix(second * 1000, if (second % 10 == 0L) 0.0 else 2.0))
        assertEquals(GpsMotion.STATIONARY, analyzer.snapshot(25_000).motion)
    }

    @Test fun oneFastFixCannotSwitchTheMotionState() {
        val analyzer = GpsMotionAnalyzer()
        analyzer.addFix(fix(0, speed = 0.0, speedError = 0.1))
        analyzer.addFix(fix(5000, speed = 0.0, speedError = 0.1))
        assertEquals(GpsMotion.STATIONARY, analyzer.addFix(fix(10_000, speed = 2.0, speedError = 0.1)).motion)
        assertEquals(GpsMotion.STATIONARY, analyzer.addFix(fix(15_000, speed = 0.0, speedError = 0.1)).motion)
    }

    @Test fun noisyGpsSpeedFallsBackToCoordinatesInsteadOfClaimingMotion() {
        val analyzer = GpsMotionAnalyzer()
        for (second in 0L..25L step 5L) analyzer.addFix(fix(second * 1000, speed = 2.0, speedError = 5.0))
        assertEquals(GpsMotion.STATIONARY, analyzer.snapshot(25_000).motion)
    }

    @Test fun poorAccuracyAndExpiredSignalRemoveTheBias() {
        val analyzer = GpsMotionAnalyzer()
        analyzer.addFix(fix(0, speed = 1.2, speedError = 0.1))
        analyzer.addFix(fix(5000, speed = 1.2, speedError = 0.1))
        assertEquals(GpsMotion.UNKNOWN, analyzer.snapshot(20_001).motion)
        analyzer.addFix(fix(25_000, speed = 1.2, speedError = 0.1))
        analyzer.addFix(fix(30_000, speed = 1.2, speedError = 0.1))
        assertEquals(GpsMotion.UNKNOWN, analyzer.addFix(fix(35_000, accuracy = 100.0, speed = 10.0, speedError = 0.1)).motion)
    }

    @Test fun mockedOldAndOutOfOrderFixesDoNotDriveMotion() {
        val analyzer = GpsMotionAnalyzer()
        assertEquals(GpsMotion.UNKNOWN, analyzer.addFix(fix(0, speed = 3.0, speedError = 0.1, mock = true)).motion)
        assertEquals(GpsMotion.UNKNOWN, analyzer.addFix(fix(5000, speed = 3.0, speedError = 0.1, mock = true)).motion)
        assertEquals(GpsMotion.UNKNOWN, analyzer.addFix(fix(0, speed = 3.0, speedError = 0.1), 20_000).motion)
        analyzer.addFix(fix(25_000, speed = 0.0, speedError = 0.1))
        assertEquals(GpsMotion.UNKNOWN, analyzer.addFix(fix(24_000, speed = 3.0, speedError = 0.1), 25_000).motion)
        assertEquals(GpsMotion.UNKNOWN, analyzer.addFix(fix(25_000, speed = 3.0, speedError = 0.1)).motion)
    }

    @Test fun returningToTheStartDoesNotImmediatelyClaimAStop() {
        val analyzer = GpsMotionAnalyzer()
        analyzer.addFix(fix(0))
        analyzer.addFix(fix(5000, 10.0))
        analyzer.addFix(fix(10_000, 20.0))
        analyzer.addFix(fix(15_000, 10.0))
        assertEquals(GpsMotion.UNKNOWN, analyzer.addFix(fix(20_000)).motion)
    }

    @Test fun resetRequiresNewEvidenceBeforeBiasingAgain() {
        val analyzer = GpsMotionAnalyzer()
        analyzer.addFix(fix(0, speed = 1.2, speedError = 0.1))
        analyzer.addFix(fix(5000, speed = 1.2, speedError = 0.1))
        analyzer.reset()
        assertEquals(GpsMotion.UNKNOWN, analyzer.snapshot(6000).motion)
        assertEquals(GpsMotion.UNKNOWN, analyzer.addFix(fix(10_000, speed = 1.2, speedError = 0.1)).motion)
    }
}
