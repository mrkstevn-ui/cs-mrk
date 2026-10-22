package com.turk

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class RareFilmm : MainAPI() {
    override var mainUrl = "https://rarefilmm.com"
    override var name = "RareFilmm"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Latest Rare Films",
        "$mainUrl/category/drama/page/" to "Drama",
        "$mainUrl/category/comedy/page/" to "Comedy",
        "$mainUrl/category/horror/page/" to "Horror",
        "$mainUrl/category/sci-fi/page/" to "Sci-Fi",
        "$mainUrl/category/documentary/page/" to "Documentary",
        "$mainUrl/category/western/page/" to "Western",
        "$mainUrl/category/crime/page/" to "Crime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (page <= 1) {
            request.data.replace("/page/", "/")
        } else {
            "${request.data}$page/"
        }

        val document = app.get(targetUrl, headers = mapOf("Referer" to "$mainUrl/")).document

        val items = document.select("div.post, article.excerpt").mapNotNull { card ->
            toSearchResult(card)
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    private fun toSearchResult(card: Element): SearchResponse? {
        val linkEl = card.selectFirst("h2.excerpt-title a, a.featured-image-link") ?: return null
        val href = fixUrlNull(linkEl.attr("href")) ?: return null
        if (href.contains("/ask-for-reuploads") || href.contains("/about/")) return null

        val title = card.selectFirst("h2.excerpt-title")?.text()?.trim()
            ?: linkEl.text().trim()
            ?: return null

        val style = card.selectFirst(".featured-image")?.attr("style") ?: ""
        val posterMatch = Regex("""url\(['"]?(https?://[^'")]+)['"]?\)""").find(style)
        val posterUrl = posterMatch?.groupValues?.get(1) ?: cleanPosterUrl(card.selectFirst("img"), mainUrl)

        return newMovieSearchResponse(title, href, TvType.Movie) {
            addPoster(posterUrl, mainUrl)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.trim().replace(" ", "+")}"
        val document = app.get(searchUrl, headers = mapOf("Referer" to "$mainUrl/")).document

        return document.select("div.post, article.excerpt, .entry-header").mapNotNull { card ->
            toSearchResult(card)
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mapOf("Referer" to "$mainUrl/")).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: "Rare Film"

        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst(".featured-image")?.let {
                val s = it.attr("style")
                Regex("""url\(['"]?(https?://[^'")]+)['"]?\)""").find(s)?.groupValues?.get(1)
            } ?: cleanPosterUrl(document.selectFirst("img"), mainUrl)

        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst(".entry-content p")?.text()?.trim()

        val year = Regex("""\b(19\d\d|20\d\d)\b""").find(title)?.groupValues?.get(1)?.toIntOrNull()

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
        var foundLinks = false

        try {
            val response = app.get(data, headers = mapOf("Referer" to "$mainUrl/"))
            val document = response.document

            document.select("iframe").forEach { iframe ->
                var src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                if (src.startsWith("//")) {
                    src = "https:$src"
                }

                if (src.startsWith("http")) {
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
