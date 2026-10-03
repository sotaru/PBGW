package app.pikminbloom.gps

import app.pikminbloom.gps.steps.StepFlushProgress
import app.pikminbloom.gps.steps.StepWriteOutbox
import app.pikminbloom.gps.steps.TimedStepCounter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.ZoneId

class TimedStepOutboxTest {
    @get:Rule val temp = TemporaryFolder()
    private val zone = ZoneId.of("Asia/Taipei")
    private val start = Instant.parse("2026-10-03T04:00:00Z")

    @Test fun finalPartialMinuteAndRetryWriteExactlyTheTargetWithoutDistance() = runBlocking {
        val counter = TimedStepCounter(125, 100)
        val boxFile = File(temp.root, "steps.json")
        val box = StepWriteOutbox(boxFile)
        counter.advance(60_000)
        box.enqueue(StepWriteOutbox.windows("real-1-first", start, start.plusSeconds(60), counter.generated, 0.0, zone),
            StepFlushProgress(1, counter.generated.toDouble(), counter.generated, start.plusSeconds(60).toEpochMilli()))
        val inserted = mutableMapOf<String, Long>()
        box.drain(50_000, { _, _ -> inserted.values.sum() }) { inserted[it.id] = it.acceptedSteps!!; false }
        counter.advance(15_000)
        box.enqueue(StepWriteOutbox.windows("real-1-last", start.plusSeconds(60), start.plusSeconds(75), 25, 0.0, zone),
            StepFlushProgress(1, 125.0, 125, start.plusSeconds(75).toEpochMilli()))
        StepWriteOutbox(boxFile).drain(50_000, { _, _ -> inserted.values.sum() }) {
            assertEquals(0.0, it.acceptedDistanceM, 0.0)
            inserted[it.id] = it.acceptedSteps!!
            true
        }
        assertEquals(125L, inserted.values.sum())
        assertEquals(2, inserted.size)
        assertFalse(StepWriteOutbox(boxFile).hasPending)
    }

    @Test fun existingPatrolAndTimedStepsShareTheSameDailyBudget() = runBlocking {
        val box = StepWriteOutbox(File(temp.root, "steps.json"))
        val old = StepWriteOutbox.windows("patrol", start, start.plusSeconds(60), 80, 56.0, zone)
        val timed = StepWriteOutbox.windows("real-2", start.plusSeconds(60), start.plusSeconds(120), 100, 0.0, zone)
        box.enqueue(old + timed, StepFlushProgress(2, 100.0, 100, start.plusSeconds(120).toEpochMilli()))
        var total = 0L
        val completed = box.drain(100, { _, _ -> total }) { total += it.acceptedSteps!!; true }
        assertEquals(100L, total)
        assertEquals(20L, completed.last().acceptedSteps)
        assertTrue(completed.last().acceptedSteps!! < completed.last().steps)
    }
}
