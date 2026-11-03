package com.turk

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URI
import java.util.concurrent.CancellationException

/** Conservative fallback for standard media markup. Never treats an HTML player as a stream. */
class HtmlMediaResolver(private val providerName: String) {
    private val visited = mutableSetOf<String>()
    private var requests = 0

    private fun mediaType(url: String, mime: String? = null): ExtractorLinkType? {
        val path = try { URI(url).path.lowercase() } catch (_: Exception) { return null }
        return when {
            path.endsWith(".m3u8") || mime?.contains("mpegurl", true) == true -> ExtractorLinkType.M3U8
            path.endsWith(".mpd") || mime?.contains("dash+xml", true) == true -> ExtractorLinkType.DASH
            path.endsWith(".mp4") || path.endsWith(".webm") || mime?.startsWith("video/", true) == true -> ExtractorLinkType.VIDEO
            else -> null
        }
    }

    private suspend fun emit(raw: String?, base: String, mime: String?, callback: (ExtractorLink) -> Unit) {
        val url = resolveHttpUrl(raw, base) ?: return
        val type = mediaType(url, mime) ?: return
        callback(newExtractorLink(source = providerName, name = "$providerName - Alternatif", url = url, type = type) {
            referer = base
            quality = Qualities.Unknown.value
            headers = mapOf("Referer" to base, "User-Agent" to TURK_USER_AGENT)
        })
    }

    suspend fun resolve(url: String, referer: String,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit, depth: Int = 0) {
        val target = resolveHttpUrl(url, referer) ?: return
        if (depth > 2 || requests >= 6 || !visited.add(target)) return
        if (mediaType(target) != null) { emit(target, referer, null, callback); return }
        requests++
        // Let Cloudstream's host registry try its specialized protocol before reading generic markup.
        if (depth > 0) {
            var extracted = false
            try {
                loadExtractor(target, referer, subtitleCallback) { link ->
                    if (resolveHttpUrl(link.url) != null) { extracted = true; callback(link) }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Standard markup remains an alternative. */ }
            if (extracted) return
        }
        val response = app.get(target, referer = referer,
            headers = mapOf("User-Agent" to TURK_USER_AGENT), timeout = 10L)
        if (response.code !in 200..299) return
        val contentType = response.headers["Content-Type"].orEmpty()
        // A fallback page must be HTML. Do not download a binary stream to guess its type.
        if (contentType.isNotBlank() && !contentType.contains("html", true)) return
        val text = response.text
        if (text.length > 2_000_000) return
        val document = Jsoup.parse(text, response.url).requireContent(providerName)
        val base = response.url
        val player = document.selectFirst("#player, #video-player, .video-player, .player-container, .film-player")
        val scope = player ?: document
        scope.select("video[src], video source[src], audio[src], audio source[src]").take(12).forEach {
            // Exclude explicitly marked trailers and advertising widgets.
            if ((listOf(it) + it.parents()).any { parent -> parent.hasClass("trailer") || parent.hasClass("advertisement") }) return@forEach
            emit(it.attr("src"), base, it.attr("type"), callback)
        }
        scope.select("track[src][kind=subtitles], track[src][kind=captions]").take(20).forEach {
            val src = resolveHttpUrl(it.attr("src"), base) ?: return@forEach
            subtitleCallback(SubtitleFile(it.attr("label").ifBlank { it.attr("srclang").ifBlank { "Altyazı" } }, src))
        }
        // Only top-level VideoObject nodes; nested trailer/recommendation objects are not traversed.
        document.primaryVideoObjects().take(4).forEach {
            val content = it.optString("contentUrl")
            emit(content, base, it.optString("encodingFormat"), callback)
            val embed = resolveHttpUrl(it.optString("embedUrl"), base)
            if (content.isBlank() && embed != null) {
                try { resolve(embed, base, subtitleCallback, callback, depth + 1) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Continue with other declared sources. */ }
            }
        }
        scope.select("iframe[src], iframe[data-src]").filter {
            !(listOf(it) + it.parents()).any { parent -> parent.hasClass("trailer") || parent.hasClass("advertisement") } &&
                !it.attr("src").contains("recaptcha", true) && !it.attr("src").contains("devtools", true)
        }.take(4).forEach {
            val src = resolveHttpUrl(it.attr("src").ifBlank { it.attr("data-src") }, base) ?: return@forEach
            try { resolve(src, base, subtitleCallback, callback, depth + 1) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* One broken alternative must not hide another. */ }
        }
    }
}
