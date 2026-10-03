package app.pikminbloom.gps.ui

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.widget.NestedScrollView
import app.pikminbloom.gps.R
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors

/** Descriptive, scrollable actions shared by mode, jump and tools sheets. */
object ActionSheet {
    data class Item(@param:StringRes val title: Int, @param:DrawableRes val icon: Int = 0, @param:StringRes val description: Int = 0, val action: (() -> Unit)? = null)

    fun show(activity: Activity, @StringRes title: Int, @StringRes description: Int, items: List<Item>) {
        val sheet = BottomSheetDialog(activity)
        val pad = activity.resources.getDimensionPixelSize(R.dimen.space_xl)
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun text(@StringRes res: Int, appearance: Int) = TextView(activity).apply {
            setText(res)
            setTextAppearance(appearance)
            setPadding(0, pad / 3, 0, pad / 3)
        }
        column.addView(text(title, com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall).apply {
            markHeading(this)
        })
        column.addView(text(description, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium))
        items.forEach { item ->
            if (item.action == null) {
                column.addView(text(item.title, com.google.android.material.R.style.TextAppearance_Material3_TitleSmall).apply {
                    setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondary))
                    markHeading(this)
                })
            } else {
                val card = MaterialCardView(activity).apply {
                    radius = pad * 0.75f
                    cardElevation = 0f
                    setCardBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainer))
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = pad / 3 }
                    isClickable = true
                    isFocusable = true
                    contentDescription = activity.getString(item.title) +
                        if (item.description != 0) ". " + activity.getString(item.description) else ""
                    setOnClickListener { sheet.dismiss(); item.action.invoke() }
                }
                val body = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(pad / 2, pad / 3, pad / 2, pad / 3)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                }
                body.addView(text(item.title, com.google.android.material.R.style.TextAppearance_Material3_TitleMedium).apply {
                    minHeight = (48 * resources.displayMetrics.density).toInt()
                    gravity = Gravity.CENTER_VERTICAL
                    if (item.icon != 0) {
                        setCompoundDrawablesRelativeWithIntrinsicBounds(item.icon, 0, 0, 0)
                        compoundDrawablePadding = pad / 2
                        compoundDrawableTintList = android.content.res.ColorStateList.valueOf(
                            MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
                    }
                })
                if (item.description != 0) body.addView(text(item.description, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium))
                card.addView(body)
                column.addView(card)
            }
        }
        column.addView(MaterialButton(activity, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            setText(R.string.ui_close)
            setOnClickListener { sheet.dismiss() }
        })
        sheet.setContentView(NestedScrollView(activity).apply { addView(column) })
        sheet.show()
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
    }

    private fun markHeading(view: View) = androidx.core.view.ViewCompat.setAccessibilityHeading(view, true)
}
