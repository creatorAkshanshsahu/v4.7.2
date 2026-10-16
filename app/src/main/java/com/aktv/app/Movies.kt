package com.aktv.app

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Movie & series catalogue (TMDB: trending, genres, search, posters, backdrops, episodes) and the
 * TGVID embed player links (https://tgvid.lovable.app/docs).
 */
object Movies {
    data class Title(
        val imdb: String, val tmdb: Int, val type: String, val name: String, val poster: String,
        val background: String, val logo: String, val year: String, val rating: String,
        val genres: List<String>, val description: String, val runtime: String = ""
    ) { val isSeries get() = type == "series" }

    data class Episode(val season: Int, val episode: Int, val name: String, val overview: String, val thumb: String)
    data class Shelf(val title: String, val type: String, val catalog: String, val genre: String?)

    private const val TM = "https://api.themoviedb.org/3"
    private const val TMDB_KEY = "e86d249e1b4fc2dcd7d1f1cb09a004f8"   // TMDB v3 API key (catalogue, posters, search)
    private const val IMG = "https://image.tmdb.org/t/p"
    private const val PLAYER = "https://tgvid.lovable.app/embed"
    private const val ACCENT = "e50914"

    /**
     * Movies and Series are separate tabs with their own shelves. Order matters: the app shows each title only in the
     * first shelf that contains it, so the broad shelves come first and the genre shelves only add what is new.
     */
    val MOVIE_SHELVES: List<Shelf> = listOf(
        Shelf("Trending movies", "movie", "top", null),
        Shelf("Top rated movies", "movie", "imdbRating", null)
    ) + listOf("Action", "Comedy", "Thriller", "Sci-Fi", "Horror", "Romance", "Animation", "Crime", "Drama", "Family", "Fantasy", "Mystery", "Adventure", "Documentary")
        .map { Shelf("$it movies", "movie", "top", it) }

    val SERIES_SHELVES: List<Shelf> = listOf(
        Shelf("Trending series", "series", "top", null),
        Shelf("Top rated series", "series", "imdbRating", null)
    ) + listOf("Action", "Comedy", "Crime", "Drama", "Sci-Fi", "Animation", "Mystery", "Reality-TV")
        .map { Shelf("$it series", "series", "top", it) }

    fun shelves(type: String) = if (type == "series") SERIES_SHELVES else MOVIE_SHELVES

    fun movieUrl(tmdb: Int, t: Long = 0) = "$PLAYER/movie/$tmdb?color=$ACCENT&back=false" + (if (t > 5) "&t=$t" else "")
    fun tvUrl(tmdb: Int, s: Int, e: Int, t: Long = 0) =
        "$PLAYER/tv/$tmdb/$s/$e?color=$ACCENT&back=false&autoplayNextEp=true" + (if (t > 5) "&t=$t" else "")

    private val MOVIE_GENRES = mapOf(28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime", 99 to "Documentary",
        18 to "Drama", 10751 to "Family", 14 to "Fantasy", 9648 to "Mystery", 10749 to "Romance", 878 to "Sci-Fi", 53 to "Thriller", 27 to "Horror")
    private val TV_GENRES = mapOf(10759 to "Action & Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime", 99 to "Documentary", 18 to "Drama",
        9648 to "Mystery", 10764 to "Reality", 10765 to "Sci-Fi & Fantasy")
    private val MOVIE_IDS = mapOf("Action" to 28, "Comedy" to 35, "Thriller" to 53, "Sci-Fi" to 878, "Horror" to 27, "Romance" to 10749, "Animation" to 16,
        "Crime" to 80, "Drama" to 18, "Family" to 10751, "Fantasy" to 14, "Mystery" to 9648, "Adventure" to 12, "Documentary" to 99)
    private val TV_IDS = mapOf("Action" to 10759, "Comedy" to 35, "Crime" to 80, "Drama" to 18, "Sci-Fi" to 10765, "Animation" to 16, "Mystery" to 9648, "Reality-TV" to 10764)

    private val cache = HashMap<String, List<Title>>()
    /** Set by the app: shelves are kept on disk so the Movies and Series tabs open instantly on the next launch. */
    @Volatile var diskDir: java.io.File? = null

    private fun http(u: String): String {
        var last: Exception? = null
        for (n in 1..3) {
            try {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 12000; c.readTimeout = 15000; c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", Streams.CHROME); c.setRequestProperty("Accept", "application/json")
                if (c.responseCode in 200..299) return c.inputStream.bufferedReader().use { it.readText() }
                last = IOException("HTTP ${c.responseCode}"); c.disconnect()
            } catch (e: Exception) { last = e }
            Thread.sleep(500L * n)
        }
        throw last ?: IOException("no response")
    }

    private fun tm(path: String, q: String = "") = "$TM$path?api_key=$TMDB_KEY&language=en-US$q"
    private fun s(o: JSONObject, k: String) = if (o.isNull(k)) "" else o.optString(k, "")

    /** One TMDB movie / TV object (list item or full details) as a Title. [imdb] is just our unique id: "mv123" / "tv123". */
    private fun parse(o: JSONObject, type: String): Title? {
        val id = o.optInt("id", 0)
        val series = type == "series"
        val name = s(o, if (series) "name" else "title")
        val poster = s(o, "poster_path")
        if (id <= 0 || name.isEmpty() || poster.isEmpty()) return null
        val bg = s(o, "backdrop_path")
        val ra = o.optDouble("vote_average", 0.0)
        val map = if (series) TV_GENRES else MOVIE_GENRES
        val genres = ArrayList<String>()
        o.optJSONArray("genre_ids")?.let { g -> for (i in 0 until g.length()) map[g.optInt(i)]?.let { genres.add(it) } }
        o.optJSONArray("genres")?.let { g -> for (i in 0 until g.length()) g.optJSONObject(i)?.optString("name")?.takeIf { it.isNotEmpty() }?.let { genres.add(it) } }
        val rt = if (series) o.optJSONArray("episode_run_time")?.optInt(0, 0) ?: 0 else o.optInt("runtime", 0)
        return Title(
            (if (series) "tv" else "mv") + id, id, type, name, "$IMG/w342$poster", if (bg.isEmpty()) "" else "$IMG/w1280$bg", "",
            s(o, if (series) "first_air_date" else "release_date").take(4),
            if (ra > 0) "%.1f".format(java.util.Locale.US, ra) else "", genres, s(o, "overview"), if (rt > 0) "${rt}m" else ""
        )
    }

    private fun results(body: String, type: String): List<Title> {
        val arr = JSONObject(body).optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> parse(o, type) } }
    }

    /** One shelf of titles (two pages, cached for the session and for 12 h on disk). Every title has a TMDB id, so every title can play. */
    fun shelf(sh: Shelf): List<Title> {
        val key = "${sh.type}/${sh.catalog}/${sh.genre}"
        synchronized(cache) { cache[key] }?.let { return it }
        val kind = if (sh.type == "series") "tv" else "movie"
        val path = when {
            sh.genre != null -> "/discover/$kind"
            sh.catalog == "imdbRating" -> "/$kind/top_rated"
            else -> "/trending/$kind/week"
        }
        val extra = if (sh.genre != null) {
            val gid = (if (sh.type == "series") TV_IDS else MOVIE_IDS)[sh.genre] ?: 0
            "&with_genres=$gid&sort_by=popularity.desc&vote_count.gte=300"
        } else ""
        val f = diskDir?.let { java.io.File(it, "tmdb_" + key.replace(Regex("[^A-Za-z0-9]"), "_") + ".json") }
        val fresh = f != null && f.exists() && System.currentTimeMillis() - f.lastModified() < 12 * 3600_000L
        val body = if (fresh) f!!.readText() else try {
            val all = org.json.JSONArray()
            for (page in 1..2) {
                val arr = JSONObject(http(tm(path, "$extra&page=$page"))).optJSONArray("results") ?: break
                for (i in 0 until arr.length()) all.put(arr.get(i))
            }
            JSONObject().put("results", all).toString().also { b -> try { f?.writeText(b) } catch (_: Exception) {} }
        } catch (e: Exception) { if (f != null && f.exists()) f.readText() else throw e }   // offline: use the older copy
        val out = results(body, sh.type).distinctBy { it.imdb }
        synchronized(cache) { cache[key] = out }
        return out
    }

    /** [only] = "movie" or "series" searches just that catalogue (each tab has its own search); null searches both. */
    fun search(q: String, only: String? = null): List<Title> {
        val enc = URLEncoder.encode(q, "UTF-8").replace("+", "%20")
        return (if (only != null) listOf(only) else listOf("movie", "series")).flatMap { t ->
            try { results(http(tm("/search/" + (if (t == "series") "tv" else "movie"), "&query=$enc&include_adult=false")), t) }
            catch (_: Exception) { emptyList() }
        }
    }

    /** Full details with the title logo; for series also every episode (specials left out), fetched 20 seasons per request. */
    fun details(type: String, tmdb: Int): Pair<Title?, List<Episode>> {
        val series = type == "series"
        val kind = if (series) "tv" else "movie"
        val base = "&append_to_response=images&include_image_language=en,null"
        val m = JSONObject(http(tm("/$kind/$tmdb", base)))
        val t0 = parse(m, type) ?: return null to emptyList()
        val logo = m.optJSONObject("images")?.optJSONArray("logos")?.optJSONObject(0)?.let { s(it, "file_path") } ?: ""
        val t = if (logo.isEmpty()) t0 else t0.copy(logo = "$IMG/w500$logo")
        if (!series) return t to emptyList()
        val nums = ArrayList<Int>()
        m.optJSONArray("seasons")?.let { a -> for (i in 0 until a.length()) { val n = a.optJSONObject(i)?.optInt("season_number", 0) ?: 0; if (n > 0) nums.add(n) } }
        val eps = ArrayList<Episode>()
        for (chunk in nums.chunked(20)) {
            val o = try { JSONObject(http(tm("/tv/$tmdb", "&append_to_response=" + chunk.joinToString(",") { "season/$it" }))) } catch (_: Exception) { continue }
            for (n in chunk) {
                val arr = o.optJSONObject("season/$n")?.optJSONArray("episodes") ?: continue
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    val ep = e.optInt("episode_number", 0); if (ep <= 0) continue
                    val still = s(e, "still_path")
                    eps.add(Episode(n, ep, s(e, "name").ifEmpty { "Episode $ep" }, s(e, "overview"), if (still.isEmpty()) "" else "$IMG/w300$still"))
                }
            }
        }
        return t to eps.distinctBy { it.season * 10000 + it.episode }.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    // ---------- continue watching (saved positions from the player) ----------
    fun saveTitle(p: android.content.SharedPreferences, t: Title) {
        val o = JSONObject().put("imdb", t.imdb).put("tmdb", t.tmdb).put("type", t.type).put("name", t.name)
            .put("poster", t.poster).put("background", t.background).put("logo", t.logo).put("year", t.year)
            .put("rating", t.rating).put("desc", t.description).put("genres", t.genres.joinToString("|"))
        val list = recent(p).filter { it.imdb != t.imdb }.toMutableList()
        val arr = org.json.JSONArray().put(o)
        list.take(19).forEach { r ->
            arr.put(JSONObject().put("imdb", r.imdb).put("tmdb", r.tmdb).put("type", r.type).put("name", r.name)
                .put("poster", r.poster).put("background", r.background).put("logo", r.logo).put("year", r.year)
                .put("rating", r.rating).put("desc", r.description).put("genres", r.genres.joinToString("|")))
        }
        p.edit().putString("movie_recent", arr.toString()).apply()
    }

    fun recent(p: android.content.SharedPreferences): List<Title> = try {
        val arr = org.json.JSONArray(p.getString("movie_recent", "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Title(s(o, "imdb"), o.optInt("tmdb"), s(o, "type"), s(o, "name"), s(o, "poster"), s(o, "background"),
                s(o, "logo"), s(o, "year"), s(o, "rating"), s(o, "genres").split("|").filter { it.isNotEmpty() }, s(o, "desc"))
        }
    } catch (_: Exception) { emptyList() }
}
