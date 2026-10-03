package app.pikminbloom.gps.data

import android.content.Context
import android.util.Log
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.util.UUID

/** A named set of Big Flowers the user can switch between (「巡邏1」「巡邏2」…). */
data class Route(
    val id: String,
    val name: String,
    val waypoints: List<Waypoint>,
    /**
     * How to cover the leg from home to the FIRST waypoint (and back). WALK for an ordinary Big
     * Flower patrol; a vehicle mode for a decor trip whose destination is kilometres away, in
     * which case the walking (steps, planting) happens only inside that waypoint's circle.
     */
    val travelMode: TravelMode = TravelMode.WALK,
    /** Visit every flower once, pass through its circle, then walk home and park. */
    val collectOnce: Boolean = false,
)

/**
 * Big Flower routes persisted as JSON in the app's private files dir. Process-wide singleton.
 *
 * Every mutating call that does not name a route acts on the **active** route, so the rest of the
 * app can keep treating this as "the waypoint list" while the user switches between saved patrols.
 *
 * File format v2:
 * ```json
 * { "version": 2, "activeRouteId": "...", "routes": [ { "id": "...", "name": "...", "waypoints": [...] } ] }
 * ```
 * v1 (`{"version":1,"waypoints":[...]}`) and a bare array are migrated into one route on load.
 */
class WaypointStore private constructor(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val atomicFile = AtomicFile(file)

    private var state: Snapshot = readFile()

    private val _routes = MutableStateFlow(state.routes)
    /** Every saved route, in user order. */
    val routes: StateFlow<List<Route>> = _routes

    private val _waypoints = MutableStateFlow(state.active?.waypoints ?: emptyList())
    /** Waypoints of the active route. Unchanged contract for existing callers. */
    val waypoints: StateFlow<List<Waypoint>> = _waypoints

    private val _activeRouteId = MutableStateFlow(state.activeRouteId)
    val activeRouteId: StateFlow<String> = _activeRouteId

    private data class Snapshot(val routes: List<Route>, val activeRouteId: String) {
        val active: Route? get() = routes.firstOrNull { it.id == activeRouteId } ?: routes.firstOrNull()
    }

    // ---------------------------------------------------------------- active route

    fun load(): List<Waypoint> = _waypoints.value

    fun activeRoute(): Route? = state.active

    fun activeRouteName(): String = state.active?.name.orEmpty()

    @Synchronized
    fun save(list: List<Waypoint>) {
        val active = state.active ?: run {
            // No routes at all yet (fresh install): create one so the list has somewhere to live.
            val created = Route(UUID.randomUUID().toString(), DEFAULT_ROUTE_NAME, list)
            commit(Snapshot(listOf(created), created.id))
            return
        }
        commit(state.copy(routes = state.routes.map { if (it.id == active.id) it.copy(waypoints = list) else it }))
    }

    fun add(wp: Waypoint) = save(load() + wp)

    fun update(wp: Waypoint) = save(load().map { if (it.id == wp.id) wp else it })

    fun remove(id: String) = save(load().filterNot { it.id == id })

    fun move(from: Int, to: Int) {
        val list = load().toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        list.add(to, list.removeAt(from))
        save(list)
    }

    fun clear() = save(emptyList())

    // ---------------------------------------------------------------- routes

    fun routeList(): List<Route> = state.routes

    /** @return the new route's id. [copyFromId] duplicates that route's waypoints. */
    @Synchronized
    fun createRoute(name: String, copyFromId: String? = null, travelMode: TravelMode = TravelMode.WALK): String {
        val source = copyFromId?.let { id -> state.routes.firstOrNull { it.id == id } }
        val route = Route(
            id = UUID.randomUUID().toString(),
            name = uniqueName(name.ifBlank { DEFAULT_ROUTE_NAME }),
            waypoints = source?.waypoints.orEmpty(),
            travelMode = if (source != null) source.travelMode else travelMode,
            collectOnce = source?.collectOnce ?: false,
        )
        commit(Snapshot(state.routes + route, route.id))
        return route.id
    }

    /** Persist the complete new route before publishing it; a failed write changes no active route. */
    @Synchronized
    fun createCollectionRoute(name: String, waypoints: List<Waypoint>): String {
        require(waypoints.isNotEmpty())
        val route = Route(UUID.randomUUID().toString(), uniqueName(name.ifBlank { "採花路線" }), waypoints,
            travelMode = TravelMode.WALK, collectOnce = true)
        commit(Snapshot(state.routes + route, route.id), strict = true)
        return route.id
    }

    @Synchronized
    fun renameRoute(id: String, name: String) {
        if (name.isBlank()) return
        commit(state.copy(routes = state.routes.map { if (it.id == id) it.copy(name = uniqueName(name, except = id)) else it }))
    }

    /** Deleting the last route leaves one empty route behind, so the app always has somewhere to add to. */
    @Synchronized
    fun deleteRoute(id: String) {
        val remaining = state.routes.filterNot { it.id == id }
        if (remaining.isEmpty()) {
            val fresh = Route(UUID.randomUUID().toString(), DEFAULT_ROUTE_NAME, emptyList())
            commit(Snapshot(listOf(fresh), fresh.id))
            return
        }
        val nextActive = if (state.activeRouteId == id) remaining.first().id else state.activeRouteId
        commit(Snapshot(remaining, nextActive))
    }

    @Synchronized
    fun setRouteTravelMode(id: String, mode: TravelMode) {
        commit(state.copy(routes = state.routes.map { if (it.id == id) it.copy(travelMode = mode) else it }))
    }

    @Synchronized
    fun switchTo(id: String) {
        if (state.routes.none { it.id == id } || state.activeRouteId == id) return
        commit(state.copy(activeRouteId = id))
    }

    private fun uniqueName(wanted: String, except: String? = null): String {
        val taken = state.routes.filter { it.id != except }.map { it.name }.toSet()
        if (wanted !in taken) return wanted
        var i = 2
        while ("$wanted $i" in taken) i++
        return "$wanted $i"
    }

    @Synchronized
    private fun commit(next: Snapshot, strict: Boolean = false) {
        try {
            val output = atomicFile.startWrite()
            try {
                output.write(toJson(next).toByteArray(Charsets.UTF_8))
                atomicFile.finishWrite(output)
            } catch (t: Throwable) {
                atomicFile.failWrite(output)
                throw t
            }
        } catch (t: Throwable) {
            if (strict) throw t
            Log.w(TAG, "save routes failed", t)
            return
        }
        state = next
        _routes.value = next.routes
        _activeRouteId.value = next.activeRouteId
        _waypoints.value = next.active?.waypoints ?: emptyList()
    }

    // ---------------------------------------------------------------- import / export

    fun exportJson(): String = toJson(state)

    /**
     * Imports waypoints into the active route (replacing it). Also accepts a full v2 export, in
     * which case every route in the file is added.
     */
    fun importJson(text: String): Int {
        val trimmed = text.trim()
        if (trimmed.startsWith("{")) {
            val obj = runCatching { JSONObject(trimmed) }.getOrNull()
            val routesArr = obj?.optJSONArray("routes")
            if (routesArr != null) {
                val imported = ArrayList<Route>()
                for (i in 0 until routesArr.length()) {
                    val r = routesArr.optJSONObject(i) ?: continue
                    val wps = parseWaypoints(r.optJSONArray("waypoints") ?: JSONArray())
                    if (wps.isEmpty()) continue
                    imported.add(Route(UUID.randomUUID().toString(), uniqueName(r.optString("name").ifBlank { DEFAULT_ROUTE_NAME }), wps,
                        travelMode = runCatching { TravelMode.valueOf(r.optString("travelMode")) }.getOrDefault(TravelMode.WALK),
                        collectOnce = r.optBoolean("collectOnce", false)))
                }
                if (imported.isEmpty()) return 0
                commit(Snapshot(state.routes + imported, imported.first().id))
                return imported.sumOf { it.waypoints.size }
            }
        }
        val parsed = parseAnyWaypoints(trimmed)
        save(parsed)
        return parsed.size
    }

    fun exportGpx(): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"阿皮小夥伴\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        for (w in load()) {
            sb.append("  <wpt lat=\"${w.lat}\" lon=\"${w.lon}\">\n")
            sb.append("    <name>${escape(w.name)}</name>\n")
            sb.append("    <desc>radius=${w.radiusM};dwell=${w.dwellSec}</desc>\n")
            sb.append("  </wpt>\n")
        }
        sb.append("</gpx>\n")
        return sb.toString()
    }

    /** Imports every `<wpt>` (or, if there are none, `<rtept>`/`<trkpt>`) into the active route. */
    fun importGpx(text: String, defaultRadiusM: Double, defaultDwellSec: Int): Int {
        val out = ArrayList<Waypoint>()
        val fallback = ArrayList<Waypoint>()
        try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(text))
            var lat: Double? = null
            var lon: Double? = null
            var name: String? = null
            var desc: String? = null
            var tag: String? = null
            var inPoint = false
            var pointTag = ""
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val n = parser.name
                        if (n == "wpt" || n == "rtept" || n == "trkpt") {
                            inPoint = true; pointTag = n
                            lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                            lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                            name = null; desc = null
                        } else if (inPoint) tag = n
                    }
                    XmlPullParser.TEXT -> if (inPoint) {
                        when (tag) {
                            "name" -> name = parser.text.trim()
                            "desc" -> desc = parser.text.trim()
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val n = parser.name
                        if (inPoint && n == pointTag) {
                            val la = lat; val lo = lon
                            if (la != null && lo != null && la in -90.0..90.0 && lo in -180.0..180.0) {
                                var radius = defaultRadiusM
                                var dwell = defaultDwellSec
                                desc?.split(';')?.forEach { part ->
                                    val kv = part.split('=')
                                    if (kv.size == 2) when (kv[0].trim()) {
                                        "radius" -> kv[1].trim().toDoubleOrNull()?.let { radius = it }
                                        "dwell" -> kv[1].trim().toIntOrNull()?.let { dwell = it }
                                    }
                                }
                                val wp = Waypoint(
                                    id = UUID.randomUUID().toString(),
                                    name = name?.takeIf { it.isNotBlank() } ?: "大花 ${out.size + fallback.size + 1}",
                                    lat = la, lon = lo,
                                    radiusM = radius.coerceIn(8.0, 40.0),
                                    dwellSec = dwell.coerceIn(0, 1800),
                                )
                                if (n == "wpt") out.add(wp) else fallback.add(wp)
                            }
                            inPoint = false
                        }
                        if (inPoint) tag = null
                    }
                }
                event = parser.next()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "GPX parse failed", t)
            return 0
        }
        val result = if (out.isNotEmpty()) out else fallback
        if (result.isNotEmpty()) save(result)
        return result.size
    }

    // ---------------------------------------------------------------- persistence

    private fun readFile(): Snapshot = try {
        if (file.exists() || File(file.path + ".bak").exists())
            fromJson(atomicFile.openRead().bufferedReader().use { it.readText() }) else emptySnapshot()
    } catch (t: Throwable) {
        Log.w(TAG, "read routes failed", t); emptySnapshot()
    }

    private fun emptySnapshot(): Snapshot {
        val r = Route(UUID.randomUUID().toString(), DEFAULT_ROUTE_NAME, emptyList())
        return Snapshot(listOf(r), r.id)
    }

    private fun toJson(s: Snapshot): String {
        val routes = JSONArray()
        for (r in s.routes) {
            val wps = JSONArray()
            for (w in r.waypoints) {
                wps.put(JSONObject().apply {
                    put("id", w.id); put("name", w.name); put("lat", w.lat); put("lon", w.lon)
                    put("radiusM", w.radiusM); put("dwellSec", w.dwellSec)
                })
            }
            routes.put(JSONObject().put("id", r.id).put("name", r.name).put("travelMode", r.travelMode.name)
                .put("collectOnce", r.collectOnce).put("waypoints", wps))
        }
        return JSONObject()
            .put("version", 2)
            .put("activeRouteId", s.activeRouteId)
            .put("routes", routes)
            .toString(2)
    }

    private fun fromJson(text: String): Snapshot {
        val trimmed = text.trim()
        if (trimmed.startsWith("{")) {
            val obj = JSONObject(trimmed)
            val routesArr = obj.optJSONArray("routes")
            if (routesArr != null) {
                val routes = ArrayList<Route>(routesArr.length())
                for (i in 0 until routesArr.length()) {
                    val r = routesArr.optJSONObject(i) ?: continue
                    routes.add(
                        Route(
                            id = r.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                            name = r.optString("name").takeIf { it.isNotBlank() } ?: "$DEFAULT_ROUTE_NAME ${i + 1}",
                            waypoints = parseWaypoints(r.optJSONArray("waypoints") ?: JSONArray()),
                            travelMode = runCatching { TravelMode.valueOf(r.optString("travelMode")) }.getOrDefault(TravelMode.WALK),
                            collectOnce = r.optBoolean("collectOnce", false),
                        )
                    )
                }
                if (routes.isEmpty()) return emptySnapshot()
                val active = obj.optString("activeRouteId").takeIf { id -> routes.any { it.id == id } } ?: routes.first().id
                return Snapshot(routes, active)
            }
        }
        // v1 or a bare array: one route holding everything.
        val migrated = parseAnyWaypoints(trimmed)
        val r = Route(UUID.randomUUID().toString(), DEFAULT_ROUTE_NAME, migrated)
        Log.i(TAG, "migrated ${migrated.size} waypoints from the old single-list format")
        return Snapshot(listOf(r), r.id)
    }

    private fun parseAnyWaypoints(trimmed: String): List<Waypoint> {
        val arr = if (trimmed.startsWith("[")) JSONArray(trimmed)
        else JSONObject(trimmed).optJSONArray("waypoints") ?: JSONArray()
        return parseWaypoints(arr)
    }

    private fun parseWaypoints(arr: JSONArray): List<Waypoint> {
        val out = ArrayList<Waypoint>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val lat = o.optDouble("lat", Double.NaN)
            val lon = o.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
            out.add(
                Waypoint(
                    id = o.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                    name = o.optString("name").takeIf { it.isNotBlank() } ?: "大花 ${i + 1}",
                    lat = lat, lon = lon,
                    radiusM = o.optDouble("radiusM", Waypoint.DEFAULT_RADIUS_M).coerceIn(8.0, 40.0),
                    dwellSec = o.optInt("dwellSec", Waypoint.DEFAULT_DWELL_SEC).coerceIn(0, 1800),
                )
            )
        }
        return out
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        private const val TAG = "PikminGPS"
        private const val FILE_NAME = "waypoints.json"
        const val DEFAULT_ROUTE_NAME = "巡邏 1"

        @Volatile private var instance: WaypointStore? = null

        fun get(context: Context): WaypointStore =
            instance ?: synchronized(this) { instance ?: WaypointStore(context).also { instance = it } }
    }
}
