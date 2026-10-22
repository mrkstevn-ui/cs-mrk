package com.turk

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class KultFilmler : MainAPI() {
    override var mainUrl = "https://kultfilmler.org"
    override var name = "KültFilmler"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/film-arsivi/page/" to "Son Eklenen Filmler",
        "$mainUrl/dizi-arsivi/page/" to "Son Eklenen Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}$page/"
        val document = app.get(url).document
        val items = document.select("a.mcard").mapNotNull { card ->
            toSearchResult(card)
        }
        return newHomePageResponse(request.name, items)
    }

    private fun toSearchResult(card: Element): SearchResponse? {
        val href = fixUrlNull(card.attr("href")) ?: return null
        val img = card.selectFirst("img")
        val title = img?.attr("alt")?.ifBlank { null }
            ?: card.selectFirst(".title, h2, h3")?.text()?.ifBlank { null }
            ?: card.text().trim().takeIf { it.isNotBlank() }
            ?: return null

        val isSeries = href.contains("/dizi") || card.text().contains("Sezon", ignoreCase = true)

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
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(searchUrl).document
        return document.select("a.mcard").mapNotNull { card ->
            toSearchResult(card)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1, .single-title, meta[property='og:title']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }?.replace("izle", "", ignoreCase = true)?.trim() ?: "Bilinmeyen Başlık"

        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: cleanPosterUrl(document.selectFirst(".poster img, .single-poster img, img"), mainUrl)

        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst(".story, .entry-content p, .film-ozeti")?.text()?.trim()

        val year = document.selectFirst(".film-yil, .year, .meta-year")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        val tags = document.select(".film-turler a, .genres a").map { it.text().trim() }

        val episodeElements = document.select(".bolumler a, .episodes a, a[href*='-sezon-']")
        if (episodeElements.isNotEmpty()) {
            val episodes = episodeElements.mapNotNull { ep ->
                val epHref = fixUrlNull(ep.attr("href")) ?: return@mapNotNull null
                val epName = ep.text().trim()
                val seasonNum = Regex("(\\d+)\\.\\s*Sezon", RegexOption.IGNORE_CASE).find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epNum = Regex("(\\d+)\\.\\s*Bölüm", RegexOption.IGNORE_CASE).find(epName)?.groupValues?.get(1)?.toIntOrNull()
                newEpisode(epHref) {
                    this.name = epName
                    this.season = seasonNum
                    this.episode = epNum
                }
            }
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

    data class KfSrcItem(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("langLabel") val langLabel: String? = null,
        @JsonProperty("html") val html: String? = null
    )

    data class VidpapiResponse(
        @JsonProperty("securedLink") val securedLink: String? = null,
        @JsonProperty("videoSource") val videoSource: String? = null
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val srcDataScript = document.selectFirst("script#kf-srcdata")?.data()
        var foundAny = false

        if (!srcDataScript.isNullOrBlank()) {
            try {
                val sources = AppUtils.parseJson<List<KfSrcItem>>(srcDataScript)
                for (item in sources) {
                    val iframeHtml = item.html ?: continue
                    val iframeDoc = Jsoup.parse(iframeHtml)
                    val iframeSrc = iframeDoc.selectFirst("iframe")?.attr("src") ?: continue
                    val resolvedSrc = fixUrl(iframeSrc)

                    if (resolvedSrc.contains("vidpapi.xyz")) {
                        val hash = Regex("video/([a-zA-Z0-9]+)").find(resolvedSrc)?.groupValues?.get(1)
                        if (hash != null) {
                            try {
                                val apiUrl = "https://vidpapi.xyz/player/index.php?data=$hash&do=getVideo"
                                val postHeaders = mapOf(
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                    "Referer" to resolvedSrc,
                                    "X-Requested-With" to "XMLHttpRequest"
                                )
                                val respText = app.post(
                                    apiUrl,
                                    headers = postHeaders,
                                    data = mapOf("hash" to hash, "r" to mainUrl)
                                ).text
                                val vidpapiData = AppUtils.parseJson<VidpapiResponse>(respText)
                                val m3u8Url = vidpapiData.securedLink ?: vidpapiData.videoSource
                                if (!m3u8Url.isNullOrBlank()) {
                                    val fixedM3u8 = m3u8Url.replace("\\/", "/")
                                    callback.invoke(
                                        newExtractorLink(
                                            source = "KültFilmler (${item.name ?: "Player"})",
                                            name = "KültFilmler - ${item.name ?: "Player"} [${item.langLabel ?: "TR"}]",
                                            url = fixedM3u8,
                                            type = ExtractorLinkType.M3U8
                                        ) {
                                            this.headers = mapOf(
                                                "Referer" to "https://vidpapi.xyz/",
                                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                            )
                                        }
                                    )
                                    foundAny = true
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    } else {
                        loadExtractor(resolvedSrc, mainUrl, subtitleCallback, callback)
                        foundAny = true
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Fallback: check regular iframes on page
        document.select("iframe").forEach { iframe ->
            val src = fixUrlNull(iframe.attr("src")) ?: return@forEach
            if (!src.contains("vidpapi.xyz")) {
                loadExtractor(src, mainUrl, subtitleCallback, callback)
                foundAny = true
            }
        }

        return foundAny
    }
}
