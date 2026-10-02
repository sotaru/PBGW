package app.pikminbloom.gps.ui

import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import app.pikminbloom.gps.BuildConfig
import app.pikminbloom.gps.R
import app.pikminbloom.gps.data.Prefs
import app.pikminbloom.gps.databinding.ActivitySettingsBinding
import app.pikminbloom.gps.route.SpiralRoute
import app.pikminbloom.gps.service.PatrolService
import app.pikminbloom.gps.steps.StepInjector
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/** Hosts [SettingsFragment]; every key here is read back by [Prefs]. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.updatePadding(top = bars.top, left = bars.left, right = bars.right)
            binding.settingsContainer.updatePadding(bottom = bars.bottom, left = bars.left, right = bars.right)
            insets
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }
    }

    /** All tunables from docs/PLAN.md §3.2; numeric values are stored as Strings on purpose. */
    class SettingsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            // Alert / battery / auto-return options live in their own file and are merged in here.
            addPreferencesFromResource(R.xml.preferences_extra)

            numeric(Prefs.KEY_ALERT_GAP_SEC, decimal = false, unit = R.string.fmt_second, fallback = "60")
            numeric(Prefs.KEY_AUTO_RETURN_LAPS, decimal = false, unit = R.string.fmt_lap, fallback = "0")
            numeric(Prefs.KEY_MAX_CADENCE, decimal = false, unit = R.string.fmt_spm, fallback = "0")

            numeric(Prefs.KEY_SPEED_KMH, decimal = true, unit = R.string.fmt_kmh, fallback = "4.7")
            numeric(Prefs.KEY_SPEED_JITTER_PCT, decimal = true, unit = R.string.fmt_percent, fallback = "10")
            numeric(Prefs.KEY_STRIDE_CM, decimal = false, unit = R.string.fmt_cm, fallback = "70")
            numeric(Prefs.KEY_DEFAULT_RADIUS, decimal = true, unit = R.string.fmt_meter, fallback = "30")
            numeric(Prefs.KEY_DEFAULT_DWELL, decimal = false, unit = R.string.fmt_second, fallback = "120")
            numeric(Prefs.KEY_SPIRAL_LINE_WIDTH, decimal = true, unit = R.string.fmt_meter, fallback = "40")
            findPreference<EditTextPreference>(Prefs.KEY_SPIRAL_LINE_WIDTH)?.apply {
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
                    val width = SpiralRoute.parseLineWidth(p.text) ?: SpiralRoute.DEFAULT_LINE_WIDTH_M
                    getString(R.string.pref_spiral_width_summary, width.toString())
                }
                setOnPreferenceChangeListener { _, value ->
                    val valid = SpiralRoute.parseLineWidth(value as? String) != null
                    if (!valid) Toast.makeText(requireContext(), R.string.spiral_width_invalid, Toast.LENGTH_LONG).show()
                    valid
                }
            }
            numeric(Prefs.KEY_STEP_FLUSH_SEC, decimal = false, unit = R.string.fmt_second, fallback = "60")
            numeric(Prefs.KEY_DAILY_STEP_CAP, decimal = false, unit = R.string.fmt_step, fallback = "50000")
            numeric(Prefs.KEY_ACC_MIN, decimal = true, unit = R.string.fmt_meter, fallback = "3")
            numeric(Prefs.KEY_ACC_MAX, decimal = true, unit = R.string.fmt_meter, fallback = "9")
            numeric(Prefs.KEY_ALTITUDE, decimal = true, unit = R.string.fmt_meter, fallback = "20")

            findPreference<SwitchPreferenceCompat>(Prefs.KEY_OVERLAY_ENABLED)
                ?.setOnPreferenceChangeListener { _, value ->
                    onOverlayEnabledChanged(value as? Boolean ?: false)
                    true
                }

            findPreference<Preference>(KEY_DELETE_TODAY)?.setOnPreferenceClickListener {
                confirmDeleteTodaySteps(); true
            }
            findPreference<Preference>(KEY_VERSION)?.summary = BuildConfig.VERSION_NAME
            findPreference<Preference>(KEY_DISCLAIMER)?.setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.dlg_disclaimer_title)
                    .setMessage(R.string.dlg_disclaimer_msg)
                    .setPositiveButton(R.string.action_ok, null)
                    .show()
                true
            }
        }

        /**
         * The switch itself always flips; without "顯示在其他應用程式上層" the bar simply never shows,
         * so send the user to the system screen (HyperOS also needs 後台彈出介面).
         */
        private fun onOverlayEnabledChanged(enabled: Boolean) {
            val context = requireContext()
            if (!enabled) {
                OverlayService.stop(context)
                return
            }
            if (!Permissions.canDrawOverlays(context)) {
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.dlg_overlay_permission_title)
                    .setMessage(R.string.dlg_overlay_permission_msg)
                    .setPositiveButton(R.string.action_open_overlay_settings) { _, _ ->
                        if (!Permissions.openOverlaySettings(context)) {
                            Toast.makeText(context, R.string.toast_no_activity, Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
                return
            }
            // Already patrolling: show it right away instead of waiting for the next start.
            if (PatrolService.isRunning) OverlayService.start(context)
        }

        /** Numeric [EditTextPreference]: right keyboard + a summary that shows the value with its unit. */
        private fun numeric(key: String, decimal: Boolean, @StringRes unit: Int, fallback: String) {
            val pref = findPreference<EditTextPreference>(key) ?: return
            pref.setOnBindEditTextListener { editText ->
                editText.inputType = if (decimal) {
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                } else {
                    InputType.TYPE_CLASS_NUMBER
                }
                editText.setSelection(editText.text?.length ?: 0)
            }
            pref.summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
                getString(unit, p.text?.takeIf { it.isNotBlank() } ?: fallback)
            }
        }

        private fun confirmDeleteTodaySteps() {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.dlg_delete_steps_title)
                .setMessage(R.string.dlg_delete_steps_msg)
                .setPositiveButton(R.string.action_delete) { _, _ -> deleteTodaySteps() }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }

        private fun deleteTodaySteps() {
            val context = requireContext().applicationContext
            lifecycleScope.launch {
                val ok = StepInjector(context).deleteOurRecordsToday()
                Toast.makeText(
                    context,
                    if (ok) R.string.toast_delete_steps_ok else R.string.toast_delete_steps_failed,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

        companion object {
            private const val KEY_DELETE_TODAY = "delete_today_steps"
            private const val KEY_VERSION = "app_version"
            private const val KEY_DISCLAIMER = "disclaimer"
        }
    }
}
