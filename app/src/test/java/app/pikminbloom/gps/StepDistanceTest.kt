package app.pikminbloom.gps

import app.pikminbloom.gps.data.PatrolConfig
import app.pikminbloom.gps.data.TravelMode
import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.geo.LatLng
import app.pikminbloom.gps.route.*
import app.pikminbloom.gps.sim.WalkSimulator
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class StepDistanceTest {
    private val config = PatrolConfig(speedMps = 1.3, speedJitterPct = 0.0)
    private val origin = LatLng(25.0, 121.0)

    @Test fun vehicleToWalkingTickCountsOnlyWalkingPortion() {
        val split = GeoMath.offsetMeters(origin, 24.0, 0.0)
        val drive = RouteSegment(origin, split, null, SegmentKind.TRAVEL, travelMode = TravelMode.HIGHWAY)
        val walk = RouteSegment(split, GeoMath.offsetMeters(split, 100.0, 0.0), null, SegmentKind.TRAVEL)
        val sim = WalkSimulator(config, Random(1)).also { it.load(PatrolPlan.of(listOf(drive, walk))) }
        val s = sim.advance(1.0)
        val expected = (1.0 - drive.lengthM / TravelMode.HIGHWAY.speedMps) * config.speedMps
        assertEquals(24.052, s.distanceDeltaM, 0.001)
        assertEquals(expected, s.stepDistanceDeltaM, 1e-8)
        assertEquals(0.0, sim.current().stepDistanceDeltaM, 0.0)
        assertEquals(1.3, sim.advance(1.0).stepDistanceDeltaM, 1e-8)
    }

    @Test fun walkingToVehicleTickDoesNotLoseWalkingPortion() {
        val split = GeoMath.offsetMeters(origin, 0.65, 0.0)
        val walk = RouteSegment(origin, split, null, SegmentKind.TRAVEL)
        val drive = RouteSegment(split, GeoMath.offsetMeters(split, 100.0, 0.0), null, SegmentKind.TRAVEL, travelMode = TravelMode.HIGHWAY)
        val sim = WalkSimulator(config, Random(2)).also { it.load(PatrolPlan.of(listOf(walk, drive))) }
        val s = sim.advance(1.0)
        assertFalse(s.countsSteps) // Ending leg is a vehicle, but the tick still contains walking.
        assertEquals(walk.lengthM, s.stepDistanceDeltaM, 1e-8)
        assertEquals(0.0, sim.advance(1.0).stepDistanceDeltaM, 0.0)
    }

    @Test fun manualMovementAndRelocationResetStepDistanceCorrectly() {
        val sim = WalkSimulator(config, Random(3))
        sim.relocate(origin)
        assertEquals(1.3, sim.advanceManual(1.0, 0.0, 1.0).stepDistanceDeltaM, 1e-8)
        sim.travelOverride = TravelMode.CAR
        assertEquals(0.0, sim.advanceManual(1.0, 0.0, 1.0).stepDistanceDeltaM, 0.0)
        sim.relocate(origin)
        assertEquals(0.0, sim.advance(1.0).stepDistanceDeltaM, 0.0)
    }
}
