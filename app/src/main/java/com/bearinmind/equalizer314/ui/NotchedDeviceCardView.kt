package com.bearinmind.equalizer314.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import com.google.android.material.card.MaterialCardView

/**
 * `MaterialCardView` that draws its outline stroke with a notched cutout on the LEFT edge, so the
 * drag handle straddling the card's left edge appears to sit *on* the outline rather than inside it.
 * Same pattern Material uses for the floated label on `TextInputLayout.OutlinedBox`, but on the
 * vertical edge instead of horizontal.
 *
 * Implementation: built-in stroke suppressed (`strokeWidth = 0`), outline re-drawn as a Path in
 * `onDraw` skipping the left-edge segment between [cutoutTopPx] and [cutoutBottomPx]. Call
 * [setLeftEdgeCutout] from the adapter after the handle is measured so the gap matches its position.
 */
class NotchedDeviceCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialCardViewStyle,
) : MaterialCardView(context, attrs, defStyleAttr) {

    private var cutoutTopPx = 0f
    private var cutoutBottomPx = 0f

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val outlinePath = Path()
    private val cornerRect = RectF()

    private val customStrokeWidthPx: Float
    private val customStrokeColor: Int

    init {
        // Capture user's app:strokeColor/strokeWidth from XML before zeroing them, so the built-in
        // MaterialCardView stroke doesn't double-draw over our custom path.
        customStrokeWidthPx = strokeWidth
            .takeIf { it > 0 }?.toFloat()
            ?: resources.displayMetrics.density   // default 1dp
        customStrokeColor = strokeColorStateList?.defaultColor ?: 0x66FFFFFF
        setStrokeWidth(0)
        setWillNotDraw(false)

        outlinePaint.strokeWidth = customStrokeWidthPx
        outlinePaint.color = customStrokeColor
    }

    /** Set the vertical range of the cutout on the LEFT edge, in
     *  this card's local pixel coordinates. Pass `0f, 0f` to remove. */
    fun setLeftEdgeCutout(topPx: Float, bottomPx: Float) {
        if (topPx == cutoutTopPx && bottomPx == cutoutBottomPx) return
        cutoutTopPx = topPx
        cutoutBottomPx = bottomPx
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        buildPath()
        canvas.drawPath(outlinePath, outlinePaint)
    }

    private fun buildPath() {
        val sw = outlinePaint.strokeWidth / 2f
        val w = width.toFloat()
        val h = height.toFloat()
        val left = sw
        val top = sw
        val right = w - sw
        val bottom = h - sw
        val r = radius

        outlinePath.reset()

        val hasCutout = cutoutBottomPx > cutoutTopPx
            && cutoutTopPx > top + r
            && cutoutBottomPx < bottom - r

        if (!hasCutout) {
            // No valid cutout configured — full rounded rectangle.
            outlinePath.addRoundRect(RectF(left, top, right, bottom), r, r, Path.Direction.CW)
            return
        }

        // Clockwise path from cutout top on the left edge, around the whole perimeter, ending at
        // cutout bottom on the left edge. The left-edge segment between the endpoints is left open — the cutout.
        outlinePath.moveTo(left, cutoutTopPx)
        outlinePath.lineTo(left, top + r)
        cornerRect.set(left, top, left + 2 * r, top + 2 * r)
        outlinePath.arcTo(cornerRect, 180f, 90f, false)
        outlinePath.lineTo(right - r, top)
        cornerRect.set(right - 2 * r, top, right, top + 2 * r)
        outlinePath.arcTo(cornerRect, 270f, 90f, false)
        outlinePath.lineTo(right, bottom - r)
        cornerRect.set(right - 2 * r, bottom - 2 * r, right, bottom)
        outlinePath.arcTo(cornerRect, 0f, 90f, false)
        outlinePath.lineTo(left + r, bottom)
        cornerRect.set(left, bottom - 2 * r, left + 2 * r, bottom)
        outlinePath.arcTo(cornerRect, 90f, 90f, false)
        outlinePath.lineTo(left, cutoutBottomPx)
    }
}
