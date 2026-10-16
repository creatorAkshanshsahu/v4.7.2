package com.aktv.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max

/** Palette (Google TV style: neutral dark surfaces, white focus) + colour extraction from channel logos. */
object Accent {
    val ABYSS = 0xFF0E0F12.toInt()
    val SURFACE = 0xFF25272D.toInt()
    val SIGNAL = 0xFF8AB4F8.toInt()
    val ION = 0xFF7FE7FF.toInt()
    val INDIGO = 0xFF4B3BD6.toInt()
    val DEEP = 0xFF1B1A6B.toInt()
    val LIVE = 0xFFE8453C.toInt()
    val TEXT = 0xFFE8EAED.toInt()
    val DIM = 0xFFBDC1C6.toInt()
    val FAINT = 0xFF80868B.toInt()

    /** Makes any logo colour usable as a tint: saturated, mid-light. Greys fall back to the brand blue. */
    fun normalize(c: Int?): Int {
        if (c == null) return SIGNAL
        val hsl = FloatArray(3); ColorUtils.colorToHSL(c, hsl)
        if (hsl[1] < 0.18f) return SIGNAL
        hsl[1] = max(hsl[1], 0.65f); hsl[2] = hsl[2].coerceIn(0.48f, 0.62f)
        return ColorUtils.HSLToColor(hsl)
    }

    /** A neighbouring hue so the hero panel has a little depth instead of one flat colour. */
    fun partner(c: Int): Int {
        val hsl = FloatArray(3); ColorUtils.colorToHSL(c, hsl)
        hsl[0] = (hsl[0] + 48f) % 360f; hsl[2] = 0.42f
        return ColorUtils.HSLToColor(hsl)
    }

    /** Stable colour for channels that have no logo to sample (V2 / 24-7 channels): same name, same tint. */
    fun fromName(name: String): Int {
        var h = 7
        for (ch in name.lowercase()) h = (h * 31 + ch.code) and 0x7fffffff
        return ColorUtils.HSLToColor(floatArrayOf((h % 360).toFloat(), 0.72f, 0.56f))
    }

    fun from(d: Drawable, cb: (Int) -> Unit) {
        val bmp = (d as? BitmapDrawable)?.bitmap
        if (bmp == null || bmp.config == Bitmap.Config.HARDWARE) { cb(SIGNAL); return }
        Palette.from(bmp).maximumColorCount(16).generate { p ->
            val sw = p?.vibrantSwatch ?: p?.lightVibrantSwatch ?: p?.darkVibrantSwatch ?: p?.dominantSwatch
            cb(normalize(sw?.rgb))
        }
    }
}

/** Flat, cheap surfaces: plain colour fills, no shaders, no animators, no shadows. */
object Ui {
    fun mix(a: Int, b: Int, f: Float) = ColorUtils.blendARGB(a, b, f)

    /** Rounded flat fill. radiusDp = 100 (default) gives a pill. */
    fun pill(dp: Float, color: Int, radiusDp: Float = 100f) =
        GradientDrawable().apply { cornerRadius = radiusDp * dp; setColor(color) }

    /**
     * List-row background driven by the view's own state: white when focused, soft grey when activated
     * (the row that is "playing" / "open"), [rest] otherwise. The framework switches it; no code runs per focus change.
     */
    fun focusBg(dp: Float, radiusDp: Float, rest: Int = Color.TRANSPARENT, active: Int = 0xFF2B2D33.toInt()) =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), pill(dp, Accent.TEXT, radiusDp))
            addState(intArrayOf(android.R.attr.state_activated), pill(dp, active, radiusDp))
            addState(intArrayOf(), pill(dp, rest, radiusDp))
        }
}

/** Keeps the focused item pinned to the start edge, like Google TV / Netflix rows. */
class SnapLayoutManager(c: Context, orientation: Int) : LinearLayoutManager(c, orientation, false) {
    override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: Rect,
                                               immediate: Boolean, focusedChildVisible: Boolean): Boolean {
        val lp = child.layoutParams as ViewGroup.MarginLayoutParams
        val d = if (orientation == RecyclerView.HORIZONTAL) child.left - lp.leftMargin - parent.paddingLeft
                else child.top - lp.topMargin - parent.paddingTop
        if (d == 0) return false
        if (orientation == RecyclerView.HORIZONTAL) { if (immediate) parent.scrollBy(d, 0) else parent.smoothScrollBy(d, 0) }
        else { if (immediate) parent.scrollBy(0, d) else parent.smoothScrollBy(0, d) }
        return true
    }
}
