package app.pikminbloom.gps.route

import app.pikminbloom.gps.geo.LatLng
import java.text.Normalizer

/** Extracts one decimal-degree pair per line; unrelated rows are reported and skipped. */
object CoordinatePasteParser {
    const val MAX_POINTS = 301
    const val MAX_TEXT_LENGTH = 100_000
    data class Point(val name: String, val position: LatLng, val line: Int)
    data class Parsed(val points: List<Point>, val duplicates: Int, val skippedLines: List<Int> = emptyList())

    private const val NUMBER = "[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?"
    // Do not extract plausible numeric suffixes from overflowing values or IDs.
    private val numbers = Regex("(?<![A-Za-z0-9_.])$NUMBER(?![A-Za-z0-9_.])")
    private val gap = Regex("^[\\s,;、|/()\\[\\]{}°:=]+(?:(?:經度|经度|longitude|lon|lng)\\s*[:=]?\\s*)?$", RegexOption.IGNORE_CASE)
    private val label = Regex("(?:緯度|纬度|(?<![A-Za-z])(?:latitude|lat|longitude|lon|lng)(?![A-Za-z])|經度|经度)\\s*[:=]?", RegexOption.IGNORE_CASE)
    private val punctuation = charArrayOf(' ', '\t', ',', ';', ':', '=', '(', ')', '[', ']', '{', '}', '|', '/', '°', '「', '」', '【', '】')

    fun parse(text: String): Parsed {
        require(text.length <= MAX_TEXT_LENGTH) { "文字太長，請分批匯入（最多 $MAX_TEXT_LENGTH 字）。" }
        val points = linkedMapOf<LatLng, Point>()
        val skipped = mutableListOf<Int>()
        var duplicates = 0
        text.lineSequence().forEachIndexed { index, raw ->
            val row = Normalizer.normalize(raw, Normalizer.Form.NFKC).trim().removePrefix("\uFEFF").trim().replace('−', '-')
            if (row.isBlank() || row.startsWith("#")) return@forEachIndexed
            val tokens = numbers.findAll(row).toList()
            val candidates = tokens.zipWithNext().mapNotNull { (a, b) ->
                if (!gap.matches(row.substring(a.range.last + 1, b.range.first))) return@mapNotNull null
                val lat = a.value.toDoubleOrNull() ?: return@mapNotNull null
                val lon = b.value.toDoubleOrNull() ?: return@mapNotNull null
                if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
                Triple(a, b, LatLng(if (lat == 0.0) 0.0 else lat, if (lon == 0.0) 0.0 else lon))
            }
            // Prefer a complete decimal pair to a leading list number separated by spaces.
            val numberedPrefix = tokens.size >= 3 && tokens.first().value.all { it.isDigit() } &&
                row.substring(0, tokens.first().range.first).isBlank()
            val match = candidates.maxByOrNull { (a, b, _) ->
                (if (numberedPrefix && a == tokens.first()) -4 else 0) +
                (if ('.' in a.value || 'e' in a.value.lowercase()) 1 else 0) +
                    (if ('.' in b.value || 'e' in b.value.lowercase()) 1 else 0)
            }
            if (match == null) { skipped += index + 1; return@forEachIndexed }
            val (a, b, position) = match
            val remainder = row.removeRange(a.range.first, b.range.last + 1)
            val name = if (row.contains("https://") || row.contains("http://")) "" else
                label.replace(remainder, "").trim(*punctuation)
            if (points.containsKey(position)) duplicates++
            else points[position] = Point(name.ifBlank { "座標 ${points.size + 1}" }, position, index + 1)
            require(points.size <= MAX_POINTS) { "一次最多匯入一個家與 ${MAX_POINTS - 1} 朵大花。" }
        }
        require(points.size >= 2) { "只找到 ${points.size} 個不同的有效座標，略過 ${skipped.size} 行。請提供一個家與至少一朵大花（緯度 −90～90，經度 −180～180）。" }
        return Parsed(points.values.toList(), duplicates, skipped)
    }
}
