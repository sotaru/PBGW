package app.pikminbloom.gps

import app.pikminbloom.gps.route.SpiralRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpiralWidthTest {
    @Test fun acceptsBoundsAndDecimals() {
        assertEquals(1.0, SpiralRoute.parseLineWidth("1")!!, 0.0)
        assertEquals(1000.0, SpiralRoute.parseLineWidth("1000")!!, 0.0)
        assertEquals(25.5, SpiralRoute.parseLineWidth(" 25.5 ")!!, 0.0)
    }

    @Test fun rejectsInvalidSettings() {
        listOf(null, "", "abc", "NaN", "Infinity", "-Infinity", "0", "-1", "0.99", "1000.01").forEach {
            assertNull("Invalid width: $it", SpiralRoute.parseLineWidth(it))
        }
    }

    @Test fun usesSavedDefaultForEachNewRun() {
        repeat(3) { assertEquals(25.5, SpiralRoute.resolveLineWidth(null, 25.5), 0.0) }
    }

    @Test fun temporaryChoiceDoesNotReplaceDefault() {
        val savedDefault = 25.5
        assertEquals(10.0, SpiralRoute.resolveLineWidth(10.0, savedDefault), 0.0)
        assertEquals(savedDefault, SpiralRoute.resolveLineWidth(null, savedDefault), 0.0)
        assertEquals(13.0, SpiralRoute.spacingForWidth(10.0), 0.0)
    }

    @Test fun invalidRequestAndDefaultFallBackSafely() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, 1001.0).forEach {
            assertEquals(25.5, SpiralRoute.resolveLineWidth(it, 25.5), 0.0)
            assertEquals(40.0, SpiralRoute.resolveLineWidth(null, it), 0.0)
        }
    }
}
