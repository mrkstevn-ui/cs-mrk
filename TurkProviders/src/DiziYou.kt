package com.turk

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class DiziYou : MainAPI() {
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (page <= 1) {
            request.data.replace("/page/", "/")
        } else {
            "${request.data}$page/"
        }

        val document = app.get(targetUrl, headers = mapOf("Referer" to "$mainUrl/")).document

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

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.trim().replace(" ", "+")}"
        val document = app.get(searchUrl, headers = mapOf("Referer" to "$mainUrl/")).document

        return document.select("article.card, a[href*='/dizi/'], a[href*='/film/']").mapNotNull { card ->
            if (card.tagName() == "a") {
                val href = fixUrlNull(card.attr("href")) ?: return@mapNotNull null
                val isMovie = href.contains("/film/")
                val title = card.text().replace("izle", "", ignoreCase = true).trim().ifEmpty { return@mapNotNull null }
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
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mapOf("Referer" to "$mainUrl/")).document

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
            }
        }

        // Series: Extract slug
        val slug = url.trimEnd('/').substringAfterLast('/')
        val episodes = mutableListOf<Episode>()

        // Check season buttons
        val seasonButtons = document.select("button[data-season-number]")
        if (seasonButtons.isNotEmpty()) {
            for (btn in seasonButtons) {
                val seasonNum = btn.attr("data-season-number").toIntOrNull() ?: 1
                val totalEps = btn.attr("data-total-episodes").toIntOrNull() ?: 1

                for (ep in 1..totalEps) {
                    val epUrl = "$mainUrl/bolum/$slug-$seasonNum-sezon-$ep-bolum/"
                    episodes.add(
                        newEpisode(epUrl) {
                            this.name = "Sezon $seasonNum Bölüm $ep"
                            this.season = seasonNum
                            this.episode = ep
                        }
                    )
                }
            }
        } else {
            // Fallback: Check links to /bolum/
            document.select("a[href*='/bolum/']").forEach { linkEl ->
                val epHref = fixUrlNull(linkEl.attr("href")) ?: return@forEach
                val epMatch = Regex("""(\d+)-sezon-(\d+)-bolum""").find(epHref)
                val s = epMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val e = epMatch?.groupValues?.get(2)?.toIntOrNull() ?: 1
                val epName = linkEl.text().trim().ifEmpty { "Sezon $s Bölüm $e" }

                episodes.add(
                    newEpisode(epHref) {
                        this.name = epName
                        this.season = s
                        this.episode = e
                    }
                )
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
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
            val html = response.text
            val doc = Jsoup.parse(html)

            doc.select("iframe").forEach { iframe ->
                val src = fixUrlNull(iframe.attr("src").ifBlank { iframe.attr("data-src") }) ?: return@forEach

                if (src.contains("ksdpictures.site/dzembed.php")) {
                    try {
                        val embedHtml = app.get(src, headers = mapOf("Referer" to "$mainUrl/")).text
                        val sourceMatch = Regex("""var\s+SOURCE\s*=\s*["']([^"']+)["']""").find(embedHtml)
                        val m3u8Url = sourceMatch?.groupValues?.get(1)

                        if (m3u8Url != null && m3u8Url.isNotBlank()) {
                            callback.invoke(
                                ExtractorLink(
                                    name = this.name,
                                    source = this.name,
                                    url = m3u8Url,
                                    referer = "https://ksdpictures.site/",
                                    quality = Qualities.P1080.value,
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
                                val subFile = track.optString("file")
                                val subLabel = track.optString("label").ifEmpty { "Altyazı" }
                                if (subFile.isNotBlank()) {
                                    subtitleCallback.invoke(SubtitleFile(subLabel, subFile))
                                }
                            }
                        }
                    } catch (e: Exception) {
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
            // Ignore
        }

        return foundLinks
    }
}
