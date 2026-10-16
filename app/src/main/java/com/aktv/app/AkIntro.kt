package com.aktv.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs

/** The AK ribbon mark: same shapes as the launcher icon and TV banner. Box is 136 x 100 units. */
object AkMark {
    const val W = 136f
    const val H = 100f
    class Piece(val name: String, val pts: FloatArray, val shade: Int)

    // shade: 0 light, 1 mid, 2 dark, 3 fold, 4 bar. Listed back to front.
    val PIECES = listOf(
        Piece("A_left", floatArrayOf(24.50f, 0.00f, 39.50f, 0.00f, 15.00f, 100.00f, 0.00f, 100.00f), 1),
        Piece("A_right", floatArrayOf(24.50f, 0.00f, 39.50f, 0.00f, 64.00f, 100.00f, 49.00f, 100.00f), 2),
        Piece("A_fold", floatArrayOf(24.50f, 0.00f, 39.50f, 0.00f, 32.00f, 30.61f), 3),
        Piece("A_bar", floatArrayOf(23.31f, 62.00f, 40.69f, 62.00f, 43.88f, 75.00f, 20.12f, 75.00f), 4),
        Piece("K_leg", floatArrayOf(87.00f, 58.00f, 93.80f, 40.00f, 131.00f, 100.00f, 113.00f, 100.00f), 2),
        Piece("K_arm", floatArrayOf(116.00f, 0.00f, 134.00f, 0.00f, 87.00f, 75.81f, 87.00f, 46.77f), 1),
        Piece("K_stem", floatArrayOf(72.00f, 0.00f, 87.00f, 0.00f, 87.00f, 100.00f, 72.00f, 100.00f), 0)
    )

    // top colour, bottom colour of the ribbon gradient (runs the full height of the mark)
    val SHADES = arrayOf(
        intArrayOf(0xFF9ADCFF.toInt(), 0xFF52A8FF.toInt()),
        intArrayOf(0xFF55A0FF.toInt(), 0xFF3B72F2.toInt()),
        intArrayOf(0xFF3C62E4.toInt(), 0xFF2A3CB6.toInt()),
        intArrayOf(0xFF1E2C8E.toInt(), 0xFF1E2C8E.toInt()),
        intArrayOf(0xFF4672F0.toInt(), 0xFF3458D8.toInt())
    )
}

/**
 * Entrance animation. The ribbons of the AK unfold one by one, a light sweeps across them,
 * pillars of colour burst out behind, then the mark flies through the camera into the home screen.
 * Any key press skips to the fly-through.
 */
class AkIntroView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    /** Fired once, when the fly-through begins: start fading the home screen in underneath. */
    var onReveal: (() -> Unit)? = null
    var onDone: (() -> Unit)? = null
    var wordmark: Typeface? = null
        set(v) { field = v; textP.typeface = v; creditP.typeface = v }

    private val total = 3400L
    private val exitAt = 2450L
    private var t = 0L
    private var revealed = false
    private var finished = false

    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = total; interpolator = LinearInterpolator()
        addUpdateListener {
            t = (it.animatedFraction * total).toLong()
            if (!revealed && t >= exitAt + 80) { revealed = true; onReveal?.invoke() }
            invalidate()
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { finish() }
        })
    }

    // per piece timing, same order as AkMark.PIECES: start ms, duration ms, mode (0 wipe down, 1 wipe right, 2 fade)
    private val pStart = longArrayOf(250, 400, 800, 750, 900, 700, 500)
    private val pDur = longArrayOf(650, 650, 350, 450, 650, 650, 600)
    private val pMode = intArrayOf(0, 0, 2, 1, 0, 0, 0)

    private val paths = ArrayList<Path>()
    private val bounds = ArrayList<RectF>()
    private val fills = ArrayList<Paint>()
    private val markRect = RectF()
    private val layerRect = RectF()
    private var cx = 0f
    private var cy = 0f

    private val glowP = Paint()
    private val beamP = Paint()
    private val sheenP = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }
    private val bandP = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; letterSpacing = 0.35f }
    private val creditP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; letterSpacing = 0.08f }

    private val nb = 15
    private val beamX = FloatArray(nb)
    private val beamW = FloatArray(nb)
    private val beamShaders = arrayOfNulls<LinearGradient>(nb)
    private var beamH = 0f
    private var bandW = 0f

    fun start() { if (!anim.isStarted) anim.start() }
    fun skip() { if (t < exitAt - 120) anim.currentPlayTime = exitAt - 120 }

    private fun finish() {
        if (finished) return
        finished = true
        if (!revealed) { revealed = true; onReveal?.invoke() }
        onDone?.invoke()
    }

    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val s = h * 0.30f / AkMark.H
        cx = w / 2f; cy = h * 0.44f
        val ox = cx - AkMark.W * s / 2f
        val oy = cy - AkMark.H * s / 2f
        markRect.set(ox, oy, ox + AkMark.W * s, oy + AkMark.H * s)
        layerRect.set(markRect); layerRect.inset(-h * 0.02f, -h * 0.02f)

        val shaders = AkMark.SHADES.map { LinearGradient(0f, oy, 0f, oy + AkMark.H * s, it[0], it[1], Shader.TileMode.CLAMP) }
        paths.clear(); bounds.clear(); fills.clear()
        for (pc in AkMark.PIECES) {
            val path = Path(); val p = pc.pts
            path.moveTo(ox + p[0] * s, oy + p[1] * s)
            var i = 2
            while (i < p.size) { path.lineTo(ox + p[i] * s, oy + p[i + 1] * s); i += 2 }
            path.close(); paths.add(path)
            val rb = RectF(); path.computeBounds(rb, true); bounds.add(rb)
            fills.add(Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = shaders[pc.shade] })
        }

        glowP.shader = RadialGradient(cx, cy, h * 0.62f,
            intArrayOf(Color.argb(120, 61, 139, 255), Color.argb(46, 75, 59, 214), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        sheenP.shader = LinearGradient(0f, oy, 0f, oy + AkMark.H * s * 0.45f,
            Color.argb(60, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)

        bandW = markRect.width() * 0.30f
        bandP.shader = LinearGradient(0f, 0f, bandW, 0f,
            intArrayOf(Color.TRANSPARENT, Color.argb(200, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)

        beamH = h * 0.95f
        val palette = intArrayOf(0xFF7FE7FF.toInt(), 0xFF3D8BFF.toInt(), 0xFF7B5CFF.toInt())
        for (i in 0 until nb) {
            val f = i - (nb - 1) / 2f
            val j = ((i * 37) % 11) / 10f                      // deterministic variation, no randomness per frame
            beamX[i] = cx + f * markRect.width() * 0.062f
            beamW[i] = markRect.width() * 0.022f * (0.7f + 0.6f * j)
            beamShaders[i] = LinearGradient(0f, cy - beamH / 2, 0f, cy + beamH / 2,
                intArrayOf(Color.TRANSPARENT, palette[i % 3], Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        }
        textP.textSize = h * 0.05f
        creditP.textSize = h * 0.026f
    }

    private fun phase(now: Long, from: Long, to: Long) = ((now - from).toFloat() / (to - from)).coerceIn(0f, 1f)
    private fun smooth(x: Float) = x * x * (3f - 2f * x)
    private fun easeOut(x: Float): Float { val k = 1f - x; return 1f - k * k * k }
    private fun easeIn(x: Float) = x * x * x

    override fun onDraw(cv: Canvas) {
        if (paths.isEmpty()) return
        val w = width.toFloat(); val h = height.toFloat()

        // background fades out last so the home screen is revealed under the flying mark
        val bgA = 1f - smooth(phase(t, exitAt + 200, total))
        cv.drawColor(Color.argb((bgA * 255).toInt(), 5, 6, 13))

        val exitZ = easeIn(phase(t, exitAt, total))
        val markA = 1f - smooth(phase(t, exitAt + 150, total - 120))
        val breath = phase(t, 900, exitAt)
        val zoom = (1f + 0.035f * breath) * (1f + 2.2f * exitZ)

        cv.save()
        cv.scale(zoom, zoom, cx, cy)

        // soft glow behind the mark
        val glowA = smooth(phase(t, 150, 1300)) * markA
        if (glowA > 0f) { glowP.alpha = (glowA * 255).toInt(); cv.drawRect(0f, 0f, w, h, glowP) }

        // pillars of colour
        for (i in 0 until nb) {
            val f = i - (nb - 1) / 2f
            val st = 1250L + (abs(f) * 55f).toLong()
            val up = easeOut(phase(t, st, st + 420))
            val down = phase(t, st + 420, st + 1000)
            val a = up * (1f - down) * 0.85f * markA
            if (a <= 0.01f) continue
            beamP.shader = beamShaders[i]; beamP.alpha = (a * 255).toInt()
            cv.save()
            cv.scale(1f, 0.25f + 0.75f * up, cx, cy)
            cv.drawRect(beamX[i] - beamW[i] / 2, cy - beamH / 2, beamX[i] + beamW[i] / 2, cy + beamH / 2, beamP)
            cv.restore()
        }

        // the ribbons, drawn in one layer so the sheen and the light sweep only touch the letters
        cv.saveLayerAlpha(layerRect, (markA * 255).toInt())
        for (i in paths.indices) {
            val r = easeOut(phase(t, pStart[i], pStart[i] + pDur[i]))
            if (r <= 0f) continue
            val b = bounds[i]
            cv.save()
            when (pMode[i]) {
                0 -> cv.clipRect(b.left - 2f, b.top - 2f, b.right + 2f, b.top + b.height() * r)
                1 -> cv.clipRect(b.left - 2f, b.top - 2f, b.left + b.width() * r, b.bottom + 2f)
            }
            val p = fills[i]
            p.alpha = if (pMode[i] == 2) (255 * r).toInt() else 255
            cv.drawPath(paths[i], p)
            cv.restore()
        }
        cv.drawRect(markRect, sheenP)
        val sw = phase(t, 1550, 2250)
        if (sw > 0f && sw < 1f) {
            cv.save()
            cv.rotate(18f, cx, cy)
            val x = markRect.left - bandW + (markRect.width() + bandW * 2f) * smooth(sw)
            cv.translate(x, 0f)
            cv.drawRect(0f, markRect.top - markRect.height(), bandW, markRect.bottom + markRect.height(), bandP)
            cv.restore()
        }
        cv.restore()    // layer

        // wordmark
        val ta = smooth(phase(t, 1700, 2150)) * markA
        if (ta > 0f) {
            textP.color = 0xFFC4CCEB.toInt(); textP.alpha = (ta * 255).toInt()
            cv.drawText("tv", cx, markRect.bottom + h * 0.105f + (1f - ta) * h * 0.012f, textP)
        }
        cv.restore()    // zoom

        // credit stays put while the mark flies through
        val ca = smooth(phase(t, 1900, 2300)) * (1f - smooth(phase(t, exitAt, exitAt + 350)))
        if (ca > 0f) {
            creditP.color = 0xFF5F6590.toInt(); creditP.alpha = (ca * 255).toInt()
            cv.drawText("by Akshansh Sahu", w / 2f, h * 0.93f, creditP)
        }
    }
}
