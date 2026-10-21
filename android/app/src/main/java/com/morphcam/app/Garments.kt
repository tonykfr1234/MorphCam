package com.morphcam.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Shader
import kotlin.math.hypot

/** MediaPipe pose landmark indices used for dressing. */
object Lm {
    const val L_SHOULDER = 11; const val R_SHOULDER = 12
    const val L_ELBOW = 13; const val R_ELBOW = 14
    const val L_WRIST = 15; const val R_WRIST = 16
    const val L_HIP = 23; const val R_HIP = 24
    const val L_KNEE = 25; const val R_KNEE = 26
    const val L_ANKLE = 27; const val R_ANKLE = 28
}

/** Body points in view pixels; null when that joint is not visible. */
class Body(val p: Array<PointF?>) {
    operator fun get(i: Int) = p[i]
}

sealed class Garment(val name: String) {
    abstract fun draw(c: Canvas, b: Body)
}

private fun lerp(a: PointF, b: PointF, t: Float) = PointF(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
private fun dist(a: PointF, b: PointF) = hypot(a.x - b.x, a.y - b.y)

/**
 * Procedurally drawn clothes. Torso, sleeves and trouser legs are each attached
 * to their own joints, so the outfit follows the arms and legs as they move.
 */
class VectorGarment(
    name: String,
    private val color: Int,
    private val color2: Int = color,
    private val sleeve: Float = 0.45f,      // 0 = sleeveless, 1 = elbow, 2 = wrist
    private val stripes: Boolean = false,
    private val pants: Int? = null,
    private val tie: Int? = null,
) : Garment(name) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val limb = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x55000000; strokeWidth = 3f
    }

    override fun draw(c: Canvas, b: Body) {
        val ls = b[Lm.L_SHOULDER] ?: return
        val rs = b[Lm.R_SHOULDER] ?: return
        val shoulderW = dist(ls, rs)
        val (left, right) = if (ls.x < rs.x) ls to rs else rs to ls
        val lh0 = b[Lm.L_HIP]; val rh0 = b[Lm.R_HIP]
        val (lh, rh) = if (lh0 != null && rh0 != null) {
            if (lh0.x < rh0.x) lh0 to rh0 else rh0 to lh0
        } else {
            // Hips out of frame: extend the torso straight down.
            PointF(left.x + shoulderW * 0.1f, left.y + shoulderW * 1.5f) to
                PointF(right.x - shoulderW * 0.1f, right.y + shoulderW * 1.5f)
        }

        // Pants first, so the shirt hem covers the waist.
        pants?.let { pc ->
            limb.shader = null; limb.color = pc; limb.strokeWidth = shoulderW * 0.42f
            for ((hip, knee, ankle) in listOf(
                Triple(Lm.L_HIP, Lm.L_KNEE, Lm.L_ANKLE), Triple(Lm.R_HIP, Lm.R_KNEE, Lm.R_ANKLE)
            )) {
                val h = b[hip] ?: continue
                val k = b[knee] ?: continue
                val path = Path().apply { moveTo(h.x, h.y); lineTo(k.x, k.y); b[ankle]?.let { lineTo(it.x, it.y) } }
                c.drawPath(path, limb)
            }
        }

        // Sleeves follow the upper arm (and forearm for long sleeves).
        if (sleeve > 0f) {
            limb.color = color; limb.shader = null; limb.strokeWidth = shoulderW * 0.36f
            for ((s, e, w) in listOf(
                Triple(Lm.L_SHOULDER, Lm.L_ELBOW, Lm.L_WRIST), Triple(Lm.R_SHOULDER, Lm.R_ELBOW, Lm.R_WRIST)
            )) {
                val sp = b[s] ?: continue
                val ep = b[e] ?: continue
                val path = Path().apply { moveTo(sp.x, sp.y) }
                if (sleeve <= 1f) {
                    lerp(sp, ep, sleeve).let { path.lineTo(it.x, it.y) }
                } else {
                    path.lineTo(ep.x, ep.y)
                    b[w]?.let { wp -> lerp(ep, wp, sleeve - 1f).let { path.lineTo(it.x, it.y) } }
                }
                c.drawPath(path, limb)
            }
        }

        // Torso: shoulders widened a bit, waist slightly below the hip joints.
        val out = shoulderW * 0.14f
        val sl = PointF(left.x - out, left.y - shoulderW * 0.05f)
        val sr = PointF(right.x + out, right.y - shoulderW * 0.05f)
        val hl = PointF(lh.x - out * 0.9f, lh.y + shoulderW * 0.12f)
        val hr = PointF(rh.x + out * 0.9f, rh.y + shoulderW * 0.12f)
        val neckL = lerp(sl, sr, 0.36f)
        val neckR = lerp(sl, sr, 0.64f)
        val neckDip = lerp(lerp(sl, sr, 0.5f), lerp(hl, hr, 0.5f), if (tie != null) 0.28f else 0.12f)
        val torso = Path().apply {
            moveTo(sl.x, sl.y)
            lineTo(neckL.x, neckL.y)
            quadTo(neckDip.x, neckDip.y, neckR.x, neckR.y)
            lineTo(sr.x, sr.y)
            lineTo(hr.x, hr.y)
            lineTo(hl.x, hl.y)
            close()
        }
        fill.shader = if (stripes) {
            LinearGradient(sl.x, 0f, sl.x + shoulderW * 0.12f, 0f,
                intArrayOf(color, color, color2, color2), floatArrayOf(0f, 0.5f, 0.5f, 1f), Shader.TileMode.REPEAT)
        } else if (color2 != color) {
            LinearGradient(0f, sl.y, 0f, hl.y, color, color2, Shader.TileMode.CLAMP)
        } else null
        fill.color = color
        c.drawPath(torso, fill)
        c.drawPath(torso, edge)

        tie?.let { tc ->
            // White shirt V plus a tie under the jacket opening.
            val top = lerp(neckL, neckR, 0.5f)
            fill.shader = null; fill.color = Color.WHITE
            c.drawPath(Path().apply {
                moveTo(neckL.x, neckL.y); lineTo(neckR.x, neckR.y); lineTo(neckDip.x, neckDip.y); close()
            }, fill)
            fill.color = tc
            val w = shoulderW * 0.06f
            val bottom = lerp(top, lerp(hl, hr, 0.5f), 0.55f)
            c.drawPath(Path().apply {
                moveTo(top.x - w * 0.6f, top.y); lineTo(top.x + w * 0.6f, top.y)
                lineTo(bottom.x + w, bottom.y - w * 2); lineTo(bottom.x, bottom.y)
                lineTo(bottom.x - w, bottom.y - w * 2); close()
            }, fill)
        }
    }
}

/**
 * A user-supplied transparent PNG (front view of a top). The image is
 * perspective-warped so its shoulder/hip anchors land on the body.
 * Expected anchors: shoulders at 25%/75% width, 15% height; hips at 30%/70%, 92%.
 */
class ImageGarment(name: String, private val bmp: Bitmap) : Garment(name) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val m = Matrix()

    override fun draw(c: Canvas, b: Body) {
        val ls = b[Lm.L_SHOULDER] ?: return
        val rs = b[Lm.R_SHOULDER] ?: return
        val (sl, sr) = if (ls.x < rs.x) ls to rs else rs to ls
        val sw = dist(sl, sr)
        val lh0 = b[Lm.L_HIP]; val rh0 = b[Lm.R_HIP]
        val (hl, hr) = if (lh0 != null && rh0 != null) {
            if (lh0.x < rh0.x) lh0 to rh0 else rh0 to lh0
        } else PointF(sl.x, sl.y + sw * 1.5f) to PointF(sr.x, sr.y + sw * 1.5f)
        val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
        val src = floatArrayOf(0.25f * w, 0.15f * h, 0.75f * w, 0.15f * h, 0.70f * w, 0.92f * h, 0.30f * w, 0.92f * h)
        val dst = floatArrayOf(sl.x, sl.y, sr.x, sr.y, hr.x, hr.y, hl.x, hl.y)
        m.setPolyToPoly(src, 0, dst, 0, 4)
        c.drawBitmap(bmp, m, paint)
    }
}

object Wardrobe {
    val builtIn: List<Garment> = listOf(
        VectorGarment("紅色T恤", 0xFFD32F2F.toInt()),
        VectorGarment("黑西裝", 0xFF1C1C22.toInt(), sleeve = 2f, pants = 0xFF1C1C22.toInt(), tie = 0xFFB71C1C.toInt()),
        VectorGarment("藍條紋襯衫", 0xFF1565C0.toInt(), Color.WHITE, sleeve = 2f, stripes = true),
        VectorGarment("黃金戰甲", 0xFFFFD54F.toInt(), 0xFFB8860B.toInt(), sleeve = 2f, pants = 0xFF8D6E00.toInt()),
        VectorGarment("綠色背心", 0xFF2E7D32.toInt(), sleeve = 0f),
        VectorGarment("粉紅連帽衫", 0xFFF48FB1.toInt(), 0xFFEC407A.toInt(), sleeve = 2f),
    )
}
