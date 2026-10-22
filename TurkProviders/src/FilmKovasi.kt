package com.turk

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.nio.charset.StandardCharsets

class FilmKovasi : MainAPI() {
    override var mainUrl = "https://filmkovasi.co"
    override var name = "FilmKovası"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Son Eklenen Filmler",
        "$mainUrl/tur/aksiyon-filmleri-izle/page/" to "Aksiyon Filmleri",
        "$mainUrl/tur/komedi-filmleri-izle/page/" to "Komedi Filmleri",
        "$mainUrl/tur/korku-filmleri-izle/page/" to "Korku Filmleri",
        "$mainUrl/tur/bilim-kurgu-filmleri-izle/page/" to "Bilim Kurgu Filmleri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (page <= 1) {
            request.data.replace("/page/", "/")
        } else {
            "${request.data}$page/"
        }

        val document = app.get(targetUrl, headers = mapOf("Referer" to "$mainUrl/")).document
        val items = document.select(".movie-box, article, div.poster").mapNotNull { card ->
            toSearchResult(card)
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    private fun toSearchResult(card: Element): SearchResponse? {
        val linkEl = card.selectFirst("a[href*='filmkovasi.co']") ?: card.selectFirst("a")
        val href = fixUrlNull(linkEl?.attr("href")?.substringBefore("?")) ?: return null
        if (href.contains("/page/") || href.contains("/tur/") || href.contains("/uye-ol/")) return null

        val img = card.selectFirst("img")
        val title = img?.attr("alt")?.replace("İzle", "", ignoreCase = true)?.trim()
            ?: card.selectFirst(".title, h2, h3")?.text()?.trim()
            ?: linkEl?.text()?.trim()
            ?: return null

        return newMovieSearchResponse(title, href, TvType.Movie) {
            addPoster(img, mainUrl)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.trim().replace(" ", "+")}"
        val document = app.get(searchUrl, headers = mapOf("Referer" to "$mainUrl/")).document

        return document.select(".movie-box, article, div.poster").mapNotNull { card ->
            toSearchResult(card)
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mapOf("Referer" to "$mainUrl/")).document

        val title = document.selectFirst("h1, .film-title, meta[property='og:title']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }?.replace("Filmkovası", "", ignoreCase = true)
            ?.replace("Film izle", "", ignoreCase = true)
            ?.replace("HD Film izle", "", ignoreCase = true)
            ?.replace("|", "")
            ?.trim() ?: "FilmKovası"

        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: cleanPosterUrl(document.selectFirst(".poster img, .img img, img"), mainUrl)

        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst(".film-icerik, .story, .entry-content p")?.text()?.trim()

        val year = document.selectFirst(".film-bilgileri, .elements")?.text()?.let {
            Regex("""\b(19\d\d|20\d\d)\b""").find(it)?.groupValues?.get(1)?.toIntOrNull()
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            addPoster(poster, mainUrl)
            this.plot = description
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pagesToScan = mutableListOf(data)
        // Check part 2 (e.g. dublaj)
        val cleanBase = data.trimEnd('/')
        pagesToScan.add("$cleanBase/2/")

        var foundLinks = false

        for (pageUrl in pagesToScan) {
            try {
                val html = app.get(pageUrl, headers = mapOf("Referer" to "$mainUrl/")).text

                // Extract base64 inside JSON.parse(d)
                val jsonParseIdx = html.indexOf("var s=JSON.parse(d);")
                if (jsonParseIdx != -1) {
                    val endQuote = html.lastIndexOf('"', jsonParseIdx)
                    if (endQuote != -1) {
                        val startQuote = html.lastIndexOf('"', endQuote - 1)
                        if (startQuote != -1) {
                            val b64 = html.substring(startQuote + 1, endQuote)
                            val decodedJson = String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8)
                            val playerUrls = Regex(""""([^"]+)"""").findAll(decodedJson).map { it.groupValues[1].replace("\\/", "/") }.toList()

                            for (playerUrl in playerUrls) {
                                if (playerUrl.startsWith("http") && !playerUrl.contains("youtube.com")) {
                                    val loaded = loadExtractor(playerUrl, "$mainUrl/", subtitleCallback, callback)
                                    if (loaded) {
                                        foundLinks = true
                                    }
                                }
                            }
                        }
                    }
                }

                // Also check any standard iframes
                val doc = Jsoup.parse(html)
                doc.select("iframe").forEach { iframe ->
                    val src = iframe.attr("src").takeIf { it.isNotBlank() && !it.startsWith("about:") }
                        ?: iframe.attr("data-src").takeIf { it.isNotBlank() && !it.startsWith("about:") }
                    if (src != null && src.startsWith("http") && !src.contains("youtube.com")) {
                        val loaded = loadExtractor(src, "$mainUrl/", subtitleCallback, callback)
                        if (loaded) {
                            foundLinks = true
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        return foundLinks
    }
}
