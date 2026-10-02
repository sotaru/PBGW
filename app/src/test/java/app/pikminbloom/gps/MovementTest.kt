package app.pikminbloom.gps

import app.pikminbloom.gps.data.PatrolConfig
import app.pikminbloom.gps.data.PatrolMode
import app.pikminbloom.gps.data.PatrolPhase
import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.geo.LatLng
import app.pikminbloom.gps.route.SpiralRoute
import app.pikminbloom.gps.service.PatrolCheckpoint
import app.pikminbloom.gps.sim.WalkSimulator
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class MovementTest {
    private val center = LatLng(25.0330, 121.5654)

    @Test fun spiralStartsAtCurrentPositionAndGrowsAcrossChunks() {
        val route = SpiralRoute(center)
        var previous = center
        var radius = 0.0
        repeat(30) {
            val plan = route.nextPlan(previous)
            assertEquals(SpiralRoute.CHUNK_SEGMENTS, plan.segments.size)
            assertEquals(previous, plan.start)
            plan.segments.forEach { segment ->
                assertEquals(previous, segment.from)
                val nextRadius = GeoMath.distanceM(center, segment.to)
                assertTrue(nextRadius > radius)
                assertTrue(segment.lengthM > 0 && segment.lengthM < 6.0)
                assertNull(segment.waypointIndex)
                assertFalse(segment.arrivalAtEnd)
                previous = segment.to
                radius = nextRadius
            }
        }
        assertTrue(radius > 400)
        assertEquals(3 * route.spacingM, GeoMath.distanceM(center, route.pointAt(6 * Math.PI)), 0.001)
    }

    @Test fun selectedLineWidthControlsClearanceBetweenTurns() {
        for (width in listOf(1.0, 10.0, 40.0, 1000.0)) {
            val route = SpiralRoute(center, lineWidthM = width)
            assertTrue(route.spacingM >= width + 3.0)
            // Compare points around the next full winding, including nearby angles rather than
            // testing only the convenient same-bearing pair. Contiguous joins are naturally shared.
            for (turn in 1..4) {
                for (part in 0..12) {
                    val angle = turn * 2.0 * Math.PI + part * Math.PI / 6.0
                    val p = route.pointAt(angle)
                    val minGap = (-30..30).minOf { offset ->
                        GeoMath.distanceM(p, route.pointAt(angle + 2.0 * Math.PI + offset / 100.0))
                    }
                    assertTrue("width=$width gap=$minGap", minGap > width + 2.0)
                }
            }
        }
        val narrow = SpiralRoute(center, lineWidthM = 10.0)
        val wide = SpiralRoute(center, lineWidthM = 80.0)
        assertTrue(GeoMath.distanceM(center, wide.pointAt(2 * Math.PI)) > GeoMath.distanceM(center, narrow.pointAt(2 * Math.PI)))
    }

    @Test fun spiralChunksDoNotResetToCenterOrCreateWaypoints() {
        val route = SpiralRoute(center)
        val sim = WalkSimulator(PatrolConfig(speedJitterPct = 0.0), Random(7))
        var total = 0.0
        repeat(4) {
            val from = if (it == 0) center else sim.exactCurrentPosition()
            val plan = route.nextPlan(from)
            sim.load(plan)
            var distance = 0.0
            while (!sim.finished) {
                val sample = sim.advance(1.0)
                assertNull(sample.arrivedAtWaypoint)
                distance += sample.distanceDeltaM
            }
            assertEquals(plan.totalLengthM, distance, 0.001)
            assertEquals(plan.end, sim.exactCurrentPosition())
            total += distance
        }
        assertTrue(total > 1500)
    }

    @Test fun teleportClearsOldRouteWithoutCountingDistanceOrArrivals() {
        val sim = WalkSimulator(PatrolConfig(), Random(1))
        sim.load(SpiralRoute(center).nextPlan())
        repeat(10) { sim.advance(1.0) }
        val destination = LatLng(35.6812, 139.7671)
        sim.relocate(destination)
        assertEquals(destination, sim.current().position)
        assertEquals(0.0, sim.current().distanceDeltaM, 0.0)
        assertEquals(0.0, sim.current().progressM, 0.0)
        assertNull(sim.current().arrivedAtWaypoint)
        assertTrue(sim.finished)
        assertFalse(sim.inManual)
        repeat(10) { assertEquals(0.0, sim.advance(1.0).distanceDeltaM, 0.0) }
    }

    @Test fun coordinateInputRejectsInvalidAndExtraValues() {
        assertEquals(center, LatLng.parse("25.0330, 121.5654"))
        assertEquals(center, LatLng.parse(" 25.0330，121.5654 "))
        assertEquals(center, LatLng.parse("25.0330\n121.5654"))
        listOf("", "25", "25,121,7", "NaN,121", "Infinity,121", "91,121", "25,181").forEach {
            assertNull(it, LatLng.parse(it))
        }
    }

    private fun checkpoint(mode: PatrolMode, home: LatLng? = center) = PatrolCheckpoint(
        savedAtMs = 100, startedAtMs = 50, home = home, position = center,
        routeId = "route", lap = 0, targetWaypointIndex = 0, phase = PatrolPhase.PAUSED,
        distanceWalkedM = 100.0, stepsAccrued = 140.0, stepsFlushed = 100,
        flushWindowStartMs = 90, distanceSinceFlush = 30.0, stepsWrittenToday = 100,
        lapsCompleted = 0, mode = mode, spiralCenter = center, spiralAngleRad = 12.0,
    )

    @Test fun checkpointPreservesSpiralAndHomeIndependently() {
        val original = checkpoint(PatrolMode.SPIRAL)
        assertEquals(original, PatrolCheckpoint.fromJson(original.toJson()))
        val noHome = checkpoint(PatrolMode.HOLD, null)
        assertEquals(noHome, PatrolCheckpoint.fromJson(noHome.toJson()))
    }

    @Test fun oldCheckpointStillLoadsAsWaypointPatrol() {
        val json = JSONObject(checkpoint(PatrolMode.WAYPOINTS).toJson())
        listOf("mode", "spiralLat", "spiralLon", "spiralAngleRad", "spiralSpacingM").forEach(json::remove)
        assertEquals(PatrolMode.WAYPOINTS, PatrolCheckpoint.fromJson(json.toString())!!.mode)
    }

    @Test fun spiralResumesFromCheckpointRadiusInsteadOfRestarting() {
        val route = SpiralRoute(center)
        repeat(3) { route.nextPlan() }
        val here = route.pointAt(route.angleRad - 0.25)
        val resumed = SpiralRoute(center, angleRad = route.angleAt(here))
        assertEquals(route.angleRad - 0.25, resumed.angleRad, 1e-6)
        val next = resumed.nextPlan(here)
        assertEquals(here, next.start)
        assertTrue(GeoMath.distanceM(center, next.end!!) > GeoMath.distanceM(center, here))
    }
}
