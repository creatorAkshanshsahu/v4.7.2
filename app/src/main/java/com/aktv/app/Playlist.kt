package com.aktv.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Channels from the bundled playlist (assets/playlist.m3u): stream link, ClearKey keys and token, by channel name. */
object Playlist {
    class Entry(val url: String, val drm: JSONObject?)
    private var map: Map<String, Entry>? = null

    fun norm(n: String) = n.lowercase().replace("&", "and").replace(Regex("[^a-z0-9]"), "")

    @Synchronized fun load(ctx: Context): Map<String, Entry> {
        map?.let { return it }
        val out = HashMap<String, Entry>()
        try {
            var name: String? = null; var keys: String? = null; var cookie: String? = null
            ctx.assets.open("playlist.m3u").bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val l = raw.trim()
                    when {
                        l.startsWith("#EXTINF") -> { name = l.substringAfterLast(",").trim(); keys = null; cookie = null }
                        l.startsWith("#KODIPROP:inputstream.adaptive.license_key=") -> keys = l.substringAfter("=")
                        l.startsWith("#EXTVLCOPT:http-cookie=") -> cookie = l.substringAfter("http-cookie=")
                        l.startsWith("http") && name != null -> {
                            val base = l.substringBefore("|").trimEnd('?')
                            val tok = Regex("__hdnea__=([^&|]+)").find(cookie ?: l)?.groupValues?.get(1)
                            val url = if (tok != null && !base.contains("__hdnea__")) base + (if (base.contains("?")) "&" else "?") + "__hdnea__=" + tok else base
                            var drm: JSONObject? = null
                            if (!keys.isNullOrBlank() && !keys!!.startsWith("http")) {
                                val arr = JSONArray()
                                keys!!.split(",").map { it.trim() }.filter { it.contains(":") }.forEach {
                                    arr.put(JSONObject().put("keyId", it.substringBefore(":")).put("key", it.substringAfter(":")))
                                }
                                if (arr.length() > 0) drm = JSONObject().put("type", "clearkey").put("keys", arr)
                            }
                            out.putIfAbsent(norm(name!!), Entry(url, drm))
                            name = null
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        map = out
        return out
    }

    fun find(ctx: Context, name: String): Entry? = load(ctx)[norm(name)]
}
