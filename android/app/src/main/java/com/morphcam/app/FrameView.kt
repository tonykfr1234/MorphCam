package com.morphcam.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.view.View

/**
 * Draws a camera frame scaled to fill the view (center-crop) and, optionally,
 * a garment on top. Landmarks come in normalized frame coordinates and are
 * mapped with the same transform, so clothes stay locked to the body.
 */
class FrameView(context: Context) : View(context) {
    private var frame: Bitmap? = null
    private var landmarks: Array<PointF?>? = null   // normalized 0..1
    var garment: Garment? = null
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    init { setBackgroundColor(Color.BLACK) }

    fun show(bmp: Bitmap, lms: Array<PointF?>? = null) {
        frame = bmp
        landmarks = lms
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = frame ?: return
        val scale = maxOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val w = bmp.width * scale
        val h = bmp.height * scale
        val ox = (width - w) / 2f
        val oy = (height - h) / 2f
        dst.set(ox, oy, ox + w, oy + h)
        canvas.drawBitmap(bmp, null, dst, bmpPaint)

        val g = garment ?: return
        val lms = landmarks ?: return
        val pts = Array(lms.size) { i -> lms[i]?.let { PointF(ox + it.x * w, oy + it.y * h) } }
        g.draw(canvas, Body(pts))
    }
}
