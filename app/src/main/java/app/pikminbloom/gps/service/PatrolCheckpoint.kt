package app.pikminbloom.gps.service

import android.content.Context
import android.util.Log
import app.pikminbloom.gps.data.PatrolPhase
import app.pikminbloom.gps.data.PatrolMode
import app.pikminbloom.gps.geo.LatLng
import org.json.JSONObject
import java.io.File

/**
 * Where a patrol was a few seconds ago, written continuously so that a process death (thermal
 * kill, low memory, a crash) can be resumed from the point it stopped.
 *
 * Why this exists: when the process dies the test providers stay registered and the game keeps
 * showing the last mocked position. A plain restart would take a fresh real fix as home and walk
 * the route from the beginning, which the game sees as a teleport from the crash point back to the
 * real position and then off along the route. Resuming from the checkpoint instead re-pushes the
 * very same position first, so nothing jumps.
 *
 * Written atomically (temp file + rename) at most every few seconds on the engine thread; deleted
 * on every clean exit.
 */
data class PatrolCheckpoint(
    val savedAtMs: Long,
    val startedAtMs: Long,
    val home: LatLng?,
    val position: LatLng,
    val routeId: String,
    val lap: Int,
    /** Index of the waypoint being walked to or orbited when the checkpoint was taken. */
    val targetWaypointIndex: Int,
    val phase: PatrolPhase,
    val distanceWalkedM: Double,
    val stepsAccrued: Double,
    val stepsFlushed: Long,
    val flushWindowStartMs: Long,
    val distanceSinceFlush: Double,
    val stepsWrittenToday: Long,
    val lapsCompleted: Int,
    val mode: PatrolMode = PatrolMode.WAYPOINTS,
    val spiralCenter: LatLng? = null,
    val spiralAngleRad: Double = 0.0,
    val spiralSpacingM: Double = 40.0,
    val spiralLineWidthM: Double = 40.0,
) {
    val ageMs: Long get() = System.currentTimeMillis() - savedAtMs

    fun toJson(): String = JSONObject()
        .put("version", 2)
        .put("savedAtMs", savedAtMs)
        .put("startedAtMs", startedAtMs)
        .put("homeLat", home?.lat).put("homeLon", home?.lon)
        .put("posLat", position.lat).put("posLon", position.lon)
        .put("routeId", routeId)
        .put("lap", lap)
        .put("targetWaypointIndex", targetWaypointIndex)
        .put("phase", phase.name)
        .put("distanceWalkedM", distanceWalkedM)
        .put("stepsAccrued", stepsAccrued)
        .put("stepsFlushed", stepsFlushed)
        .put("flushWindowStartMs", flushWindowStartMs)
        .put("distanceSinceFlush", distanceSinceFlush)
        .put("stepsWrittenToday", stepsWrittenToday)
        .put("lapsCompleted", lapsCompleted)
        .put("mode", mode.name)
        .put("spiralLat", spiralCenter?.lat).put("spiralLon", spiralCenter?.lon)
        .put("spiralAngleRad", spiralAngleRad)
        .put("spiralSpacingM", spiralSpacingM)
        .put("spiralLineWidthM", spiralLineWidthM)
        .toString()

    companion object {
        private const val TAG = "PikminGPS"
        private const val FILE_NAME = "patrol_checkpoint.json"

        /** A checkpoint older than this is not worth resuming; the world has moved on. */
        const val MAX_RESUME_AGE_MS = 12L * 60 * 60 * 1000

        fun fromJson(text: String): PatrolCheckpoint? = runCatching {
            val o = JSONObject(text)
            PatrolCheckpoint(
                savedAtMs = o.getLong("savedAtMs"),
                startedAtMs = o.optLong("startedAtMs", o.getLong("savedAtMs")),
                home = if (o.has("homeLat") && o.has("homeLon")) LatLng(o.getDouble("homeLat"), o.getDouble("homeLon")) else null,
                position = LatLng(o.getDouble("posLat"), o.getDouble("posLon")),
                routeId = o.optString("routeId"),
                lap = o.optInt("lap", 0),
                targetWaypointIndex = o.optInt("targetWaypointIndex", 0),
                phase = runCatching { PatrolPhase.valueOf(o.optString("phase")) }.getOrDefault(PatrolPhase.WALKING),
                distanceWalkedM = o.optDouble("distanceWalkedM", 0.0),
                stepsAccrued = o.optDouble("stepsAccrued", 0.0),
                stepsFlushed = o.optLong("stepsFlushed", 0L),
                flushWindowStartMs = o.optLong("flushWindowStartMs", o.getLong("savedAtMs")),
                distanceSinceFlush = o.optDouble("distanceSinceFlush", 0.0),
                stepsWrittenToday = o.optLong("stepsWrittenToday", 0L),
                lapsCompleted = o.optInt("lapsCompleted", 0),
                mode = runCatching { PatrolMode.valueOf(o.optString("mode")) }.getOrDefault(PatrolMode.WAYPOINTS),
                spiralCenter = if (o.has("spiralLat") && o.has("spiralLon")) LatLng(o.getDouble("spiralLat"), o.getDouble("spiralLon")) else null,
                spiralAngleRad = o.optDouble("spiralAngleRad", 0.0),
                spiralSpacingM = o.optDouble("spiralSpacingM", 40.0),
                spiralLineWidthM = o.optDouble("spiralLineWidthM", 40.0),
            )
        }.getOrNull()

        private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

        fun load(context: Context): PatrolCheckpoint? {
            val f = file(context)
            if (!f.exists()) return null
            return runCatching { fromJson(f.readText()) }
                .onFailure { Log.w(TAG, "checkpoint unreadable", it) }
                .getOrNull()
        }

        /** Atomic: a kill halfway through a write must not leave a truncated file behind. */
        fun save(context: Context, cp: PatrolCheckpoint) {
            val f = file(context)
            val tmp = File(f.parentFile, "$FILE_NAME.tmp")
            runCatching {
                tmp.writeText(cp.toJson())
                if (!tmp.renameTo(f)) {
                    f.delete()
                    tmp.renameTo(f)
                }
            }.onFailure { Log.w(TAG, "checkpoint save failed", it) }
        }

        fun clear(context: Context) {
            runCatching { file(context).delete(); File(file(context).parentFile, "$FILE_NAME.tmp").delete() }
        }

        /** A checkpoint exists and is recent enough that resuming makes sense. */
        fun resumable(context: Context): PatrolCheckpoint? =
            load(context)?.takeIf { it.ageMs in 0..MAX_RESUME_AGE_MS }
    }
}
