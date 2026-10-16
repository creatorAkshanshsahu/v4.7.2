package com.aktv.app

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import org.mozilla.geckoview.*

/** Glowing D-pad pointer with a ripple on click. */
class CursorView(c: Context) : View(c) {
    var px = 0f; var py = 0f
    private val dp = c.resources.displayMetrics.density
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * dp; color = Accent.ION }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f * dp; color = Color.argb(120, 0, 0, 0) }
    private val ripple = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * dp; color = Accent.ION }
    private var pulse = 0f

    fun pulse() {
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 380; interpolator = DecelerateInterpolator()
            addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
            start()
        }
    }
    override fun onDraw(cv: Canvas) {
        val r = 11f * dp
        halo.shader = RadialGradient(px, py, r * 2.6f, ColorUtils.setAlphaComponent(Accent.ION, 90), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        cv.drawCircle(px, py, r * 2.6f, halo)
        cv.drawCircle(px, py, r, edge); cv.drawCircle(px, py, r, ring); cv.drawCircle(px, py, 3.5f * dp, core)
        if (pulse > 0f) {
            ripple.alpha = (pulse * 255).toInt()
            cv.drawCircle(px, py, r + (1f - pulse) * 24f * dp, ripple)
        }
    }
}

/** Thin page-load line across the top. */
class ProgressLine(c: Context) : View(c) {
    private val p = Paint()
    private var shown = 0f
    private var anim: ValueAnimator? = null
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        p.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, Accent.SIGNAL, Accent.ION, Shader.TileMode.CLAMP)
    }
    fun to(v: Float) {
        anim?.cancel(); animate().cancel(); alpha = 1f
        anim = ValueAnimator.ofFloat(shown, v.coerceIn(0f, 1f)).apply {
            duration = 260; addUpdateListener { shown = it.animatedValue as Float; invalidate() }; start()
        }
    }
    fun reset() { anim?.cancel(); animate().cancel(); shown = 0f; alpha = 1f; invalidate() }
    fun finish() { to(1f); animate().alpha(0f).setStartDelay(350).setDuration(400).start() }
    override fun onDraw(cv: Canvas) { cv.drawRect(0f, 0f, width * shown, height.toFloat(), p) }
}

/** Opens a website in a bundled Firefox engine (GeckoView), with a D-pad pointer. */
class BrowserActivity : Activity() {
    companion object {
        var runtime: GeckoRuntime? = null
        /** One shared Firefox engine for the whole app. Creating it is the slow part of opening a movie, so the details page warms it up. */
        fun sharedRuntime(c: Context): GeckoRuntime = runtime ?: GeckoRuntime.create(c.applicationContext).also {
            it.settings.contentBlocking.setAntiTracking(ContentBlocking.AntiTracking.AD or ContentBlocking.AntiTracking.ANALYTIC)
            runtime = it
        }
    }
    private lateinit var geckoView: GeckoView
    private lateinit var cursor: CursorView
    private lateinit var session: GeckoSession
    private lateinit var bar: ProgressLine
    private var canGoBack = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); hideUi()
        val dp = resources.displayMetrics.density
        val root = FrameLayout(this); root.setBackgroundColor(Accent.ABYSS)
        geckoView = GeckoView(this)
        cursor = CursorView(this).apply { isFocusable = false; isClickable = false }
        bar = ProgressLine(this)
        val hint = TextView(this).apply {
            text = controlsHint(); textSize = 13f; setTextColor(Accent.DIM)
            typeface = ResourcesCompat.getFont(this@BrowserActivity, R.font.sora_regular)
            setPadding((22 * dp).toInt(), (11 * dp).toInt(), (22 * dp).toInt(), (11 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 20 * dp; setColor(Color.argb(210, 30, 32, 38)) }
        }
        root.addView(geckoView, FrameLayout.LayoutParams(-1, -1))
        root.addView(bar, FrameLayout.LayoutParams(-1, (3 * dp).toInt(), Gravity.TOP))
        root.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (28 * dp).toInt() })
        root.addView(cursor, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        hint.alpha = 0f; hint.translationY = 12 * dp
        hint.animate().alpha(1f).translationY(0f).setStartDelay(400).setDuration(350).withEndAction {
            hint.animate().alpha(0f).setStartDelay(5000).setDuration(500).start()
        }.start()

        if (runtime == null) {
            runtime = GeckoRuntime.create(applicationContext)
            // block ads & trackers (also cuts most pop-ups)
            runtime!!.settings.contentBlocking.setAntiTracking(ContentBlocking.AntiTracking.AD or ContentBlocking.AntiTracking.ANALYTIC)
        }
        session = GeckoSession(GeckoSessionSettings.Builder()
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP).build())
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) { this@BrowserActivity.canGoBack = canGoBack }
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? = null  // block pop-up windows
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) { hideUi() }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) { bar.reset() }
            override fun onProgressChange(session: GeckoSession, progress: Int) { bar.to(progress / 100f) }
            override fun onPageStop(session: GeckoSession, success: Boolean) { bar.finish() }
        }
        session.open(runtime!!); geckoView.setSession(session)
        session.loadUri(intent.getStringExtra("url") ?: "https://livetgtv.lovable.app/")
        root.post { cursor.px = root.width / 2f; cursor.py = root.height / 2f; cursor.invalidate() }
    }

    private fun controlsHint(): CharSequence {
        val semi = ResourcesCompat.getFont(this, R.font.sora_semibold) ?: Typeface.DEFAULT_BOLD
        val sb = SpannableStringBuilder()
        listOf("Arrows" to "Move pointer", "OK" to "Click", "Back" to "Go back").forEachIndexed { i, (k, l) ->
            if (i > 0) sb.append("      ")
            val s = sb.length; sb.append(k)
            sb.setSpan(ForegroundColorSpan(Accent.TEXT), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(TypefaceSpan(semi), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("  ").append(l)
        }
        return sb
    }

    private fun hideUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }
    private fun move(dx: Float, dy: Float) {
        val w = geckoView.width.toFloat(); val h = geckoView.height.toFloat(); val edge = 40f
        var ny = cursor.py + dy
        if (dy < 0 && ny < edge) { scroll(dy); ny = edge }
        if (dy > 0 && ny > h - edge) { scroll(dy); ny = h - edge }
        cursor.px = (cursor.px + dx).coerceIn(0f, w - 1); cursor.py = ny.coerceIn(0f, h - 1); cursor.invalidate()
    }
    private fun scroll(dy: Float) { session.panZoomController.scrollBy(ScreenLength.zero(), ScreenLength.fromPixels(dy.toDouble() * 2)) }
    private fun tap() {
        cursor.pulse()
        val t = SystemClock.uptimeMillis(); val x = cursor.px; val y = cursor.py
        val d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0); d.source = InputDevice.SOURCE_TOUCHSCREEN
        geckoView.dispatchTouchEvent(d); d.recycle()
        geckoView.postDelayed({
            val u = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0); u.source = InputDevice.SOURCE_TOUCHSCREEN
            geckoView.dispatchTouchEvent(u); u.recycle()
        }, 60)
    }
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        val step = 30f + minOf(e.repeatCount, 15) * 6f
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { if (down) move(0f, -step); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) move(0f, step); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) move(-step, 0f); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) move(step, 0f); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { if (down && e.repeatCount == 0) tap(); return true }
            KeyEvent.KEYCODE_BACK -> { if (down) { if (canGoBack) session.goBack() else finish() }; return true }
        }
        return super.dispatchKeyEvent(e)
    }
    override fun onWindowFocusChanged(f: Boolean) { super.onWindowFocusChanged(f); if (f) hideUi() }
    override fun onDestroy() { super.onDestroy(); session.close() }
}
