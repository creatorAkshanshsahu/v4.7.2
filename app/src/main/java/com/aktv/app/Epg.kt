package com.aktv.app

import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

data class Programme(
    val title: String, val start: Long, val stop: Long,
    val startText: String, val stopText: String,
    val desc: String, val category: String, val image: String
)

data class Guide(val now: Programme?, val next: Programme?, val upcoming: List<Programme>)

/**
 * Programme guide from GET /api/public/guide/{id} (V1 channels only).
 * start / stop are unix seconds; startTime / stopTime are the provider's own display strings.
 * Everything is read defensively: an empty or odd schedule just means "no programme details".
 */
object Epg {
    private class Hit(val at: Long, val g: Guide)
    private val cache = HashMap<String, Hit>()
    private const val TTL = 45_000L          // the API itself caches for ~30 s

    fun cached(id: String): Guide? =
        cache[id]?.takeIf { System.currentTimeMillis() - it.at < TTL }?.g

    fun put(id: String, g: Guide) { cache[id] = Hit(System.currentTimeMillis(), g) }

    private fun str(o: JSONObject, k: String) = if (o.isNull(k)) "" else o.optString(k, "").trim()

    private fun prog(o: JSONObject?): Programme? {
        if (o == null) return null
        val title = str(o, "title")
        if (title.isEmpty()) return null
        return Programme(title, o.optLong("start", 0L), o.optLong("stop", 0L),
            str(o, "startTime"), str(o, "stopTime"), str(o, "desc"), str(o, "category"), str(o, "image"))
    }

    private fun list(a: JSONArray?): List<Programme> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { prog(a.optJSONObject(it)) }

    fun parse(json: String, nowSec: Long = System.currentTimeMillis() / 1000): Guide {
        val o = JSONObject(json)
        val sched = list(o.optJSONArray("guide")).sortedBy { it.start }
        val now = prog(o.optJSONObject("nowPlaying"))
            ?: sched.firstOrNull { it.start in 1..nowSec && nowSec < it.stop }
        val after = now?.stop?.takeIf { it > 0 } ?: nowSec
        val next = prog(o.optJSONObject("upNext")) ?: sched.firstOrNull { it.start >= after }
        var up = list(o.optJSONArray("upcoming"))
        if (up.isEmpty()) up = sched.filter { it.start >= (next?.stop?.takeIf { s -> s > 0 } ?: after) }
        return Guide(now, next, up.take(4))
    }

    fun time(sec: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(sec * 1000))

    fun startOf(p: Programme) = if (p.start > 0) time(p.start) else p.startText

    fun range(p: Programme): String =
        if (p.start > 0 && p.stop > 0) "${time(p.start)} – ${time(p.stop)}"
        else listOf(p.startText, p.stopText).filter { it.isNotBlank() }.joinToString(" – ")

    fun progress(p: Programme, nowSec: Long = System.currentTimeMillis() / 1000): Float =
        if (p.stop <= p.start) 0f else ((nowSec - p.start).toFloat() / (p.stop - p.start)).coerceIn(0f, 1f)
}
