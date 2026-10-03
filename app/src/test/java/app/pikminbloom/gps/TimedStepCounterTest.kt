package app.pikminbloom.gps

import app.pikminbloom.gps.steps.TimedStepCounter
import app.pikminbloom.gps.geo.GpsMotion
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class TimedStepCounterTest {
    @Test fun accumulatesFractionalTicksWithoutLosingSteps() {
        val counter = TimedStepCounter(1000, 100)
        repeat(600) { counter.advance(100) }
        assertEquals(100L, counter.generated)
    }

    @Test fun pausesDoNotAccrueOrCatchUpOnResume() {
        val counter = TimedStepCounter(1000, 60)
        counter.advance(10_000)
        counter.paused = true
        counter.advance(600_000)
        assertEquals(10L, counter.generated)
        counter.paused = false
        counter.advance(1000)
        assertEquals(11L, counter.generated)
    }

    @Test fun neverExceedsTargetEvenWithALargeElapsedInterval() {
        val counter = TimedStepCounter(7, 180)
        assertEquals(7L, counter.advance(Long.MAX_VALUE))
        assertTrue(counter.complete)
        assertEquals(0L, counter.advance(60_000))
    }

    @Test fun slowRateWaitsForTheFullStepInterval() {
        val counter = TimedStepCounter(1, 1)
        assertEquals(0L, counter.advance(59_999))
        assertEquals(1L, counter.advance(1))
    }

    @Test fun rejectsInvalidInputs() {
        for ((target, rate) in listOf(0L to 100, 200_001L to 100, 100L to 0, 100L to 181)) {
            assertThrows(IllegalArgumentException::class.java) { TimedStepCounter(target, rate) }
        }
        assertThrows(IllegalArgumentException::class.java) { TimedStepCounter(100, 100).advance(-1) }
        for (jitter in listOf(-1.0, 31.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { TimedStepCounter(100, 100, jitter) }
        }
        assertThrows(IllegalArgumentException::class.java) { TimedStepCounter(100, 100, maxCadenceSpm = -1) }
    }

    @Test fun jitterStaysWithinTheConfiguredRangeAndChangesOverTime() {
        val counter = TimedStepCounter(1000, 100, 10.0, random = Random(7))
        val rates = mutableSetOf<Double>()
        repeat(60) {
            counter.advance(1000)
            assertTrue(counter.currentRate in 90.0..110.0)
            rates += counter.currentRate
        }
        assertTrue(rates.size >= 6)
        assertTrue(counter.generated in 90..110)
    }

    @Test fun tickSizeDoesNotChangeJitterWindows() {
        val largeTick = TimedStepCounter(1000, 100, 30.0, random = Random(17))
        val smallTicks = TimedStepCounter(1000, 100, 30.0, random = Random(17))
        largeTick.advance(60_123)
        repeat(60) { smallTicks.advance(1000) }
        smallTicks.advance(123)
        assertEquals(largeTick.generated, smallTicks.generated)
        assertEquals(largeTick.currentRate, smallTicks.currentRate, 0.0)
    }

    @Test fun pausesFreezeJitterAndDoNotAccrueSteps() {
        val counter = TimedStepCounter(1000, 100, 10.0, random = Random(9))
        val control = TimedStepCounter(1000, 100, 10.0, random = Random(9))
        counter.advance(10_000)
        control.advance(10_000)
        counter.paused = true
        counter.advance(600_000)
        assertEquals(control.generated, counter.generated)
        assertEquals(control.currentRate, counter.currentRate, 0.0)
        counter.paused = false
        counter.advance(30_000)
        control.advance(30_000)
        assertEquals(control.generated, counter.generated)
        assertEquals(control.currentRate, counter.currentRate, 0.0)
    }

    @Test fun jitterHonorsTheCadenceCapAndSettingsCanChangeLive() {
        val counter = TimedStepCounter(1000, 100, 30.0, 100, Random(2))
        counter.updateRateVariation(30.0, 100, GpsMotion.MOVING)
        repeat(60) {
            counter.advance(1000)
            assertTrue(counter.currentRate <= 100.0)
        }
        assertTrue(counter.generated <= 100)
        counter.updateRateVariation(0.0, 50)
        val before = counter.generated
        counter.advance(60_000)
        assertEquals(50L, counter.generated - before)
        assertEquals(50.0, counter.currentRate, 0.0)
    }

    @Test fun biasedJitterStillFinishesExactlyAtTheTarget() {
        for (motion in GpsMotion.entries) {
            val counter = TimedStepCounter(7, 180, 30.0, random = Random(4))
            counter.updateRateVariation(30.0, 0, motion)
            assertEquals(7L, counter.advance(Long.MAX_VALUE))
            assertTrue(counter.complete)
            assertEquals(0L, counter.advance(1000))
        }
    }

    @Test fun movingFavorsMoreStepsAndStationaryFavorsFewerWithinTheSameRange() {
        fun rateSamples(motion: GpsMotion): List<Double> {
            val counter = TimedStepCounter(200_000, 100, 10.0, random = Random(42))
            counter.updateRateVariation(10.0, 0, motion)
            return List(2000) {
                counter.advance(8001) // Every sample crosses at least one rate window.
                counter.currentRate.also { assertTrue(it in 90.0..110.0) }
            }
        }
        val moving = rateSamples(GpsMotion.MOVING)
        val stationary = rateSamples(GpsMotion.STATIONARY)
        val unknown = rateSamples(GpsMotion.UNKNOWN)
        assertTrue(moving.count { it > 100 } > 1300)
        assertTrue(stationary.count { it < 100 } > 1300)
        assertTrue(moving.average() > unknown.average() + 2)
        assertTrue(stationary.average() < unknown.average() - 2)
    }

    @Test fun zeroJitterRemainsFixedRegardlessOfMovement() {
        val counter = TimedStepCounter(1000, 100)
        for (motion in GpsMotion.entries) {
            counter.updateRateVariation(0.0, 0, motion)
            val before = counter.generated
            counter.advance(60_000)
            assertEquals(100L, counter.generated - before)
            assertEquals(100.0, counter.currentRate, 0.0)
        }
    }
}
