package app.pikminbloom.gps.ui

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import app.pikminbloom.gps.R
import app.pikminbloom.gps.data.Prefs
import app.pikminbloom.gps.steps.TimedStepCounter
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object RealGpsStepsDialog {
    fun show(context: Context, prefs: Prefs, onStart: (Long, Int) -> Unit) {
        val target = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            setText(prefs.sp.getLong("real_steps_target", 1000).toString())
        }
        val rate = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            setText(prefs.sp.getInt("real_steps_rate", 100).toString())
        }
        val padding = context.resources.getDimensionPixelSize(R.dimen.space_xl)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(TextView(context).apply { setText(R.string.real_steps_explanation) })
            addView(TextView(context).apply {
                text = context.getString(R.string.real_steps_jitter_help, prefs.config().speedJitterPct)
            })
            addView(TextView(context).apply { setText(R.string.real_steps_target) })
            addView(target)
            addView(TextView(context).apply { setText(R.string.real_steps_rate) })
            addView(rate)
        }
        val dialog = MaterialAlertDialogBuilder(context).setTitle(R.string.real_steps_title).setView(content)
            .setPositiveButton(R.string.real_steps_start, null).setNegativeButton(R.string.action_cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val goal = target.text.toString().trim().toLongOrNull()
                val pace = rate.text.toString().trim().toIntOrNull()
                when {
                    goal == null || goal !in 1..TimedStepCounter.MAX_TARGET -> target.error = context.getString(R.string.real_steps_invalid_target)
                    pace == null || pace !in 1..TimedStepCounter.MAX_RATE -> rate.error = context.getString(R.string.real_steps_invalid_rate)
                    prefs.config().maxCadenceSpm > 0 && pace > prefs.config().maxCadenceSpm ->
                        rate.error = context.getString(R.string.real_steps_cadence_limit, prefs.config().maxCadenceSpm)
                    else -> {
                        prefs.sp.edit().putLong("real_steps_target", goal).putInt("real_steps_rate", pace).apply()
                        dialog.dismiss()
                        onStart(goal, pace)
                    }
                }
            }
        }
        dialog.show()
    }
}
