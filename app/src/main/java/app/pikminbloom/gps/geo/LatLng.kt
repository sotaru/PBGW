package app.pikminbloom.gps.geo

/** WGS-84 coordinate in decimal degrees. Immutable value type shared by every module. */
data class LatLng(val lat: Double, val lon: Double) {
    init {
        require(lat in -90.0..90.0) { "lat out of range: $lat" }
        require(lon in -180.0..180.0) { "lon out of range: $lon" }
    }

    override fun toString(): String = String.format(java.util.Locale.US, "%.6f,%.6f", lat, lon)

    companion object {
        fun parse(text: String): LatLng? {
            val parts = text.trim().split(Regex("[,，;；\\s]+"))
            if (parts.size != 2) return null
            val lat = parts[0].toDoubleOrNull() ?: return null
            val lon = parts[1].toDoubleOrNull() ?: return null
            return runCatching { LatLng(lat, lon) }.getOrNull()
        }
    }
}
