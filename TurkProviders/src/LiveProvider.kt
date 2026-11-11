package com.turk

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CancellationException

/** The API origin is independent of the catalog origin; never derive it by renaming the host. */
abstract class LiveProvider : AdaptiveProvider() {
    abstract var apiUrl: String
    @Volatile private var resolvedBase: String? = null
    @Volatile private var resolvedAt = 0L
    protected val matchesApi get() = "$apiUrl/matches.php"
    protected val channelsApi get() = "$apiUrl/channels.php"

    protected suspend fun liveArray(endpoint: String): JSONArray {
        val response = app.get(endpoint, referer = "$mainUrl/",
            headers = mapOf("User-Agent" to TURK_USER_AGENT), timeout = 10L)
        check(response.code in 200..299) { "Live API HTTP status" }
        val json = response.text.trim()
        check(json.startsWith("[")) { "Live API schema mismatch" }
        return JSONArray(json)
    }

    private suspend fun activeBase(page: String): String? {
        val now = System.currentTimeMillis()
        if (now - resolvedAt in 0..300_000 && resolvedBase != null) return resolvedBase
        try {
            val response = app.get("$apiUrl/domain.php", referer = "$mainUrl/", timeout = 10L)
            if (response.code in 200..299) {
                val base = resolveHttpUrl(JSONObject(response.text).optString("baseurl"), "$apiUrl/")
                if (base != null) { resolvedBase = base; resolvedAt = now; return base }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { reportStage("live-domain-api", e) }
        try {
            val response = app.get(page, referer = "$mainUrl/", timeout = 10L)
            val raw = Regex("""baseUrl\s*:\s*['"]([^'"]+)['"]""").find(response.text)?.groupValues?.get(1)
            val base = resolveHttpUrl(raw, response.url)
            if (base != null) { resolvedBase = base; resolvedAt = now; return base }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { reportStage("live-domain-page", e) }
        // Expired or hardcoded CDNs must not masquerade as a fresh successful resolution.
        return null
    }

    final override suspend fun resolveLinks(data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val target = queryValue(data, "id")
        val direct = resolveHttpUrl(target) ?: resolveHttpUrl(data)?.takeIf {
            val path = java.net.URI(it).path.lowercase()
            path.endsWith(".m3u8") || path.endsWith(".mpd") || path.endsWith(".mp4")
        }
        if (direct != null) {
            callback(newExtractorLink(source = name, name = name, url = direct,
                type = when {
                    java.net.URI(direct).path.endsWith(".mpd", true) -> ExtractorLinkType.DASH
                    java.net.URI(direct).path.endsWith(".mp4", true) -> ExtractorLinkType.VIDEO
                    else -> ExtractorLinkType.M3U8
                }) { referer = "$mainUrl/"; quality = Qualities.Unknown.value })
            return true
        }
        if (target.isNullOrBlank() || !Regex("[A-Za-z0-9_-]+").matches(target)) return false
        val base = activeBase(data) ?: return false
        val baseUri = java.net.URI(base)
        val stream = "${baseUri.scheme}://${baseUri.rawAuthority}${baseUri.rawPath.orEmpty().trimEnd('/')}/${encodePathSegment(target)}/mono.m3u8" +
            baseUri.rawQuery?.let { "?$it" }.orEmpty()
        callback(newExtractorLink(source = name, name = name, url = stream, type = ExtractorLinkType.M3U8) {
            referer = "$mainUrl/"
            quality = Qualities.Unknown.value
            headers = mapOf("User-Agent" to TURK_USER_AGENT, "Referer" to "$mainUrl/")
        })
        return true
    }
}
