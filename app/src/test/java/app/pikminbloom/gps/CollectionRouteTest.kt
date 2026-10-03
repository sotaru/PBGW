package app.pikminbloom.gps

import app.pikminbloom.gps.data.LoopMode
import app.pikminbloom.gps.data.PatrolConfig
import app.pikminbloom.gps.data.ReturnMode
import app.pikminbloom.gps.data.Waypoint
import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.geo.LatLng
import app.pikminbloom.gps.route.CollectionRoutePlanner
import app.pikminbloom.gps.route.CoordinatePasteParser
import app.pikminbloom.gps.route.PatrolPlanner
import app.pikminbloom.gps.route.SegmentKind
import app.pikminbloom.gps.route.forCollection
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class CollectionRouteTest {
    @Test fun namedRowsSeparatorsCommentsAndDuplicates() {
        val parsed = CoordinatePasteParser.parse("\uFEFF家,25.033,121.565\r\n\n# 備註\n大花 1，25.034，121.566\n(25.035;121.567) 大花 B\n25.034\t121.566\n25.036 121.568")
        assertEquals(4, parsed.points.size)
        assertEquals(1, parsed.duplicates)
        assertEquals("家", parsed.points[0].name)
        assertEquals("大花 1", parsed.points[1].name)
        assertEquals("大花 B", parsed.points[2].name)
        assertEquals(4, parsed.points[1].line)
    }

    @Test fun malformedRowsAreSkippedAndReported() {
        for (row in listOf("沒有座標", "91,121", "25,181", "NaN,121", "1e999,121", "25")) {
            val parsed = CoordinatePasteParser.parse("25,121\n$row\n26,122")
            assertEquals(listOf(2), parsed.skippedLines)
            assertEquals(listOf(1, 3), parsed.points.map { it.line })
        }
    }

    @Test fun findsCoordinatesInsideMixedTextAndFullWidthSymbols() {
        val parsed = CoordinatePasteParser.parse("今天採花清單\n家【２５．０３３０，１２１．５６５４】\n大花 A：緯度:25.0348, 經度:121.5680\n大花 B (25.0356 / 121.5620) 已確認\n備註：不用匯入")
        assertEquals(listOf(1, 5), parsed.skippedLines)
        assertEquals(3, parsed.points.size)
        assertEquals(LatLng(25.0330, 121.5654), parsed.points[0].position)
        assertEquals("家", parsed.points[0].name)
        assertEquals(LatLng(25.0348, 121.5680), parsed.points[1].position)
        assertEquals(LatLng(25.0356, 121.5620), parsed.points[2].position)
    }

    @Test fun numberedRowsPreferFullDecimalPair() {
        val parsed = CoordinatePasteParser.parse("1 25.0330 121.5654\n2 25.0348 121.5680")
        assertEquals(LatLng(25.0330, 121.5654), parsed.points[0].position)
        assertEquals(LatLng(25.0348, 121.5680), parsed.points[1].position)
    }

    @Test fun numberedIntegerCoordinatesAndNamesContainingLatArePreserved() {
        val parsed = CoordinatePasteParser.parse("1 25 121\nChocolate,26,122")
        assertEquals(LatLng(25.0, 121.0), parsed.points[0].position)
        assertEquals("Chocolate", parsed.points[1].name)
    }

    @Test fun findsPairAfterAnInvalidCandidateAndUsesOnePerLine() {
        val parsed = CoordinatePasteParser.parse("無效 91,181；家 25.033,121.565；另一組 26,122\n大花 25.034,121.566")
        assertEquals(2, parsed.points.size)
        assertEquals(LatLng(25.033, 121.565), parsed.points[0].position)
    }

    @Test fun coordinateUrlsAndLabelsWorkWithoutTreatingDatesOrIdsAsLocations() {
        val parsed = CoordinatePasteParser.parse("日期 2026-10-03\nID ABC25.033,121.565\nhttps://maps.google.com/?q=25.033,121.565\nlatitude: -25.034; longitude: -121.566")
        assertEquals(listOf(1, 2), parsed.skippedLines)
        assertEquals("座標 1", parsed.points[0].name)
        assertEquals(LatLng(-25.034, -121.566), parsed.points[1].position)
    }

    @Test fun skipsBlankAndCommentLinesWithoutCountingThemAsErrors() {
        val parsed = CoordinatePasteParser.parse("\n# 備註 27,123\n25,121\n標題\n26,122\n25,121")
        assertEquals(listOf(4), parsed.skippedLines)
        assertEquals(1, parsed.duplicates)
        assertEquals(2, parsed.points.size)
    }

    @Test fun noValidPairStillGivesUsefulErrorAndLimitsApplyAfterSkipping() {
        val error = runCatching { CoordinatePasteParser.parse("標題\n91,181\n25") }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("略過 3 行"))
        val rows = (0..300).joinToString("\n") { "備註\n位置 25,${120.0 + it / 10000.0}" }
        val parsed = CoordinatePasteParser.parse(rows)
        assertEquals(301, parsed.points.size)
        assertEquals(301, parsed.skippedLines.size)
    }

    @Test fun requiresHomeAndDistinctFlower() {
        for (text in listOf("", "25,121", "25,121\n25,121")) {
            assertTrue(runCatching { CoordinatePasteParser.parse(text) }.isFailure)
        }
        assertEquals(2, CoordinatePasteParser.parse("90,180\n-90,-180").points.size)
    }

    @Test fun signedZeroIsOneLocation() {
        val parsed = CoordinatePasteParser.parse("-0,-0\n0,0\n1e-3,1e-3")
        assertEquals(1, parsed.duplicates)
        assertEquals(2, parsed.points.size)
    }

    @Test fun enforcesPointAndTextLimits() {
        val rows = (0..300).joinToString("\n") { "25,${120.0 + it / 10000.0}" }
        assertEquals(301, CoordinatePasteParser.parse(rows).points.size)
        assertTrue(runCatching { CoordinatePasteParser.parse(rows + "\n26,122") }.isFailure)
        assertTrue(runCatching { CoordinatePasteParser.parse(" ".repeat(100001)) }.isFailure)
    }

    private val home = LatLng(25.033, 121.565)
    private fun length(points: List<LatLng>, order: List<Int>): Double {
        val tour = listOf(home) + order.map { points[it] } + home
        return tour.zipWithNext().sumOf { (a, b) -> GeoMath.distanceM(a, b) }
    }
    private fun permutations(input: List<Int>): Sequence<List<Int>> = sequence {
        if (input.isEmpty()) yield(emptyList())
        else for (first in input) for (tail in permutations(input - first)) yield(listOf(first) + tail)
    }

    @Test fun exactMatchesIndependentBruteForceTours() {
        val random = Random(420)
        repeat(8) {
            val points = List(6) { LatLng(home.lat + random.nextDouble(-0.02, 0.02), home.lon + random.nextDouble(-0.02, 0.02)) }
            val plan = CollectionRoutePlanner.plan(home, points)
            val optimum = permutations(points.indices.toList()).minOf { length(points, it) }
            assertEquals(optimum, plan.distanceM, 1e-6)
            assertEquals(points.indices.toSet(), plan.order.toSet())
            assertEquals(points.size, plan.order.size)
            assertTrue(plan.exact)
        }
    }

    @Test fun closedTourBeatsCrossingPasteOrder() {
        val points = listOf(LatLng(25.04, 121.56), LatLng(25.03, 121.58), LatLng(25.04, 121.58), LatLng(25.03, 121.56))
        val plan = CollectionRoutePlanner.plan(home, points)
        assertTrue(plan.distanceM + 1 < plan.originalDistanceM)
        assertEquals(length(points, plan.order), plan.distanceM, 1e-6)
    }

    @Test fun singleFlowerIncludesReturnLegAndExactThreshold() {
        val point = LatLng(25.04, 121.57)
        val plan = CollectionRoutePlanner.plan(home, listOf(point))
        assertEquals(listOf(0), plan.order)
        assertEquals(2 * GeoMath.distanceM(home, point), plan.distanceM, 1e-6)
        val points = List(15) { LatLng(25.0 + it * 0.001, 121.5) }
        assertTrue(CollectionRoutePlanner.plan(home, points.take(14)).exact)
        assertFalse(CollectionRoutePlanner.plan(home, points).exact)
    }

    @Test fun largeTourPreservesEveryPointAndNeverWorsensPasteOrder() {
        val random = Random(51)
        val points = List(300) { LatLng(25.0 + random.nextDouble(), 121.0 + random.nextDouble()) }
        val plan = CollectionRoutePlanner.plan(home, points)
        assertFalse(plan.exact)
        assertEquals(300, plan.order.size)
        assertEquals(points.indices.toSet(), plan.order.toSet())
        assertTrue(plan.distanceM <= plan.originalDistanceM)
        assertEquals(plan.order, CollectionRoutePlanner.plan(home, points).order)
    }

    @Test fun collectionOverridesOnlyEndAndDwellSettings() {
        val original = PatrolConfig(loopMode = LoopMode.PINGPONG, orbitAtWaypoints = true,
            autoReturnAfterLaps = 5, returnMode = ReturnMode.TELEPORT, speedMps = 2.1, speedJitterPct = 32.0,
            injectSteps = false, dailyStepCap = 1234)
        val collection = original.forCollection(true)
        assertEquals(LoopMode.ONCE, collection.loopMode)
        assertFalse(collection.orbitAtWaypoints)
        assertEquals(1, collection.autoReturnAfterLaps)
        assertEquals(ReturnMode.WALK, collection.returnMode)
        assertEquals(original.speedMps, collection.speedMps, 0.0)
        assertEquals(original.speedJitterPct, collection.speedJitterPct, 0.0)
        assertEquals(original.injectSteps, collection.injectSteps)
        assertEquals(original.dailyStepCap, collection.dailyStepCap)
        assertSame(original, original.forCollection(false))
    }

    @Test fun collectionPlanVisitsOnceWithoutOrbits() {
        val flowers = listOf(Waypoint("a", "A", 25.04, 121.56), Waypoint("b", "B", 25.04, 121.58))
        val config = PatrolConfig(orbitAtWaypoints = true).forCollection(true)
        val plan = PatrolPlanner.planLap(home, flowers, config, PatrolPlanner.orderFor(0, flowers.size, config.loopMode))
        assertTrue(plan.segments.all { it.kind == SegmentKind.TRAVEL })
        assertEquals(listOf(0, 1), plan.segments.filter { it.arrivalAtEnd }.map { it.waypointIndex })
        assertTrue(PatrolPlanner.orderFor(1, flowers.size, config.loopMode).isEmpty())
    }
}
