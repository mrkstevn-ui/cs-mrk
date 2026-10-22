package com.turk

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class DiziGecesi : MainAPI() {
    override var mainUrl = "https://dizigecesi.com"
    override var name = "DiziGecesi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/diziler?page=" to "Son Eklenen Diziler",
        "$mainUrl/filmler?page=" to "Son Eklenen Filmler",
        "$mainUrl/populer" to "Popüler İçerikler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (request.data.contains("?page=")) {
            "${request.data}$page"
        } else {
            request.data
        }

        val document = app.get(targetUrl, headers = mapOf("Referer" to "$mainUrl/tr")).document
        val items = document.select("a.card-series, a[class*='home-slider-item']").mapNotNull { card ->
            toSearchResult(card)
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    private fun toSearchResult(card: Element): SearchResponse? {
        val href = fixUrlNull(card.attr("href")) ?: return null
        if (!href.contains("/dizi/") && !href.contains("/film/")) return null

        val img = card.selectFirst("img")
        val title = card.selectFirst(".card-series__content-title, .home-slider-item__content-title")?.text()?.trim()
            ?: img?.attr("alt")?.trim()
            ?: card.text().trim().takeIf { it.isNotBlank() }
            ?: return null

        val isSeries = href.contains("/dizi/")

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

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = query.trim().replace(" ", "+")
        val searchUrl = "$mainUrl/arama/$encodedQuery"
        val document = app.get(searchUrl, headers = mapOf("Referer" to "$mainUrl/tr")).document

        return document.select("a.card-series").mapNotNull { card ->
            toSearchResult(card)
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mapOf("Referer" to "$mainUrl/tr")).document
        val title = document.selectFirst("h1, .series-info-heading__title, meta[property='og:title']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }?.replace("İzle", "", ignoreCase = true)?.replace("- Dizigecesi", "", ignoreCase = true)?.trim() ?: "DiziGecesi"

        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: cleanPosterUrl(document.selectFirst(".card-series__image img, img.card-series__image-media, img"), mainUrl)

        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst(".series-info-heading__description, .overview, p.description")?.text()?.trim()

        val year = document.selectFirst(".series-info-heading__meta-year, .year")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        val tags = document.select(".series-info-heading__genres a, .genres a").map { it.text().trim() }

        val episodeElements = document.select(".card-episode")
        if (episodeElements.isNotEmpty() || url.contains("/dizi/")) {
            val episodes = episodeElements.mapNotNull { epCard ->
                val epLinkEl = epCard.selectFirst("a[href*='-bolum']") ?: epCard.selectFirst("a")
                val epHref = fixUrlNull(epLinkEl?.attr("href")) ?: return@mapNotNull null
                val epTitle = epCard.selectFirst(".card-episode__content-title")?.text()?.trim()
                val epDesc = epCard.selectFirst(".card-episode__content-description")?.text()?.trim()

                val sNum = Regex("(\\d+)-sezon", RegexOption.IGNORE_CASE).find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("(\\d+)\\.\\s*Sezon", RegexOption.IGNORE_CASE).find(epDesc ?: "")?.groupValues?.get(1)?.toIntOrNull()
                    ?: 1
                val epNum = Regex("(\\d+)-bolum", RegexOption.IGNORE_CASE).find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("(\\d+)\\.\\s*Bölüm", RegexOption.IGNORE_CASE).find(epDesc ?: "")?.groupValues?.get(1)?.toIntOrNull()

                val epName = if (!epDesc.isNullOrBlank()) epDesc else "$sNum. Sezon $epNum. Bölüm"
                val epThumb = fixUrlNull(epCard.selectFirst("img")?.attr("src"))

                newEpisode(epHref) {
                    this.name = epName
                    this.season = sNum
                    this.episode = epNum
                    this.posterUrl = epThumb
                }
            }.distinctBy { it.data }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                addPoster(poster, mainUrl)
                this.plot = description
                this.year = year
                this.tags = tags
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            addPoster(poster, mainUrl)
            this.plot = description
            this.year = year
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = mapOf("Referer" to "$mainUrl/")).document

        // Collect all embed IDs
        val embedIds = document.select("[data-embed]").mapNotNull { el ->
            el.attr("data-embed").trim().takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() } }
        }.distinct()

        var foundLinks = false

        for (embedId in embedIds) {
            try {
                val ajaxRes = app.post(
                    "$mainUrl/ajax/embed",
                    data = mapOf("id" to embedId),
                    headers = mapOf(
                        "Referer" to data,
                        "X-Requested-With" to "XMLHttpRequest",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                    )
                ).text

                val videoUrl = Regex("""src=["']([^"']+)["']""").find(ajaxRes)?.groupValues?.get(1) ?: continue
                val fullVideoUrl = fixUrl(videoUrl)

                val videoPage = app.get(fullVideoUrl, headers = mapOf("Referer" to "$mainUrl/")).text
                val playerSrc = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(videoPage)?.groupValues?.get(1) ?: continue
                val cleanPlayerSrc = playerSrc.substringBefore("#")

                if (cleanPlayerSrc.isNotBlank() && !cleanPlayerSrc.contains("404")) {
                    val loaded = loadExtractor(cleanPlayerSrc, "$mainUrl/", subtitleCallback, callback)
                    if (loaded) {
                        foundLinks = true
                    }
                }
            } catch (e: Exception) {
                // Continue to next alternative player
            }
        }

        return foundLinks
    }
}
