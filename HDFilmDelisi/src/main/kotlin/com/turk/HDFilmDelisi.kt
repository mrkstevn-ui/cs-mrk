package com.turk

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class HDFilmDelisi : MainAPI() {
    override var mainUrl = "https://hdfilmdelisi.one"
    override var name = "HDFilmDelisi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Eklenen Filmler",
        "$mainUrl/populer" to "En Çok İzlenenler",
        "$mainUrl/imdb-top-250" to "IMDb Top 250",
        "$mainUrl/tur/aksiyon" to "Aksiyon Filmleri",
        "$mainUrl/tur/komedi" to "Komedi Filmleri",
        "$mainUrl/tur/korku" to "Korku Filmleri",
        "$mainUrl/tur/bilim-kurgu" to "Bilim Kurgu Filmleri",
        "$mainUrl/tur/gerilim" to "Gerilim Filmleri",
        "$mainUrl/tur/animasyon" to "Animasyon Filmleri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = request.data
        val document = app.get(targetUrl, headers = mapOf("Referer" to "$mainUrl/")).document

        val items = document.select("a[href*='/film/']").mapNotNull { card ->
            toSearchResult(card)
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    private fun toSearchResult(card: Element): SearchResponse? {
        val href = card.attr("href").takeIf { it.contains("/film/") } ?: return null
        val fullUrl = fixUrl(href)

        val img = card.selectFirst("img")
        val rawTitle = img?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: card.selectFirst("h2, h3, .title")?.text()
            ?: return null

        val title = rawTitle
            .replace("izle", "", ignoreCase = true)
            .replace("Full HD", "", ignoreCase = true)
            .replace("Türkçe Dublaj", "", ignoreCase = true)
            .replace("Türkçe Altyazılı", "", ignoreCase = true)
            .replace(Regex("""\(\d{4}\)"""), "")
            .trim()

        val posterUrl = fixUrlNull(
            img?.attr("data-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
        )

        return newMovieSearchResponse(title, fullUrl, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.trim().replace(" ", "+")}"
        val document = app.get(searchUrl, headers = mapOf("Referer" to "$mainUrl/")).document

        return document.select("a[href*='/film/']").mapNotNull { card ->
            toSearchResult(card)
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mapOf("Referer" to "$mainUrl/")).document

        val rawTitle = document.selectFirst("h1")?.text()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: "Film"

        val title = rawTitle
            .replace("izle", "", ignoreCase = true)
            .replace("Full HD", "", ignoreCase = true)
            .replace("Türkçe Dublaj", "", ignoreCase = true)
            .replace("Türkçe Altyazılı", "", ignoreCase = true)
            .replace(Regex("""\(\d{4}\)"""), "")
            .trim()

        val poster = fixUrlNull(
            document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("img[src*='/posters/']")?.attr("src")
        )

        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst("p.description, .overview, .plot")?.text()?.trim()

        val year = Regex("""\b(19\d\d|20\d\d)\b""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
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
        var foundLinks = false

        try {
            val response = app.get(data, headers = mapOf("Referer" to "$mainUrl/"))
            val html = response.text

            // 1. Check direct MP4 links in HTML
            val mp4Matches = Regex("""https?://hdfilmdelisi\.one/vids/[^\s"'<>]+\.mp4""").findAll(html)
            for (match in mp4Matches) {
                val mp4Url = match.value
                callback.invoke(
                    ExtractorLink(
                        name = this.name,
                        source = this.name,
                        url = mp4Url,
                        referer = "$mainUrl/",
                        quality = Qualities.P1080.value,
                        type = ExtractorLinkType.VIDEO
                    )
                )
                foundLinks = true
            }

            // 2. Check embed links
            val embedMatches = Regex("""https?://hdfilmdelisi\.one/embed/[^\s"'<>]+""").findAll(html)
            for (match in embedMatches) {
                try {
                    val embedUrl = match.value
                    val embedDoc = app.get(embedUrl, headers = mapOf("Referer" to data)).document
                    val sourceEl = embedDoc.selectFirst("video source")
                    val src = sourceEl?.attr("src")
                    if (src != null && src.isNotBlank()) {
                        val videoUrl = fixUrl(src)
                        callback.invoke(
                            ExtractorLink(
                                name = this.name,
                                source = "${this.name} Direct",
                                url = videoUrl,
                                referer = "$mainUrl/",
                                quality = Qualities.P1080.value,
                                type = ExtractorLinkType.VIDEO
                            )
                        )
                        foundLinks = true
                    }
                } catch (e: Exception) {
                    // Ignore embed error
                }
            }

            // 3. Fallback to standard iframes
            val doc = Jsoup.parse(html)
            doc.select("iframe").forEach { iframe ->
                val src = fixUrlNull(iframe.attr("src").ifBlank { iframe.attr("data-src") })
                if (src != null && src.startsWith("http") && !src.contains("izlegec.com")) {
                    val loaded = loadExtractor(src, "$mainUrl/", subtitleCallback, callback)
                    if (loaded) {
                        foundLinks = true
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }

        return foundLinks
    }
}
