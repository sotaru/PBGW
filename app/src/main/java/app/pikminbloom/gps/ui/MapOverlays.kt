package app.pikminbloom.gps.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import app.pikminbloom.gps.R
import app.pikminbloom.gps.data.Waypoint
import app.pikminbloom.gps.geo.LatLng
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.infowindow.InfoWindow
import org.osmdroid.views.overlay.infowindow.MarkerInfoWindow

/**
 * Owns every osmdroid overlay drawn on the patrol map: waypoint pins, their 40 m Big-Flower circle
 * and their own orbit radius, the home pin, the planned route, the walked trail and the simulated
 * position arrow.
 *
 * [rebuildStatic] re-creates everything that only changes when the waypoint list / settings change;
 * [updatePosition] / [updateTrail] are cheap and run on every 1 Hz state update.
 */
interface PatrolMapOverlays {
    fun rebuildStatic(waypoints: List<Waypoint>, home: LatLng?, route: List<GeoPoint>)
    fun updateRoute(route: List<GeoPoint>)
    fun updateTrail(points: List<GeoPoint>)
    fun updatePosition(position: LatLng?, bearingDeg: Float)
    fun updateScanned(flowers: List<LatLng>, names: List<String> = emptyList())
}

class MapOverlays(
    private val context: Context,
    private val map: MapView,
    private val eventsOverlay: Overlay,
    private val onWaypointTap: (Int) -> Unit,
) : PatrolMapOverlays {

    private val density = context.resources.displayMetrics.density
    private val routeColor = ContextCompat.getColor(context, R.color.route_line)
    private val orbitColor = ContextCompat.getColor(context, R.color.orbit_circle)
    private val homeColor = ContextCompat.getColor(context, R.color.home_marker)
    private val trailColor = ContextCompat.getColor(context, R.color.petal_pink)

    private val routeLine = Polyline(map).apply {
        outlinePaint.color = routeColor
        outlinePaint.strokeWidth = context.resources.getDimension(R.dimen.route_width)
        outlinePaint.strokeCap = Paint.Cap.ROUND
        setInfoWindow(NO_INFO_WINDOW)
    }
    private val trailLine = Polyline(map).apply {
        outlinePaint.color = trailColor
        outlinePaint.strokeWidth = context.resources.getDimension(R.dimen.trail_width)
        outlinePaint.strokeCap = Paint.Cap.ROUND
        setInfoWindow(NO_INFO_WINDOW)
    }
    private val homeMarker = Marker(map).apply {
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        setInfoWindow(NO_MARKER_INFO_WINDOW)
        setIcon(homeIcon())
    }
    private val positionMarker = Marker(map).apply {
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        setInfoWindow(NO_MARKER_INFO_WINDOW)
        setFlat(true)
        setIcon(AppCompatResources.getDrawable(context, R.drawable.ic_navigation))
    }

    private val waypointMarkers = ArrayList<Marker>()
    private val circles = ArrayList<Polygon>()
    private val iconCache = HashMap<Int, Drawable>()

    /** Big Flowers found by the bird's-eye scan (vision/FlowerScanner), not yet in any route. */
    private val scannedMarkers = ArrayList<Marker>()
    private val scannedColor = ContextCompat.getColor(context, R.color.scan_marker)
    private var scannedIcon: Drawable? = null

    private var hasHome = false
    private var hasPosition = false

    /** Rebuilds waypoints / circles / home / planned route and re-stacks the overlay list. */
    override fun rebuildStatic(waypoints: List<Waypoint>, home: LatLng?, route: List<GeoPoint>) {
        waypointMarkers.clear()
        circles.clear()

        waypoints.forEachIndexed { index, wp ->
            val center = GeoPoint(wp.lat, wp.lon)
            circles.add(
                Polygon(map).apply {
                    setPoints(Polygon.pointsAsCircle(center, BIG_FLOWER_RADIUS_M))
                    fillPaint.color = orbitColor
                    outlinePaint.color = Color.TRANSPARENT
                    outlinePaint.strokeWidth = 0f
                    setInfoWindow(NO_INFO_WINDOW)
                }
            )
            circles.add(
                Polygon(map).apply {
                    setPoints(Polygon.pointsAsCircle(center, wp.radiusM))
                    fillPaint.color = Color.TRANSPARENT
                    outlinePaint.color = routeColor
                    outlinePaint.strokeWidth = context.resources.getDimension(R.dimen.circle_stroke)
                    setInfoWindow(NO_INFO_WINDOW)
                }
            )
            waypointMarkers.add(
                Marker(map).apply {
                    setPosition(center)
                    setTitle(wp.name)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setInfoWindow(NO_MARKER_INFO_WINDOW)
                    setIcon(numberedIcon(index + 1))
                    setOnMarkerClickListener { _, _ -> onWaypointTap(index); true }
                }
            )
        }

        hasHome = home != null
        if (home != null) homeMarker.setPosition(GeoPoint(home.lat, home.lon))
        routeLine.setPoints(route)

        restack()
    }

    override fun updateRoute(route: List<GeoPoint>) {
        routeLine.setPoints(route)
        map.invalidate()
    }

    override fun updateTrail(points: List<GeoPoint>) {
        trailLine.setPoints(points)
        map.invalidate()
    }

    /** Simulated position arrow; pass null to hide it. */
    override fun updatePosition(position: LatLng?, bearingDeg: Float) {
        val show = position != null
        if (position != null) {
            positionMarker.setPosition(GeoPoint(position.lat, position.lon))
            // osmdroid rotates the canvas by -rotation, so negate to get a clockwise compass bearing.
            positionMarker.setRotation(-bearingDeg)
        }
        if (show != hasPosition) {
            hasPosition = show
            restack()
        } else {
            map.invalidate()
        }
    }

    /**
     * Flowers found by the bird's-eye scan, drawn in a distinct colour so they are not mistaken for
     * route waypoints. Pass an empty list to clear them.
     */
    override fun updateScanned(flowers: List<LatLng>, names: List<String>) {
        val changed = flowers.size != scannedMarkers.size ||
            flowers.withIndex().any { (i, p) ->
                val m = scannedMarkers[i]
                m.position.latitude != p.lat || m.position.longitude != p.lon
            }
        if (!changed) return
        scannedMarkers.clear()
        val icon = scannedIcon ?: flowerIcon(scannedColor).also { scannedIcon = it }
        flowers.forEachIndexed { i, p ->
            scannedMarkers.add(
                Marker(map).apply {
                    setPosition(GeoPoint(p.lat, p.lon))
                    setTitle(names.getOrNull(i))
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setInfoWindow(NO_MARKER_INFO_WINDOW)
                    setIcon(icon)
                    setOnMarkerClickListener { _, _ -> true }
                }
            )
        }
        restack()
    }

    /** Rebuilds `map.overlays` bottom-up so markers stay tappable above the circles. */
    private fun restack() {
        val overlays = map.overlays
        overlays.clear()
        overlays.add(eventsOverlay)      // index 0: consulted last for taps
        overlays.addAll(circles)
        overlays.add(routeLine)
        overlays.add(trailLine)
        if (hasHome) overlays.add(homeMarker)
        overlays.addAll(scannedMarkers)
        overlays.addAll(waypointMarkers)
        if (hasPosition) overlays.add(positionMarker)
        map.invalidate()
    }

    private fun flowerIcon(color: Int): Drawable {
        val d = AppCompatResources.getDrawable(context, R.drawable.ic_flower)!!.mutate()
        DrawableCompat.setTint(d, color)
        return d
    }

    private fun homeIcon(): Drawable {
        val d = AppCompatResources.getDrawable(context, R.drawable.ic_home)!!.mutate()
        DrawableCompat.setTint(d, homeColor)
        return d
    }

    private fun numberedIcon(number: Int): Drawable = iconCache.getOrPut(number) {
        val size = context.resources.getDimensionPixelSize(R.dimen.marker_size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val c = size / 2f
        val ring = 2f * density
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = trailColor
            style = Paint.Style.FILL
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = ring
        }
        canvas.drawCircle(c, c, c - ring, fill)
        canvas.drawCircle(c, c, c - ring, stroke)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
            textSize = size * 0.5f
            typeface = Typeface.DEFAULT_BOLD
        }
        val metrics = text.fontMetrics
        canvas.drawText(number.toString(), c, c - (metrics.ascent + metrics.descent) / 2f, text)
        BitmapDrawable(context.resources, bitmap)
    }

    companion object {
        /** Pikmin Bloom counts flowers planted within 40 m of a Big Flower. */
        const val BIG_FLOWER_RADIUS_M = 40.0

        private val NO_INFO_WINDOW: InfoWindow? = null
        private val NO_MARKER_INFO_WINDOW: MarkerInfoWindow? = null
    }
}
