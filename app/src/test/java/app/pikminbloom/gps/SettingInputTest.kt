package app.pikminbloom.gps

import app.pikminbloom.gps.ui.SettingInput
import org.junit.Assert.*
import org.junit.Test

class SettingInputTest {
    @Test fun rejectsNonFiniteAndOutOfRangeValues() {
        val spec = SettingInput.specs.getValue("speed_kmh")
        listOf(null, "", "NaN", "Infinity", "1e999", "0", "21").forEach { assertFalse(spec.accepts(it)) }
        assertTrue(spec.accepts("1.08"))
        assertTrue(spec.accepts(" 20 "))
    }

    @Test fun integerFieldsRejectFractionsAndAllowZeroWhereSupported() {
        val cap = SettingInput.specs.getValue("daily_step_cap")
        assertFalse(cap.accepts("0.5"))
        assertTrue(cap.accepts("0"))
        assertTrue(cap.accepts("200000"))
        assertFalse(cap.accepts("200001"))
    }

    @Test fun altitudeAcceptsNegativeAndDecimalValues() {
        val spec = SettingInput.specs.getValue("altitude_m")
        assertTrue(spec.accepts("-100"))
        assertTrue(spec.accepts("-15.5"))
        assertFalse(spec.accepts("-101"))
    }
}
