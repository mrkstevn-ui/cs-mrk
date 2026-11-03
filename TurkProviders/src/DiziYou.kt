package com.turk

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class DiziYou : AdaptiveProvider() {
    override var mainUrl = "https://diziyou.net"
    override var name = "DiziYou"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/diziler/page/" to "Diziler",
        "$mainUrl/filmler/page/" to "Filmler",
        "$mainUrl/tur/asya-dizileri/page/" to "Asya Dizileri",
        "$mainUrl/tur/bilim-kurgu/page/" to "Bilim Kurgu",
        "$mainUrl/tur/gerilim/page/" to "Gerilim",
        "$mainUrl/tur/korku/page/" to "Korku",
        "$mainUrl/tur/fantastik/page/" to "Fantastik"
    )

    override suspend fun mainPageProvider(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (page <= 1) {
            request.data.replace("/page/", "/")
        } else {
            "${request.data}$page/"
        }

        val document = app.get(
            targetUrl,
            headers = mapOf(
                "User-Agent" to TURK_USER_AGENT,
                "Referer" to "$mainUrl/"
            )
        ).document.requireContent(name)

        val items = document.select("article.card").mapNotNull { card ->
            toSearchResult(card)
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    private fun toSearchResult(card: Element): SearchResponse? {
        val linkEl = card.selectFirst("a[href*='/dizi/'], a[href*='/film/']") ?: return null
        val href = fixUrlNull(linkEl.attr("href")) ?: return null

        val isMovie = href.contains("/film/")
        val type = if (isMovie) TvType.Movie else TvType.TvSeries

        val img = card.selectFirst("picture img, img")
        val title = card.selectFirst("h3")?.text()?.trim()
            ?: img?.attr("alt")?.trim()
            ?: linkEl.text().trim()
            ?: return null

        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                addPoster(img, mainUrl)
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                addPoster(img, mainUrl)
            }
        }
    }

    override suspend fun searchProvider(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${encodeQuery(query)}"
        val document = app.get(
            searchUrl,
            headers = mapOf(
                "User-Agent" to TURK_USER_AGENT,
                "Referer" to "$mainUrl/"
            )
        ).document.requireContent(name).cleanNoise()

        val container = document.selectFirst(".search-results, .site-main, #main, #content, .content-area, .items, main") ?: document
        return container.select("article.card, a[href*='/dizi/'], a[href*='/film/']").mapNotNull { card ->
            if (card.tagName() == "a") {
                val href = fixUrlNull(card.attr("href")) ?: return@mapNotNull null
                val isMovie = href.contains("/film/")
                val title = card.text().replace("izle", "", ignoreCase = true).trim().ifEmpty { return@mapNotNull null }
                if (!isSearchMatch(title, href, query)) return@mapNotNull null
                val poster = card.selectFirst("img")?.attr("src")

                if (isMovie) {
                    newMovieSearchResponse(title, href, TvType.Movie) {
                        addPoster(poster, mainUrl)
                    }
                } else {
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        addPoster(poster, mainUrl)
                    }
                }
            } else {
                toSearchResult(card)
            }
        }.filter { it.isMatch(query) }.distinctBy { it.url }
    }

    override suspend fun loadProvider(url: String): LoadResponse {
        val document = app.get(
            url,
            headers = mapOf(
                "User-Agent" to TURK_USER_AGENT,
                "Referer" to "$mainUrl/"
            )
        ).document.requireContent(name)

        val title = document.selectFirst("h1, .dizi-title, meta[property='og:title']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }?.replace("izle", "", ignoreCase = true)
            ?.replace("Diziyou", "", ignoreCase = true)
            ?.replace("|", "")
            ?.trim() ?: "DiziYou"

        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: cleanPosterUrl(document.selectFirst(".dizi__poster img, picture.card__poster img, img"), mainUrl)

        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst(".dizi__story, .overview, p.story")?.text()?.trim()

        val year = document.selectFirst(".card__meta, .dizi__meta")?.text()?.let {
            Regex("""\b(19\d\d|20\d\d)\b""").find(it)?.groupValues?.get(1)?.toIntOrNull()
        }

        if (url.contains("/film/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                addPoster(poster, mainUrl)
                this.plot = description
                this.year = year
            }.withMetadata(document)
        }

        // Only publish episode URLs actually supplied by this series page.
        val records = document.episodeRecords(url).toMutableList()
        val seasonPages = document.select("button[data-season-number][data-url], a[data-season-number][href]")
            .mapNotNull { resolveHttpUrl(it.attr("data-url").ifBlank { it.attr("href") }, url) }
            .filter { httpOrigin(it) == httpOrigin(url) && it != url }.distinct()
        check(seasonPages.size <= 100) { "DiziYou: unexpected season page count" }
        // A populated first season does not mean later seasons have already been loaded.
        for (seasonPage in seasonPages) {
            val seasonDoc = app.get(seasonPage, headers = mapOf("Referer" to url), timeout = 10L)
                .document.requireContent(name)
            records.addAll(seasonDoc.episodeRecords(url))
        }
        if (records.isEmpty()) throw IllegalStateException("DiziYou: no explicit episode records; site format may have changed")
        val episodes = records.distinctBy { it.url }.map { record ->
            newEpisode(record.url) {
                this.name = record.title
                this.season = record.season
                this.episode = record.number
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
            addPoster(poster, mainUrl)
            this.plot = description
            this.year = year
        }.withMetadata(document)
    }

    override suspend fun resolveLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false

        try {
            val response = app.get(
                data,
                headers = mapOf(
                    "User-Agent" to TURK_USER_AGENT,
                    "Referer" to "$mainUrl/"
                )
            )
            val html = response.text
            val doc = Jsoup.parse(html, response.url).requireContent(name)

            doc.select("iframe").forEach { iframe ->
                val src = resolveHttpUrl(iframe.attr("src").ifBlank { iframe.attr("data-src") }, response.url) ?: return@forEach

                if (src.contains("ksdpictures.site/dzembed.php")) {
                    try {
                        val embedResponse = app.get(src, headers = mapOf("Referer" to response.url))
                        val embedHtml = embedResponse.text
                        val sourceMatch = Regex("""var\s+SOURCE\s*=\s*["']([^"']+)["']""").find(embedHtml)
                        val m3u8Url = resolveHttpUrl(sourceMatch?.groupValues?.get(1), embedResponse.url)

                        if (m3u8Url != null && m3u8Url.isNotBlank()) {
                            callback.invoke(
                                ExtractorLink(
                                    name = this.name,
                                    source = this.name,
                                    url = m3u8Url,
                                    referer = embedResponse.url,
                                    quality = Qualities.Unknown.value,
                                    type = ExtractorLinkType.M3U8
                                )
                            )
                            foundLinks = true
                        }

                        val tracksMatch = Regex("""var\s+TRACKS\s*=\s*(\[.*?\]);""").find(embedHtml)
                        if (tracksMatch != null) {
                            val tracksJson = tracksMatch.groupValues[1]
                            val jsonArray = JSONArray(tracksJson)
                            for (i in 0 until jsonArray.length()) {
                                val track = jsonArray.getJSONObject(i)
                                val subFile = resolveHttpUrl(track.optString("file"), embedResponse.url) ?: continue
                                val subLabel = track.optString("label").ifEmpty { "Altyazı" }
                                if (subFile.isNotBlank()) {
                                    subtitleCallback.invoke(SubtitleFile(subLabel, subFile))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (e is java.util.concurrent.CancellationException) throw e
                        reportStage("adapter", e)
                        // Ignore embed error
                    }
                } else if (src.startsWith("http")) {
                    val loaded = loadExtractor(src, "$mainUrl/", subtitleCallback, callback)
                    if (loaded) {
                        foundLinks = true
                    }
                }
            }
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            reportStage("adapter", e)
            // Ignore
        }

        return foundLinks
    }
}
