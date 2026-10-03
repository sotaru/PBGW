package app.pikminbloom.gps.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import app.pikminbloom.gps.R
import app.pikminbloom.gps.databinding.ActivitySetupBinding
import app.pikminbloom.gps.databinding.ItemSetupRowBinding
import app.pikminbloom.gps.mock.MockLocationController
import app.pikminbloom.gps.steps.StepInjector
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.launch

/**
 * One-time setup checklist. Every row re-evaluates itself in [onResume], because most fixes happen
 * in a system settings screen we cannot observe.
 */
class SetupActivity : AppCompatActivity() {

    private enum class Status { OK, TODO, MANUAL }

    private lateinit var binding: ActivitySetupBinding
    private lateinit var mock: MockLocationController
    private lateinit var steps: StepInjector

    private lateinit var locationLauncher: ActivityResultLauncher<String>
    private lateinit var notificationLauncher: ActivityResultLauncher<String>
    private lateinit var healthLauncher: ActivityResultLauncher<Set<String>>

    private var healthGranted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mock = MockLocationController(this)
        steps = StepInjector(this)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnDone.setOnClickListener { finish() }

        locationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
        notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
        healthLauncher = registerForActivityResult(steps.permissionContract()) { granted ->
            healthGranted = granted.containsAll(steps.requiredPermissions)
            refresh()
        }

        val basePadding = binding.scroll.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.updatePadding(top = bars.top, left = bars.left, right = bars.right)
            binding.scroll.updatePadding(bottom = basePadding + bars.bottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ------------------------------------------------------------------ checklist

    private fun refresh() {
        bindRow(
            row = binding.rowLocation,
            title = R.string.setup_location_title,
            desc = R.string.setup_location_desc,
            status = if (Permissions.hasFineLocation(this)) Status.OK else Status.TODO,
            primaryLabel = if (Permissions.hasFineLocation(this)) null else R.string.action_grant,
            primaryAction = { locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
            secondaryLabel = R.string.action_open_settings_app_info,
            secondaryAction = { Permissions.openAppDetails(this) },
        )

        val notificationsOk = Permissions.hasNotifications(this)
        bindRow(
            row = binding.rowNotification,
            title = R.string.setup_notification_title,
            desc = R.string.setup_notification_desc,
            status = if (notificationsOk) Status.OK else Status.TODO,
            primaryLabel = if (notificationsOk) null else R.string.action_grant,
            primaryAction = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(POST_NOTIFICATIONS)
                }
            },
        )

        bindRow(
            row = binding.rowMock,
            title = R.string.setup_mock_title,
            desc = R.string.setup_mock_desc,
            status = if (mock.isMockAppSelected()) Status.OK else Status.TODO,
            primaryLabel = R.string.action_open_developer_options,
            primaryAction = { mock.openMockAppPicker(this) },
        )

        bindHealthRow(healthGranted)
        lifecycleScope.launch {
            healthGranted = steps.isAvailable && steps.hasPermissions()
            bindHealthRow(healthGranted)
        }

        bindRow(
            row = binding.rowBattery,
            title = R.string.setup_battery_title,
            desc = R.string.setup_battery_desc,
            status = if (Permissions.isIgnoringBatteryOptimizations(this)) Status.OK else Status.TODO,
            primaryLabel = R.string.action_exclude_battery,
            primaryAction = {
                if (!Permissions.requestIgnoreBatteryOptimizations(this)) {
                    toast(R.string.toast_no_activity)
                }
            },
            secondaryLabel = R.string.action_open_settings_app_info,
            secondaryAction = { Permissions.openAppDetails(this) },
        )

        bindRow(
            row = binding.rowOverlay,
            title = R.string.setup_overlay_title,
            desc = R.string.setup_overlay_desc,
            status = if (Permissions.canDrawOverlays(this)) Status.OK else Status.TODO,
            hint = R.string.setup_overlay_hint,
            primaryLabel = R.string.action_open_overlay_settings,
            primaryAction = {
                if (!Permissions.openOverlaySettings(this)) toast(R.string.toast_no_activity)
            },
            secondaryLabel = R.string.action_open_settings_app_info,
            secondaryAction = { Permissions.openAppDetails(this) },
        )

        bindRow(
            row = binding.rowGame,
            title = R.string.setup_game_title,
            desc = R.string.setup_game_desc,
            status = Status.MANUAL,
            primaryLabel = R.string.action_open_pikmin,
            primaryAction = {
                if (!Permissions.openApp(this, PIKMIN_PACKAGE)) toast(R.string.toast_pikmin_not_installed)
            },
            secondaryLabel = R.string.action_pikmin_app_info,
            secondaryAction = {
                if (!Permissions.openAppDetails(this, PIKMIN_PACKAGE)) toast(R.string.toast_pikmin_not_installed)
            },
        )
    }

    private fun bindHealthRow(granted: Boolean) {
        val available = steps.isAvailable
        val status = when {
            available && granted -> Status.OK
            else -> Status.TODO
        }
        val statusOverride = when {
            !available && steps.needsProviderUpdate -> getString(R.string.setup_health_update)
            !available -> getString(R.string.setup_health_unavailable)
            else -> null
        }
        bindRow(
            row = binding.rowHealth,
            title = R.string.setup_health_title,
            desc = R.string.setup_health_desc,
            status = status,
            statusText = statusOverride,
            hint = R.string.setup_health_hint,
            primaryLabel = if (available && !granted) R.string.action_grant else null,
            primaryAction = { healthLauncher.launch(steps.requiredPermissions) },
            secondaryLabel = R.string.action_open_health_connect,
            secondaryAction = { steps.openHealthConnectSettings(this) },
        )
    }

    // ------------------------------------------------------------------ row rendering

    private fun bindRow(
        row: ItemSetupRowBinding,
        @StringRes title: Int,
        @StringRes desc: Int,
        status: Status,
        statusText: String? = null,
        @StringRes hint: Int? = null,
        @StringRes primaryLabel: Int? = null,
        primaryAction: (() -> Unit)? = null,
        @StringRes secondaryLabel: Int? = null,
        secondaryAction: (() -> Unit)? = null,
    ) {
        row.title.setText(title)
        row.desc.setText(desc)
        row.status.text = statusText ?: getString(
            when (status) {
                Status.OK -> R.string.setup_ok
                Status.TODO -> R.string.setup_todo
                Status.MANUAL -> R.string.setup_manual
            }
        )
        row.icon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        row.icon.setImageResource(iconFor(status))
        row.icon.setColorFilter(colorFor(status))
        row.status.setTextColor(colorFor(status))

        if (hint == null) {
            row.hint.visibility = View.GONE
        } else {
            row.hint.visibility = View.VISIBLE
            row.hint.setText(hint)
        }

        bindButton(row.btnPrimary, primaryLabel, primaryAction)
        bindButton(row.btnSecondary, secondaryLabel, secondaryAction)
    }

    private fun bindButton(
        button: com.google.android.material.button.MaterialButton,
        @StringRes label: Int?,
        action: (() -> Unit)?,
    ) {
        if (label == null || action == null) {
            button.visibility = View.GONE
            button.setOnClickListener(null)
        } else {
            button.visibility = View.VISIBLE
            button.setText(label)
            button.setOnClickListener { action() }
        }
    }

    @DrawableRes
    private fun iconFor(status: Status): Int = when (status) {
        Status.OK -> R.drawable.ic_check
        Status.TODO -> R.drawable.ic_warning
        Status.MANUAL -> R.drawable.ic_flag
    }

    private fun colorFor(status: Status): Int = MaterialColors.getColor(
        binding.root,
        when (status) {
            Status.OK -> androidx.appcompat.R.attr.colorPrimary
            Status.TODO -> androidx.appcompat.R.attr.colorPrimary
            Status.MANUAL -> com.google.android.material.R.attr.colorOnSurfaceVariant
        },
    )

    private fun toast(@StringRes text: Int) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        private const val PIKMIN_PACKAGE = "com.nianticlabs.pikmin"
        private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    }
}
