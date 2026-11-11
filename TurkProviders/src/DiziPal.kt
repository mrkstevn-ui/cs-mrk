package com.turk

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class DiziPal : AdaptiveProvider() {
    override var mainUrl = "https://dizipal.rest"
    override var name = "DiziPal"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val cfInterceptor = WebViewResolver(Regex(""".*dizipal.*"""))

    override val mainPage = mainPageOf(
        "$mainUrl/filmler" to "Son Eklenen Filmler",
        "$mainUrl/diziler" to "Son Eklenen Diziler",
        "$mainUrl/trend" to "Trendler & Popüler",
        "$mainUrl/anime" to "Animeler"
    )

    override suspend fun mainPageProvider(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (page > 1) {
            "${request.data}?page=$page"
        } else {
            request.data
        }

        val document = app.get(targetUrl, interceptor = cfInterceptor, headers = mapOf("Referer" to "$mainUrl/")).document.requireContent(name)
        val items = document.select("article.content-card, li.content-card, .card-link, a[href*='/film/'], a[href*='/dizi/']").mapNotNull { card ->
            toSearchResult(card)
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    private fun toSearchResult(card: Element): SearchResponse? {
        val linkEl = if (card.tagName() == "a") card else card.selectFirst("a")
        val href = fixUrlNull(linkEl?.attr("href")) ?: return null
        if (!href.contains("/film/") && !href.contains("/dizi/") && !href.contains("/movies/") && !href.contains("/series/")) return null

        val ariaLabel = linkEl?.attr("aria-label")?.trim().orEmpty()
        val img = card.selectFirst("img")
        val cardTitle = card.selectFirst(".card-title")?.text()?.trim()
        val title = when {
            !cardTitle.isNullOrBlank() -> cardTitle
            ariaLabel.isNotBlank() -> ariaLabel.split(",").firstOrNull()?.trim()
            else -> img?.attr("alt")?.trim()?.ifBlank { null }
                ?: href.substringAfterLast("/").replace("-", " ").capitalizeWords()
        } ?: return null

        val isSeries = href.contains("/series/") || href.contains("/dizi/")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                addPoster(img, mainUrl)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                addPoster(img, mainUrl)
            }
        }
    }

    private fun String.capitalizeWords(): String =
        split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    override suspend fun searchProvider(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val searchUrl = "$mainUrl/arama?q=$encoded"
        val results = mutableListOf<SearchResponse>()

        try {
            val document = app.get(searchUrl, interceptor = cfInterceptor, headers = mapOf("Referer" to "$mainUrl/")).document.requireContent(name).cleanNoise()
            val container = document.selectFirst(".search-results, .site-main, #main, #content, .content-area, .items, main") ?: document
            container.select("article.content-card, li.content-card, .card-link, a[href*='/film/'], a[href*='/dizi/']").forEach { card ->
                toSearchResult(card)?.let { results.add(it) }
            }
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            reportStage("adapter", e)
            // fallback
        }

        return results.filter { it.isMatch(query) }.distinctBy { it.url }
    }

    override suspend fun loadProvider(url: String): LoadResponse {
        val document = app.get(url, interceptor = cfInterceptor, headers = mapOf("Referer" to "$mainUrl/")).document.requireContent(name)
        val fullHtml = document.html()

        val rawTitle = document.selectFirst("title")?.text() ?: ""
        val title = rawTitle
            .replace("Dizipal", "", ignoreCase = true)
            .replace("1080P Türkçe Dublaj & Altyazılı izle", "", ignoreCase = true)
            .replace("izle", "", ignoreCase = true)
            .replace("&amp;", "&")
            .trim()
            .ifBlank { "DiziPal" }

        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst("img")?.attr("src")

        val description = document.selectFirst("meta[name='description'], meta[property='og:description']")?.attr("content")?.trim()

        if (url.contains("/series/") || url.contains("/dizi/")) {
            val seriesSlug = url.substringBefore("?").trimEnd('/').substringAfterLast('/')
            val scopedContainer = document.clone().cleanNoise()
            val epMatches = Regex("""/bolum/([a-zA-Z0-9_-]+)""").findAll(scopedContainer.html())
            val allMatches = epMatches.map { it.groupValues[1] }.distinct().toList()
            val filteredSlugs = allMatches.filter { it.startsWith(seriesSlug, ignoreCase = true) }.ifEmpty { allMatches }

            val episodes = filteredSlugs.map { epSlug ->
                val epUrl = "$mainUrl/bolum/$epSlug"
                val seasonEpisodeMatch = Regex("""-(\d+)-sezon-(\d+)-bolum""").find(epSlug)
                    ?: Regex("""-(\d+)x(\d+)""").find(epSlug)

                val sNum = seasonEpisodeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epNum = seasonEpisodeMatch?.groupValues?.get(2)?.toIntOrNull()

                newEpisode(epUrl) {
                    this.name = if (epNum != null) "$sNum. Sezon $epNum. Bölüm" else epSlug.replace("-", " ")
                    this.season = sNum
                    this.episode = epNum
                }
            }.distinctBy { it.data }.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: Int.MAX_VALUE }))

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                addPoster(poster, mainUrl)
                this.plot = description
            }.withMetadata(document)
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            addPoster(poster, mainUrl)
            this.plot = description
        }.withMetadata(document)
    }

    override suspend fun resolveLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data, interceptor = cfInterceptor, headers = mapOf("Referer" to "$mainUrl/")).text

        val ciphertext = Regex("""ciphertext[^a-zA-Z0-9]+([a-zA-Z0-9+/=]+)""").find(html)?.groupValues?.get(1) ?: return false
        val ivHex = Regex("""iv[^a-zA-Z0-9]+([a-f0-9]{32})""").find(html)?.groupValues?.get(1) ?: return false
        val saltHex = Regex("""salt[^a-zA-Z0-9]+([a-f0-9]{256,})""").find(html)?.groupValues?.get(1) ?: return false
        val kList = Regex("""k[^a-zA-Z0-9]+([a-zA-Z0-9]{50,})""").findAll(html).map { it.groupValues[1] }.toList()
        val k = kList.firstOrNull() ?: return false

        val decryptedRaw = try {
            decryptDizipal(ciphertext, ivHex, saltHex, k)
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            reportStage("adapter", e)
            return false
        }

        val iframeUrl = if (decryptedRaw.startsWith("//")) "https:$decryptedRaw" else decryptedRaw
        if (!iframeUrl.startsWith("http")) return false

        // Fetch iframe content
        val iframeHtml = app.get(iframeUrl, headers = mapOf("Referer" to "$mainUrl/")).text
        val playlistToken = Regex("""openPlayer\(['"]([^'"]+)['"]""").find(iframeHtml)?.groupValues?.get(1) ?: return false

        val encodedPlaylist = URLEncoder.encode(playlistToken, "UTF-8")
        val iframeUri = URI(iframeUrl)
        val sourceUrl = "${iframeUri.scheme}://${iframeUri.host}/source2.php?v=$encodedPlaylist"

        val sourceJson = app.get(
            sourceUrl,
            headers = mapOf(
                "Referer" to iframeUrl,
                "X-Requested-With" to "XMLHttpRequest"
            )
        ).text

        val mFile = Regex(""""file"\s*:\s*"([^"]+)"""").find(sourceJson)?.groupValues?.get(1)?.replace("\\/", "/") ?: return false
        val masterUrl = mFile.replace("m.php", "master.m3u8")

        val host = iframeUri.host
        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = masterUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = iframeUrl
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "Referer" to iframeUrl,
                    "Origin" to "https://$host"
                )
            }
        )

        return true
    }

    private fun decryptDizipal(ciphertext: String, ivHex: String, saltHex: String, k: String): String {
        val salt = hexToByteArray(saltHex)
        val iv = hexToByteArray(ivHex)

        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        val spec = PBEKeySpec(k.toCharArray(), salt, 999, 256)
        val tmp = factory.generateSecret(spec)
        val secretKey = SecretKeySpec(tmp.encoded, "AES")

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))

        val decodedCipher = Base64.decode(ciphertext.replace("\\/", "/"), Base64.DEFAULT)
        val decryptedBytes = cipher.doFinal(decodedCipher)
        return String(decryptedBytes, StandardCharsets.UTF_8).trim()
    }

    private fun hexToByteArray(hex: String): ByteArray {
        val cleanHex = if (hex.length % 2 != 0) "0$hex" else hex
        val bytes = ByteArray(cleanHex.length / 2)
        for (i in bytes.indices) {
            val index = i * 2
            bytes[i] = cleanHex.substring(index, index + 2).toInt(16).toByte()
        }
        return bytes
    }
}
