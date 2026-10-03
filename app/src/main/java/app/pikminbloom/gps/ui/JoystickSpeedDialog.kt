package app.pikminbloom.gps.ui

import android.content.Context
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import app.pikminbloom.gps.R
import app.pikminbloom.gps.data.JoystickSpeeds
import app.pikminbloom.gps.data.Prefs
import app.pikminbloom.gps.data.TravelMode
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** The same explicit speed choice from the main screen and from the floating controls. */
object JoystickSpeedDialog {
    fun show(
        context: Context,
        overlay: Boolean = false,
        onDismiss: () -> Unit = {},
        onSelected: (TravelMode?) -> Unit,
    ): AlertDialog {
        val themed = if (overlay) ContextThemeWrapper(context, R.style.Theme_PikminGps) else context
        val choices = JoystickSpeeds.choices
        val labels = choices.map { mode ->
            if (mode == null) {
                themed.getString(R.string.travel_walk_configured, "%.1f".format(Prefs(context).config().speedMps * 3.6))
            } else {
                themed.getString(if (mode.countsSteps) R.string.travel_item else R.string.travel_item_no_steps,
                    mode.label, mode.speedKmh.toInt())
            }
        }.toTypedArray()
        val dialog = MaterialAlertDialogBuilder(themed)
            .setTitle(R.string.dlg_joystick_speed_title)
            .setItems(labels) { _, index -> onSelected(choices[index]) }
            .setNegativeButton(R.string.action_cancel, null)
            .setOnDismissListener { onDismiss() }
            .create()
        if (overlay) dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
        return dialog
    }
}
