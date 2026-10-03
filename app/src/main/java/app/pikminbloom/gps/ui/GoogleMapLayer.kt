package app.pikminbloom.gps.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import app.pikminbloom.gps.R
import app.pikminbloom.gps.data.Waypoint
import app.pikminbloom.gps.geo.LatLng
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import org.osmdroid.util.GeoPoint
import com.google.android.gms.maps.model.LatLng as GoogleLatLng

/** Native Google Maps renderer, with the same patrol data and gestures as the OSM map. */
class GoogleMapLayer(
    private val context: Context,
    container: FrameLayout,
    savedState: Bundle?,
    private val onWaypointTap: (Int) -> Unit,
    private val onLongPress: (LatLng) -> Unit,
) : PatrolMapOverlays {
    private val view = MapView(context)
    private var map: GoogleMap? = null
    private var started = false
    private var resumed = false
    private var center = LatLng(25.0330, 121.5654)
    private var zoom = 17.0
    private var topInset = 0
    private var bottomInset = 0
    private var waypoints: List<Waypoint> = emptyList()
    private var home: LatLng? = null
    private var route: List<GeoPoint> = emptyList()
    private var trail: List<GeoPoint> = emptyList()
    private var scanned: List<LatLng> = emptyList()
    private var scannedNames: List<String> = emptyList()
    private var position: LatLng? = null
    private var bearing = 0f
    private var routeLine: Polyline? = null
    private var trailLine: Polyline? = null
    private var positionMarker: Marker? = null
    private val staticMarkers = ArrayList<Marker>()
    private val circles = ArrayList<com.google.android.gms.maps.model.Circle>()
    private val scannedMarkers = ArrayList<Marker>()
    private val routeColor = ContextCompat.getColor(context, R.color.route_line)
    private val trailColor = ContextCompat.getColor(context, R.color.petal_pink)

    init {
        container.addView(view, FrameLayout.LayoutParams(-1, -1))
        view.onCreate(savedState)
        view.getMapAsync { ready ->
            map = ready
            ready.uiSettings.isMapToolbarEnabled = false
            ready.uiSettings.isCompassEnabled = false
            ready.uiSettings.isRotateGesturesEnabled = false
            ready.uiSettings.isTiltGesturesEnabled = false
            ready.setPadding(0, topInset, 0, bottomInset)
            ready.setOnMapLongClickListener { onLongPress(LatLng(it.latitude, it.longitude)) }
            ready.setOnMarkerClickListener { marker ->
                (marker.tag as? Int)?.let(onWaypointTap)
                true
            }
            ready.moveCamera(CameraUpdateFactory.newLatLngZoom(center.google(), zoom.toFloat()))
            drawStatic()
            updateTrail(trail)
            updateScanned(scanned, scannedNames)
            updatePosition(position, bearing)
        }
    }

    fun currentCenter(): LatLng = map?.cameraPosition?.target?.let { LatLng(it.latitude, it.longitude) } ?: center
    fun currentZoom(): Double = map?.cameraPosition?.zoom?.toDouble() ?: zoom

    /** Keep Google attribution and controls above the app's bottom panel. */
    fun setContentInsets(top: Int, bottom: Int) {
        val nextTop = top.coerceAtLeast(0)
        val nextBottom = bottom.coerceAtLeast(0)
        if (nextTop == topInset && nextBottom == bottomInset) return
        val ready = map
        val target = ready?.cameraPosition?.target
        topInset = nextTop
        bottomInset = nextBottom
        ready?.setPadding(0, topInset, 0, bottomInset)
        // Padding changes the camera's effective center. Keep the chosen coordinate under the
        // crosshair when the status card finishes layout or changes height.
        if (target != null) ready.moveCamera(CameraUpdateFactory.newLatLng(target))
    }

    fun center(position: LatLng, zoomLevel: Double? = null, animate: Boolean = true) {
        center = position
        if (zoomLevel != null) zoom = zoomLevel
        val ready = map ?: return
        val update = if (zoomLevel != null) CameraUpdateFactory.newLatLngZoom(position.google(), zoomLevel.toFloat())
            else CameraUpdateFactory.newLatLng(position.google())
        if (animate) ready.animateCamera(update) else ready.moveCamera(update)
    }

    override fun rebuildStatic(waypoints: List<Waypoint>, home: LatLng?, route: List<GeoPoint>) {
        this.waypoints = waypoints.toList()
        this.home = home
        this.route = route.toList()
        drawStatic()
    }

    private fun drawStatic() {
        val ready = map ?: return
        staticMarkers.forEach { it.remove() }; staticMarkers.clear()
        circles.forEach { it.remove() }; circles.clear()
        waypoints.forEachIndexed { index, wp ->
            val p = wp.latLng.google()
            circles += ready.addCircle(CircleOptions().center(p).radius(40.0).strokeWidth(0f)
                .fillColor(ContextCompat.getColor(context, R.color.orbit_circle)))
            circles += ready.addCircle(CircleOptions().center(p).radius(wp.radiusM).strokeColor(routeColor).strokeWidth(2f))
            ready.addMarker(MarkerOptions().position(p).title(wp.name).anchor(0.5f, 0.5f)
                .icon(BitmapDescriptorFactory.fromBitmap(numberedIcon(index + 1))))?.let {
                it.tag = index; staticMarkers += it
            }
        }
        home?.let { p ->
            ready.addMarker(MarkerOptions().position(p.google()).title(context.getString(R.string.btn_return_home))
                .anchor(0.5f, 0.5f).icon(BitmapDescriptorFactory.fromBitmap(drawableIcon(R.drawable.ic_home))))?.let { staticMarkers += it }
        }
        updateRoute(route)
    }

    override fun updateRoute(route: List<GeoPoint>) {
        this.route = route.toList()
        val ready = map ?: return
        if (routeLine == null) routeLine = ready.addPolyline(PolylineOptions().color(routeColor).width(5f))
        routeLine?.points = route.map { GoogleLatLng(it.latitude, it.longitude) }
    }

    override fun updateTrail(points: List<GeoPoint>) {
        trail = points.toList()
        val ready = map ?: return
        if (trailLine == null) trailLine = ready.addPolyline(PolylineOptions().color(trailColor).width(5f).zIndex(1f))
        trailLine?.points = points.map { GoogleLatLng(it.latitude, it.longitude) }
    }

    override fun updatePosition(position: LatLng?, bearingDeg: Float) {
        this.position = position; bearing = bearingDeg
        val ready = map ?: return
        if (position == null) { positionMarker?.remove(); positionMarker = null; return }
        if (positionMarker == null) positionMarker = ready.addMarker(MarkerOptions().position(position.google())
            .flat(true).anchor(0.5f, 0.5f).zIndex(3f).icon(BitmapDescriptorFactory.fromBitmap(drawableIcon(R.drawable.ic_navigation))))
        positionMarker?.position = position.google()
        positionMarker?.rotation = bearingDeg
    }

    override fun updateScanned(flowers: List<LatLng>, names: List<String>) {
        scanned = flowers.toList(); scannedNames = names.toList()
        val ready = map ?: return
        scannedMarkers.forEach { it.remove() }; scannedMarkers.clear()
        val icon = BitmapDescriptorFactory.fromBitmap(drawableIcon(R.drawable.ic_flower))
        flowers.forEachIndexed { i, p -> ready.addMarker(MarkerOptions().position(p.google()).title(names.getOrNull(i))
            .anchor(0.5f, 0.5f).icon(icon))?.let { scannedMarkers += it } }
    }

    private fun drawableIcon(id: Int): Bitmap {
        val size = context.resources.getDimensionPixelSize(R.dimen.marker_size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        AppCompatResources.getDrawable(context, id)?.apply { setBounds(0, 0, size, size); draw(Canvas(bitmap)) }
        return bitmap
    }

    private fun numberedIcon(number: Int): Bitmap {
        val size = context.resources.getDimensionPixelSize(R.dimen.marker_size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = trailColor }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2f, paint)
        paint.color = Color.BLACK; paint.textAlign = Paint.Align.CENTER; paint.textSize = size * 0.5f
        paint.typeface = Typeface.DEFAULT_BOLD
        val metrics = paint.fontMetrics
        canvas.drawText(number.toString(), size / 2f, size / 2f - (metrics.ascent + metrics.descent) / 2f, paint)
        return bitmap
    }

    fun onStart() { if (!started) { view.onStart(); started = true } }
    fun onResume() { if (!resumed) { view.onResume(); resumed = true } }
    fun onPause() { if (resumed) { view.onPause(); resumed = false } }
    fun onStop() { if (started) { view.onStop(); started = false } }
    fun onDestroy() { onPause(); onStop(); view.onDestroy() }
    fun onLowMemory() = view.onLowMemory()
    fun saveState(): Bundle = Bundle().also { view.onSaveInstanceState(it) }
    private fun LatLng.google() = GoogleLatLng(lat, lon)
}
