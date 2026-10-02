package app.pikminbloom.gps

import app.pikminbloom.gps.data.PatrolConfig
import app.pikminbloom.gps.data.TravelMode
import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.geo.LatLng
import app.pikminbloom.gps.route.SpiralRoute
import app.pikminbloom.gps.sim.ManualInputBuffer
import app.pikminbloom.gps.sim.WalkSimulator
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ManualInputBufferTest {
    private val origin = LatLng(25.033, 121.5654)
    private val config = PatrolConfig(speedMps = 1.3, speedJitterPct = 0.0)

    @Test fun shortGestureReleasedBetweenTicksIsPreserved() {
        val input = ManualInputBuffer()
        input.start(0, 0.0, 0.0)
        input.update(100, 90.0, 1.0)
        input.update(180, 0.0, 0.0)
        val slices = input.consume(1000)
        assertEquals(listOf(100L, 80L, 820L), slices.map { it.durationMs })
        assertEquals(80L, slices.filter { it.magnitude > 0.0 }.sumOf { it.durationMs })
    }

    @Test fun consumptionNeverReplaysMovement() {
        val input = ManualInputBuffer()
        input.start(0, 90.0, 1.0)
        assertEquals(100L, input.consume(100).sumOf { it.durationMs })
        assertTrue(input.consume(100).isEmpty())
        assertEquals(50L, input.consume(150).sumOf { it.durationMs })
    }

    @Test fun releaseStopsWithoutAFullTickOfExtraWalking() {
        val input = ManualInputBuffer()
        input.start(0, 90.0, 1.0)
        input.update(80, 0.0, 0.0)
        val sim = WalkSimulator(config, Random(1)).also { it.relocate(origin) }
        val samples = input.consume(1000).map { sim.advanceManual(it.durationMs / 1000.0, it.bearingDeg, it.magnitude) }
        assertEquals(1.3 * 0.08, samples.sumOf { it.distanceDeltaM }, 1e-9)
        assertEquals(1.3 * 0.08, samples.sumOf { it.stepDistanceDeltaM }, 1e-9)
        assertEquals(0.0, samples.last().speedMps, 0.0)
        assertEquals(0.0, input.consume(2000).sumOf { it.durationMs * it.magnitude }, 0.0)
    }

    @Test fun directionsAndMagnitudesKeepTheirOwnDurations() {
        val input = ManualInputBuffer()
        input.start(0, 0.0, 1.0)
        input.update(200, 90.0, 0.5)
        input.update(400, 0.0, 0.0)
        val slices = input.consume(1000)
        assertEquals(listOf(0.0, 90.0, 0.0), slices.map { it.bearingDeg })
        assertEquals(listOf(1.0, 0.5, 0.0), slices.map { it.magnitude })
        assertEquals(300.0, slices.sumOf { it.durationMs * it.magnitude }, 0.0)
    }

    @Test fun pausedInputIsNotReplayedOnResume() {
        val input = ManualInputBuffer()
        input.start(0, 90.0, 1.0)
        input.consume(100)
        input.stop()
        input.update(200, 90.0, 1.0)
        input.update(2000, 0.0, 0.0)
        assertTrue(input.consume(5000).isEmpty())
        input.start(5000, 0.0, 0.0)
        assertEquals(0.0, input.consume(5100).sumOf { it.durationMs * it.magnitude }, 0.0)
    }

    @Test fun relocationAndNewSessionDiscardOldMovement() {
        val input = ManualInputBuffer()
        input.start(0, 90.0, 1.0)
        input.update(80, 0.0, 0.0)
        input.stop()
        input.start(1000, 0.0, 0.0)
        assertEquals(0.0, input.consume(1100).sumOf { it.durationMs * it.magnitude }, 0.0)
    }

    @Test fun stalledEngineHasBoundedCatchUpAndMemory() {
        val input = ManualInputBuffer()
        input.start(0, 90.0, 1.0)
        assertEquals(3000L, input.consume(60_000).sumOf { it.durationMs })
        repeat(10_000) { input.update(60_001L + it, (it % 360).toDouble(), 1.0) }
        val pending = input.consume(70_000)
        assertTrue(pending.size <= 512)
        assertTrue(pending.sumOf { it.durationMs } <= 3000)
    }

    @Test fun invalidAndOutOfOrderTimesDoNotProduceInvalidMovement() {
        val input = ManualInputBuffer()
        input.start(100, Double.NaN, 1.0)
        assertEquals(0.0, input.consume(200).single().magnitude, 0.0)
        input.update(200, 90.0, Double.POSITIVE_INFINITY)
        assertEquals(0.0, input.consume(300).single().magnitude, 0.0)
        input.update(250, 90.0, 1.0)
        assertTrue(input.consume(250).isEmpty())
        assertEquals(100L, input.consume(400).single().durationMs)
    }

    @Test fun shortGesturesWorkAfterTeleportAndInSpiralPatrol() {
        for (spiral in listOf(false, true)) {
            val sim = WalkSimulator(config, Random(1))
            if (spiral) sim.load(SpiralRoute(origin).nextPlan()) else sim.relocate(origin)
            val before = sim.exactCurrentPosition()
            val input = ManualInputBuffer()
            input.start(0, 90.0, 1.0)
            input.update(80, 0.0, 0.0)
            input.consume(100).forEach { sim.advanceManual(it.durationMs / 1000.0, it.bearingDeg, it.magnitude) }
            assertEquals(1.3 * 0.08, GeoMath.distanceM(before, sim.exactCurrentPosition()), 1e-6)
        }
    }

    @Test fun shortVehicleGesturesNeverProduceWalkingSteps() {
        val sim = WalkSimulator(config, Random(1)).also { it.relocate(origin); it.travelOverride = TravelMode.CAR }
        val input = ManualInputBuffer()
        input.start(0, 90.0, 1.0)
        input.update(80, 0.0, 0.0)
        val samples = input.consume(100).map { sim.advanceManual(it.durationMs / 1000.0, it.bearingDeg, it.magnitude) }
        assertEquals(TravelMode.CAR.speedMps * 0.08, samples.sumOf { it.distanceDeltaM }, 1e-9)
        assertEquals(0.0, samples.sumOf { it.stepDistanceDeltaM }, 0.0)
    }
}
