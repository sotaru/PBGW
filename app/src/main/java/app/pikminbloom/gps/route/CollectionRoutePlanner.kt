package app.pikminbloom.gps.route

import app.pikminbloom.gps.data.LoopMode
import app.pikminbloom.gps.data.PatrolConfig
import app.pikminbloom.gps.data.ReturnMode
import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.geo.LatLng

/** Optimizes a closed tour of point centres. It does not calculate a road or footpath route. */
object CollectionRoutePlanner {
    const val EXACT_LIMIT = 14
    data class Plan(val order: List<Int>, val distanceM: Double, val originalDistanceM: Double, val exact: Boolean)

    fun plan(home: LatLng, flowers: List<LatLng>): Plan {
        require(flowers.isNotEmpty() && flowers.size < CoordinatePasteParser.MAX_POINTS)
        val points = listOf(home) + flowers
        val d = Array(points.size) { i -> DoubleArray(points.size) { j -> GeoMath.distanceM(points[i], points[j]) } }
        fun length(order: List<Int>): Double {
            var last = 0
            var sum = 0.0
            for (i in order) { sum += d[last][i + 1]; last = i + 1 }
            return sum + d[last][0]
        }
        val original = flowers.indices.toList()
        val exact = flowers.size <= EXACT_LIMIT
        val order = if (exact) exactOrder(d) else {
            var best = improve(original, d)
            // A bounded set of starting flowers keeps a 300-point paste responsive.
            val starts = (listOf(0) + flowers.indices.sortedBy { d[0][it + 1] }.take(7)).distinct()
            for (start in starts) {
                val remaining = flowers.indices.toMutableSet()
                val tour = mutableListOf<Int>()
                var next = start
                while (remaining.isNotEmpty()) {
                    tour.add(next); remaining.remove(next)
                    if (remaining.isNotEmpty()) next = remaining.minWith(compareBy<Int> { d[next + 1][it + 1] }.thenBy { it })
                }
                val candidate = improve(tour, d)
                if (length(candidate) < length(best)) best = candidate
            }
            best
        }
        return Plan(order, length(order), length(original), exact)
    }

    private fun exactOrder(d: Array<DoubleArray>): List<Int> {
        val n = d.size - 1
        val size = 1 shl n
        val costs = DoubleArray(size * n) { Double.POSITIVE_INFINITY }
        val parents = IntArray(size * n) { -1 }
        for (i in 0 until n) costs[(1 shl i) * n + i] = d[0][i + 1]
        for (mask in 1 until size) for (last in 0 until n) {
            if (mask and (1 shl last) == 0) continue
            val prevMask = mask xor (1 shl last)
            if (prevMask == 0) continue
            val slot = mask * n + last
            for (prev in 0 until n) if (prevMask and (1 shl prev) != 0) {
                val candidate = costs[prevMask * n + prev] + d[prev + 1][last + 1]
                if (candidate < costs[slot]) { costs[slot] = candidate; parents[slot] = prev }
            }
        }
        var mask = size - 1
        var last = (0 until n).minBy { costs[mask * n + it] + d[it + 1][0] }
        val reverse = mutableListOf<Int>()
        while (last >= 0) {
            reverse.add(last)
            val prev = parents[mask * n + last]
            mask = mask xor (1 shl last)
            last = prev
        }
        return reverse.asReversed()
    }

    private fun improve(input: List<Int>, d: Array<DoubleArray>): List<Int> {
        val order = input.toMutableList()
        // 2-opt removes crossing legs; each accepted reversal strictly reduces the closed tour.
        repeat(30) {
            var changed = false
            for (i in 0 until order.lastIndex) for (j in i + 1 until order.size) {
                val a = if (i == 0) 0 else order[i - 1] + 1
                val b = order[i] + 1
                val c = order[j] + 1
                val e = if (j == order.lastIndex) 0 else order[j + 1] + 1
                if (d[a][c] + d[b][e] + 1e-7 < d[a][b] + d[c][e]) {
                    order.subList(i, j + 1).reverse(); changed = true
                }
            }
            if (!changed) return order
        }
        return order
    }
}

/** Route-local overrides leave the user's saved general patrol settings intact. */
fun PatrolConfig.forCollection(collectOnce: Boolean): PatrolConfig = if (!collectOnce) this else copy(
    loopMode = LoopMode.ONCE, orbitAtWaypoints = false, autoReturnAfterLaps = 1, returnMode = ReturnMode.WALK,
)
