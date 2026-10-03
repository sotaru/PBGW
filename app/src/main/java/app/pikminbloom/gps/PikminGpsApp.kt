package app.pikminbloom.gps

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import app.pikminbloom.gps.mock.MockLocationController
import app.pikminbloom.gps.service.PatrolService
import org.osmdroid.config.Configuration

class PikminGpsApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // osmdroid needs a user agent for the OSM tile servers and a private cache dir.
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = getExternalFilesDir(null) ?: filesDir
            osmdroidTileCache = java.io.File(osmdroidBasePath, "tiles")
        }

        createNotificationChannels()
        cleanupStaleMockProviders()
    }

    /**
     * If the previous process died mid-patrol, its test providers are still installed and the phone's
     * GPS is frozen at the last fake position for every app. Remove them as early as possible.
     */
    private fun cleanupStaleMockProviders() {
        if (PatrolService.isRunning) return
        // A checkpoint means the last patrol died mid-way and the game is still parked at its last
        // mocked position. Leave the providers alone so it STAYS parked; MainActivity offers to
        // resume from that exact spot. Cleaning up here would be the teleport we are avoiding.
        if (app.pikminbloom.gps.service.PatrolCheckpoint.resumable(this) != null) {
            Log.i("PikminGPS", "checkpoint present: keeping stale mock providers for a resume")
            return
        }
        try {
            val mock = MockLocationController(this)
            if (mock.isMockAppSelected()) mock.stop()
        } catch (t: Throwable) {
            Log.w("PikminGPS", "stale mock cleanup failed", t)
        }
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PATROL, "巡邏狀態", NotificationManager.IMPORTANCE_LOW).apply {
                description = "巡邏進行中的常駐通知"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, "抵達提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "抵達巨大花朵、回家完成、錯誤等提醒"
                enableVibration(true)
            }
        )
        // The screen-capture foreground service (bird's-eye Big Flower scan) needs its own quiet channel.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SCREEN_SCAN, getString(R.string.scan_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.scan_channel_desc)
                setShowBadge(false)
            }
        )
    }

    companion object {
        const val CHANNEL_PATROL = "patrol"
        const val CHANNEL_EVENTS = "events"
        const val CHANNEL_SCREEN_SCAN = "screen_scan"
    }
}
