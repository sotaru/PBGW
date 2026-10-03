package app.pikminbloom.gps.route

import app.pikminbloom.gps.geo.LatLng

/** Plain-text rows: latitude, longitude, optionally with a name before or after the pair. */
object CoordinatePasteParser {
    const val MAX_POINTS = 301
    const val MAX_TEXT_LENGTH = 100_000
    data class Point(val name: String, val position: LatLng, val line: Int)
    data class Parsed(val points: List<Point>, val duplicates: Int)

    private const val NUMBER = "[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?"
    private const val SEP = "[\\s,，;；]+"
    private val pairFirst = Regex("^\\(?\\s*($NUMBER)$SEP($NUMBER)\\s*\\)?(?:[\\s,，;；]+(.+))?$")
    private val nameFirst = Regex("^(.+?)[\\s,，;；]+\\(?\\s*($NUMBER)$SEP($NUMBER)\\s*\\)?$")

    fun parse(text: String): Parsed {
        require(text.length <= MAX_TEXT_LENGTH) { "文字太長，請分批匯入（最多 $MAX_TEXT_LENGTH 字）。" }
        val points = linkedMapOf<LatLng, Point>()
        var duplicates = 0
        text.lineSequence().forEachIndexed { index, raw ->
            val row = raw.trim().removePrefix("\uFEFF").trim()
            if (row.isBlank() || row.startsWith("#")) return@forEachIndexed
            val first = pairFirst.matchEntire(row)
            val named = if (first == null) nameFirst.matchEntire(row) else null
            require(first != null || named != null) { "第 ${index + 1} 行讀不到座標，請用「緯度,經度」或「名稱,緯度,經度」。" }
            val latText = first?.groupValues?.get(1) ?: named!!.groupValues[2]
            val lonText = first?.groupValues?.get(2) ?: named!!.groupValues[3]
            val lat = latText.toDoubleOrNull()
            val lon = lonText.toDoubleOrNull()
            require(lat != null && lon != null && lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) {
                "第 ${index + 1} 行座標超出範圍：緯度 −90～90，經度 −180～180。"
            }
            val position = LatLng(if (lat == 0.0) 0.0 else lat, if (lon == 0.0) 0.0 else lon)
            val name = (first?.groupValues?.get(3) ?: named!!.groupValues[1]).trim().ifBlank { "座標 ${points.size + 1}" }
            if (points.containsKey(position)) duplicates++
            else points[position] = Point(name, position, index + 1)
            require(points.size <= MAX_POINTS) { "一次最多匯入一個家與 ${MAX_POINTS - 1} 朵大花。" }
        }
        require(points.size >= 2) { "請提供至少兩個不同座標：一個家與至少一朵大花。" }
        return Parsed(points.values.toList(), duplicates)
    }
}
