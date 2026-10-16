package com.aktv.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import org.json.JSONObject
import org.mozilla.geckoview.*
import java.util.concurrent.Executors

/** Netflix / Apple TV style details page: backdrop, logo, facts, Play, and for series the seasons and episodes. */
class DetailActivity : Activity() {
    companion object {
        fun open(c: Context, t: Movies.Title) {
            c.startActivity(Intent(c, DetailActivity::class.java).putExtra("type", t.type).putExtra("imdb", t.imdb)
                .putExtra("tmdb", t.tmdb).putExtra("name", t.name).putExtra("poster", t.poster).putExtra("bg", t.background)
                .putExtra("logo", t.logo).putExtra("year", t.year).putExtra("rating", t.rating)
                .putExtra("desc", t.description).putExtra("genres", t.genres.joinToString("|")))
        }
    }

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val dp by lazy { resources.displayMetrics.density }
    private val fReg by lazy { ResourcesCompat.getFont(this, R.font.sora_regular) ?: Typeface.DEFAULT }
    private val fSemi by lazy { ResourcesCompat.getFont(this, R.font.sora_semibold) ?: Typeface.DEFAULT_BOLD }
    private val fBold by lazy { ResourcesCompat.getFont(this, R.font.sora_bold) ?: Typeface.DEFAULT_BOLD }
    private val prefs by lazy { getSharedPreferences("aktv", Context.MODE_PRIVATE) }
    private val RED = 0xFFE50914.toInt()

    private lateinit var title: Movies.Title
    private var episodes = listOf<Movies.Episode>(); private var seasons = listOf<Int>(); private var season = 1
    private lateinit var backdrop: ImageView; private lateinit var logo: ImageView; private lateinit var nameTv: TextView
    private lateinit var facts: TextView; private lateinit var desc: TextView; private lateinit var playBtn: TextView
    private lateinit var seasonRow: LinearLayout; private lateinit var epList: RecyclerView; private lateinit var body: LinearLayout
    private lateinit var loading: ProgressBar

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val i = intent
        title = Movies.Title(i.getStringExtra("imdb") ?: "", i.getIntExtra("tmdb", 0), i.getStringExtra("type") ?: "movie",
            i.getStringExtra("name") ?: "", i.getStringExtra("poster") ?: "", i.getStringExtra("bg") ?: "", i.getStringExtra("logo") ?: "",
            i.getStringExtra("year") ?: "", i.getStringExtra("rating") ?: "",
            (i.getStringExtra("genres") ?: "").split("|").filter { it.isNotEmpty() }, i.getStringExtra("desc") ?: "")
        build(); bind()
        ui.postDelayed({ if (!isFinishing) try { BrowserActivity.sharedRuntime(this) } catch (_: Exception) {} }, 500)   // movies start faster
        io.execute {
            val (t, eps) = try { Movies.details(title.type, title.tmdb) } catch (_: Exception) { null to emptyList() }
            ui.post {
                if (isFinishing) return@post
                loading.visibility = View.GONE
                if (t != null) title = t.copy(tmdb = if (t.tmdb > 0) t.tmdb else title.tmdb)
                episodes = eps; seasons = eps.map { it.season }.distinct().sorted()
                val last = prefs.getInt("last_season_${title.imdb}", seasons.firstOrNull() ?: 1)
                season = if (last in seasons) last else seasons.firstOrNull() ?: 1
                bind(); if (title.isSeries) bindSeasons()
            }
        }
    }

    private fun tv(size: Float, color: Int, face: Typeface) = TextView(this).apply { textSize = size; setTextColor(color); typeface = face }

    private fun build() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this); root.setBackgroundColor(Accent.ABYSS)
        backdrop = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.argb(250, 5, 6, 13), Color.argb(200, 5, 6, 13), Color.argb(40, 5, 6, 13)))
        }, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(255, 5, 6, 13), Color.argb(0, 5, 6, 13)))
        }, FrameLayout.LayoutParams(-1, (300 * dp).toInt(), Gravity.BOTTOM))

        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((64 * dp).toInt(), (56 * dp).toInt(), (64 * dp).toInt(), (28 * dp).toInt()) }
        logo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START; adjustViewBounds = true; visibility = View.GONE }
        body.addView(logo, LinearLayout.LayoutParams((420 * dp).toInt(), (120 * dp).toInt()))
        nameTv = tv(42f, Accent.TEXT, fBold).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        body.addView(nameTv, LinearLayout.LayoutParams((620 * dp).toInt(), -2))
        facts = tv(15f, Accent.DIM, fSemi)
        body.addView(facts, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (14 * dp).toInt() })
        desc = tv(15f, Accent.DIM, fReg).apply { maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END; setLineSpacing(0f, 1.25f) }
        body.addView(desc, LinearLayout.LayoutParams((620 * dp).toInt(), -2).apply { topMargin = (12 * dp).toInt() })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        playBtn = button("▶  Play", primary = true) { playCurrent() }
        buttons.addView(playBtn)
        loading = ProgressBar(this).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(RED) }
        buttons.addView(loading, LinearLayout.LayoutParams((28 * dp).toInt(), (28 * dp).toInt()).apply { marginStart = (16 * dp).toInt() })
        body.addView(buttons, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (26 * dp).toInt() })

        val sc = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false }
        seasonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (8 * dp).toInt(), (40 * dp).toInt(), (8 * dp).toInt()) }
        sc.addView(seasonRow)
        body.addView(sc, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (26 * dp).toInt() })
        epList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@DetailActivity, RecyclerView.HORIZONTAL, false)
            adapter = epAdapter; itemAnimator = null; clipChildren = false; clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER; setPadding(0, (6 * dp).toInt(), (160 * dp).toInt(), (20 * dp).toInt())
            setItemViewCacheSize(12)
        }
        body.addView(epList, LinearLayout.LayoutParams(-1, -2))
        root.addView(body, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        body.alpha = 0f; body.translationX = -30 * dp
        body.animate().alpha(1f).translationX(0f).setDuration(420).setInterpolator(DecelerateInterpolator(2f)).start()
        playBtn.requestFocus()
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 17f; typeface = fSemi; gravity = Gravity.CENTER; isFocusable = true; isClickable = true
        setPadding((34 * dp).toInt(), (13 * dp).toInt(), (38 * dp).toInt(), (13 * dp).toInt())
        val bg = GradientDrawable().apply { cornerRadius = 12 * dp }
        background = bg
        val style = { f: Boolean ->
            bg.setColor(if (f) Color.WHITE else if (primary) Color.argb(60, 255, 255, 255) else Color.argb(30, 255, 255, 255))
            setTextColor(if (f) Accent.ABYSS else Accent.TEXT)
            animate().scaleX(if (f) 1.08f else 1f).scaleY(if (f) 1.08f else 1f).setDuration(200).setInterpolator(DecelerateInterpolator(2f)).start()
        }
        style(false)
        setOnFocusChangeListener { _, f -> style(f) }
        setOnClickListener { onClick() }
    }

    private fun bind() {
        if (title.background.isNotEmpty()) backdrop.load(title.background) {
            crossfade(true); listener(onSuccess = { _, _ -> backdrop.animate().alpha(1f).setDuration(600).start() })
        }
        nameTv.text = title.name
        if (title.logo.isNotEmpty()) logo.load(title.logo) {
            listener(onSuccess = { _, _ -> logo.visibility = View.VISIBLE; nameTv.visibility = View.GONE },
                onError = { _, _ -> logo.visibility = View.GONE; nameTv.visibility = View.VISIBLE })
        }
        val bits = ArrayList<String>()
        if (title.rating.isNotEmpty()) bits.add("★ ${title.rating}")
        if (title.year.isNotEmpty()) bits.add(title.year)
        if (title.runtime.isNotEmpty()) bits.add(title.runtime)
        bits.add(if (title.isSeries) "Series" else "Movie")
        if (title.genres.isNotEmpty()) bits.add(title.genres.take(3).joinToString(" · "))
        facts.text = bits.joinToString("   •   ")
        desc.text = title.description
        playBtn.text = if (title.isSeries) {
            val (s, e) = lastEpisode(); "▶  Play S$s · E$e"
        } else "▶  Play"
    }

    private fun lastEpisode(): Pair<Int, Int> {
        val s = prefs.getInt("last_season_${title.imdb}", episodes.firstOrNull()?.season ?: 1)
        val e = prefs.getInt("last_ep_${title.imdb}", episodes.firstOrNull { it.season == s }?.episode ?: 1)
        return s to e
    }

    private fun bindSeasons() {
        seasonRow.removeAllViews()
        if (seasons.isEmpty()) return
        seasons.forEach { s ->
            val chip = TextView(this).apply {
                text = "Season $s"; textSize = 14f; typeface = fSemi; isFocusable = true; isClickable = true
                setPadding((20 * dp).toInt(), (9 * dp).toInt(), (20 * dp).toInt(), (9 * dp).toInt())
                val bg = GradientDrawable().apply { cornerRadius = 40 * dp }; background = bg
                val style = { f: Boolean ->
                    bg.setColor(when { f -> Color.WHITE; s == season -> Color.argb(70, 255, 255, 255); else -> Color.argb(22, 255, 255, 255) })
                    setTextColor(if (f) Accent.ABYSS else if (s == season) Accent.TEXT else Accent.DIM)
                }
                style(false); tag = style
                setOnFocusChangeListener { _, f -> style(f); if (f && season != s) { season = s; refreshSeasonChips(); showSeason() } }
                setOnClickListener { season = s; refreshSeasonChips(); showSeason(); epList.post { epList.getChildAt(0)?.requestFocus() } }
            }
            seasonRow.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (10 * dp).toInt() })
        }
        showSeason()
    }

    @Suppress("UNCHECKED_CAST")
    private fun refreshSeasonChips() {
        for (i in 0 until seasonRow.childCount) { val c = seasonRow.getChildAt(i); (c.tag as? (Boolean) -> Unit)?.invoke(c.isFocused) }
    }

    private var seasonEps = listOf<Movies.Episode>()
    private fun showSeason() {
        seasonEps = episodes.filter { it.season == season }
        epAdapter.notifyDataSetChanged(); epList.scrollToPosition(0)
        epList.alpha = 0f; epList.animate().alpha(1f).setDuration(260).start()
    }

    private val epAdapter = object : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = seasonEps.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val card = LinearLayout(p.context).apply { orientation = LinearLayout.VERTICAL; isFocusable = true; isClickable = true }
            val img = ImageView(p.context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; id = 1; clipToOutline = true
                background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(Color.argb(30, 255, 255, 255)) } }
            card.addView(img, LinearLayout.LayoutParams((240 * dp).toInt(), (135 * dp).toInt()))
            val n = tv(14f, Accent.TEXT, fSemi).apply { id = 2; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            card.addView(n, LinearLayout.LayoutParams((240 * dp).toInt(), -2).apply { topMargin = (10 * dp).toInt() })
            card.layoutParams = RecyclerView.LayoutParams(-2, -2).apply { marginEnd = (18 * dp).toInt() }
            return VH(card)
        }
        override fun onBindViewHolder(h: VH, i: Int) {
            val e = seasonEps[i]; val v = h.itemView
            val img = v.findViewById<ImageView>(1); img.setImageDrawable(null); if (e.thumb.isNotEmpty()) img.load(e.thumb) { crossfade(true) }
            v.findViewById<TextView>(2).text = "${e.episode}. ${e.name}"
            v.setOnFocusChangeListener { x, f ->
                x.animate().scaleX(if (f) 1.08f else 1f).scaleY(if (f) 1.08f else 1f).translationZ(if (f) 20 * dp else 0f)
                    .setDuration(220).setInterpolator(DecelerateInterpolator(2f)).start()
                (x as LinearLayout).getChildAt(1).let { (it as TextView).setTextColor(if (f) Accent.TEXT else Accent.DIM) }
                if (f) desc.text = e.overview.ifEmpty { title.description }
            }
            (v as LinearLayout).getChildAt(1).let { (it as TextView).setTextColor(Accent.DIM) }
            v.setOnClickListener { play(e.season, e.episode) }
        }
    }

    private fun playCurrent() {
        if (title.isSeries) { val (s, e) = lastEpisode(); play(s, e) } else play(0, 0)
    }

    private fun play(s: Int, e: Int) {
        if (title.tmdb <= 0) { Toast.makeText(this, "This title can't be played right now", Toast.LENGTH_SHORT).show(); return }
        Movies.saveTitle(prefs, title)
        val url = if (title.isSeries) {
            prefs.edit().putInt("last_season_${title.imdb}", s).putInt("last_ep_${title.imdb}", e).apply()
            Movies.tvUrl(title.tmdb, s, e)
        } else Movies.movieUrl(title.tmdb)
        startActivity(Intent(this, MoviePlayerActivity::class.java).putExtra("url", url).putExtra("name", title.name))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onResume() { super.onResume(); if (::playBtn.isInitialized) bind() }
    override fun onBackPressed() { finish(); overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out) }
}

/**
 * Full-screen TGVID player (opens the movie / episode itself, not the website). Remote keys go straight to the
 * player: OK = play / pause, Left / Right = seek, Up / Down = volume, Back = close.
 */
class MoviePlayerActivity : Activity() {
    private lateinit var gv: GeckoView
    private lateinit var session: GeckoSession
    private lateinit var spinner: ProgressBar
    private val ui = Handler(Looper.getMainLooper())
    private var started = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); hideUi()
        val root = FrameLayout(this); root.setBackgroundColor(Color.BLACK); root.keepScreenOn = true
        gv = GeckoView(this)
        spinner = ProgressBar(this).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(0xFFE50914.toInt()) }
        root.addView(gv, FrameLayout.LayoutParams(-1, -1))
        root.addView(spinner, FrameLayout.LayoutParams((56 * resources.displayMetrics.density).toInt(), (56 * resources.displayMetrics.density).toInt(), Gravity.CENTER))
        setContentView(root)

        val rt = BrowserActivity.sharedRuntime(this)
        session = GeckoSession(GeckoSessionSettings.Builder()
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP).build())
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? = null   // no pop-ups / ads
        }
        session.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(session: GeckoSession, perm: GeckoSession.PermissionDelegate.ContentPermission): GeckoResult<Int>? =
                GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)   // let it autoplay with sound
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) { hideUi() }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                spinner.animate().alpha(0f).setDuration(300).withEndAction { spinner.visibility = View.GONE }.start()
                gv.requestFocus()
            }
        }
        session.open(rt); gv.setSession(session)
        gv.isFocusable = true; gv.isFocusableInTouchMode = true
        session.loadUri(intent.getStringExtra("url") ?: "about:blank")
    }

    private fun hideUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    /** Sends a keyboard key to the page (the player listens for Space, arrows, F, C ...). */
    private fun sendKey(code: Int) {
        val t = SystemClock.uptimeMillis()
        gv.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        gv.dispatchKeyEvent(KeyEvent(t, t + 40, KeyEvent.ACTION_UP, code, 0))
    }

    private fun tapCenter() {
        val x = gv.width / 2f; val y = gv.height / 2f; val t = SystemClock.uptimeMillis()
        val d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0); d.source = InputDevice.SOURCE_TOUCHSCREEN
        gv.dispatchTouchEvent(d); d.recycle()
        ui.postDelayed({
            val u = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0); u.source = InputDevice.SOURCE_TOUCHSCREEN
            gv.dispatchTouchEvent(u); u.recycle()
        }, 60)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        when (e.keyCode) {
            KeyEvent.KEYCODE_BACK -> { if (down) finish(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                // OK = play / pause (Space in the player)
                if (down && e.repeatCount == 0) { if (!started && spinner.visibility == View.VISIBLE) tapCenter() else sendKey(KeyEvent.KEYCODE_SPACE); started = true }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) sendKey(KeyEvent.KEYCODE_DPAD_RIGHT); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) sendKey(KeyEvent.KEYCODE_DPAD_LEFT); return true }
        }
        return gv.dispatchKeyEvent(e) || super.dispatchKeyEvent(e)
    }

    override fun onWindowFocusChanged(f: Boolean) { super.onWindowFocusChanged(f); if (f) hideUi() }

    // Leaving the player: the web view's video surface is released BEFORE the screen below comes back. Waiting for onDestroy
    // (which can run after the next screen is already showing) left the old surface on top and the app looked black.
    private var closed = false
    private fun shutdown() {
        if (closed) return; closed = true
        ui.removeCallbacksAndMessages(null)
        try { session.setActive(false) } catch (_: Exception) {}
        try { gv.releaseSession() } catch (_: Exception) {}
        try { session.close() } catch (_: Exception) {}
        (gv.parent as? ViewGroup)?.removeView(gv)
    }
    override fun finish() { shutdown(); super.finish(); overridePendingTransition(0, 0) }
    override fun onPause() { super.onPause(); if (!closed) try { session.setActive(false) } catch (_: Exception) {} }
    override fun onResume() { super.onResume(); if (!closed) try { session.setActive(true) } catch (_: Exception) {} }
    override fun onDestroy() { shutdown(); super.onDestroy() }
}
