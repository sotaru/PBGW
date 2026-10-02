package app.pikminbloom.gps

import app.pikminbloom.gps.steps.StepFlushProgress
import app.pikminbloom.gps.steps.StepWriteBatch
import app.pikminbloom.gps.steps.StepWriteOutbox
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class StepWriteOutboxTest {
    @get:Rule val temp = TemporaryFolder()
    private val zone = ZoneId.of("Asia/Taipei")
    private val start = Instant.parse("2026-10-02T12:00:00Z")
    private val end = start.plusSeconds(60)
    private val progress = StepFlushProgress(123, 100.4, 100, end.toEpochMilli())
    private fun file() = File(temp.root, "outbox.json")
    private fun batch(count: Long = 100): List<StepWriteBatch> =
        StepWriteOutbox.windows("session-window", start, end, count, count * 0.7, zone)

    @Test fun commitPersistsStepsAndRecoveryWatermarkTogether() {
        StepWriteOutbox(file()).enqueue(batch(), progress)
        val restored = StepWriteOutbox(file())
        assertEquals(batch(), restored.pending)
        assertEquals(progress, restored.progress)
        assertEquals(100L, restored.progress!!.flushed)
    }

    @Test fun failedFinalWriteSurvivesStopAndNextProcessRetriesExactPayload() = runBlocking {
        val box = StepWriteOutbox(file())
        box.enqueue(batch(), progress)
        assertTrue(box.drain(10_000, { _, _ -> 0 }) { false }.isEmpty())
        val prepared = box.pending.single()
        assertEquals(100L, prepared.acceptedSteps)
        val reopened = StepWriteOutbox(file())
        var retried: StepWriteBatch? = null
        val completed = reopened.drain(10_000, { _, _ -> error("Must not recalculate a prepared payload") }) {
            retried = it; true
        }
        assertEquals(prepared, retried)
        assertEquals(1, completed.size)
        assertFalse(StepWriteOutbox(file()).hasPending)
        assertEquals(progress, StepWriteOutbox(file()).progress)
    }

    @Test fun uncertainSuccessfulInsertDoesNotDoubleWriteOnRetry() = runBlocking {
        val inserted = mutableMapOf<String, Long>()
        val box = StepWriteOutbox(file())
        box.enqueue(batch(), progress)
        box.drain(10_000, { _, _ -> 0 }) { b ->
            inserted.putIfAbsent(b.id, b.acceptedSteps!!)
            false // Simulate a lost response after HC accepted the record.
        }
        StepWriteOutbox(file()).drain(10_000, { _, _ -> inserted.values.sum() }) { b ->
            inserted.putIfAbsent(b.id, b.acceptedSteps!!)
            true
        }
        assertEquals(100L, inserted.values.sum())
        assertEquals(1, inserted.size)
    }

    @Test fun midnightResetsDailyCapWithoutRestartingPatrol() = runBlocking {
        val beforeMidnight = Instant.parse("2026-10-02T15:59:30Z")
        val windows = StepWriteOutbox.windows("midnight", beforeMidnight, beforeMidnight.plusSeconds(60), 120, 84.0, zone)
        assertEquals(listOf(LocalDate.parse("2026-10-02"), LocalDate.parse("2026-10-03")), windows.map { it.date })
        assertEquals(listOf(60L, 60L), windows.map { it.steps })
        val box = StepWriteOutbox(file())
        box.enqueue(windows, progress.copy(flushed = 120))
        val writes = mutableListOf<StepWriteBatch>()
        box.drain(10_000, { day, _ -> if (day == windows.first().date) 10_000 else 0 }) { writes += it; true }
        assertEquals(1, writes.size)
        assertEquals(LocalDate.parse("2026-10-03"), writes.single().date)
        assertEquals(60L, writes.single().acceptedSteps)
        assertFalse(box.hasPending)
    }

    @Test fun dailyCapLimitsStepsAndDistanceTogether() = runBlocking {
        val box = StepWriteOutbox(file())
        box.enqueue(batch(), progress)
        var written: StepWriteBatch? = null
        box.drain(10_000, { _, _ -> 9_990 }) { written = it; true }
        assertEquals(10L, written!!.acceptedSteps)
        assertEquals(7.0, written!!.acceptedDistanceM, 1e-9)
    }

    @Test fun failedReadIsNotZeroAndDoesNotBypassDailyCap() = runBlocking {
        val box = StepWriteOutbox(file())
        box.enqueue(batch(), progress)
        box.drain(10_000, { _, _ -> null }) { error("Must not write on unknown daily total") }
        assertEquals(batch(), StepWriteOutbox(file()).pending)
        var wrote = false
        box.drain(10_000, { _, _ -> 10_000 }) { wrote = true; true }
        assertFalse(wrote)
        assertFalse(box.hasPending)
    }

    @Test fun subsequentBatchesRespectAlreadyWrittenSteps() = runBlocking {
        val box = StepWriteOutbox(file())
        val second = batch().single().copy(id = "second", startMs = end.toEpochMilli(), endMs = end.plusSeconds(60).toEpochMilli())
        box.enqueue(batch() + second, progress)
        var actual = 9_850L
        box.drain(10_000, { _, _ -> actual }) { actual += it.acceptedSteps!!; true }
        assertEquals(10_000L, actual)
        assertFalse(box.hasPending)
    }

    @Test fun splittingPreservesRoundedStepsAndDistance() {
        val from = Instant.parse("2026-10-02T15:59:59Z")
        val windows = StepWriteOutbox.windows("rounding", from, from.plusSeconds(4), 3, 2.1, zone)
        assertEquals(3L, windows.sumOf { it.steps })
        assertEquals(2.1, windows.sumOf { it.distanceM }, 1e-9)
        assertTrue(windows.all { it.steps > 0 && it.endMs > it.startMs })
    }

    @Test fun splittingUsesLocalMidnightAcrossDaylightSavingChange() {
        val dstZone = ZoneId.of("America/New_York")
        val from = LocalDate.parse("2026-03-08").atStartOfDay(dstZone).toInstant()
        val to = LocalDate.parse("2026-03-10").atStartOfDay(dstZone).toInstant()
        val windows = StepWriteOutbox.windows("dst", from, to, 4700, 3290.0, dstZone)
        assertEquals(listOf(2300L, 2400L), windows.map { it.steps })
        assertEquals(4700L, windows.sumOf { it.steps })
    }

    @Test fun repeatedEnqueueKeepsOnlyOneCopyOfWindow() {
        val box = StepWriteOutbox(file())
        box.enqueue(batch(), progress)
        box.enqueue(batch(), progress)
        assertEquals(1, StepWriteOutbox(file()).pending.size)
    }

    @Test fun failedDiskCommitDoesNotConsumeStepsInMemory() {
        val parentIsFile = temp.newFile("not-a-directory")
        val box = StepWriteOutbox(File(parentIsFile, "outbox.json"))
        try { box.enqueue(batch(), progress); fail("Must fail to persist") } catch (_: java.io.IOException) {}
        assertFalse(box.hasPending)
        assertNull(box.progress)
    }
}
