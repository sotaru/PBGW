package app.pikminbloom.gps.ui

import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.pikminbloom.gps.R
import app.pikminbloom.gps.geo.LatLng
import app.pikminbloom.gps.route.CollectionRoutePlanner
import app.pikminbloom.gps.route.CoordinatePasteParser
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Nothing is saved until the final route preview is accepted. */
object CoordinatePasteDialog {
    fun show(activity: AppCompatActivity, onApply: (String, LatLng, List<CoordinatePasteParser.Point>) -> Boolean) {
        val padding = activity.resources.getDimensionPixelSize(R.dimen.space_xl)
        fun column() = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, padding / 2)
        }
        fun scroll(view: LinearLayout) = ScrollView(activity).apply { addView(view) }
        fun label(id: Int) = TextView(activity).apply { setText(id) }
        val name = EditText(activity).apply {
            setSingleLine(); setHint(R.string.route_name_hint); setText(activity.getString(R.string.collection_default_name))
        }
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 5; maxLines = 9
            setHint(R.string.collection_example)
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        val error = TextView(activity).apply { setTextColor(0xFFB3261E.toInt()) }
        val content = column().apply {
            addView(label(R.string.collection_help)); addView(name); addView(input); addView(error)
        }
        val editor = MaterialAlertDialogBuilder(activity).setTitle(R.string.collection_title).setView(scroll(content))
            .setPositiveButton(R.string.collection_read, null).setNegativeButton(R.string.action_cancel, null).create()
        var job: Job? = null
        var chooser: AlertDialog? = null
        var preview: AlertDialog? = null
        editor.setOnDismissListener { job?.cancel(); preview?.dismiss(); chooser?.dismiss() }
        editor.setOnShowListener {
            editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parsed = try { CoordinatePasteParser.parse(input.text.toString()) } catch (e: IllegalArgumentException) {
                    error.text = e.message; input.error = e.message; return@setOnClickListener
                }
                error.text = ""; input.error = null
                val homePicker = Spinner(activity).apply {
                    adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
                        parsed.points.map { "${it.name}（${it.position}）" })
                }
                val count = TextView(activity).apply {
                    text = activity.getString(R.string.collection_points, parsed.points.size - 1, parsed.duplicates)
                }
                val homeContent = column().apply { addView(count); addView(label(R.string.collection_home)); addView(homePicker) }
                val homeDialog = MaterialAlertDialogBuilder(activity).setTitle(R.string.collection_choose_home).setView(scroll(homeContent))
                    .setPositiveButton(R.string.collection_plan, null).setNegativeButton(R.string.collection_edit, null).create()
                chooser = homeDialog
                homeDialog.setOnDismissListener { job?.cancel(); preview?.dismiss() }
                homeDialog.setOnShowListener {
                    homeDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val home = parsed.points[homePicker.selectedItemPosition]
                        val flowers = parsed.points.filter { it != home }
                        val button = homeDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        button.isEnabled = false
                        homePicker.isEnabled = false
                        button.setText(R.string.collection_planning)
                        job = activity.lifecycleScope.launch {
                            try {
                                val plan = withContext(Dispatchers.Default) {
                                    CollectionRoutePlanner.plan(home.position, flowers.map { it.position })
                                }
                                if (!editor.isShowing || !homeDialog.isShowing) return@launch
                                val ordered = plan.order.map { flowers[it] }
                                fun distance(m: Double) = String.format(Locale.TAIWAN, "%.2f 公里", m / 1000.0)
                                val details = activity.getString(R.string.collection_summary, ordered.size,
                                    distance(plan.distanceM), distance(plan.originalDistanceM),
                                    activity.getString(if (plan.exact) R.string.collection_exact else R.string.collection_approximate)) +
                                    "\n\n" + activity.getString(R.string.collection_preview_home, home.name, home.position.toString()) +
                                    "\n" + ordered.mapIndexed { i, point -> "${i + 1}. ${point.name}（${point.position}）" }.joinToString("\n") +
                                    "\n" + activity.getString(R.string.collection_return_home)
                                val routeText = TextView(activity).apply { text = details; setTextIsSelectable(true) }
                                val routeDialog = MaterialAlertDialogBuilder(activity).setTitle(R.string.collection_preview)
                                    .setView(scroll(column().apply { addView(routeText) }))
                                    .setPositiveButton(R.string.collection_apply, null).setNegativeButton(R.string.collection_back, null).create()
                                preview = routeDialog
                                routeDialog.setOnShowListener {
                                    routeDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                                        if (onApply(name.text.toString().trim(), home.position, ordered)) {
                                            routeDialog.dismiss(); homeDialog.dismiss(); editor.dismiss()
                                            MaterialAlertDialogBuilder(activity).setTitle(R.string.collection_ready)
                                                .setMessage(R.string.collection_start_help).setPositiveButton(android.R.string.ok, null).show()
                                        }
                                    }
                                }
                                routeDialog.show()
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { count.setText(R.string.collection_plan_failed) }
                            finally {
                                button.isEnabled = true; homePicker.isEnabled = true; button.setText(R.string.collection_plan)
                            }
                        }
                    }
                }
                homeDialog.show()
            }
        }
        editor.show()
    }
}
