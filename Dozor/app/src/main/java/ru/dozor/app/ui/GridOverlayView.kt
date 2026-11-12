package ru.dozor.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Сетка для наведения: трети кадра, центр и уголки рамки. */
class GridOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val d = resources.displayMetrics.density
    private val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 255, 179, 0)
        strokeWidth = 1f * d
    }
    private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 179, 0)
        strokeWidth = 3f * d
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in 1..2) {
            canvas.drawLine(w * i / 3, 0f, w * i / 3, h, thin)
            canvas.drawLine(0f, h * i / 3, w, h * i / 3, thin)
        }
        val c = 14 * d
        canvas.drawLine(w / 2 - c, h / 2, w / 2 + c, h / 2, thin)
        canvas.drawLine(w / 2, h / 2 - c, w / 2, h / 2 + c, thin)

        val inset = 0.05f * minOf(w, h)
        val len = 0.08f * minOf(w, h)
        val l = inset
        val t = inset
        val r = w - inset
        val b = h - inset
        canvas.drawLine(l, t, l + len, t, corner); canvas.drawLine(l, t, l, t + len, corner)
        canvas.drawLine(r, t, r - len, t, corner); canvas.drawLine(r, t, r, t + len, corner)
        canvas.drawLine(l, b, l + len, b, corner); canvas.drawLine(l, b, l, b - len, corner)
        canvas.drawLine(r, b, r - len, b, corner); canvas.drawLine(r, b, r, b - len, corner)
    }
}
