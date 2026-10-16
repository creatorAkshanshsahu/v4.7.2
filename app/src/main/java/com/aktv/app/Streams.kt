package com.aktv.app

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy

/**
 * How a stream request introduces itself to the CDN. The CDN behind some channels (Star Sports,
 * Sony YAY ...) answers 403 to one presentation and plays another, so when a channel is refused
 * the player walks this list, and remembers the one that worked for that channel.
 */
class StreamProfile(
    val name: String, val userAgent: String,
    val cookie: Boolean,            // send the __hdnea__ token from the manifest URL as a Cookie
    val tokenOnSegments: Boolean,   // also append the token to every request on the manifest's host
    val referer: String?            // send Referer + Origin
)

object Streams {
    private const val SITE = "https://livetgtv.lovable.app/"
    const val CHROME = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    val PROFILES = listOf(
        // 0: exactly what the app always did, so channels that already play keep playing
        StreamProfile("player", "plaYtv/7.1.5 (Linux;Android 13) ExoPlayerLib/2.11.7", true, false, null),
        // 1: what a phone browser sends (the way these channels already play on the phone)
        StreamProfile("browser", CHROME, false, false, null),
        // 2: browser + token carried on every segment request
        StreamProfile("browser+token", CHROME, true, true, null),
        // 3: browser coming from the TG TV site
        StreamProfile("site", CHROME, false, false, SITE),
        // 4: plain ExoPlayer + token on segments
        StreamProfile("exo", "ExoPlayerLib/1.3.1 (Linux; Android 13)", true, true, null),
        // 5: what the website's player sends: browser identity, site Referer/Origin, token as cookie and on every segment
        StreamProfile("site+token", CHROME, true, true, SITE),
        // 6: exactly what the channel playlist asks for (JioTV CDN): its UA, JioTV Referer/Origin, token as cookie
        StreamProfile("jiotv", "@shoebbro", true, false, "https://www.jiotv.com/"),
        // 7: same, token also on every segment
        StreamProfile("jiotv+token", "@shoebbro", true, true, "https://www.jiotv.com/")
    )

    fun isJio(url: String) = url.contains("jio.com", ignoreCase = true)

    private val jar = CookieManager(null, CookiePolicy.ACCEPT_ALL)

    /** Profile 0 uses no cookie jar (unchanged behaviour). The others keep Set-Cookie from the CDN, like a browser. */
    fun jarFor(profile: Int) {
        jar.cookieStore.removeAll()
        CookieHandler.setDefault(if (profile == 0) null else jar)
    }

    fun tokenOf(url: String): String? = Regex("__hdnea__=([^&]+)").find(url)?.groupValues?.get(1)

    fun factory(p: StreamProfile, url: String): DataSource.Factory {
        val token = tokenOf(url)
        val hdrs = HashMap<String, String>()
        if (p.cookie && token != null) hdrs["Cookie"] = "__hdnea__=$token"
        if (p.referer != null) { hdrs["Referer"] = p.referer; hdrs["Origin"] = p.referer.trimEnd('/') }
        val http = DefaultHttpDataSource.Factory().setUserAgent(p.userAgent)
            .setDefaultRequestProperties(hdrs).setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000).setReadTimeoutMs(15000)
        if (!p.tokenOnSegments || token == null) return http
        val host = Uri.parse(url).host
        // like the website's player: manifest and every segment on the stream's CDN carry the token as their whole query
        // (also after a redirect from a relay server to the Jio CDN, which is how the backup servers hand streams out)
        return ResolvingDataSource.Factory(http, ResolvingDataSource.Resolver { spec ->
            val u = spec.uri
            val h = u.host ?: ""
            if (h == host || h.endsWith("cdn.jio.com"))
                spec.withUri(Uri.parse(u.toString().substringBefore("?").replace(Regex("(?<!:)//+"), "/") + "?__hdnea__=" + token))
            else spec
        })
    }
}
