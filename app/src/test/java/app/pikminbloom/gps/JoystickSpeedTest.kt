package app.pikminbloom.gps

import app.pikminbloom.gps.data.JoystickSpeeds
import app.pikminbloom.gps.data.PatrolConfig
import app.pikminbloom.gps.data.TravelMode
import app.pikminbloom.gps.geo.LatLng
import app.pikminbloom.gps.route.PatrolPlan
import app.pikminbloom.gps.route.RouteSegment
import app.pikminbloom.gps.route.SegmentKind
import app.pikminbloom.gps.sim.WalkSimulator
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class JoystickSpeedTest {
    private val config = PatrolConfig(speedMps = 4.7 / 3.6, speedJitterPct = 0.0)
    private val origin = LatLng(25.033, 121.5654)
    private fun simulator() = WalkSimulator(config, Random(1)).also { it.relocate(origin) }

    @Test fun choicesIncludeConfiguredWalkingAndAllDistinctSpeeds() {
        assertEquals(null, JoystickSpeeds.choices.first())
        assertEquals(7, JoystickSpeeds.choices.size)
        assertTrue(JoystickSpeeds.choices.contains(TravelMode.BRISK))
        assertTrue(JoystickSpeeds.choices.contains(TravelMode.RUN))
        assertFalse(JoystickSpeeds.choices.contains(TravelMode.WALK))
        assertEquals(JoystickSpeeds.choices.size, JoystickSpeeds.choices.distinct().size)
    }

    @Test fun configuredWalkingDoesNotInheritAnAutomaticVehicleSpeed() {
        val sim = simulator().also { it.travelOverride = TravelMode.CAR }
        val sample = sim.advanceManual(1.0, 90.0, 1.0, speedMode = null)
        assertEquals(config.speedMps, sample.distanceDeltaM, 1e-9)
        assertTrue(sample.countsSteps)
        assertEquals(config.speedMps, sample.stepDistanceDeltaM, 1e-9)
        assertEquals(TravelMode.CAR, sim.travelOverride)
    }

    @Test fun eachExplicitSpeedUsesItsOwnSpeedAndStepRule() {
        for (mode in JoystickSpeeds.choices.filterNotNull()) {
            val sim = simulator()
            val sample = sim.advanceManual(0.1, 90.0, 0.5, speedMode = mode)
            assertEquals(mode.speedMps * 0.1 * 0.5, sample.distanceDeltaM, 1e-9)
            assertEquals(mode.countsSteps, sample.countsSteps)
            assertEquals(if (mode.countsSteps) sample.distanceDeltaM else 0.0, sample.stepDistanceDeltaM, 1e-9)
            assertNull(sim.travelOverride)
        }
    }

    @Test fun leavingJoystickDoesNotChangeAutomaticPatrolSpeed() {
        val sim = simulator()
        sim.advanceManual(0.1, 90.0, 1.0, speedMode = TravelMode.PLANE)
        val here = sim.exactCurrentPosition()
        sim.load(PatrolPlan.of(listOf(RouteSegment(here, LatLng(25.034, 121.5654), null, SegmentKind.TRAVEL))))
        val automatic = sim.advance(1.0)
        assertEquals(config.speedMps, automatic.distanceDeltaM, 1e-9)
        assertTrue(automatic.countsSteps)
    }

    @Test fun changingManualSpeedKeepsTheOriginalAutomaticOverride() {
        val sim = simulator().also { it.travelOverride = TravelMode.HIGHWAY }
        assertEquals(TravelMode.BRISK.speedMps, sim.advanceManual(1.0, 90.0, 1.0, TravelMode.BRISK).distanceDeltaM, 1e-9)
        assertEquals(TravelMode.CAR.speedMps, sim.advanceManual(1.0, 90.0, 1.0, TravelMode.CAR).distanceDeltaM, 1e-9)
        assertEquals(config.speedMps, sim.advanceManual(1.0, 90.0, 1.0, null).distanceDeltaM, 1e-9)
        assertEquals(TravelMode.HIGHWAY, sim.travelOverride)
    }
}
