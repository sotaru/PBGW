package app.pikminbloom.gps.steps

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Immutable windows; prepared payloads and IDs survive uncertain inserts and process death. */
data class StepWriteBatch(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val steps: Long,
    val distanceM: Double,
    val zoneId: String,
    val acceptedSteps: Long? = null,
) {
    val zone: ZoneId get() = ZoneId.of(zoneId)
    val date: LocalDate get() = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()
    val acceptedDistanceM: Double get() = distanceM * ((acceptedSteps ?: steps).toDouble() / steps)
}

data class StepFlushProgress(val sessionId: Long, val accrued: Double, val flushed: Long, val endMs: Long)

/** Engine-thread only. This file must live in noBackupFilesDir: never replay health data on another device. */
class StepWriteOutbox(private val file: File) {
    private var batches = mutableListOf<StepWriteBatch>()
    var progress: StepFlushProgress? = null
        private set
    val pending: List<StepWriteBatch> get() = batches.toList()
    val hasPending: Boolean get() = batches.isNotEmpty()

    init {
        if (file.exists()) {
            val json = JSONObject(file.readText())
            val array = json.getJSONArray("batches")
            batches = (0 until array.length()).map { i ->
                val b = array.getJSONObject(i)
                StepWriteBatch(b.getString("id"), b.getLong("startMs"), b.getLong("endMs"),
                    b.getLong("steps"), b.getDouble("distanceM"), b.getString("zoneId"),
                    if (b.has("acceptedSteps")) b.getLong("acceptedSteps") else null)
            }.toMutableList()
            json.optJSONObject("progress")?.let {
                progress = StepFlushProgress(it.getLong("sessionId"), it.getDouble("accrued"), it.getLong("flushed"), it.getLong("endMs"))
            }
        }
    }

    /** Save the windows and consumed-step watermark in ONE atomic transaction, before touching HC. */
    fun enqueue(windows: List<StepWriteBatch>, nextProgress: StepFlushProgress) {
        val next = batches.toMutableList()
        windows.forEach { window -> if (next.none { it.id == window.id }) next.add(window) }
        save(next, nextProgress)
        batches = next
        progress = nextProgress
    }

    /** A failure leaves the exact payload on disk. The next patrol retries it with the same client ID. */
    suspend fun drain(
        dailyCap: Long,
        readCount: suspend (LocalDate, ZoneId) -> Long?,
        write: suspend (StepWriteBatch) -> Boolean,
    ): List<StepWriteBatch> {
        val completed = mutableListOf<StepWriteBatch>()
        while (batches.isNotEmpty()) {
            var batch = batches.first()
            if (batch.acceptedSteps == null) {
                // Failed reads must NOT be treated as zero, which could bypass the daily cap.
                val existing = readCount(batch.date, batch.zone) ?: break
                batch = batch.copy(acceptedSteps = minOf(batch.steps, (dailyCap - existing).coerceAtLeast(0)))
                val prepared = batches.toMutableList().also { it[0] = batch }
                save(prepared, progress)
                batches = prepared
            }
            if (batch.acceptedSteps!! > 0 && !write(batch)) break
            val remaining = batches.drop(1).toMutableList()
            save(remaining, progress)
            batches = remaining
            completed += batch
        }
        return completed
    }

    private fun save(next: List<StepWriteBatch>, marker: StepFlushProgress?) {
        val json = JSONObject().put("version", 1).put("batches", JSONArray().apply {
            next.forEach { b -> put(JSONObject().put("id", b.id).put("startMs", b.startMs).put("endMs", b.endMs)
                .put("steps", b.steps).put("distanceM", b.distanceM).put("zoneId", b.zoneId)
                .put("acceptedSteps", b.acceptedSteps)) }
        })
        marker?.let { json.put("progress", JSONObject().put("sessionId", it.sessionId).put("accrued", it.accrued)
            .put("flushed", it.flushed).put("endMs", it.endMs)) }
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.outputStream().use { stream ->
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        /** Split at local midnight and distribute by duration, carrying rounding into the last window. */
        fun windows(id: String, start: Instant, end: Instant, steps: Long, distanceM: Double, zone: ZoneId): List<StepWriteBatch> {
            require(end > start && steps >= 0 && distanceM.isFinite() && distanceM >= 0)
            if (steps == 0L) return emptyList()
            val durationMs = Duration.between(start, end).toMillis().coerceAtLeast(1)
            val out = mutableListOf<StepWriteBatch>()
            var cursor = start
            var allocated = 0L
            var distanceAllocated = 0.0
            while (cursor < end) {
                val midnight = cursor.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
                val until = minOf(end, midnight)
                val fraction = Duration.between(start, until).toMillis().toDouble() / durationMs
                val cumulative = if (until == end) steps else (steps * fraction).toLong()
                val count = cumulative - allocated
                val cumulativeDistance = if (until == end) distanceM else distanceM * (cumulative.toDouble() / steps)
                if (count > 0) out += StepWriteBatch("$id-${out.size}", cursor.toEpochMilli(), until.toEpochMilli(),
                    count, cumulativeDistance - distanceAllocated, zone.id)
                allocated = cumulative
                distanceAllocated = cumulativeDistance
                cursor = until
            }
            return out
        }
    }
}
