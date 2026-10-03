package app.pikminbloom.gps.ui

/** Mirrors the supported ranges in Prefs, so a displayed value is also the value that is used. */
object SettingInput {
    data class Spec(val min: Double, val max: Double, val integer: Boolean = false) {
        fun accepts(text: String?): Boolean {
            val value = text?.trim()?.toDoubleOrNull() ?: return false
            return value.isFinite() && value in min..max && (!integer || value % 1.0 == 0.0)
        }
    }

    val specs = mapOf(
        "speed_kmh" to Spec(1.08, 20.0), "speed_jitter_pct" to Spec(0.0, 30.0),
        "stride_cm" to Spec(40.0, 120.0, true), "default_radius_m" to Spec(8.0, 40.0),
        "default_dwell_sec" to Spec(0.0, 1800.0, true), "spiral_line_width_m" to Spec(1.0, 1000.0),
        "step_flush_sec" to Spec(20.0, 600.0, true), "daily_step_cap" to Spec(0.0, 200000.0, true),
        "accuracy_min_m" to Spec(1.0, 30.0), "accuracy_max_m" to Spec(1.0, 50.0),
        "altitude_m" to Spec(-100.0, 4000.0), "alert_gap_sec" to Spec(0.0, 3600.0, true),
        "auto_return_after_laps" to Spec(0.0, 999.0, true), "max_cadence_spm" to Spec(0.0, 1000.0, true),
    )
}
