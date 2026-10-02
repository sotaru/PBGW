package app.pikminbloom.gps.steps

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Length
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Writes the simulated walk into Health Connect as [StepsRecord] + [DistanceRecord] so that
 * Pikmin Bloom (set to "Health Connect" in 設定 → 隱私權&步數 → 步數) can read it.
 *
 * Records are stamped `autoRecorded` from a `TYPE_PHONE` device so they look like the handset's own
 * pedometer; manual entries are known to be discarded by Niantic in Google Fit mode.
 */
class StepInjector(context: Context) {

    private val app = context.applicationContext
    private val packageName = app.packageName

    val sdkStatus: Int get() = HealthConnectClient.getSdkStatus(app)
    val isAvailable: Boolean get() = sdkStatus == HealthConnectClient.SDK_AVAILABLE
    val needsProviderUpdate: Boolean get() = sdkStatus == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED

    val requiredPermissions: Set<String> = setOf(
        HealthPermission.getWritePermission(StepsRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
    )

    private val client: HealthConnectClient? by lazy {
        if (isAvailable) runCatching { HealthConnectClient.getOrCreate(app) }
            .onFailure { Log.w(TAG, "HealthConnectClient.getOrCreate failed", it) }
            .getOrNull()
        else null
    }

    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    suspend fun grantedPermissions(): Set<String> = try {
        client?.permissionController?.getGrantedPermissions() ?: emptySet()
    } catch (t: Throwable) {
        Log.w(TAG, "getGrantedPermissions failed", t); emptySet()
    }

    suspend fun hasPermissions(): Boolean = grantedPermissions().containsAll(requiredPermissions)

    /**
     * Writes [steps] steps / [distanceM] metres spread over [start, end]. Long spans are split into
     * chunks of at most [MAX_CHUNK] so every record stays plausible. Never throws; returns false on
     * failure (missing permission, rate limit, HC unavailable...).
     */
    suspend fun write(
        start: Instant, end: Instant, steps: Long, distanceM: Double,
        recordId: String? = null, zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        val hc = client ?: run { Log.w(TAG, "write: Health Connect unavailable"); return false }
        if (steps <= 0) return true
        val now = Instant.now()
        // Outbox retries must not change the immutable payload when the wall clock moves backwards.
        if (recordId != null && (end <= start || end > now)) return false
        val safeEnd = if (end.isAfter(now)) now else end
        var safeStart = if (start.isBefore(safeEnd)) start else safeEnd.minusSeconds(1)
        if (safeStart.isAfter(now)) safeStart = now.minusSeconds(1)

        val total = Duration.between(safeStart, safeEnd)
        val chunkCount = ((total.toMillis() + MAX_CHUNK.toMillis() - 1) / MAX_CHUNK.toMillis()).toInt().coerceAtLeast(1)
        val device = Device(manufacturer = Build.MANUFACTURER, model = Build.MODEL, type = Device.TYPE_PHONE)

        val records = ArrayList<androidx.health.connect.client.records.Record>(chunkCount * 2)
        var stepsLeft = steps
        var distLeft = distanceM
        var cursor = safeStart
        for (i in 0 until chunkCount) {
            val chunkEnd = if (i == chunkCount - 1) safeEnd else cursor.plus(MAX_CHUNK)
            val fraction = Duration.between(cursor, chunkEnd).toMillis().toDouble() / total.toMillis().coerceAtLeast(1)
            val chunkSteps = if (i == chunkCount - 1) stepsLeft else (steps * fraction).toLong()
            val chunkDist = if (i == chunkCount - 1) distLeft else distanceM * fraction
            stepsLeft -= chunkSteps
            distLeft -= chunkDist
            val startOff = zone.rules.getOffset(cursor)
            val endOff = zone.rules.getOffset(chunkEnd)
            if (chunkSteps > 0) {
                records.add(
                    StepsRecord(
                        startTime = cursor,
                        startZoneOffset = startOff,
                        endTime = chunkEnd,
                        endZoneOffset = endOff,
                        count = chunkSteps.coerceIn(1, 1_000_000),
                        metadata = Metadata.autoRecorded(
                            device = device,
                            clientRecordId = "$CLIENT_PREFIX-steps-${recordId ?: cursor.toEpochMilli()}-$i",
                        ),
                    )
                )
            }
            if (chunkDist > 0.5) {
                records.add(
                    DistanceRecord(
                        startTime = cursor,
                        startZoneOffset = startOff,
                        endTime = chunkEnd,
                        endZoneOffset = endOff,
                        distance = Length.meters(chunkDist),
                        metadata = Metadata.autoRecorded(
                            device = device,
                            clientRecordId = "$CLIENT_PREFIX-dist-${recordId ?: cursor.toEpochMilli()}-$i",
                        ),
                    )
                )
            }
            cursor = chunkEnd
        }
        if (records.isEmpty()) return true
        return try {
            hc.insertRecords(records)
            Log.i(TAG, "HC write ok: $steps steps / ${"%.0f".format(distanceM)} m over ${total.seconds}s in ${records.size} records")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "HC insertRecords failed: ${t.message}", t)
            false
        }
    }

    /** Steps from every source since local midnight (what the user sees in Health Connect). */
    suspend fun stepsToday(): Long {
        val hc = client ?: return 0
        return try {
            val res = hc.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(localMidnight(), Instant.now()),
                )
            )
            res[StepsRecord.COUNT_TOTAL] ?: 0L
        } catch (t: Throwable) {
            Log.w(TAG, "aggregate failed: ${t.message}"); 0
        }
    }

    /** Steps this app itself wrote since local midnight. */
    suspend fun stepsWrittenByUsToday(): Long = stepsWrittenByUsOn(LocalDate.now(), ZoneId.systemDefault()) ?: 0L

    /** Nullable on read failure; callers enforcing a cap must not confuse failure with zero. */
    suspend fun stepsWrittenByUsOn(date: LocalDate, zone: ZoneId): Long? {
        val hc = client ?: return null
        val start = date.atStartOfDay(zone).toInstant()
        val end = minOf(date.plusDays(1).atStartOfDay(zone).toInstant(), Instant.now())
        if (end <= start) return 0L
        var total = 0L
        var token: String? = null
        try {
            do {
                val resp = hc.readRecords(
                    ReadRecordsRequest(
                        recordType = StepsRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(start, end),
                        dataOriginFilter = setOf(DataOrigin(packageName)),
                        pageSize = 1000,
                        pageToken = token,
                    )
                )
                // Old versions could write a record spanning midnight. Only count the part in this day.
                total += resp.records.sumOf { record ->
                    val overlap = Duration.between(maxOf(record.startTime, start), minOf(record.endTime, end)).toMillis().coerceAtLeast(0)
                    val duration = Duration.between(record.startTime, record.endTime).toMillis().coerceAtLeast(1)
                    (record.count * (overlap.toDouble() / duration)).toLong()
                }
                token = resp.pageToken
            } while (token != null)
        } catch (t: Throwable) {
            Log.w(TAG, "readRecords failed: ${t.message}")
            return null
        }
        return total
    }

    /**
     * Health Connect only counts an app toward the daily totals once the user has added it under
     * 管理資料 → 資料來源與優先順序. Until then our records exist but are invisible to Pikmin Bloom.
     *
     * @return null when we cannot tell (no data written yet, or the aggregate call failed),
     *         true when our steps are included in the total, false when they are being ignored.
     */
    suspend fun stepsAreCountedInTotals(): Boolean? {
        val ours = stepsWrittenByUsToday()
        if (ours <= 0) return null
        val total = stepsToday()
        if (total <= 0) return false
        return total >= ours
    }

    /** Deletes every steps/distance record this app wrote today (only our own records are affected). */
    suspend fun deleteOurRecordsToday(): Boolean {
        val hc = client ?: return false
        val range = TimeRangeFilter.between(localMidnight(), Instant.now())
        return try {
            hc.deleteRecords(StepsRecord::class, range)
            hc.deleteRecords(DistanceRecord::class, range)
            Log.i(TAG, "deleted our HC records for today")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "deleteRecords failed: ${t.message}"); false
        }
    }

    /** Opens Health Connect's "manage data" screen, where 資料來源與優先順序 lives. */
    fun openHealthConnectDataSources(context: Context) {
        val intents = listOf(
            Intent("android.health.connect.action.MANAGE_HEALTH_DATA"),
            Intent("android.health.connect.action.HEALTH_HOME_SETTINGS"),
            Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS),
        )
        for (i in intents) {
            try {
                context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Throwable) {
            }
        }
        openHealthConnectSettings(context)
    }

    fun openHealthConnectSettings(context: Context) {
        val intents = listOf(
            Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS),
            Intent("android.health.connect.action.HEALTH_HOME_SETTINGS"),
            Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"),
        )
        for (i in intents) {
            try {
                context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Throwable) {
            }
        }
        Log.w(TAG, "no Health Connect settings activity found")
    }

    private fun localMidnight(): Instant =
        LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant()

    companion object {
        const val TAG = "PikminGPS"
        private const val CLIENT_PREFIX = "pbgps"
        private val MAX_CHUNK: Duration = Duration.ofMinutes(50)
    }
}
