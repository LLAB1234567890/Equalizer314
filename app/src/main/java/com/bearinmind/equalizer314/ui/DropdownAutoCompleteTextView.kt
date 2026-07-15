package com.bearinmind.equalizer314.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import com.google.android.material.textfield.MaterialAutoCompleteTextView

/**
 * `MaterialAutoCompleteTextView` variant that doesn't show the text insertion handle on tap.
 *
 * The default subclass extends `EditText`, so `ACTION_DOWN` runs cursor-placement logic that briefly
 * fades in the insertion-handle teardrop. `cursorVisible="false"`, `textIsSelectable="false"` etc.
 * suppress this on stock Android, but Samsung One UI's customised `Editor` still draws the handle for
 * a frame or two before checking the flag, so we bypass `EditText.onTouchEvent` entirely.
 */
class DropdownAutoCompleteTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.autoCompleteTextViewStyle,
) : MaterialAutoCompleteTextView(context, attrs, defStyleAttr) {

    // Don't call super (EditText's insertion-handle code) and don't consume the touch — return false
    // so the gesture bubbles to the parent TextInputLayout, which owns the ripple foreground +
    // showDropDown() click handler and drives both via standard View touch processing.
    override fun onTouchEvent(event: MotionEvent): Boolean = false
}
