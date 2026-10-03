package app.pikminbloom.gps.mock

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import app.pikminbloom.gps.data.PatrolConfig
import app.pikminbloom.gps.geo.LatLng
import app.pikminbloom.gps.sim.Sample
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Thrown when this app is not the selected "mock location app" in Developer options. */
class MockNotAllowedException(msg: String) : Exception(msg)

/**
 * Drives Android's test location providers (and Play services' FLP mock mode) so that other apps —
 * Pikmin Bloom in particular — see the simulated walk instead of the real GPS.
 *
 * Requires the user to pick this app in 開發者選項 → 選擇模擬位置應用程式
 * (`adb shell appops set app.pikminbloom.gps android:mock_location allow`).
 */
class MockLocationController(context: Context) {

    private val app: Context = context.applicationContext
    private val lm: LocationManager? = app.getSystemService(LocationManager::class.java)
    private val fused: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(app)
    }

    /** Providers we successfully installed, in push order. */
    private val activeProviders = ArrayList<String>(3)
    private var flpMockEnabled = false

    val isRunning: Boolean get() = activeProviders.isNotEmpty() || flpMockEnabled

    // ---------------------------------------------------------------- selection

    /** True when this app is the selected mock location app (AppOps `android:mock_location`). */
    fun isMockAppSelected(): Boolean {
        val ops = app.getSystemService(AppOpsManager::class.java) ?: return false
        val uid = android.os.Process.myUid()
        val pkg = app.packageName
        return try {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, uid, pkg)
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, uid, pkg)
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (t: Throwable) {
            Log.w(TAG, "isMockAppSelected failed", t)
            false
        }
    }

    /** Opens 設定 → 開發者選項 so the user can pick this app as the mock location app. */
    fun openMockAppPicker(activity: Activity) {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        try {
            activity.startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "developer settings unavailable, falling back to app details", t)
            try {
                activity.startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS))
            } catch (t2: Throwable) {
                Log.w(TAG, "no settings activity at all", t2)
            }
        }
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * Installs the test providers. Throws [MockNotAllowedException] when the platform refuses us
     * (app not selected in Developer options) or when no provider could be installed at all.
     */
    /**
     * @param keepExisting true when RESUMING after a process death: the previous process's test
     *        providers are still registered and the game is still showing their last fix. Removing
     *        them first would expose the real GPS for a moment (a visible teleport), so instead
     *        they are replaced in place (addTestProvider replaces a same-named provider on
     *        Android 11+) and the caller pushes the checkpoint position immediately afterwards.
     */
    fun start(config: PatrolConfig, keepExisting: Boolean = false) {
        val manager = lm ?: throw MockNotAllowedException("LocationManager unavailable")
        activeProviders.clear()
        lastFlpError = null
        if (!keepExisting) {
            // Belt and braces: a crashed previous run may have left providers behind. Only
            // meaningful while we are the selected mock app (no-op / SecurityException otherwise).
            for (provider in ALL_PROVIDERS) runCatching { manager.removeTestProvider(provider) }
        }

        val wanted = buildList {
            add(LocationManager.GPS_PROVIDER)
            if (config.mockNetworkProvider) add(LocationManager.NETWORK_PROVIDER)
            if (config.mockFusedProvider && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(LocationManager.FUSED_PROVIDER)
            }
        }

        var security: SecurityException? = null
        for (provider in wanted) {
            try {
                // A previous run may have left the provider installed. When resuming we rely on
                // addTestProvider replacing it in place instead, to avoid a gap.
                if (!keepExisting) runCatching { manager.removeTestProvider(provider) }
                addTestProvider(manager, provider)
                manager.setTestProviderEnabled(provider, true)
                activeProviders.add(provider)
                Log.i(TAG, "test provider ready: $provider")
            } catch (e: SecurityException) {
                security = e
                Log.w(TAG, "not allowed to mock $provider", e)
            } catch (e: IllegalArgumentException) {
                // Provider already exists / unknown provider on this device: try to just enable it.
                Log.w(TAG, "addTestProvider($provider) rejected", e)
                try {
                    manager.setTestProviderEnabled(provider, true)
                    activeProviders.add(provider)
                } catch (t: Throwable) {
                    Log.w(TAG, "setTestProviderEnabled($provider) failed too", t)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "unexpected failure installing $provider", t)
            }
        }

        if (activeProviders.isEmpty()) {
            throw MockNotAllowedException(
                security?.message ?: "無法啟用模擬位置提供者，請確認已在開發者選項選擇本 App"
            )
        }

        if (config.useFlpMockMode) {
            try {
                fused.setMockMode(true)
                    .addOnSuccessListener { flpMockEnabled = true; Log.i(TAG, "FLP mock mode on") }
                    .addOnFailureListener {
                        flpMockEnabled = false
                        lastFlpError = it.message
                        Log.w(TAG, "FLP setMockMode(true) failed: ${it.message}")
                    }
            } catch (t: Throwable) {
                lastFlpError = t.message
                Log.w(TAG, "FLP setMockMode threw", t)
            }
        }
    }

    /** Last Fused Location Provider mock-mode failure, if any (null when everything worked). */
    @Volatile var lastFlpError: String? = null
        private set

    private fun addTestProvider(manager: LocationManager, provider: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val power = if (provider == LocationManager.GPS_PROVIDER) {
                ProviderProperties.POWER_USAGE_HIGH
            } else {
                ProviderProperties.POWER_USAGE_LOW
            }
            val props = ProviderProperties.Builder()
                .setHasNetworkRequirement(false)
                .setHasSatelliteRequirement(false)
                .setHasCellRequirement(false)
                .setHasMonetaryCost(false)
                .setHasAltitudeSupport(true)
                .setHasSpeedSupport(true)
                .setHasBearingSupport(true)
                .setPowerUsage(power)
                .setAccuracy(ProviderProperties.ACCURACY_FINE)
                .build()
            manager.addTestProvider(provider, props)
        } else {
            @Suppress("DEPRECATION")
            manager.addTestProvider(
                provider,
                false, // requiresNetwork
                false, // requiresSatellite
                false, // requiresCell
                false, // hasMonetaryCost
                true,  // supportsAltitude
                true,  // supportsSpeed
                true,  // supportsBearing
                Criteria.POWER_LOW,
                Criteria.ACCURACY_FINE,
            )
        }
    }

    /** Pushes one simulated fix to every active provider. Never throws. */
    fun push(sample: Sample) {
        val manager = lm ?: return
        val now = System.currentTimeMillis()
        val nanos = SystemClock.elapsedRealtimeNanos()
        for (provider in activeProviders) {
            try {
                manager.setTestProviderLocation(provider, buildLocation(provider, sample, now, nanos))
            } catch (t: Throwable) {
                Log.w(TAG, "setTestProviderLocation($provider) failed: ${t.message}")
            }
        }
        if (flpMockEnabled) {
            try {
                fused.setMockLocation(
                    buildLocation(LocationManager.FUSED_PROVIDER, sample, now, nanos)
                ).addOnFailureListener { Log.w(TAG, "FLP setMockLocation failed: ${it.message}") }
            } catch (t: Throwable) {
                Log.w(TAG, "FLP setMockLocation threw", t)
            }
        }
    }

    /** Pushes a raw position (used for the final "settle at home" fixes). */
    fun pushRaw(
        position: LatLng,
        speedMps: Double = 0.0,
        bearingDeg: Double = 0.0,
        accuracyM: Float = 5f,
        altitudeM: Double = 20.0,
    ) {
        val manager = lm ?: return
        val now = System.currentTimeMillis()
        val nanos = SystemClock.elapsedRealtimeNanos()
        for (provider in activeProviders) {
            try {
                manager.setTestProviderLocation(
                    provider,
                    buildLocation(provider, position, speedMps, bearingDeg, accuracyM, altitudeM, now, nanos)
                )
            } catch (t: Throwable) {
                Log.w(TAG, "setTestProviderLocation($provider) failed: ${t.message}")
            }
        }
        if (flpMockEnabled) {
            try {
                fused.setMockLocation(
                    buildLocation(
                        LocationManager.FUSED_PROVIDER, position, speedMps, bearingDeg,
                        accuracyM, altitudeM, now, nanos
                    )
                ).addOnFailureListener { Log.w(TAG, "FLP setMockLocation failed: ${it.message}") }
            } catch (t: Throwable) {
                Log.w(TAG, "FLP setMockLocation threw", t)
            }
        }
    }

    /** Removes every test provider and turns FLP mock mode off. Never throws. */
    fun stop() {
        val manager = lm
        if (manager != null) {
            // Remove every provider we may own, including ones left by a killed earlier process
            // (activeProviders is empty after a restart). Harmless when nothing is installed.
            val toRemove = if (activeProviders.isEmpty()) ALL_PROVIDERS else activeProviders.toList()
            for (provider in toRemove) {
                runCatching { manager.setTestProviderEnabled(provider, false) }
                runCatching { manager.removeTestProvider(provider) }
                    .onFailure { Log.w(TAG, "remove $provider: ${it.message}") }
            }
        }
        activeProviders.clear()
        if (flpMockEnabled) {
            flpMockEnabled = false
            try {
                fused.setMockMode(false)
                    .addOnFailureListener { Log.w(TAG, "FLP setMockMode(false) failed: ${it.message}") }
            } catch (t: Throwable) {
                Log.w(TAG, "FLP setMockMode(false) threw", t)
            }
        }
        Log.i(TAG, "mock stopped")
    }

    // ---------------------------------------------------------------- real position

    /**
     * The device's real position. ONLY meaningful while the mock is stopped — call it before
     * [start]. Returns null when we have no permission or nothing usable within [timeoutMs].
     */
    @SuppressLint("MissingPermission")
    suspend fun currentRealLocation(timeoutMs: Long = 15_000): LatLng? {
        if (!hasLocationPermission()) {
            Log.w(TAG, "currentRealLocation: no ACCESS_FINE_LOCATION")
            return null
        }
        val cts = CancellationTokenSource()
        val fromFlp = try {
            withTimeoutOrNull(timeoutMs) {
                val fresh = try {
                    awaitTask(fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token))
                } catch (t: Throwable) {
                    Log.w(TAG, "getCurrentLocation failed", t); null
                }
                fresh?.takeIf { !it.isMockFix() }?.toLatLng()
                    ?: try {
                        awaitTask(fused.lastLocation)?.takeIf { !it.isMockFix() }?.toLatLng()
                    } catch (t: Throwable) {
                        Log.w(TAG, "lastLocation failed", t); null
                    }
            }
        } finally {
            cts.cancel()   // stop the high-accuracy request when we time out or get cancelled
        }
        return fromFlp ?: lastKnownFromManager()
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownFromManager(): LatLng? {
        val manager = lm ?: return null
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val loc = try {
                manager.getLastKnownLocation(provider)
            } catch (t: Throwable) {
                Log.w(TAG, "getLastKnownLocation($provider) failed: ${t.message}"); null
            }
            if (loc != null && !loc.isMockFix()) return loc.toLatLng()
        }
        return null
    }

    /**
     * The last fix the system is still serving from a (possibly stale, previous-process) test
     * provider - i.e. where the game currently sees the player parked after a crash. Null when the
     * last known fix is real or missing.
     */
    @SuppressLint("MissingPermission")
    fun lastParkedMockFix(): LatLng? {
        val manager = lm ?: return null
        if (!hasLocationPermission()) return null
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val loc = runCatching { manager.getLastKnownLocation(provider) }.getOrNull() ?: continue
            if (loc.isMockFix()) return loc.toLatLng()
        }
        return null
    }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- helpers

    /** Also clear FLP mock mode left by a previous process before a real-GPS session. */
    suspend fun releaseForRealLocation() {
        stop()
        if (isMockAppSelected()) withTimeoutOrNull(5000) { awaitTask(fused.setMockMode(false)) }
    }

    private fun buildLocation(provider: String, s: Sample, now: Long, nanos: Long): Location =
        buildLocation(
            provider, s.position, s.speedMps, s.bearingDeg, s.accuracyM, s.altitudeM, now, nanos
        )

    private fun buildLocation(
        provider: String,
        position: LatLng,
        speedMps: Double,
        bearingDeg: Double,
        accuracyM: Float,
        altitudeM: Double,
        now: Long,
        nanos: Long,
    ): Location = Location(provider).apply {
        latitude = position.lat
        longitude = position.lon
        altitude = altitudeM
        accuracy = accuracyM
        speed = speedMps.toFloat()
        bearing = ((bearingDeg % 360.0 + 360.0) % 360.0).toFloat()
        time = now
        elapsedRealtimeNanos = nanos
        // minSdk is 28, so these O-era setters are always available.
        verticalAccuracyMeters = 1.5f
        speedAccuracyMetersPerSecond = 0.3f
        bearingAccuracyDegrees = 5f
        extras = Bundle().apply {
            putInt("satellites", 9)
            putInt("satellitesUsedInFix", 9)
        }
    }

    private fun Location.toLatLng() = LatLng(latitude, longitude)

    private fun Location.isMockFix(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else {
            @Suppress("DEPRECATION") isFromMockProvider
        }

    private suspend fun <T> awaitTask(task: Task<T>): T? = suspendCancellableCoroutine { cont ->
        task.addOnCompleteListener { done ->
            if (cont.isActive) {
                if (done.isSuccessful) cont.resume(done.result) else {
                    Log.w(TAG, "task failed: ${done.exception?.message}")
                    cont.resume(null)
                }
            }
        }
    }

    companion object {
        const val TAG = "PikminGPS"
        private val ALL_PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            "fused",
        )
    }
}
