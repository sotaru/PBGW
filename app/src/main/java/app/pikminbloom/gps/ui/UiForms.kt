package app.pikminbloom.gps.ui

import android.content.Context
import android.widget.EditText
import com.google.android.material.textfield.TextInputLayout

/** Gives programmatic dialog fields the same labelled outline as XML forms. */
object UiForms {
    fun field(context: Context, label: CharSequence, input: EditText): TextInputLayout = TextInputLayout(context).apply {
        hint = label
        boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        input.hint?.takeIf { it != label }?.let { placeholderText = it }
        input.hint = null
        addView(input)
    }
}
