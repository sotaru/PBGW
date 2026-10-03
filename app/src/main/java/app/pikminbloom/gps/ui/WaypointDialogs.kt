package app.pikminbloom.gps.ui

import android.app.Activity
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import app.pikminbloom.gps.R
import app.pikminbloom.gps.data.Prefs
import app.pikminbloom.gps.data.Waypoint
import app.pikminbloom.gps.data.WaypointStore
import app.pikminbloom.gps.databinding.DialogWaypointBinding
import app.pikminbloom.gps.databinding.ItemWaypointBinding
import app.pikminbloom.gps.databinding.SheetWaypointActionsBinding
import app.pikminbloom.gps.databinding.SheetWaypointListBinding
import app.pikminbloom.gps.geo.LatLng
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.UUID

/** Add / edit / reorder dialogs for Big Flower waypoints. Pure UI: everything persists via [WaypointStore]. */
object WaypointDialogs {

    private const val MIN_RADIUS_M = 8.0
    private const val MAX_RADIUS_M = 40.0
    private const val MIN_DWELL_SEC = 0
    private const val MAX_DWELL_SEC = 1800

    /**
     * Shows the add (when [existing] is null) or edit dialog.
     * [position] is only used to build the default name of a new waypoint ("大花 N").
     */
    fun showEditor(
        activity: Activity,
        prefs: Prefs,
        existing: Waypoint?,
        lat: Double,
        lon: Double,
        position: Int,
        onSave: (Waypoint) -> Unit,
    ) {
        val binding = DialogWaypointBinding.inflate(LayoutInflater.from(activity))
        binding.tvCoords.text = activity.getString(R.string.label_coords, "%.6f, %.6f".format(lat, lon))
        binding.etName.setText(existing?.name ?: activity.getString(R.string.default_waypoint_name, position))
        binding.etRadius.setText(fmt(existing?.radiusM ?: prefs.defaultRadiusM))
        binding.etDwell.setText((existing?.dwellSec ?: prefs.defaultDwellSec).toString())

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(if (existing == null) R.string.dlg_add_waypoint_title else R.string.dlg_edit_waypoint_title)
            .setView(binding.root)
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                binding.tilName.error = null
                binding.tilRadius.error = null
                binding.tilDwell.error = null

                val name = binding.etName.text?.toString()?.trim().orEmpty()
                val radius = binding.etRadius.text?.toString()?.trim()?.toDoubleOrNull()
                val dwell = binding.etDwell.text?.toString()?.trim()?.toIntOrNull()

                var ok = true
                if (name.isEmpty()) {
                    binding.tilName.error = activity.getString(R.string.err_name_required); ok = false
                }
                if (radius == null || !radius.isFinite() || radius < MIN_RADIUS_M || radius > MAX_RADIUS_M) {
                    binding.tilRadius.error = activity.getString(R.string.err_radius_range); ok = false
                }
                if (dwell == null || dwell < MIN_DWELL_SEC || dwell > MAX_DWELL_SEC) {
                    binding.tilDwell.error = activity.getString(R.string.err_dwell_range); ok = false
                }
                if (!ok) return@setOnClickListener

                // Remember what was just used, so the next Big Flower starts from these numbers
                // instead of the factory defaults.
                prefs.defaultRadiusM = radius!!
                prefs.defaultDwellSec = dwell!!

                onSave(
                    Waypoint(
                        id = existing?.id ?: UUID.randomUUID().toString(),
                        name = name,
                        lat = lat,
                        lon = lon,
                        radiusM = radius,
                        dwellSec = dwell,
                    )
                )
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /**
     * Bottom sheet shown when a waypoint marker is tapped. [running] relabels 從這裡開始 as
     * 立刻前往這朵: mid-patrol the same action makes that flower the next target.
     */
    fun showMarkerActions(
        activity: Activity,
        store: WaypointStore,
        prefs: Prefs,
        index: Int,
        running: Boolean = false,
        onStartHere: (Int) -> Unit,
    ) {
        val list = store.load()
        val wp = list.getOrNull(index) ?: return
        val binding = SheetWaypointActionsBinding.inflate(LayoutInflater.from(activity))
        val sheet = BottomSheetDialog(activity)
        sheet.setContentView(binding.root)

        binding.tvTitle.text = "${index + 1}. ${wp.name}"
        binding.tvSubtitle.text = summary(activity, wp)
        binding.actionMoveUp.isEnabled = index > 0
        binding.actionMoveDown.isEnabled = index < list.size - 1
        binding.actionMoveUp.alpha = if (index > 0) 1f else 0.4f
        binding.actionMoveDown.alpha = if (index < list.size - 1) 1f else 0.4f

        binding.actionEdit.setOnClickListener {
            sheet.dismiss()
            showEditor(activity, prefs, wp, wp.lat, wp.lon, index + 1) { store.update(it) }
        }
        binding.actionMoveUp.setOnClickListener { store.move(index, index - 1); sheet.dismiss() }
        binding.actionMoveDown.setOnClickListener { store.move(index, index + 1); sheet.dismiss() }
        binding.actionStartHere.setText(if (running) R.string.action_go_now else R.string.action_start_here)
        binding.actionStartHere.setOnClickListener { sheet.dismiss(); onStartHere(index) }
        binding.actionDelete.setOnClickListener {
            sheet.dismiss()
            confirmDelete(activity, wp) { store.remove(wp.id) }
        }
        sheet.show()
    }

    /** Bottom sheet listing every waypoint with reorder / edit / delete / start-here. */
    fun showList(
        activity: Activity,
        store: WaypointStore,
        prefs: Prefs,
        onFocus: (Waypoint) -> Unit,
        onStartHere: (Int) -> Unit,
        onAddRequested: () -> Unit,
    ) {
        val binding = SheetWaypointListBinding.inflate(LayoutInflater.from(activity))
        val sheet = BottomSheetDialog(activity)
        sheet.setContentView(binding.root)

        binding.listScroll.maximumHeight = (activity.resources.displayMetrics.heightPixels * 0.45f).toInt()

        fun render() {
            val list = store.load()
            binding.tvEmpty.visibility = if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            binding.listContainer.removeAllViews()
            list.forEachIndexed { index, wp ->
                val row = ItemWaypointBinding.inflate(LayoutInflater.from(activity), binding.listContainer, false)
                row.tvIndex.text = (index + 1).toString()
                row.tvName.text = wp.name
                row.tvSummary.text = summary(activity, wp)
                row.btnUp.isEnabled = index > 0
                row.btnDown.isEnabled = index < list.size - 1
                row.root.setOnClickListener { sheet.dismiss(); onFocus(wp) }
                row.root.setOnLongClickListener { sheet.dismiss(); onStartHere(index); true }
                row.btnUp.setOnClickListener { store.move(index, index - 1); render() }
                row.btnDown.setOnClickListener { store.move(index, index + 1); render() }
                row.btnEdit.setOnClickListener {
                    showEditor(activity, prefs, wp, wp.lat, wp.lon, index + 1) { store.update(it); render() }
                }
                row.btnDelete.setOnClickListener {
                    confirmDelete(activity, wp) { store.remove(wp.id); render() }
                }
                binding.listContainer.addView(row.root)
            }
        }

        binding.btnRoute.text = store.activeRouteName().ifBlank { activity.getString(R.string.title_waypoint_list) }
        binding.btnRoute.setOnClickListener {
            sheet.dismiss()
            // Re-open this sheet once the user is done picking, so switching routes feels in-place.
            showRoutePicker(activity, store) {
                showList(activity, store, prefs, onFocus, onStartHere, onAddRequested)
            }
        }
        binding.btnAdd.setOnClickListener { sheet.dismiss(); onAddRequested() }
        binding.btnSearchNearby.setOnClickListener {
            sheet.dismiss()
            val host = activity as? androidx.appcompat.app.AppCompatActivity ?: return@setOnClickListener
            // Search around wherever we currently are: the simulated position while patrolling,
            // otherwise the saved home, the last known position, or the first waypoint.
            val state = app.pikminbloom.gps.service.PatrolService.state.value
            val center = state.position.takeIf { state.phase != app.pikminbloom.gps.data.PatrolPhase.IDLE }
                ?: prefs.customHome ?: prefs.home
                ?: prefs.lastPosition
                ?: store.load().firstOrNull()?.latLng
            OverpassSearch.show(host, prefs, store, center)
        }
        binding.btnDecorHunt.setOnClickListener {
            sheet.dismiss()
            val host = activity as? androidx.appcompat.app.AppCompatActivity ?: return@setOnClickListener
            val state = app.pikminbloom.gps.service.PatrolService.state.value
            val center = state.position.takeIf { state.phase != app.pikminbloom.gps.data.PatrolPhase.IDLE }
                ?: prefs.customHome ?: prefs.home
                ?: prefs.lastPosition
                ?: store.load().firstOrNull()?.latLng
            DecorHunt.show(host, prefs, store, center)
        }
        render()
        sheet.show()
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
    }

    /**
     * Route switcher: pick a saved patrol, or create / rename / duplicate / delete one.
     * [onDone] fires after any change (and after a plain dismissal) so the caller can refresh.
     */
    fun showRoutePicker(activity: Activity, store: WaypointStore, onDone: () -> Unit) {
        val routes = store.routeList()
        val activeId = store.activeRouteId.value
        val labels = routes.map { r ->
            activity.getString(R.string.route_item, r.name, r.waypoints.size) +
                if (r.collectOnce) activity.getString(R.string.collection_route_badge) else ""
        }.toTypedArray()
        val checkedIndex = routes.indexOfFirst { it.id == activeId }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.route_picker_title)
            .setSingleChoiceItems(labels, checkedIndex) { d, which ->
                store.switchTo(routes[which].id)
                d.dismiss()
                onDone()
            }
            .setNeutralButton(R.string.route_manage) { _, _ -> showRouteManage(activity, store, onDone) }
            .setPositiveButton(R.string.route_new) { _, _ -> promptRouteName(activity, null) { name -> store.createRoute(name); onDone() } }
            .setNegativeButton(R.string.action_cancel) { _, _ -> onDone() }
            .show()
    }

    private fun showRouteManage(activity: Activity, store: WaypointStore, onDone: () -> Unit) {
        val routes = store.routeList()
        val labels = routes.map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.route_manage)
            .setItems(labels) { _, which ->
                val route = routes[which]
                MaterialAlertDialogBuilder(activity)
                    .setTitle(route.name)
                    .setItems(
                        arrayOf(
                            activity.getString(R.string.route_rename),
                            activity.getString(R.string.route_duplicate),
                            activity.getString(R.string.route_delete),
                        )
                    ) { _, action ->
                        when (action) {
                            0 -> promptRouteName(activity, route.name) { store.renameRoute(route.id, it); onDone() }
                            1 -> { store.createRoute(activity.getString(R.string.route_copy_name, route.name), route.id); onDone() }
                            2 -> MaterialAlertDialogBuilder(activity)
                                .setTitle(R.string.route_delete)
                                .setMessage(activity.getString(R.string.route_delete_msg, route.name, route.waypoints.size))
                                .setPositiveButton(R.string.action_delete) { _, _ -> store.deleteRoute(route.id); onDone() }
                                .setNegativeButton(R.string.action_cancel) { _, _ -> onDone() }
                                .show()
                        }
                    }
                    .show()
            }
            .setNegativeButton(R.string.action_cancel) { _, _ -> onDone() }
            .show()
    }

    private fun promptRouteName(activity: Activity, current: String?, onName: (String) -> Unit) {
        val input = android.widget.EditText(activity).apply {
            setText(current ?: "")
            setSingleLine()
            setHint(R.string.route_name_hint)
        }
        val pad = (24 * activity.resources.displayMetrics.density).toInt()
        val box = android.widget.FrameLayout(activity).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(if (current == null) R.string.route_new else R.string.route_rename)
            .setView(box)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) onName(name)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    fun confirmDelete(activity: Activity, wp: Waypoint, onConfirmed: () -> Unit) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.dlg_delete_title)
            .setMessage(activity.getString(R.string.dlg_delete_message, wp.name))
            .setPositiveButton(R.string.action_delete) { _, _ -> onConfirmed() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    fun summary(activity: Activity, wp: Waypoint): String =
        activity.getString(R.string.waypoint_summary, wp.lat, wp.lon, wp.radiusM, wp.dwellSec)

    fun latLngOf(wp: Waypoint): LatLng = LatLng(wp.lat, wp.lon)

    private fun fmt(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)
}
