package com.aktv.app

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.util.Base64
import android.view.*
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.*
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.*
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.*
import coil.load
import coil.imageLoader
import coil.request.ImageRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class VH(v: View) : RecyclerView.ViewHolder(v)

/** The TG TV API answered with a non-2xx status. */
class ApiException(val code: Int) : IOException("HTTP $code")

class MainActivity : AppCompatActivity() {
    /** V1 = live TV, v2 = the 24/7 catalogue. [key] is unique across both, so favourites and glow colours never collide. */
    data class Channel(val id: String, val name: String, val category: String, val logo: String, val v2: Boolean = false) {
        val key: String get() = if (v2) "v2:$id" else id
    }
    sealed class Tile {
        data class Ch(val c: Channel) : Tile()
        data class Mv(val m: Movies.Title) : Tile()
        object Hint : Tile()
    }
    class Row(val title: String, var tiles: List<Tile>)

    // movie & series shelves (filled the first time the tab opens)
    private val vodRows = arrayOf(listOf<Row>(), listOf<Row>()); private val vodLoading = booleanArrayOf(false, false)   // [0] Movies, [1] Series
    private val RECENT = "Recently watched"; private val CONTINUE = "Continue watching"
    private lateinit var heroBackdrop: ImageView; private lateinit var heroPreview: View; private lateinit var previewPv: PlayerView
    private var previewPlayer: ExoPlayer? = null; private var previewToken = 0
    // ➜ CHANNEL UPDATE LINKS: the app re-reads both lists from here every launch
    private val SITE = "https://livetgtv.lovable.app"
    private val BASE = "$SITE/api/public/channels"            // V1 live TV
    private val BASE_V2 = "$SITE/api/public/v2/channels"      // V2 24/7
    private val GUIDE = "$SITE/api/public/guide"              // programme details (V1)
    private val UA = "Mozilla/5.0 (Linux; Android 9; TV) AppleWebKit/537.36 Chrome/110 Safari/537.36"
    private val io = Executors.newFixedThreadPool(4)
    private val prefs by lazy { getSharedPreferences("aktv", Context.MODE_PRIVATE) }
    private val dp by lazy { resources.displayMetrics.density }
    private val ui = Handler(Looper.getMainLooper())
    private val fRegular by lazy { ResourcesCompat.getFont(this, R.font.sora_regular) ?: Typeface.DEFAULT }
    private val fSemi by lazy { ResourcesCompat.getFont(this, R.font.sora_semibold) ?: Typeface.DEFAULT_BOLD }

    private var all = listOf<Channel>(); private var allV2 = listOf<Channel>(); private var shown = listOf<Channel>()
    private var rows = listOf<Row>(); private val inner = HashMap<Int, TileAdapter>()
    private var favs = setOf<String>(); private var heroTile: Tile? = null
    private val accents = HashMap<String, Int>()
    private var tab = 0; private var current = -1; private var retries = 0
    private var player: ExoPlayer? = null
    private var lastRefresh = 0L; private var v1Msg = "Loading channels…"

    // playback state: how this channel is being tried, so a refused stream can move on by itself
    private var playToken = 0
    private var routes = listOf<Pair<Int, Int>>(); private var routeIdx = 0          // (source, header profile)
    private var v1Sources = listOf<String>(); private var v1Drms = listOf<JSONObject?>()
    private var v2Servers = listOf<Pair<String, String>>(); private var serverIdx = 0  // (stream url, type)
    private var v2Extra = listOf<Pair<JSONObject?, Int>>()   // per V2 server: (ClearKey drm, header profile or -1 for the plain relay)
    private var attempts = 0; private var refreshed = false; private var lastCode: Int? = null
    private var okLong = false; private var stalls = 0
    // HD smoothness: picture size is capped to what this box can decode, and lowered by itself if frames start dropping
    private var trackSel: androidx.media3.exoplayer.trackselection.DefaultTrackSelector? = null
    private var capH = 1080; private var badSeconds = 0
    private val lowEnd by lazy {
        (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).isLowRamDevice || android.os.Build.SUPPORTED_64_BIT_ABIS.isEmpty()
    }
    private fun startCap(): Int {
        val ch = shown.getOrNull(current)
        val saved = if (ch != null) prefs.getInt("q_${ch.key}", 0) else 0     // what worked last time for this channel
        return if (saved > 0) saved else if (lowEnd) 720 else 1080
    }
    private val dropWatcher = object : AnalyticsListener {
        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            if (elapsedMs <= 0) return
            if (droppedFrames * 1000f / elapsedMs >= 6f) badSeconds++ else badSeconds = (badSeconds - 1).coerceAtLeast(0)
            if (badSeconds < 3 || capH <= 540) return
            badSeconds = 0
            capH = if (capH > 720) 720 else 540
            shown.getOrNull(current)?.let { prefs.edit().putInt("q_${it.key}", capH).apply() }
            trackSel?.let { it.setParameters(it.buildUponParameters().setMaxVideoSize(capH * 16 / 9, capH)) }
            playMsg("Smoothing playback (${capH}p)…")
        }
    }

    private lateinit var rowsView: RecyclerView; private lateinit var status: TextView
    private lateinit var layer: View; private lateinit var pv: PlayerView; private lateinit var spinner: ProgressBar
    private lateinit var segLive: TextView; private lateinit var segV2: TextView; private lateinit var segSites: TextView; private lateinit var segSeries: TextView
    private val segs by lazy { listOf(segLive, segV2, segSites, segSeries) }   // 0 Live, 1 24/7, 2 Movies, 3 Series
    // top navigation (Google TV style): flat pills, white while focused, grey for the open tab
    private val segBgs by lazy { List(4) { Ui.pill(dp, Color.TRANSPARENT) } }
    private var lastRowsFocus: View? = null
    private val compact by lazy { resources.configuration.screenHeightDp < 420 }
    private lateinit var searchBtn: TextView; private lateinit var intro: AkIntroView
    private lateinit var heroText: View; private lateinit var heroDot: View; private lateinit var heroBadge: TextView
    private lateinit var heroTitle: TextView; private lateinit var heroChips: LinearLayout
    private lateinit var heroOrb: View; private lateinit var heroLogo: ImageView; private lateinit var heroMono: TextView
    private lateinit var heroProg: TextView; private lateinit var heroProgress: ProgressBar
    private lateinit var heroDesc: TextView; private lateinit var heroNext: TextView
    private val orbBg by lazy { GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Accent.ABYSS, Accent.ABYSS)) }
    private lateinit var infoCard: View; private lateinit var infoLogo: ImageView; private lateinit var infoName: TextView
    private lateinit var infoCat: TextView; private lateinit var playerScrim: View; private lateinit var playerClock: View
    private lateinit var infoMono: TextView; private lateinit var infoNow: TextView; private lateinit var infoProgress: ProgressBar
    private lateinit var infoDesc: TextView; private lateinit var infoNext: TextView; private lateinit var playStatus: TextView
    // channel guide
    private val FAV = "Favorites"
    private lateinit var home: ViewGroup; private var homeFocusMode = 0; private var lastHomeFocus: View? = null
    private lateinit var guide: ViewGroup; private lateinit var guideScrim: View; private lateinit var guideDivider: View
    private lateinit var catList: RecyclerView; private lateinit var chList: RecyclerView
    private lateinit var chCol: View; private lateinit var chHeader: TextView; private lateinit var channelSearch: EditText
    private var guideShown = false; private var shownCat = ""
    private var guideCats = listOf<String>(); private var catCounts = mapOf<String, Int>()
    private var guideCat = ""; private var guideChannels = listOf<Channel>(); private var categoryChannels = listOf<Channel>()
    // search
    private lateinit var searchLayer: View; private lateinit var searchQuery: TextView; private lateinit var searchKeys: LinearLayout
    private lateinit var searchResults: RecyclerView; private lateinit var searchCount: TextView; private lateinit var searchEmpty: TextView
    private var searchShown = false; private val searchBuf = StringBuilder(); private var results = listOf<Channel>()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        TgServers.init(applicationContext); TgServers.refreshSite()
        setContentView(R.layout.activity_main)
        rowsView = findViewById(R.id.rows); status = findViewById(R.id.status)
        layer = findViewById(R.id.playerLayer); pv = findViewById(R.id.playerView); spinner = findViewById(R.id.spinner)
        segLive = findViewById(R.id.segLive); segV2 = findViewById(R.id.segV2); segSites = findViewById(R.id.segSites); segSeries = findViewById(R.id.segSeries)
        searchBtn = findViewById(R.id.searchBtn)
        heroText = findViewById(R.id.heroText); heroDot = findViewById(R.id.heroDot); heroBadge = findViewById(R.id.heroBadge)
        heroTitle = findViewById(R.id.heroTitle); heroChips = findViewById(R.id.heroChips)
        heroOrb = findViewById(R.id.heroOrb); heroLogo = findViewById(R.id.heroLogo); heroMono = findViewById(R.id.heroMono)
        heroProg = findViewById(R.id.heroProg); heroProgress = findViewById(R.id.heroProgress)
        heroDesc = findViewById(R.id.heroDesc); heroNext = findViewById(R.id.heroNext)
        infoCard = findViewById(R.id.infoCard); infoLogo = findViewById(R.id.infoLogo); infoName = findViewById(R.id.infoName)
        infoCat = findViewById(R.id.infoCat); playerScrim = findViewById(R.id.playerScrim); playerClock = findViewById(R.id.playerClock)
        infoMono = findViewById(R.id.infoMono); infoNow = findViewById(R.id.infoNow); infoProgress = findViewById(R.id.infoProgress)
        infoDesc = findViewById(R.id.infoDesc); infoNext = findViewById(R.id.infoNext); playStatus = findViewById(R.id.playStatus)
        searchLayer = findViewById(R.id.searchLayer); searchQuery = findViewById(R.id.searchQuery); searchKeys = findViewById(R.id.searchKeys)
        searchResults = findViewById(R.id.searchResults); searchCount = findViewById(R.id.searchCount); searchEmpty = findViewById(R.id.searchEmpty)
        intro = findViewById(R.id.splash)
        heroBackdrop = findViewById(R.id.heroBackdrop); heroPreview = findViewById(R.id.heroPreview); previewPv = findViewById(R.id.heroPreviewPv)

        // flat surfaces (no glass: it made the UI laggy)
        layoutHero()
        segs.forEachIndexed { i, tv -> tv.background = segBgs[i] }
        heroOrb.background = orbBg
        findViewById<View>(R.id.infoLogoBox).background = Ui.pill(dp, Accent.SURFACE, 16f)
        findViewById<TextView>(R.id.infoHint).text = SpannableStringBuilder().apply {
            key(this, "▲ ▼", "Channel"); append("     "); key(this, "▶", "All channels"); append("     ")
            key(this, "Hold OK", "Reload"); append("     "); key(this, "◀", "Switch player"); append("     "); key(this, "Back", "Exit")
        }
        val track = ColorStateList.valueOf(Color.argb(60, 255, 255, 255))
        heroProgress.progressBackgroundTintList = track; infoProgress.progressBackgroundTintList = track

        // search pill in the top bar
        val sbPill = Ui.pill(dp, Accent.SURFACE)
        searchBtn.background = sbPill
        searchBtn.setOnFocusChangeListener { _, f ->
            sbPill.setColor(if (f) Accent.TEXT else Accent.SURFACE)
            searchBtn.setTextColor(if (f) Accent.ABYSS else Accent.DIM)
            searchBtn.compoundDrawableTintList = ColorStateList.valueOf(if (f) Accent.ABYSS else Accent.DIM)
            styleSegs()
        }
        searchBtn.setOnClickListener { openSearch() }

        home = findViewById(R.id.home); homeFocusMode = home.descendantFocusability
        guide = findViewById(R.id.guide); guideScrim = findViewById(R.id.guideScrim); guideDivider = findViewById(R.id.guideDivider)
        catList = findViewById(R.id.catList); chList = findViewById(R.id.chList)
        chCol = findViewById(R.id.chCol); chHeader = findViewById(R.id.chHeader); channelSearch = findViewById(R.id.channelSearch)
        guide.background = Ui.pill(dp, 0xF21A1C21.toInt(), 22f)
        guide.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        listOf(catList to catAdapter, chList to chAdapter).forEach { (rv, ad) ->
            rv.layoutManager = LinearLayoutManager(this); rv.adapter = ad; rv.itemAnimator = null
            rv.overScrollMode = View.OVER_SCROLL_NEVER
        }
        searchResults.layoutManager = LinearLayoutManager(this); searchResults.adapter = searchAdapter
        searchResults.itemAnimator = null; searchResults.overScrollMode = View.OVER_SCROLL_NEVER
        searchQuery.background = Ui.pill(dp, Accent.SURFACE, 14f)
        buildKeys(); renderQuery()

        favs = prefs.getStringSet("favs", emptySet())!!.toSet()
        rowsView.layoutManager = SnapLayoutManager(this, RecyclerView.VERTICAL); rowsView.adapter = rowAdapter
        rowsView.itemAnimator = null; rowsView.setItemViewCacheSize(6); rowsView.setHasFixedSize(true)
        (rowsView.layoutManager as LinearLayoutManager).initialPrefetchItemCount = 3
        rowsView.overScrollMode = View.OVER_SCROLL_NEVER
        rowsView.viewTreeObserver.addOnGlobalFocusChangeListener { old, new ->
            dimRows()
            if (new != null && isInside(new, rowsView)) {
                lastRowsFocus = new
                // first row = full hero; any row below it = hero folds away, so the list is the whole page and you can see where you are
                val pos = rowsView.findContainingItemView(new)?.let { rowsView.getChildAdapterPosition(it) } ?: 0
                setHeroCollapsed(pos >= 1)
            }
            val ni = segs.indexOfFirst { it === new }
            if (ni >= 0) {
                val fromSeg = segs.any { it === old } || old === searchBtn
                if (fromSeg) switchTab(ni)
                else if (ni != tab) segs[tab].post { if (segs[ni].isFocused) segs[tab].requestFocus() }   // focus fell onto the bar by accident
            }
        }
        channelSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim().orEmpty()
                guideChannels = if (q.isEmpty()) categoryChannels else categoryChannels.filter {
                    it.name.contains(q, ignoreCase = true) || it.category.contains(q, ignoreCase = true)
                }
                chHeader.text = "$guideCat  ${guideChannels.size}"
                chAdapter.notifyDataSetChanged()
                chList.scrollToPosition(0)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        segs.forEachIndexed { i, tv ->
            tv.setOnClickListener { switchTab(i) }
            tv.setOnFocusChangeListener { _, _ -> styleSegs() }
        }
        styleSegs(); fillHero(null); loadChannels()

        // Entrance: the AK ribbons unfold, then the mark flies through and the home screen rises in behind it.
        intro.wordmark = fSemi
        home.alpha = 0f; home.translationY = 28 * dp
        intro.onReveal = {
            home.animate().alpha(1f).translationY(0f).setDuration(650).setInterpolator(DecelerateInterpolator(2f)).start()
        }
        intro.onDone = { intro.visibility = View.GONE; rowsView.requestFocus() }
        Movies.diskDir = cacheDir
        ui.postDelayed({ loadVod(2) }, 1500)   // warm up the Movies tab, then Series, in the background
        ui.postDelayed({ loadVod(3) }, 5000)
        intro.start()
    }

    // ---------- helpers ----------
    private fun key(sb: SpannableStringBuilder, k: String, label: String) {
        val s = sb.length; sb.append(k)
        sb.setSpan(ForegroundColorSpan(Accent.TEXT), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(TypefaceSpan(fSemi), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("  ").append(label)
    }
    private fun keyHint(k: String, label: String) = SpannableStringBuilder().also { key(it, k, label) }

    /** Hero button: [primary] is the white pill (Watch / Details), the rest are soft grey pills. */
    private fun chip(text: CharSequence, primary: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = 13f; typeface = fSemi
        setTextColor(if (primary) Accent.ABYSS else Accent.TEXT)
        setPadding((18 * dp).toInt(), (9 * dp).toInt(), (18 * dp).toInt(), (9 * dp).toInt())
        background = Ui.pill(dp, if (primary) Accent.TEXT else 0xCC2B2D33.toInt())
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (10 * dp).toInt() }
    }

    /** Sizes the hero from the real screen: media top-right, text bottom-left, rows below. */
    private fun layoutHero() {
        val dm = resources.displayMetrics
        val w = dm.widthPixels; val h = dm.heightPixels
        val bar = ((if (compact) 56 else 72) * dp).toInt()
        val mediaH = (h * 0.58f).toInt()
        findViewById<View>(R.id.topBar).layoutParams.height = bar
        findViewById<View>(R.id.heroMedia).layoutParams.apply { width = (w * 0.70f).toInt(); height = mediaH }
        findViewById<View>(R.id.heroArea).layoutParams.height = (mediaH - bar).coerceAtLeast((120 * dp).toInt())
        heroText.layoutParams.width = (w * 0.46f).toInt()
        heroDesc.maxLines = if (compact) 1 else 2
        heroTitle.textSize = if (compact) 28f else 36f
    }

    private fun styleSegs() {
        val label = when (tab) { 2 -> "Search movies"; 3 -> "Search series"; else -> "Search channels" }
        if (searchBtn.text.toString() != label) searchBtn.text = label
        segs.forEachIndexed { i, tv ->
            val f = tv.isFocused
            segBgs[i].setColor(when { f -> Accent.TEXT; tab == i -> 0xFF3C4043.toInt(); else -> Color.TRANSPARENT })
            tv.setTextColor(when { f -> Accent.ABYSS; tab == i -> Accent.TEXT; else -> Accent.DIM })
        }
    }

    /** Focused card grows a little; the white ring comes from the view's own foreground state. No shadows, no glow. */
    private fun focusAnim(x: View, f: Boolean, color: Int = 0) {
        val s = if (f) 1.08f else 1f
        x.animate().cancel()
        x.animate().setStartDelay(0).scaleX(s).scaleY(s).setDuration(150).setInterpolator(DecelerateInterpolator(1.6f)).start()
    }
    /**
     * Rows without focus step back so the active row reads first. Only the row's title steps back:
     * fading the whole row put it in an offscreen layer that clipped the tiles' glow into a hard dark band over the channels.
     */
    private fun dimRows() {
        val inRows = rowsView.hasFocus()
        for (i in 0 until rowsView.childCount) {
            val c = rowsView.getChildAt(i)
            val a = if (!inRows || c.hasFocus()) 1f else 0.45f
            for (id in intArrayOf(R.id.rowTitle, R.id.rowCount)) {
                val t = c.findViewById<View>(id) ?: continue
                if (t.alpha != a) t.animate().alpha(a).setDuration(220).start()
            }
        }
    }
    private fun focusRowAt(pos: Int) {
        val holder = rowsView.findViewHolderForAdapterPosition(pos) ?: return
        val rv = holder.itemView.findViewById<RecyclerView>(R.id.rowList) ?: return
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstCompletelyVisibleItemPosition().coerceAtLeast(0)
        (lm.findViewByPosition(first) ?: rv.getChildAt(0))?.requestFocus()
    }
    private fun isInside(v: View, parent: View): Boolean {
        var p: ViewParent? = v.parent
        while (p != null) { if (p === parent) return true; p = p.parent }
        return false
    }
    private fun accentOf(key: String) = accents[key] ?: Accent.SIGNAL
    private fun extractAccent(key: String, d: android.graphics.drawable.Drawable, cb: (Int) -> Unit) {
        accents[key]?.let { cb(it); return }
        Accent.from(d) { c -> accents[key] = c; cb(c) }
    }
    private fun str(o: JSONObject, k: String) = if (o.isNull(k)) "" else o.optString(k, "")
    private fun initials(n: String): String {
        val w = n.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return (if (w.size >= 2) "${w[0].first()}${w[1].first()}" else n.trim().take(2)).uppercase()
    }
    /** Channel logo, or a coloured monogram when the channel has no logo (all of V2). */
    private fun bindLogo(img: ImageView, mono: TextView, ch: Channel) {
        if (ch.logo.isBlank()) {
            img.setImageDrawable(null); img.visibility = View.GONE
            mono.visibility = View.VISIBLE; mono.text = initials(ch.name); mono.setTextColor(accentOf(ch.key))
        } else { mono.visibility = View.GONE; img.visibility = View.VISIBLE; img.load(ch.logo) }
    }

    /**
     * GET with quick retries: the API answers 502 now and then when its upstream blinks, and
     * that used to surface straight away as a failed channel.
     */
    private fun get(u: String, tries: Int = 3, ua: String = UA): String {
        var last: Exception? = null
        for (n in 1..tries) {
            try {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 15000
                c.setRequestProperty("User-Agent", ua); c.setRequestProperty("Accept", "application/json")
                val code = c.responseCode
                if (code in 200..299) return c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                last = ApiException(code)
                if (code in 400..499 && code != 408 && code != 429) break      // asking again won't change a 4xx
            } catch (e: IOException) { last = e }
            if (n < tries) Thread.sleep(600L * n)
        }
        throw last ?: IOException("no response")
    }
    private fun why(e: Throwable): String = when {
        e is ApiException && e.code == 404 -> "no live stream for this channel right now"
        e is ApiException && e.code == 400 -> "unknown channel"
        e is ApiException && e.code >= 500 -> "the TG TV server is busy (HTTP ${e.code}), try again in a moment"
        e is ApiException -> "HTTP ${e.code}"
        e is java.net.UnknownHostException || e is java.net.SocketTimeoutException || e is java.net.ConnectException -> "no connection to the server"
        else -> e.message ?: e.javaClass.simpleName
    }

    // ---------- data ----------
    private fun cacheFile(n: String) = File(filesDir, n)
    private fun readCache(n: String): String? = try { cacheFile(n).takeIf { it.exists() }?.readText() } catch (e: Exception) { null }
    private fun writeCache(n: String, s: String) { try { cacheFile(n).writeText(s) } catch (_: Exception) {} }

    private fun parseV1(json: String): List<Channel> {
        val arr = JSONObject(json).getJSONArray("channels")
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = str(o, "id"); val name = str(o, "name").trim()
            if (id.isEmpty() || name.isEmpty()) null
            else Channel(id, name, str(o, "category").ifBlank { "Other" }, str(o, "logo"))
        }
    }
    private fun parseV2(json: String): List<Channel> {
        val arr = JSONObject(json).getJSONArray("channels")
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = str(o, "id"); val name = str(o, "name").trim()
            if (id.isEmpty() || name.isEmpty()) null
            else {
                val first = name.first().uppercaseChar()
                Channel(id, name, str(o, "category").ifBlank { if (first.isLetter()) first.toString() else "#" }, str(o, "logo"), true)
            }
        }.sortedBy { it.name.lowercase() }
    }
    /** Channels without a logo to sample get a stable colour from their name, so the UI still reacts to them. */
    private fun seedAccents(list: List<Channel>) { for (c in list) if (c.logo.isBlank()) accents[c.key] = Accent.fromName(c.name) }

    private fun applyV1(list: List<Channel>) {
        if (list == all) return
        all = list; seedAccents(list); updateStatus()
        if (tab == 0) { refreshRows(); if (intro.visibility != View.VISIBLE && currentFocus == null) rowsView.requestFocus() }
        if (searchShown) updateResults()
    }
    private fun applyV2(list: List<Channel>) {
        if (list == allV2) return
        allV2 = list; seedAccents(list); updateStatus()
        if (tab == 1) { refreshRows(); if (intro.visibility != View.VISIBLE && currentFocus == null) rowsView.requestFocus() }
        if (searchShown) updateResults()
    }
    private fun updateStatus() {
        val msg = when { tab == 0 && all.isEmpty() -> v1Msg; tab == 1 && allV2.isEmpty() -> "Loading 24/7 channels…"
            tab >= 2 && vodRows[tab - 2].isEmpty() -> { val w = if (tab == 3) "series" else "movies"; if (vodLoading[tab - 2]) "Loading $w…" else "Couldn't load $w. Check the connection and open the tab again." }
            else -> "" }
        status.text = msg; status.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Shows the saved lists instantly, then re-reads both update links so new / changed channels arrive on their own. */
    private fun loadChannels() {
        lastRefresh = System.currentTimeMillis()
        readCache("v1.json")?.let { try { applyV1(parseV1(it)) } catch (_: Exception) {} }
        readCache("v2.json")?.let { try { applyV2(parseV2(it)) } catch (_: Exception) {} }
        v1Msg = "Loading channels…"; updateStatus()
        io.execute {
            try {
                val j = get(BASE); val list = parseV1(j)
                if (list.isEmpty()) throw IOException("empty channel list")
                writeCache("v1.json", j)
                runOnUiThread { applyV1(list) }
            } catch (e: Exception) {
                runOnUiThread {
                    if (all.isEmpty()) { v1Msg = "Channels didn't load (${why(e)}). Check the connection and reopen the app."; updateStatus() }
                }
            }
        }
        io.execute {
            try {
                val j = get(BASE_V2); val list = parseV2(j)
                if (list.isEmpty()) throw IOException("empty list")
                writeCache("v2.json", j)
                runOnUiThread { applyV2(list) }
            } catch (_: Exception) {}
        }
    }

    private fun pool() = if (tab == 1) allV2 else all
    private fun favTiles(): List<Tile> {
        val l = pool().filter { it.key in favs }.map { Tile.Ch(it) }
        return if (l.isEmpty()) listOf(Tile.Hint) else l
    }

    /** Channels played lately (newest first), limited to the current tab's list. */
    private fun recentRow(): List<Row> {
        val keys = prefs.getString("recent_ch", "")!!.split(",").filter { it.isNotEmpty() }
        val byKey = pool().associateBy { it.key }
        val l = keys.mapNotNull { byKey[it] }.map { Tile.Ch(it) }
        return if (l.isEmpty()) emptyList() else listOf(Row(RECENT, l))
    }
    private fun rememberChannel(c: Channel) {
        val keys = listOf(c.key) + prefs.getString("recent_ch", "")!!.split(",").filter { it.isNotEmpty() && it != c.key }
        prefs.edit().putString("recent_ch", keys.take(20).joinToString(",")).apply()
    }
    private fun continueRow(): List<Row> {
        val type = if (tab == 3) "series" else "movie"
        val l = Movies.recent(prefs).filter { it.type == type }.map { Tile.Mv(it) }
        return if (l.isEmpty()) emptyList() else listOf(Row(CONTINUE, l))
    }
    /**
     * Loads the shelves of one VOD tab (2 = Movies, 3 = Series) in parallel and shows each one the moment it (and those above it) are ready.
     * A title is shown only in the first shelf that has it, so the same poster no longer repeats down the page.
     */
    private fun loadVod(t: Int) {
        val idx = t - 2
        if (idx !in 0..1 || vodLoading[idx] || vodRows[idx].isNotEmpty()) return
        vodLoading[idx] = true; updateStatus()
        val shelves = Movies.shelves(if (idx == 1) "series" else "movie")
        Thread {
            val n = shelves.size
            val res = arrayOfNulls<List<Movies.Title>>(n); val done = BooleanArray(n)
            val pool = Executors.newFixedThreadPool(6)
            var published = 0
            val publish = {
                runOnUiThread {
                    synchronized(res) {
                        var k = published
                        while (k < n && done[k]) k++
                        if (k > published) {
                            published = k
                            val fresh = dedupeShelves(shelves, res, k, vodRows[idx])
                            val before = vodRows[idx].size; vodRows[idx] = fresh
                            if (tab == t) {
                                if (before == 0) { refreshRows(); if (segs.none { it.isFocused } && !searchBtn.isFocused && !searchShown) rowsView.requestFocus() }
                                else {
                                    val base = continueRow().size
                                    rows = continueRow() + vodRows[idx]
                                    if (fresh.size > before) rowAdapter.notifyItemRangeInserted(base + before, fresh.size - before)
                                }
                            }
                            updateStatus()
                        }
                    }
                }
            }
            shelves.forEachIndexed { i, sh ->
                pool.execute {
                    try { val l = Movies.shelf(sh); if (l.isNotEmpty()) synchronized(res) { res[i] = l } } catch (_: Exception) {}
                    synchronized(res) { done[i] = true }
                    publish()
                }
            }
            pool.shutdown(); pool.awaitTermination(40, java.util.concurrent.TimeUnit.SECONDS)
            runOnUiThread { vodLoading[idx] = false; updateStatus() }
        }.start()
    }

    /** Builds the rows for the first [upTo] shelves, giving each title to the first shelf that has it. Shelves left with too few new titles are skipped. */
    private fun dedupeShelves(shelves: List<Movies.Shelf>, res: Array<List<Movies.Title>?>, upTo: Int, old: List<Row>): List<Row> {
        val seen = HashSet<String>(); val out = ArrayList<Row>()
        val byTitle = old.associateBy { it.title }
        for (i in 0 until upTo) {
            val l = res[i] ?: continue
            val keep = l.filter { seen.add(it.imdb) }.take(50)
            if (keep.size < 6) { keep.forEach { seen.remove(it.imdb) }; continue }
            val tiles = keep.map { Tile.Mv(it) }
            val title = shelves[i].title
            out.add(byTitle[title]?.takeIf { it.tiles == tiles } ?: Row(title, tiles))   // same Row object when nothing changed
        }
        return out
    }

    private fun refreshRows() {
        rows = when (tab) {
            0 -> listOf(Row(FAV, favTiles())) + recentRow() + all.groupBy { it.category }.toSortedMap().map { (k, v) -> Row(k, v.map { Tile.Ch(it) }) }
            1 -> listOf(Row(FAV, favTiles())) + recentRow() + allV2.groupBy { it.category }.toSortedMap().map { (k, v) -> Row(k, v.map { Tile.Ch(it) }) }
            else -> { loadVod(tab); continueRow() + vodRows[tab - 2] }
        }
        setHeroCollapsed(false)
        inner.clear()
        rowAdapter.notifyDataSetChanged()
        val first: Tile? = when (tab) {
            0 -> pool().firstOrNull()?.let { Tile.Ch(it) }
            1 -> pool().firstOrNull()?.let { Tile.Ch(it) }
            else -> rows.firstOrNull()?.tiles?.firstOrNull()
        }
        setHero(first, immediate = true)
        updateStatus()
    }

    private fun toggleFav(c: Channel) {
        val s = favs.toMutableSet(); val added = s.add(c.key); if (!added) s.remove(c.key)
        favs = s; prefs.edit().putStringSet("favs", s).apply()
        Toast.makeText(this, if (added) "Added to favorites" else "Removed from favorites", Toast.LENGTH_SHORT).show()
        for ((ri, ad) in inner) if (ri > 0) {
            val idx = rows.getOrNull(ri)?.tiles?.indexOfFirst { it is Tile.Ch && it.c.key == c.key } ?: -1
            if (idx >= 0) ad.notifyItemChanged(idx, "fav")
        }
        if (rows.firstOrNull()?.title == FAV) { rows[0].tiles = favTiles(); rowAdapter.notifyItemChanged(0) }
        (heroTile as? Tile.Ch)?.let { if (it.c.key == c.key) fillHero(it) }
    }

    private fun switchTab(t: Int) {
        if (t == tab) return
        val dir = if (t > tab) 1 else -1
        tab = t; styleSegs(); stopPreview()
        val shift = 28 * dp * dir
        rowsView.animate().cancel()
        rowsView.animate().alpha(0f).translationY(-shift).setDuration(150).withEndAction {
            refreshRows(); rowsView.scrollToPosition(0)
            rowsView.translationY = shift
            rowsView.animate().alpha(1f).translationY(0f).setDuration(280).setInterpolator(DecelerateInterpolator(1.8f)).start()
        }.start()
    }

    // ---------- hero ----------
    private val heroRunnable = Runnable { applyHero(heroTile) }
    private var heroCollapsed = false
    /** Folds the hero (preview + text) away once you go below the first row, and brings it back on the first row. */
    private fun setHeroCollapsed(c: Boolean) {
        if (c == heroCollapsed) return
        heroCollapsed = c
        val media = findViewById<View>(R.id.heroMedia); val area = findViewById<View>(R.id.heroArea)
        media.animate().cancel(); area.animate().cancel()
        if (c) {
            stopPreview(); ui.removeCallbacks(heroRunnable); ui.removeCallbacks(heroGuideRunnable)
            media.animate().alpha(0f).setDuration(140).start()
            area.animate().alpha(0f).setDuration(140).withEndAction { if (heroCollapsed) area.visibility = View.GONE }.start()
        } else {
            area.visibility = View.VISIBLE
            area.animate().alpha(1f).setDuration(220).start(); media.animate().alpha(1f).setDuration(220).start()
            fillHero(heroTile)
        }
    }
    private fun prefetch(url: String) {
        if (url.isEmpty()) return
        imageLoader.enqueue(ImageRequest.Builder(this).data(url).build())
    }
    private fun setHero(t: Tile?, immediate: Boolean = false) {
        heroTile = t; ui.removeCallbacks(heroRunnable)
        if (heroCollapsed && !immediate) return                 // hero is folded away: nothing to redraw while scrolling down
        if (immediate) fillHero(t) else ui.postDelayed(heroRunnable, 130)   // debounce fast scrolling
    }
    private fun applyHero(t: Tile?) {
        heroText.animate().cancel()
        heroText.animate().alpha(0f).translationX(-10 * dp).setDuration(110).withEndAction {
            fillHero(t); heroText.translationX = 16 * dp
            heroText.animate().alpha(1f).translationX(0f).setDuration(300).setInterpolator(DecelerateInterpolator(2f)).start()
        }.start()
    }
    /** Tints the still hero panel (shown until the live preview is ready) with the channel's colour. */
    private fun glowHero(c: Int, partner: Int) {
        orbBg.colors = intArrayOf(Ui.mix(Accent.ABYSS, c, 0.42f), Ui.mix(Accent.ABYSS, partner, 0.12f))
        heroProgress.progressTintList = ColorStateList.valueOf(c)
    }
    private fun showBackdrop(url: String?) {
        if (url.isNullOrEmpty()) { heroBackdrop.animate().alpha(0f).setDuration(300).start(); heroOrb.animate().alpha(1f).setDuration(250).start(); return }
        heroOrb.animate().alpha(0f).setDuration(200).start()
        heroBackdrop.load(url) {
            crossfade(180)
            listener(onSuccess = { _, _ -> if ((heroTile as? Tile.Mv)?.m?.background == url) heroBackdrop.animate().alpha(1f).setDuration(260).start() })
        }
    }
    private fun fillHero(t: Tile?) {
        heroChips.removeAllViews()
        if (t !is Tile.Mv) showBackdrop(null)
        schedulePreview((t as? Tile.Ch)?.c)
        when (t) {
            is Tile.Ch -> {
                heroDot.visibility = View.VISIBLE; heroDot.backgroundTintList = ColorStateList.valueOf(Accent.LIVE)
                heroBadge.text = "LIVE   ·   " + (if (t.c.v2) "24/7   ·   " else "") + t.c.category
                heroTitle.text = t.c.name
                heroChips.addView(chip("▶   Watch", true))
                heroChips.addView(chip(keyHint("Hold OK", if (t.c.key in favs) "Remove from favourites" else "Add to favourites")))
                if (t.c.logo.isBlank()) {
                    heroLogo.visibility = View.GONE; heroMono.visibility = View.VISIBLE
                    heroMono.text = initials(t.c.name); heroMono.textSize = if (heroMono.text.length > 1) 62f else 84f
                    heroMono.setTextColor(accentOf(t.c.key))
                } else {
                    heroMono.visibility = View.GONE; heroLogo.visibility = View.VISIBLE
                    heroLogo.load(t.c.logo) {
                        allowHardware(false); crossfade(true)
                        listener(onSuccess = { _, r ->
                            extractAccent(t.c.key, r.drawable) { c -> if ((heroTile as? Tile.Ch)?.c?.key == t.c.key) glowHero(c, Accent.partner(c)) }
                        })
                    }
                }
                accents[t.c.key]?.let { glowHero(it, Accent.partner(it)) }
                scheduleHeroGuide(t.c)
            }
            is Tile.Mv -> {
                val red = 0xFFE50914.toInt()
                heroDot.visibility = View.GONE
                heroTitle.text = t.m.name
                heroBadge.text = listOfNotNull(
                    if (t.m.isSeries) "Series" else "Movie",
                    t.m.year.ifEmpty { null },
                    if (t.m.rating.isNotEmpty()) "★ ${t.m.rating}" else null,
                    t.m.genres.take(2).joinToString(" · ").ifEmpty { null }
                ).joinToString("   ·   ")
                heroChips.addView(chip("▶   Details", true))
                heroProg.visibility = View.GONE; heroProgress.visibility = View.GONE; heroNext.visibility = View.GONE
                heroDesc.text = t.m.description; heroDesc.visibility = if (t.m.description.isEmpty()) View.GONE else View.VISIBLE
                ui.removeCallbacks(heroGuideRunnable)
                glowHero(red, 0xFF5A0A12.toInt()); showBackdrop(t.m.background.ifEmpty { t.m.poster })
            }
            else -> {
                heroDot.visibility = View.GONE; heroBadge.text = ""; heroTitle.text = "AK TV"
                heroMono.visibility = View.GONE; heroLogo.visibility = View.VISIBLE; heroLogo.setImageResource(R.drawable.ic_mark)
                glowHero(Accent.SIGNAL, Accent.INDIGO); scheduleHeroGuide(null)
            }
        }
    }

    // ---------- programme details ----------
    private fun fetchGuide(c: Channel, cb: (Guide?) -> Unit) {
        if (c.v2) { cb(null); return }
        Epg.cached(c.id)?.let { cb(it); return }
        io.execute {
            val g = try { Epg.parse(get("$GUIDE/${c.id}", 2)).also { Epg.put(c.id, it) } } catch (e: Exception) { null }
            runOnUiThread { cb(g) }
        }
    }
    private fun span(sb: SpannableStringBuilder, from: Int, color: Int) {
        sb.setSpan(ForegroundColorSpan(color), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(TypefaceSpan(fSemi), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    /** "NOW   Show title   9:30 PM – 10:00 PM" */
    private fun progLine(label: String, p: Programme, range: Boolean = true): CharSequence {
        val sb = SpannableStringBuilder()
        var s = sb.length; sb.append(label); span(sb, s, Accent.DIM)
        sb.append("   ")
        s = sb.length; sb.append(p.title); span(sb, s, Accent.TEXT)
        val r = if (range) Epg.range(p) else Epg.startOf(p)
        if (r.isNotBlank()) { sb.append("   "); s = sb.length; sb.append(r); sb.setSpan(ForegroundColorSpan(Accent.DIM), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        return sb
    }
    private val heroGuideRunnable = Runnable {
        val c = (heroTile as? Tile.Ch)?.c ?: return@Runnable
        fetchGuide(c) { g -> if ((heroTile as? Tile.Ch)?.c?.key == c.key) applyHeroGuide(g) }
    }
    private fun scheduleHeroGuide(c: Channel?) {
        ui.removeCallbacks(heroGuideRunnable)
        if (c == null) { applyHeroGuide(null); return }
        if (c.v2) {
            applyHeroGuide(null)
            heroDesc.text = "Always-on channel with several servers; if one stalls the player switches by itself."
            heroDesc.visibility = View.VISIBLE; return
        }
        val g = Epg.cached(c.id)
        if (g != null) applyHeroGuide(g) else { applyHeroGuide(null); ui.postDelayed(heroGuideRunnable, 320) }
    }
    private fun applyHeroGuide(g: Guide?) {
        val n = g?.now; val nx = g?.next
        heroProg.visibility = if (n != null) View.VISIBLE else View.GONE
        heroProgress.visibility = heroProg.visibility
        if (n != null) { heroProg.text = progLine("NOW", n); heroProgress.progress = (Epg.progress(n) * 1000).toInt() }
        val d = n?.desc.orEmpty()
        heroDesc.text = d; heroDesc.visibility = if (d.isEmpty()) View.GONE else View.VISIBLE
        heroNext.visibility = if (nx != null && !compact) View.VISIBLE else View.GONE
        if (nx != null) heroNext.text = progLine("NEXT", nx, range = false)
    }

    // ---------- player overlay ----------
    private val hideInfo = Runnable {
        infoCard.animate().alpha(0f).translationY(14 * dp).setDuration(450).start()
        playerScrim.animate().alpha(0f).setDuration(450).start()
        playerClock.animate().alpha(0f).setDuration(450).start()
    }
    private fun showInfo() {
        ui.removeCallbacks(hideInfo)
        infoCard.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator(2f)).start()
        playerScrim.animate().alpha(1f).setDuration(260).start()
        playerClock.animate().alpha(1f).setDuration(260).start()
        ui.postDelayed(hideInfo, 5000)
    }
    private fun bindInfo(ch: Channel) {
        infoName.text = ch.name
        infoCat.text = "${if (ch.v2) "V2 · 24/7" else ch.category}   ${current + 1} of ${shown.size}"
        bindLogo(infoLogo, infoMono, ch)
        val ac = accentOf(ch.key)
        spinner.indeterminateTintList = ColorStateList.valueOf(ac)
        infoProgress.progressTintList = ColorStateList.valueOf(ac)
        infoNow.visibility = View.GONE; infoProgress.visibility = View.GONE; infoDesc.visibility = View.GONE; infoNext.visibility = View.GONE
        if (ch.v2) { infoNow.text = "24/7 live channel"; infoNow.visibility = View.VISIBLE }
        else fetchGuide(ch) { g -> if (shown.getOrNull(current)?.key == ch.key) fillInfoGuide(g) }
        showInfo()
    }
    private fun fillInfoGuide(g: Guide?) {
        val n = g?.now
        if (n == null) { infoNow.visibility = View.GONE; infoProgress.visibility = View.GONE; infoDesc.visibility = View.GONE; infoNext.visibility = View.GONE; return }
        infoNow.text = progLine("NOW", n); infoNow.visibility = View.VISIBLE
        infoProgress.progress = (Epg.progress(n) * 1000).toInt(); infoProgress.visibility = View.VISIBLE
        infoDesc.text = n.desc; infoDesc.visibility = if (n.desc.isEmpty()) View.GONE else View.VISIBLE
        val nx = g.next
        if (nx != null) { infoNext.text = progLine("NEXT", nx); infoNext.visibility = View.VISIBLE } else infoNext.visibility = View.GONE
    }
    private fun playMsg(msg: String?) {
        if (msg == null) playStatus.visibility = View.GONE else { playStatus.text = msg; playStatus.visibility = View.VISIBLE }
    }

    // ---------- playback ----------
    private fun hexToB64Url(hex: String): String {
        val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    // ---------- TG TV player, embedded right in the player screen (V1 channels) ----------
    private var gv: GeckoView? = null
    private var gs: GeckoSession? = null

    private fun geckoRuntime(): GeckoRuntime {
        BrowserActivity.runtime?.let { return it }
        val r = GeckoRuntime.create(applicationContext)
        r.settings.contentBlocking.setAntiTracking(ContentBlocking.AntiTracking.AD or ContentBlocking.AntiTracking.ANALYTIC)
        BrowserActivity.runtime = r
        return r
    }

    /** V1 channels play only in the site's own player page (/embed/{id}): no stream link, header route or ExoPlayer. */
    private var embedAlt = false; private var embedStart = 0L
    private fun startEmbed(ch: Channel, alt: Boolean = false) {
        val tk = ++playToken; disarmStall()
        pv.player = null; player?.release(); player = null          // no ExoPlayer while a web player is showing
        spinner.visibility = View.VISIBLE
        embedAlt = alt; embedStart = System.currentTimeMillis()
        if (!alt) { openEmbed(ch, "$SITE/embed/${ch.id}"); return }
        // backup player: jjtvxweb.pages.dev/pind?id=<channel id>
        io.execute {
            val u = try { TgServers.altUrl(ch.id, ch.name) } catch (_: Exception) { TgServers.ALT_SITE }
            runOnUiThread { if (tk == playToken) { embedStart = System.currentTimeMillis(); openEmbed(ch, u) } }
        }
    }
    private fun openEmbed(ch: Channel, url: String) {
        val open = gs
        if (open != null) { open.loadUri(url); showInfo(); return }  // zapping reuses the same page view
        val view = GeckoView(this)
        val session = GeckoSession(GeckoSessionSettings.Builder()
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP).build())
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? = null   // block pop-ups
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                // the website's player page itself would not load: go to the last-resort player
                if (!success && !embedAlt && System.currentTimeMillis() - embedStart > 3000) { val c = shown.getOrNull(current); if (c != null && gs === session) { playMsg("TG player didn't load. Trying backup player…"); startEmbed(c, true); return } }
                spinner.visibility = View.GONE
                ui.postDelayed({ if (gs === session) playMsg(null) }, 3500)
            }
        }
        session.open(geckoRuntime()); view.setSession(session)
        (layer as ViewGroup).addView(view, 1, FrameLayout.LayoutParams(-1, -1))
        gv = view; gs = session
        session.loadUri(url)
        showInfo()
    }

    private fun releaseEmbed() {
        val v = gv; val s = gs
        gv = null; gs = null
        if (v != null) { try { v.releaseSession() } catch (_: Exception) {}; (v.parent as? ViewGroup)?.removeView(v) }
        if (s != null) { try { s.close() } catch (_: Exception) {} }
    }

    /** OK on the remote = click the middle of the page (the player's big play button). */
    private fun tapEmbed() {
        val v = gv ?: return
        val x = v.width / 2f; val y = v.height / 2f
        val t = SystemClock.uptimeMillis()
        val d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0); d.source = InputDevice.SOURCE_TOUCHSCREEN
        v.dispatchTouchEvent(d); d.recycle()
        v.postDelayed({
            val u = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0); u.source = InputDevice.SOURCE_TOUCHSCREEN
            v.dispatchTouchEvent(u); u.recycle()
        }, 60)
    }

    /** User picked a channel (or zapped): start fresh. */
    private fun play(index: Int) {
        if (shown.isEmpty()) return
        current = (index + shown.size) % shown.size
        val ch = shown[current]
        stopPreview(); rememberChannel(ch)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); layer.keepScreenOn = true
        if (layer.visibility != View.VISIBLE) { layer.animate().cancel(); layer.alpha = 1f; layer.visibility = View.VISIBLE }
        attempts = 0; refreshed = false; lastCode = null; serverIdx = 0; stalls = 0
        playMsg(null); bindInfo(ch)
        if (wantsTg(ch)) startEmbed(ch, prefs.getString("eng_${ch.key}", "native") == "alt") else resolveAndStart(ch)
    }

    // ---------- two players for V1: native (ExoPlayer + ClearKey) and the TG web player ----------
    private fun wantsTg(ch: Channel) = !ch.v2 && prefs.getString("eng_${ch.key}", "native").let { it == "tg" || it == "alt" }
    private fun setEngine(ch: Channel, tg: Boolean) {
        if (ch.v2) return
        val v = if (tg) "tg" else "native"
        if (prefs.getString("eng_${ch.key}", "native") != v) prefs.edit().putString("eng_${ch.key}", v).apply()
    }

    /** Native player could not play this V1 channel (refused, DRM, decoder, no answer): continue in the TG player. */
    private fun fallbackToTg(ch: Channel, why: String) {
        if (ch.v2) { fail("${ch.name}: $why"); return }
        setEngine(ch, true)
        playMsg("Native player: $why. Switching to TG player…")
        startEmbed(ch)
    }

    /** Every server failed: last resort, the backup player page (jjtvxweb.pages.dev) inside the app. */
    private fun fallbackAlt(ch: Channel, why: String) {
        playMsg("$why. Trying backup player…")
        startEmbed(ch, true)
    }

    /** Left / Menu while a V1 channel plays: native player -> TG player -> backup player (remembered per channel). */
    private fun switchEngine() {
        val ch = shown.getOrNull(current) ?: return
        if (ch.v2) { reloadStream(); return }
        retries = 0; attempts = 0; refreshed = false; stalls = 0; lastCode = null
        if (gs != null && !embedAlt) { prefs.edit().putString("eng_${ch.key}", "alt").apply(); playMsg("Backup player…"); startEmbed(ch, true) }
        else if (gs != null) { setEngine(ch, false); playMsg("Native player…"); resolveAndStart(ch) }
        else { setEngine(ch, true); playMsg("TG player…"); startEmbed(ch) }
    }

    /** Ask the site for the channel's current stream link(s) (they are short-lived) and start playing them. */
    private fun resolveAndStart(ch: Channel) {
        val token = ++playToken
        val fresh = attempts > 0 || retries > 0 || refreshed
        disarmStall(); releasePlayer(); spinner.visibility = View.VISIBLE
        // everything is asked at the same time (site servers, channel API, bundled playlist): playback starts as soon as they are in, not one after another
        val tgF = io.submit(java.util.concurrent.Callable<List<TgServers.Server>> { try { TgServers.fetch(ch.id, fresh) } catch (_: Exception) { emptyList() } })
        val apiF = io.submit(java.util.concurrent.Callable<String> { get(if (ch.v2) "$BASE_V2/${ch.id}" else "$BASE/${ch.id}", 2, Streams.CHROME) })
        io.execute {
            try {
                val tg = try { tgF.get(20, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { emptyList() }
                var apiBody: String? = null; var apiErr: Exception? = null
                try { apiBody = apiF.get(if (tg.isNotEmpty()) 2L else 20L, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) { apiErr = (e.cause as? Exception) ?: e }
                if (ch.v2) {
                    val list = ArrayList<Pair<String, String>>(); val extra = ArrayList<Pair<JSONObject?, Int>>()
                    fun addTg(l: List<TgServers.Server>) = l.forEach { sv ->
                        if (list.none { it.first == sv.url }) { list.add(sv.url to (if (sv.url.contains(".mpd", true)) "dash" else "hls")); extra.add(sv.drm to (if (Streams.isJio(sv.url)) 6 else 5)) }
                    }
                    addTg(tg)   // every server the site's own player gets (fresh tokens + all keys) first...
                    if (apiBody != null) try {   // ...then the relay servers
                        val sv = JSONObject(apiBody).getJSONArray("servers")
                        (0 until sv.length()).mapNotNull { sv.optJSONObject(it) }
                            .map { str(it, "playable") to str(it, "type").ifEmpty { "hls" } }
                            .filter { it.first.isNotEmpty() && list.none { x -> x.first == it.first } }
                            .forEach { list.add(it); extra.add(null to -1) }
                    } catch (e: Exception) { apiErr = e }
                    if (list.isEmpty()) { try { addTg(TgServers.scrape(ch.id)) } catch (_: Exception) {} }
                    if (list.isEmpty()) { try { addTg(TgServers.scrapeAlt(ch.name)) } catch (_: Exception) {} }   // last resort: jjtvxweb.pages.dev
                    if (list.isEmpty()) {
                        runOnUiThread { if (token == playToken) fallbackAlt(ch, "no live server") }
                        return@execute
                    }
                    runOnUiThread { if (token == playToken) { v2Servers = list; v2Extra = extra; if (serverIdx >= list.size) serverIdx = 0; startV2() } }
                } else {
                    val urls = ArrayList<String>(); val drms = ArrayList<JSONObject?>()
                    tg.forEach { if (it.url !in urls) { urls.add(it.url); drms.add(it.drm) } }
                    val pl = Playlist.find(this, ch.name)
                    if (pl != null && pl.url !in urls) { urls.add(pl.url); drms.add(pl.drm) }
                    if (apiBody != null) try {
                        val o = JSONObject(apiBody)
                        val src = o.getJSONArray("sources"); val drm = o.optJSONObject("drm")
                        (0 until src.length()).map { src.optString(it, "") }.filter { it.isNotBlank() && it !in urls }
                            .forEach { urls.add(it); drms.add(drm) }
                    } catch (e: Exception) { apiErr = e }
                    if (urls.isEmpty()) { try { TgServers.scrape(ch.id).forEach { if (it.url !in urls) { urls.add(it.url); drms.add(it.drm) } } } catch (_: Exception) {} }
                    if (urls.isEmpty()) { try { TgServers.scrapeAlt(ch.name).forEach { if (it.url !in urls) { urls.add(it.url); drms.add(it.drm) } } } catch (_: Exception) {} }
                    if (urls.isEmpty()) throw apiErr ?: IOException("no stream source")
                    runOnUiThread {
                        if (token == playToken) { v1Sources = urls; v1Drms = drms; routes = buildRoutes(ch, urls); routeIdx = 0; startV1() }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (token == playToken) {
                        if (!ch.v2) fallbackToTg(ch, why(e)) else fallbackAlt(ch, why(e))
                    }
                }
            }
        }
    }

    /** Every (source x header profile) to try for a V1 channel, starting with the profile that worked for it last time. */
    private fun buildRoutes(ch: Channel, urls: List<String>): List<Pair<Int, Int>> {
        val n = Streams.PROFILES.size
        val first = prefs.getInt("p_${ch.key}", -1)
        // every site server gets the same treatment: the website player's own presentation first (token as cookie + on every segment)
        val jioOrder = listOf(6, 7, 5, 0)
        val webOrder = listOf(5, 1, 3, 2)
        val out = ArrayList<Pair<Int, Int>>()
        for ((i, u) in urls.withIndex()) {
            val base = if (Streams.isJio(u)) jioOrder else webOrder
            val order = (if (first in 0 until n) listOf(first) else emptyList()) + base.filter { it != first }
            order.forEach { out.add(i to it) }
        }
        return out.take(16)     // each source with its likely header profiles, 16 tries at most
    }
    private fun rememberRoute() {
        val ch = shown.getOrNull(current) ?: return
        if (ch.v2) return
        setEngine(ch, false)
        val r = routes.getOrNull(routeIdx) ?: return
        if (prefs.getInt("p_${ch.key}", 0) != r.second) prefs.edit().putInt("p_${ch.key}", r.second).apply()
    }

    // smoother live playback: a deeper buffer, a calmer live offset and gentle catch-up speed instead of stalls
    private fun liveCfg() = MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(12000)
        .setMinOffsetMs(6000).setMaxOffsetMs(40000).setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build()   // speed nudging made HD sports judder

    private fun startV1() {
        if (routeIdx !in routes.indices) return
        val ch = shown.getOrNull(current) ?: return
        val (si, pi) = routes[routeIdx]
        val url = v1Sources[si]
        Streams.jarFor(pi)
        if (routeIdx > 0) playMsg("Trying another route (${routeIdx + 1} of ${routes.size})…")
        val data = Streams.factory(Streams.PROFILES[pi], url)
        val hls = url.substringBefore('?').lowercase().endsWith(".m3u8")
        val factory: MediaSource.Factory = if (hls) HlsMediaSource.Factory(data) else DashMediaSource.Factory(data)
        val drm = v1Drms.getOrNull(si)
        if (drm != null && drm.length() > 0) {
            val lic = clearKeyLicense(drm)
            if (lic == null) { nextSource(ch, "unsupported DRM"); return }
            val mgr = DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .setMultiSession(true).setPlayClearSamplesWithoutKeys(true)
                .build(LocalMediaDrmCallback(lic))
            factory.setDrmSessionManagerProvider { mgr }
        }
        val item = MediaItem.Builder().setUri(url).setLiveConfiguration(liveCfg()).build()
        attach(factory.createMediaSource(item))
    }

    /** 16-byte key / key id from hex (with or without dashes, 0x, spaces) or base64; null when it is not one. */
    private fun keyBytes(v: String): ByteArray? {
        val t = v.trim().removePrefix("0x").removePrefix("0X").replace("-", "").replace(":", "").replace(" ", "")
        if (t.length == 32 && t.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
            return ByteArray(16) { t.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return try {
            val b = Base64.decode(v.trim().replace('+', '-').replace('/', '_').trimEnd('='), Base64.URL_SAFE or Base64.NO_WRAP)
            if (b.size == 16) b else null
        } catch (_: Exception) { null }
    }
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    /**
     * ClearKey licence (JWK set) for the channel's `drm` object: {type, keyId, key}. Also accepts several keys
     * (comma separated, an array, or a `keys` list). Returns null when the DRM is not ClearKey or a key is unreadable.
     */
    private fun clearKeyLicense(drm: JSONObject): ByteArray? {
        val type = drm.optString("type", "")
        if (type.isNotEmpty() && !type.contains("clear", ignoreCase = true)) return null
        fun parts(o: Any?): List<String> = when (o) {
            is JSONArray -> (0 until o.length()).map { o.optString(it, "") }
            is String -> o.split(',', ';', '\n', ' ')
            else -> emptyList()
        }.map { it.trim() }.filter { it.isNotEmpty() }
        val pairs = ArrayList<Pair<String, String>>()
        pairs.addAll(parts(drm.opt("keyId") ?: drm.opt("kid") ?: drm.opt("key_id")).zip(parts(drm.opt("key") ?: drm.opt("k"))))
        drm.optJSONArray("keys")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("keyId", o.optString("kid", "")); val k = o.optString("key", o.optString("k", ""))
                if (id.isNotEmpty() && k.isNotEmpty()) pairs.add(id to k)
            }
        }
        if (pairs.isEmpty()) return null
        val out = JSONArray()
        for ((id, k) in pairs) {
            val idB = keyBytes(id) ?: return null; val kB = keyBytes(k) ?: return null
            out.put(JSONObject().put("kty", "oct").put("k", b64(kB)).put("kid", b64(idB)))
        }
        return JSONObject().put("keys", out).put("type", "temporary").toString().toByteArray()
    }

    private fun startV2() {
        if (serverIdx !in v2Servers.indices) return
        val (url, type) = v2Servers[serverIdx]
        val (drm, prof) = v2Extra.getOrNull(serverIdx) ?: (null to -1)
        val http: androidx.media3.datasource.DataSource.Factory
        if (prof >= 0) { Streams.jarFor(prof); http = Streams.factory(Streams.PROFILES[prof], url) }   // site servers: the route that works for them
        else {
            Streams.jarFor(0)
            http = DefaultHttpDataSource.Factory().setUserAgent(Streams.CHROME).setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000).setReadTimeoutMs(15000)
        }
        val dash = type.equals("dash", true) || url.contains(".mpd")
        val item = MediaItem.Builder().setUri(url).setLiveConfiguration(liveCfg())
            .setMimeType(if (dash) MimeTypes.APPLICATION_MPD else MimeTypes.APPLICATION_M3U8).build()
        val factory: MediaSource.Factory = if (dash) DashMediaSource.Factory(http) else HlsMediaSource.Factory(http)
        if (drm != null && drm.length() > 0) {
            val lic = clearKeyLicense(drm)
            if (lic == null) { nextServer(); return }
            val mgr = DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .setMultiSession(true).setPlayClearSamplesWithoutKeys(true)
                .build(LocalMediaDrmCallback(lic))
            factory.setDrmSessionManagerProvider { mgr }
        }
        val ms = factory.createMediaSource(item)
        if (serverIdx > 0 || attempts > 0) playMsg("Server ${serverIdx + 1} of ${v2Servers.size}…")
        infoNext.text = "Server ${serverIdx + 1} of ${v2Servers.size}" + if (v2Servers.size > 1) "   ·   OK switches server" else ""
        infoNext.visibility = View.VISIBLE
        attach(ms)
    }

    private fun attach(ms: MediaSource) {
        releasePlayer()
        stopPreview()
        val rf = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
            .forceEnableMediaCodecAsynchronousQueueing()   // smoother 1080p / 50fps decoding on TV boxes
        val lc = DefaultLoadControl.Builder().setBufferDurationsMs(20000, 50000, 1500, 4000).setBackBuffer(0, false)
            .setTargetBufferBytes(96 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(true).build()
        capH = startCap(); badSeconds = 0
        // steadier quality: switch up only after 30 s of headroom, so adaptive HD streams stop flip-flopping between sizes
        val ts = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(this, AdaptiveTrackSelection.Factory(15_000, 20_000, 20_000, 0.75f)).apply {
            setParameters(buildUponParameters()
                .setMaxVideoSize(capH * 16 / 9, capH)
                .setViewportSizeToPhysicalDisplaySize(this@MainActivity, true)   // prefer what the screen can show...
                .setExceedVideoConstraintsIfNecessary(true)                      // ...but if every track is bigger, still play the smallest
                .setExceedRendererCapabilitiesIfNecessary(true))                 // (false here left audio playing over a black picture)
        }
        val bw = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.Builder(this).setInitialBitrateEstimate(4_000_000L).build()
        val p = ExoPlayer.Builder(this, rf).setLoadControl(lc).setTrackSelector(ts).setBandwidthMeter(bw).build()
        p.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
        trackSel = ts; p.addAnalyticsListener(dropWatcher)
        p.setMediaSource(ms); p.playWhenReady = true; p.addListener(playerListener)
        pv.player = p; player = p; p.prepare()
        armStall(10000)
        showInfo()
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(s: Int) {
            when (s) {
                Player.STATE_READY -> {
                    spinner.visibility = View.GONE; playMsg(null); disarmStall()
                    retries = 0; attempts = 0; refreshed = false; rememberRoute()
                    ui.removeCallbacks(videoCheck); ui.postDelayed(videoCheck, 6000)
                }
                Player.STATE_BUFFERING -> { spinner.visibility = View.VISIBLE; armStall(11000) }
                Player.STATE_ENDED -> onStall()
            }
        }
        override fun onRenderedFirstFrame() { ui.removeCallbacks(videoCheck); stalls = 0 }
        override fun onPlayerError(e: PlaybackException) { disarmStall(); onStreamError(e) }
    }

    /** Sound but no picture 6 s after "ready" (decoder refused the video, wrong surface...): move on instead of sitting on black. */
    private val videoCheck = Runnable {
        val p = player ?: return@Runnable
        val ch = shown.getOrNull(current) ?: return@Runnable
        if (layer.visibility != View.VISIBLE || p.playbackState != Player.STATE_READY) return@Runnable
        if (p.videoSize.width == 0 && p.currentTracks.containsType(C.TRACK_TYPE_VIDEO)) {
            playMsg("No picture, trying another source…")
            if (ch.v2) nextServer() else nextSource(ch, "no picture")
        }
    }

    // A stream that stops answering is moved on automatically: next route / server, then a fresh link, then a clear message.
    private val stallRunnable = Runnable { onStall() }
    private fun armStall(ms: Long) { ui.removeCallbacks(stallRunnable); ui.postDelayed(stallRunnable, ms) }
    private fun disarmStall() { ui.removeCallbacks(stallRunnable) }
    private fun onStall() {
        val ch = shown.getOrNull(current) ?: return
        if (layer.visibility != View.VISIBLE || player == null) return
        if (ch.v2) nextServer()
        else if (++stalls >= 2) fallbackToTg(ch, "no answer")
        else nextRoute(ch)
    }

    private val routeCodes = setOf(401, 403, 404, 410, 451)
    private fun httpCode(e: Throwable): Int? {
        var c: Throwable? = e; var n = 0
        while (c != null && n++ < 8) { if (c is HttpDataSource.InvalidResponseCodeException) return c.responseCode; c = c.cause }
        return null
    }
    private fun onStreamError(e: PlaybackException) {
        val ch = shown.getOrNull(current) ?: return
        val code = httpCode(e); lastCode = code
        if (ch.v2) { nextServer(); return }
        if (e.errorCode in 6000..6999 || e.errorCode in 4000..4999) { nextSource(ch, e.errorCodeName); return }   // DRM / decoder: other headers won't help, another server might
        if (code != null && code in routeCodes) { nextRoute(ch); return }
        if (retries++ < 3) { resolveAndStart(ch); return }          // fresh link, same as before
        fallbackToTg(ch, e.errorCodeName + (code?.let { " (HTTP $it)" } ?: ""))
    }

    /** Skip the remaining header tries of this source and move on to the next server. */
    private fun nextSource(ch: Channel, why: String) {
        val si = routes.getOrNull(routeIdx)?.first ?: -1
        val nx = (routeIdx + 1 until routes.size).firstOrNull { routes[it].first != si }
        if (nx == null) { fallbackToTg(ch, why); return }
        routeIdx = nx; attempts++
        playMsg("Trying another server…")
        val tk = playToken
        ui.post { if (tk == playToken) startV1() }
    }

    private fun nextRoute(ch: Channel) {
        attempts++
        val tk = playToken
        if (routeIdx + 1 < routes.size) {
            routeIdx++
            playMsg("Trying another route (${routeIdx + 1} of ${routes.size})…")
            ui.post { if (tk == playToken) startV1() }
        } else if (!refreshed && (lastCode == null || lastCode == 404 || lastCode == 410)) {
            refreshed = true; playMsg("Getting a fresh stream link…"); resolveAndStart(ch)
        } else {
            fallbackToTg(ch, "refused" + (lastCode?.let { " (HTTP $it)" } ?: ""))
        }
    }

    private fun nextServer() {
        val ch = shown.getOrNull(current) ?: return
        attempts++
        val n = v2Servers.size
        if (attempts > n * 2) { fallbackAlt(ch, "No working server"); return }
        if (attempts == n) { playMsg("Refreshing servers…"); resolveAndStart(ch); return }   // links are short-lived: ask again
        serverIdx = (serverIdx + 1) % n
        playMsg("Trying server ${serverIdx + 1} of $n…")
        val tk = playToken
        ui.post { if (tk == playToken) startV2() }
    }

    /** OK while a V2 channel plays: pick the next server by hand. */
    private fun switchServer() {
        if (v2Servers.size < 2) return
        serverIdx = (serverIdx + 1) % v2Servers.size
        spinner.visibility = View.VISIBLE; startV2()
    }

    /** Hold OK / Menu: drop the stream and fetch it again from scratch. */
    private fun reloadStream() {
        if (shown.getOrNull(current) == null) return
        retries = 0
        play(current)
        playMsg("Reloading stream…")
    }

    private fun fail(msg: String) {
        disarmStall(); spinner.visibility = View.GONE; playMsg(null)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun releasePlayer() { ui.removeCallbacks(videoCheck); pv.player = null; player?.release(); player = null; releaseEmbed() }
    private fun closePlayer() {
        playToken++; disarmStall(); playMsg(null)
        releasePlayer(); retries = 0; ui.removeCallbacks(hideInfo); resetGuide()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); layer.keepScreenOn = false
        if (tab < 2) { val sel = heroTile; refreshRowsKeepFocus(); setHero(sel, immediate = true) }
        layer.animate().cancel(); layer.visibility = View.GONE
    }

    private fun shortOk() {
        val ch = shown.getOrNull(current)
        if (ch?.v2 == true && v2Servers.size > 1 && infoCard.alpha > 0.5f) switchServer() else showInfo()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (intro.visibility == View.VISIBLE) { if (e.action == KeyEvent.ACTION_DOWN) intro.skip(); return true }
        if (searchShown) return searchKey(e) || super.dispatchKeyEvent(e)
        if (layer.visibility == View.VISIBLE && guideShown) return guideKey(e) || super.dispatchKeyEvent(e)
        if (layer.visibility == View.VISIBLE && gs != null && (e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER)) {
            // embedded TG player: tap OK = click the player, hold OK = reload the channel
            if (e.action == KeyEvent.ACTION_DOWN) {
                if (e.repeatCount == 0) okLong = false
                else if (e.repeatCount == 8 && !okLong) { okLong = true; reloadStream() }
            } else if (e.action == KeyEvent.ACTION_UP && !okLong) { tapEmbed(); showInfo() }
            return true
        }
        if (layer.visibility == View.VISIBLE && (e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER)) {
            // tap OK = info (or next server on V2), hold OK = reload the stream
            if (e.action == KeyEvent.ACTION_DOWN) {
                if (e.repeatCount == 0) okLong = false
                else if (e.repeatCount == 8 && !okLong) { okLong = true; reloadStream() }
            } else if (e.action == KeyEvent.ACTION_UP && !okLong) shortOk()
            return true
        }
        if (layer.visibility == View.VISIBLE && e.action == KeyEvent.ACTION_DOWN) when (e.keyCode) {
            KeyEvent.KEYCODE_BACK -> { closePlayer(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { openGuide(); return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { retries = 0; play(current - 1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { retries = 0; play(current + 1); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_DPAD_LEFT -> { switchEngine(); return true }
        }
        // Back from a lower row goes back to the top of the page (the hero row) first; Back again leaves the app.
        if (layer.visibility != View.VISIBLE && e.action == KeyEvent.ACTION_DOWN && e.keyCode == KeyEvent.KEYCODE_BACK) {
            val f = currentFocus
            if (f != null && isInside(f, rowsView)) {
                val pos = rowsView.findContainingItemView(f)?.let { rowsView.getChildAdapterPosition(it) } ?: -1
                if (pos > 0) { rowsView.scrollToPosition(0); rowsView.post { focusRowAt(0) }; return true }
            }
        }
        if (layer.visibility != View.VISIBLE && e.action == KeyEvent.ACTION_DOWN && e.keyCode == KeyEvent.KEYCODE_SEARCH) { openSearch(); return true }
        if (layer.visibility != View.VISIBLE && e.action == KeyEvent.ACTION_DOWN && e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            val f = currentFocus
            if (f != null && isInside(f, rowsView)) {
                val next = FocusFinder.getInstance().findNextFocus(window.decorView as ViewGroup, f, View.FOCUS_DOWN)
                if (next == null || !isInside(next, rowsView)) {
                    val rowView = rowsView.findContainingItemView(f)
                    val pos = rowView?.let { rowsView.getChildAdapterPosition(it) } ?: -1
                    if (pos >= 0 && pos + 1 < rows.size) {
                        rowsView.scrollToPosition(pos + 1)
                        rowsView.post { focusRowAt(pos + 1) }
                    }
                    return true   // never jump to another tab or the top bar
                }
            }
        }
        // Up from the first row goes to the open tab in the top bar (Google TV behaviour).
        if (layer.visibility != View.VISIBLE && e.action == KeyEvent.ACTION_DOWN && e.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            val f = currentFocus
            if (f != null && isInside(f, rowsView)) {
                val pos = rowsView.findContainingItemView(f)?.let { rowsView.getChildAdapterPosition(it) } ?: -1
                if (pos == 0) { segs[tab].requestFocus(); return true }
            }
        }
        if (layer.visibility != View.VISIBLE && e.action == KeyEvent.ACTION_DOWN) {
            val f = currentFocus
            val inBar = f != null && (f === searchBtn || segs.any { it === f })
            if (f != null && inBar) when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // back to the card you came from
                    val back = lastRowsFocus
                    if (back != null && back.isAttachedToWindow && isInside(back, rowsView) && back.requestFocus()) return true
                    rowsView.requestFocus(); return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> return true
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val dir = if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) View.FOCUS_LEFT else View.FOCUS_RIGHT
                    val next = FocusFinder.getInstance().findNextFocus(window.decorView as ViewGroup, f, dir)
                    if (next == null || !(next === searchBtn || segs.any { it === next })) return true   // stay inside the bar
                }
            }
        }
        return super.dispatchKeyEvent(e)
    }
    /** While the app is open, quietly re-reads both channel lists every few minutes (never while a channel is playing), so website changes arrive without a restart. */
    private val autoRefresh = object : Runnable {
        override fun run() {
            if (all.isNotEmpty() && layer.visibility != View.VISIBLE && !searchShown && System.currentTimeMillis() - lastRefresh > 4 * 60_000L) loadChannels()
            ui.postDelayed(this, 5 * 60_000L)
        }
    }
    override fun onStart() {
        super.onStart()
        ui.removeCallbacks(autoRefresh); ui.postDelayed(autoRefresh, 5 * 60_000L)
        if (tab >= 2 && ::rowsView.isInitialized && rows.isNotEmpty()) refreshRowsKeepFocus()
        if (all.isNotEmpty() && System.currentTimeMillis() - lastRefresh > 3 * 60_000L) loadChannels()
    }
    override fun onStop() {
        super.onStop(); ui.removeCallbacks(autoRefresh); stopPreview(); window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); playToken++; disarmStall(); releasePlayer(); layer.visibility = View.GONE; resetGuide()
        if (searchShown) hideSearch(false)
    }

    // ---------- search ----------
    private fun buildKeys() {
        val kw = (50 * dp).toInt(); val kh = (46 * dp).toInt(); val m = (3 * dp).toInt()
        for (r in listOf("ABCDEF", "GHIJKL", "MNOPQR", "STUVWX", "YZ0123", "456789")) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (ch in r) row.addView(keyView(ch.toString(), kw, kh, m) { typeChar(ch.lowercaseChar()) })
            searchKeys.addView(row)
        }
        val wide = 2 * (kw + 2 * m) - 2 * m
        val last = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        last.addView(keyView("Space", wide, kh, m) { typeChar(' ') })
        last.addView(keyView("Delete", wide, kh, m) { backspace() })
        last.addView(keyView("Clear", wide, kh, m) { searchBuf.setLength(0); renderQuery(); updateResults() })
        searchKeys.addView(last)
    }
    private fun keyView(label: String, w: Int, h: Int, m: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; textSize = 15f; typeface = fSemi; setTextColor(Accent.DIM)
        isFocusable = true; isClickable = true
        background = Ui.focusBg(dp, 12f, rest = Accent.SURFACE)
        layoutParams = LinearLayout.LayoutParams(w, h).apply { setMargins(m, m, m, m) }
        setOnFocusChangeListener { v, f -> setTextColor(if (f) Accent.ABYSS else Accent.DIM); focusAnim(v, f) }
        setOnClickListener { onClick() }
    }
    private fun typeChar(c: Char) { if (searchBuf.length < 40) { searchBuf.append(c); renderQuery(); updateResults() } }
    private fun backspace() { if (searchBuf.isNotEmpty()) { searchBuf.setLength(searchBuf.length - 1); renderQuery(); updateResults() } }
    private fun renderQuery() {
        if (searchBuf.isEmpty()) { searchQuery.text = "Type to search"; searchQuery.setTextColor(Accent.FAINT) }
        else { searchQuery.text = "$searchBuf|"; searchQuery.setTextColor(Accent.TEXT) }
    }
    private fun runSearch(q: String): List<Channel> {
        val toks = q.lowercase().split(' ').filter { it.isNotBlank() }
        if (toks.isEmpty()) return emptyList()
        fun score(c: Channel): Int {
            val n = c.name.lowercase()
            return when { n.startsWith(toks[0]) -> 0; n.contains(" " + toks[0]) -> 1; else -> 2 }
        }
        return (all + allV2).filter { c -> val hay = (c.name + " " + c.category).lowercase(); toks.all { hay.contains(it) } }
            .sortedWith(compareBy({ score(it) }, { it.v2 }, { it.name.lowercase() })).take(120)
    }
    private var movieResults = listOf<Tile>(); private var movieSearchToken = 0; private var movieSearching = false
    private var searchScope = 0   // tab the search was opened from: 0/1 = channels, 2 = movies, 3 = series
    private val movieRow = Row("Results", emptyList())
    private val movieSearchAdapter by lazy { TileAdapter(movieRow) }
    private val movieSearchList by lazy {
        RecyclerView(this).apply {
            layoutManager = SnapLayoutManager(this@MainActivity, RecyclerView.HORIZONTAL)
            adapter = movieSearchAdapter; itemAnimator = null; overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false; clipToPadding = false; visibility = View.GONE
            setPadding((4 * dp).toInt(), (10 * dp).toInt(), (80 * dp).toInt(), (16 * dp).toInt())
            val parent = searchResults.parent as ViewGroup
            parent.addView(this, parent.indexOfChild(searchResults), LinearLayout.LayoutParams(-1, -2))
        }
    }
    private val movieSearchRunnable = Runnable {
        val q = searchBuf.toString().trim(); val tok = ++movieSearchToken
        Thread {
            val only = if (searchScope == 3) "series" else "movie"
            val r = try { Movies.search(q, only).filter { it.type == only && it.poster.isNotEmpty() }.distinctBy { it.imdb }.take(40) } catch (_: Exception) { emptyList() }
            runOnUiThread {
                if (tok != movieSearchToken || !searchShown) return@runOnUiThread
                movieSearching = false
                movieResults = r.map { Tile.Mv(it) }; movieRow.tiles = movieResults
                movieSearchAdapter.notifyDataSetChanged(); movieSearchList.scrollToPosition(0)
                movieSearchList.visibility = if (movieResults.isEmpty()) View.GONE else View.VISIBLE
                renderCounts(q)
            }
        }.start()
    }
    private fun renderCounts(q: String) {
        val m = movieResults.size
        val vod = searchScope >= 2; val kind = if (searchScope == 3) "series" else "movies"
        searchCount.text = when {
            vod && q.isEmpty() -> "Search $kind"
            vod -> "$m " + (if (searchScope == 3) "series" else if (m == 1) "movie" else "movies")
            q.isEmpty() -> "Search ${all.size + allV2.size} channels"
            else -> "${results.size} channel" + (if (results.size == 1) "" else "s")
        }
        searchEmpty.text = when {
            q.isEmpty() -> when (searchScope) { 2 -> "Type a movie name, like Inception or Dangal."; 3 -> "Type a series name, like Friends or Money Heist."; else -> "Type a channel name, like Star Sports or Sony." }
            vod && m == 0 -> if (movieSearching) "Searching for \"$q\"…" else "Nothing matches \"$q\"."
            !vod && results.isEmpty() -> "Nothing matches \"$q\"."
            else -> ""
        }
        searchEmpty.visibility = if (searchEmpty.text.isEmpty()) View.GONE else View.VISIBLE
    }
    private fun updateResults() {
        val q0 = searchBuf.toString().trim()
        ui.removeCallbacks(movieSearchRunnable); movieSearchToken++
        movieResults = emptyList(); movieRow.tiles = movieResults; movieSearchAdapter.notifyDataSetChanged()
        movieSearchList.visibility = View.GONE
        val vod = searchScope >= 2
        movieSearching = vod && q0.length >= 2
        if (movieSearching) ui.postDelayed(movieSearchRunnable, 350)
        val q = q0
        searchResults.visibility = if (vod) View.GONE else View.VISIBLE
        results = if (q.isEmpty() || vod) emptyList() else runSearch(q)
        searchAdapter.notifyDataSetChanged(); searchResults.scrollToPosition(0)
        renderCounts(q)
    }
    private fun openSearch() {
        if (searchShown || layer.visibility == View.VISIBLE || intro.visibility == View.VISIBLE) return
        searchShown = true; searchScope = tab; searchBuf.setLength(0); renderQuery(); updateResults()
        if (home.descendantFocusability != ViewGroup.FOCUS_BLOCK_DESCENDANTS) {
            lastHomeFocus = currentFocus; home.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        searchLayer.animate().cancel()
        searchLayer.visibility = View.VISIBLE; searchLayer.alpha = 0f
        searchLayer.animate().alpha(1f).setDuration(220).start()
        searchKeys.getChildAt(0)?.let { (it as ViewGroup).getChildAt(0)?.requestFocus() }
    }
    private fun closeSearch() { hideSearch(true) }
    private fun hideSearch(animate: Boolean) {
        if (!searchShown) return
        searchShown = false
        searchLayer.animate().cancel()
        if (animate) searchLayer.animate().alpha(0f).setDuration(180).withEndAction { if (!searchShown) searchLayer.visibility = View.GONE }.start()
        else searchLayer.visibility = View.GONE
        if (home.descendantFocusability == ViewGroup.FOCUS_BLOCK_DESCENDANTS && !guideShown) {
            home.descendantFocusability = homeFocusMode
            val back = lastHomeFocus; lastHomeFocus = null
            if (back?.takeIf { it.isAttachedToWindow }?.requestFocus() != true) searchBtn.requestFocus()
        }
    }
    private fun searchKey(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return false
        val k = e.keyCode
        when {
            k == KeyEvent.KEYCODE_BACK -> { closeSearch(); return true }
            k in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> { typeChar('a' + (k - KeyEvent.KEYCODE_A)); return true }
            k in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { typeChar('0' + (k - KeyEvent.KEYCODE_0)); return true }
            k == KeyEvent.KEYCODE_SPACE -> { typeChar(' '); return true }
            k == KeyEvent.KEYCODE_DEL -> { backspace(); return true }
        }
        return false
    }
    private fun playFromSearch(i: Int) {
        if (i !in results.indices) return
        shown = results; shownCat = "Search"; retries = 0
        closeSearch(); play(i)
    }

    private val searchAdapter = object : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = results.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val v = LayoutInflater.from(p.context).inflate(R.layout.item_guide_ch, p, false)
            v.background = Ui.focusBg(dp, 14f)
            v.findViewById<View>(R.id.chLogoBox).background = Ui.pill(dp, Accent.SURFACE, 12f)
            return VH(v)
        }
        override fun onBindViewHolder(h: VH, i: Int) {
            val v = h.itemView; val ch = results[i]
            val name = v.findViewById<TextView>(R.id.chName)
            name.text = ch.name
            v.findViewById<TextView>(R.id.chNum).text = (i + 1).toString()
            bindLogo(v.findViewById(R.id.chLogo), v.findViewById(R.id.chMono), ch)
            val sub = v.findViewById<TextView>(R.id.chNow)
            sub.text = if (ch.v2) "V2 · 24/7" else ch.category; sub.setTextColor(Accent.FAINT); sub.visibility = View.VISIBLE
            v.findViewById<View>(R.id.chFav).visibility = if (ch.key in favs) View.VISIBLE else View.GONE
            val style = { f: Boolean -> name.setTextColor(if (f) Accent.ABYSS else Accent.DIM); sub.setTextColor(if (f) 0xFF5F6368.toInt() else Accent.FAINT) }
            style(v.isFocused)
            v.setOnFocusChangeListener { _, f -> style(f) }
            v.setOnClickListener { playFromSearch(i) }
            v.setOnLongClickListener { toggleFav(ch); notifyItemChanged(i); true }
        }
    }

    // ---------- channel guide (Right opens · Left goes back · OK plays) ----------
    private fun guidePool() = if (shown.getOrNull(current)?.v2 == true) allV2 else all
    private fun channelsIn(cat: String) = if (cat == FAV) guidePool().filter { it.key in favs } else guidePool().filter { it.category == cat }
    private fun within(v: View?, rv: View) = v != null && (v === rv || isInside(v, rv))
    private fun itemOf(v: View, rv: RecyclerView): View { var x = v; while (x.parent !== rv) x = x.parent as View; return x }
    private val guideTimeout = Runnable { closeGuide() }
    private fun bumpGuide() { ui.removeCallbacks(guideTimeout); ui.postDelayed(guideTimeout, 15000) }

    private fun focusRow(rv: RecyclerView, i: Int) {
        val h = rv.findViewHolderForAdapterPosition(i)
        if (h != null && h.itemView.isShown) { h.itemView.requestFocus(); return }
        (rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(i, (120 * dp).toInt())
        rv.post { rv.findViewHolderForAdapterPosition(i)?.itemView?.requestFocus() ?: rv.requestFocus() }
    }

    private fun openGuide() {
        val pool = guidePool()
        if (pool.isEmpty()) return
        val byCat = pool.groupBy { it.category }
        val favCount = pool.count { it.key in favs }
        guideCats = (if (favCount > 0) listOf(FAV) else emptyList()) + byCat.keys.sorted()
        catCounts = byCat.mapValues { it.value.size } + (FAV to favCount)
        if (home.descendantFocusability != ViewGroup.FOCUS_BLOCK_DESCENDANTS) {
            lastHomeFocus = currentFocus; home.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        guideShown = true; guideCat = ""
        guide.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        chCol.visibility = View.GONE; guideDivider.visibility = View.GONE
        channelSearch.setText("")
        catAdapter.notifyDataSetChanged()
        ui.removeCallbacks(hideInfo); hideInfo.run()
        guide.animate().cancel(); guideScrim.animate().cancel()
        guide.visibility = View.VISIBLE; guide.alpha = 0f; guide.translationX = 60 * dp
        guide.animate().alpha(1f).translationX(0f).setDuration(260).setInterpolator(DecelerateInterpolator(2f)).start()
        guideScrim.visibility = View.VISIBLE; guideScrim.alpha = 0f
        guideScrim.animate().alpha(1f).setDuration(260).start()
        focusRow(catList, guideCats.indexOf(shownCat).coerceAtLeast(0))
        bumpGuide()
    }

    private fun openCategory(i: Int) {
        val cat = guideCats.getOrNull(i) ?: return
        val prev = guideCats.indexOf(guideCat)
        guideCat = cat; categoryChannels = channelsIn(cat)
        channelSearch.setText("")
        guideChannels = categoryChannels
        chHeader.text = "$cat  ${guideChannels.size}"
        chAdapter.notifyDataSetChanged()
        if (chCol.visibility != View.VISIBLE) {
            chCol.animate().cancel()
            chCol.visibility = View.VISIBLE; guideDivider.visibility = View.VISIBLE
            chCol.alpha = 0f; chCol.translationX = 30 * dp
            chCol.animate().alpha(1f).translationX(0f).setDuration(240).setInterpolator(DecelerateInterpolator(2f)).start()
        }
        val playing = if (cat == shownCat) guideChannels.indexOfFirst { it.key == shown.getOrNull(current)?.key } else -1
        chList.scrollToPosition(0)
        focusRow(chList, playing.coerceAtLeast(0))
        if (prev >= 0) catAdapter.notifyItemChanged(prev)
        catAdapter.notifyItemChanged(i)
    }

    private fun backToCats() {
        val i = guideCats.indexOf(guideCat).coerceAtLeast(0)
        guideCat = ""
        focusRow(catList, i)
        catAdapter.notifyItemChanged(i)
        chCol.animate().cancel()
        chCol.animate().alpha(0f).translationX(30 * dp).setDuration(180)
            .withEndAction { if (guideCat.isEmpty()) { chCol.visibility = View.GONE; guideDivider.visibility = View.GONE } }.start()
    }

    private fun closeGuide() {
        if (!guideShown) return
        guideShown = false; guideCat = ""; ui.removeCallbacks(guideTimeout)
        guide.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        currentFocus?.clearFocus()
        guide.animate().alpha(0f).translationX(60 * dp).setDuration(200)
            .withEndAction { if (!guideShown) { guide.visibility = View.GONE; chCol.visibility = View.GONE; guideDivider.visibility = View.GONE } }.start()
        guideScrim.animate().alpha(0f).setDuration(200).withEndAction { if (!guideShown) guideScrim.visibility = View.GONE }.start()
    }

    /** Hard reset when the player itself closes: hide the guide and give focus back to the home screen. */
    private fun resetGuide() {
        guideShown = false; guideCat = ""; ui.removeCallbacks(guideTimeout)
        guide.animate().cancel(); guideScrim.animate().cancel()
        guide.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        guide.visibility = View.GONE; guideScrim.visibility = View.GONE; chCol.visibility = View.GONE; guideDivider.visibility = View.GONE
        if (home.descendantFocusability == ViewGroup.FOCUS_BLOCK_DESCENDANTS && !searchShown) {
            home.descendantFocusability = homeFocusMode
            val back = lastHomeFocus; lastHomeFocus = null
            if (back?.takeIf { it.isAttachedToWindow }?.requestFocus() != true) rowsView.requestFocus()
        }
    }

    private fun playFromGuide(i: Int) {
        val list = guideChannels; val cat = guideCat
        if (i !in list.indices) return
        shown = list; shownCat = cat; retries = 0
        closeGuide(); play(i)
    }

    private fun guideKey(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return false
        bumpGuide()
        val f = currentFocus
        val inCh = within(f, chList); val inCat = within(f, catList); val inSearch = f === channelSearch
        when (e.keyCode) {
            KeyEvent.KEYCODE_BACK -> { closeGuide(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (inCh || inSearch) backToCats() else closeGuide(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (inCat && f != null && f !== catList) openCategory(catList.getChildAdapterPosition(itemOf(f, catList)))
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                // the search box above the channel list is now reachable: Up from the first channel, Down to leave it
                if (inSearch) { if (e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) focusRow(chList, 0); return true }
                if (!inCh && !inCat) { focusRow(catList, 0); return true }
                if (inCh && e.keyCode == KeyEvent.KEYCODE_DPAD_UP && chCol.visibility == View.VISIBLE &&
                    chList.getChildAdapterPosition(itemOf(f!!, chList)) <= 0) { channelSearch.requestFocus(); return true }
                val list = if (inCh) chList else catList
                // stay inside the current column; never jump across or out of the panel
                val next = f!!.focusSearch(if (e.keyCode == KeyEvent.KEYCODE_DPAD_UP) View.FOCUS_UP else View.FOCUS_DOWN)
                return next == null || !within(next, list)
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> return true
        }
        return false   // OK / long-press go to the focused row as normal
    }

    private val catAdapter = object : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = guideCats.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val v = LayoutInflater.from(p.context).inflate(R.layout.item_guide_cat, p, false)
            v.background = Ui.focusBg(dp, 14f)
            return VH(v)
        }
        override fun onBindViewHolder(h: VH, i: Int) {
            val v = h.itemView; val cat = guideCats[i]
            val name = v.findViewById<TextView>(R.id.catName)
            name.text = cat
            v.findViewById<TextView>(R.id.catCount).text = (catCounts[cat] ?: 0).toString()
            v.findViewById<View>(R.id.catMark).visibility = if (cat == shownCat) View.VISIBLE else View.INVISIBLE
            val open = cat == guideCat
            v.isActivated = open
            val style = { f: Boolean -> name.setTextColor(if (f) Accent.ABYSS else if (open) Accent.TEXT else Accent.DIM) }
            style(v.isFocused)
            v.setOnFocusChangeListener { _, f -> style(f) }
            v.setOnClickListener { openCategory(i) }
        }
    }

    private val chAdapter = object : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = guideChannels.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val v = LayoutInflater.from(p.context).inflate(R.layout.item_guide_ch, p, false)
            v.background = Ui.focusBg(dp, 14f)
            v.findViewById<View>(R.id.chLogoBox).background = Ui.pill(dp, Accent.SURFACE, 12f)
            return VH(v)
        }
        override fun onBindViewHolder(h: VH, i: Int) {
            val v = h.itemView; val ch = guideChannels[i]
            val name = v.findViewById<TextView>(R.id.chName)
            name.text = ch.name
            v.findViewById<TextView>(R.id.chNum).text = (i + 1).toString()
            bindLogo(v.findViewById(R.id.chLogo), v.findViewById(R.id.chMono), ch)
            val playing = shown.getOrNull(current)?.key == ch.key
            v.findViewById<View>(R.id.chNow).visibility = if (playing) View.VISIBLE else View.GONE
            v.findViewById<View>(R.id.chFav).visibility = if (ch.key in favs) View.VISIBLE else View.GONE
            v.isActivated = playing
            val style = { f: Boolean -> name.setTextColor(if (f) Accent.ABYSS else if (playing) Accent.TEXT else Accent.DIM) }
            style(v.isFocused)
            v.setOnFocusChangeListener { _, f -> style(f) }
            v.setOnClickListener { playFromGuide(i) }
            v.setOnLongClickListener { toggleFav(ch); notifyItemChanged(i); true }
        }
    }

    // ---------- live channel preview (hero window, muted) ----------
    private val previewRunnable = Runnable { startPreview() }
    private fun schedulePreview(c: Channel?) {
        stopPreview()
        // no live preview on the movie tabs, while the hero is folded away, or by default on weak boxes (it runs a second decoder)
        if (c != null && tab < 2 && !heroCollapsed && layer.visibility != View.VISIBLE && prefs.getBoolean("previews", !lowEnd)) ui.postDelayed(previewRunnable, 1200)
    }
    private fun stopPreview() {
        ui.removeCallbacks(previewRunnable); previewToken++
        previewPv.player = null
        previewPlayer?.let { try { it.release() } catch (_: Exception) {} }; previewPlayer = null
        // never GONE: a TextureView only gets its surface while laid out, and the first frame is what fades it in
        heroPreview.animate().cancel(); if (heroPreview.alpha > 0f) heroPreview.animate().alpha(0f).setDuration(200).start()
    }
    private fun startPreview() {
        val c = (heroTile as? Tile.Ch)?.c ?: return
        if (layer.visibility == View.VISIBLE || searchShown) return
        val token = ++previewToken
        io.execute {
            try {
                var url: String; var type = "hls"; var drm: JSONObject? = null
                if (c.v2) {
                    val sv = JSONObject(get("$BASE_V2/${c.id}", 1)).getJSONArray("servers").getJSONObject(0)
                    url = str(sv, "playable"); type = str(sv, "type").ifEmpty { "hls" }
                } else {
                    val s0 = TgServers.fetch(c.id).firstOrNull() ?: return@execute
                    url = s0.url; drm = s0.drm
                }
                if (url.isEmpty()) return@execute
                runOnUiThread { if (token == previewToken) playPreview(url, type, drm, c.v2) }
            } catch (_: Exception) {}
        }
    }
    private fun playPreview(url: String, type: String, drm: JSONObject?, v2: Boolean) {
        try {
            val data: androidx.media3.datasource.DataSource.Factory = if (v2)
                DefaultHttpDataSource.Factory().setUserAgent(Streams.CHROME).setAllowCrossProtocolRedirects(true)
            else { val pi = if (Streams.isJio(url)) 6 else 1; Streams.jarFor(pi); Streams.factory(Streams.PROFILES[pi], url) }
            val dash = type.equals("dash", true) || url.substringBefore('?').lowercase().endsWith(".mpd") ||
                (!v2 && !url.substringBefore('?').lowercase().endsWith(".m3u8"))
            val f: MediaSource.Factory = if (dash) DashMediaSource.Factory(data) else HlsMediaSource.Factory(data)
            if (drm != null) clearKeyLicense(drm)?.let { lic ->
                val mgr = DefaultDrmSessionManager.Builder().setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                    .setMultiSession(true).setPlayClearSamplesWithoutKeys(true).build(LocalMediaDrmCallback(lic))
                f.setDrmSessionManagerProvider { mgr }
            }
            val p = ExoPlayer.Builder(this).build()
            p.volume = 0f
            // a preview never needs more than 720p: lighter on the decoder and the network
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setMaxVideoSize(1280, 720).build()
            p.setMediaSource(f.createMediaSource(MediaItem.Builder().setUri(url).setLiveConfiguration(liveCfg()).build()))
            p.addListener(object : Player.Listener {
                // fade in only when a real picture exists, so a preview can never show as a black box
                override fun onRenderedFirstFrame() {
                    if (previewPlayer !== p) return
                    heroPreview.animate().cancel()
                    heroPreview.animate().alpha(1f).setDuration(450).start()
                }
                override fun onPlayerError(e: PlaybackException) { if (previewPlayer === p) stopPreview() }
            })
            previewPv.player = p
            previewPlayer = p; p.playWhenReady = true; p.prepare()
        } catch (_: Exception) { stopPreview() }
    }

    /** Rebuilds the rows (e.g. a new "Recently watched" entry) without losing the scroll position. */
    private fun refreshRowsKeepFocus() {
        val lm = rowsView.layoutManager as LinearLayoutManager
        val pos = lm.findFirstVisibleItemPosition()
        val top = lm.findViewByPosition(pos)?.top ?: 0
        val old = rows.map { it.title }
        rows = when (tab) {
            0 -> listOf(Row(FAV, favTiles())) + recentRow() + all.groupBy { it.category }.toSortedMap().map { (k, v) -> Row(k, v.map { Tile.Ch(it) }) }
            1 -> listOf(Row(FAV, favTiles())) + recentRow() + allV2.groupBy { it.category }.toSortedMap().map { (k, v) -> Row(k, v.map { Tile.Ch(it) }) }
            else -> continueRow() + vodRows[tab - 2]
        }
        inner.clear(); rowAdapter.notifyDataSetChanged()
        val shift = rows.size - old.size
        if (pos >= 0) lm.scrollToPositionWithOffset((pos + if (pos > 0) shift else 0).coerceAtLeast(0), top)
        rowsView.postDelayed({ if (layer.visibility != View.VISIBLE && !searchShown && currentFocus == null) rowsView.requestFocus() }, 80)
    }

    // ---------- adapters ----------
    private inner class TileAdapter(val row: Row) : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = row.tiles.size
        override fun getItemViewType(i: Int) = when (row.tiles[i]) { is Tile.Ch -> 0; is Tile.Mv -> 1; else -> 2 }
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val l = when (t) { 0 -> R.layout.item_tile; 1 -> R.layout.item_poster; else -> R.layout.item_hint }
            val v = LayoutInflater.from(p.context).inflate(l, p, false)
            v.background = Ui.pill(dp, Accent.SURFACE, if (t == 1) 10f else 12f)
            v.clipToOutline = true
            return VH(v)
        }
        override fun onBindViewHolder(h: VH, i: Int, payloads: MutableList<Any>) {
            val t = row.tiles[i]
            if (payloads.isNotEmpty() && t is Tile.Ch) { h.itemView.findViewById<View>(R.id.fav).visibility = if (t.c.key in favs) View.VISIBLE else View.GONE; return }
            super.onBindViewHolder(h, i, payloads)
        }
        override fun onBindViewHolder(h: VH, i: Int) {
            val v = h.itemView
            when (val t = row.tiles[i]) {
                is Tile.Ch -> {
                    val bg = v.background as GradientDrawable
                    v.tag = t.c.key; bg.setColor(Ui.mix(Accent.SURFACE, accentOf(t.c.key), 0.26f))
                    v.findViewById<TextView>(R.id.name).text = t.c.name
                    val logo = v.findViewById<ImageView>(R.id.logo); val mono = v.findViewById<TextView>(R.id.mono)
                    if (t.c.logo.isBlank()) {
                        logo.setImageDrawable(null); logo.visibility = View.GONE
                        val ac = accentOf(t.c.key)
                        mono.visibility = View.VISIBLE; mono.text = initials(t.c.name)
                        mono.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(ac, 150)) }
                    } else {
                        mono.visibility = View.GONE; logo.visibility = View.VISIBLE
                        logo.load(t.c.logo) {
                            allowHardware(false)
                            listener(onSuccess = { _, r ->
                                extractAccent(t.c.key, r.drawable) { c ->
                                    if (v.tag == t.c.key) bg.setColor(Ui.mix(Accent.SURFACE, c, 0.26f))
                                }
                            })
                        }
                    }
                    v.findViewById<View>(R.id.fav).visibility = if (t.c.key in favs) View.VISIBLE else View.GONE
                    v.setOnFocusChangeListener { x, f ->
                        focusAnim(x, f); if (f) setHero(t)
                    }
                    v.setOnClickListener {
                        shown = row.tiles.filterIsInstance<Tile.Ch>().map { it.c }; shownCat = row.title
                        retries = 0; play(shown.indexOfFirst { it.key == t.c.key })
                    }
                    v.setOnLongClickListener { toggleFav(t.c); true }
                }
                is Tile.Mv -> {
                    val img = v.findViewById<ImageView>(R.id.poster)
                    v.findViewById<TextView>(R.id.posterName).text = t.m.name
                    img.load(t.m.poster) { crossfade(120); size(300, 450); placeholder(null) }
                    val rt = v.findViewById<TextView>(R.id.posterRating)
                    rt.text = if (t.m.rating.isNotEmpty()) "★ ${t.m.rating}" else ""; rt.visibility = if (t.m.rating.isEmpty()) View.GONE else View.VISIBLE
                    v.setOnFocusChangeListener { x, f ->
                        focusAnim(x, f)
                        if (f) {
                            setHero(t)
                            val p = h.bindingAdapterPosition
                            for (d in 1..3) (row.tiles.getOrNull(p + d) as? Tile.Mv)?.let { prefetch(it.m.background) }
                        }
                    }
                    v.setOnClickListener {
                        stopPreview(); DetailActivity.open(this@MainActivity, t.m)
                        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                    }
                }
                else -> {}
            }
        }
    }

    private val tilePool = RecyclerView.RecycledViewPool().apply { setMaxRecycledViews(0, 40); setMaxRecycledViews(1, 40) }
    private val rowAdapter = object : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_row, p, false))
        override fun onBindViewHolder(h: VH, i: Int) {
            val v = h.itemView
            val row = rows[i]
            val title = v.findViewById<TextView>(R.id.rowTitle); val count = v.findViewById<TextView>(R.id.rowCount)
            title.text = row.title
            val n = row.tiles.count { it is Tile.Ch || it is Tile.Mv }
            count.text = ""
            val rv = v.findViewById<RecyclerView>(R.id.rowList)
            if (rv.layoutManager == null) {
                rv.layoutManager = SnapLayoutManager(this@MainActivity, RecyclerView.HORIZONTAL)
                rv.itemAnimator = null
                rv.overScrollMode = View.OVER_SCROLL_NEVER
                rv.setHasFixedSize(true)
                rv.isNestedScrollingEnabled = false
                rv.setItemViewCacheSize(10)
                (rv.layoutManager as LinearLayoutManager).initialPrefetchItemCount = 8
            }
            rv.setRecycledViewPool(tilePool)
            val cur = rv.adapter as? TileAdapter
            val ad = if (cur != null && cur.row === row) cur else TileAdapter(row).also { rv.swapAdapter(it, true) }
            inner[i] = ad
            v.animate().cancel(); v.translationY = 0f; v.alpha = 1f
            val a = if (rowsView.hasFocus()) 0.45f else 1f
            title.alpha = a; count.alpha = a
        }
    }
}
