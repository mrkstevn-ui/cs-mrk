package com.turk

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class CizgiMax : MainAPI() {
    override var mainUrl = "https://cizgimax.online"
    override var name = "ÇizgiMax"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Cartoon, TvType.Anime, TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Bölümler",
        "$mainUrl/diziler/cizgi-film/" to "Çizgi Filmler",
        "$mainUrl/diziler/anime/" to "Animeler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val items = mutableListOf<SearchResponse>()

        document.select(".poster-card, .video-card, a[href*='-izle/']").forEach { el ->
            val href = fixUrlNull(if (el.tagName() == "a") el.attr("href") else el.selectFirst("a")?.attr("href")) ?: return@forEach
            val title = el.selectFirst(".title, h2, h3, .card-title")?.text()?.trim()
                ?: el.text().trim().takeIf { it.isNotBlank() } ?: return@forEach
            val img = el.selectFirst("img")
            val isMovie = href.contains("/film/")
            val res = if (isMovie) {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    addPoster(img, mainUrl)
                }
            } else {
                newTvSeriesSearchResponse(title, href, TvType.Cartoon) {
                    addPoster(img, mainUrl)
                }
            }
            items.add(res)
        }

        return newHomePageResponse(request.name, items.distinctBy { it.url })
    }

    data class SuggestAnimeItem(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("kind") val kind: String? = null
    )

    data class SuggestResponse(
        @JsonProperty("animes") val animes: List<SuggestAnimeItem>? = null
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/api/search/suggest/?q=${query.trim().replace(" ", "+")}"
        return try {
            val json = app.get(
                searchUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to "$mainUrl/"
                )
            ).text
            val data = AppUtils.parseJson<SuggestResponse>(json)
            data.animes?.mapNotNull { item ->
                val link = fixUrlNull(item.url) ?: return@mapNotNull null
                val title = item.name ?: "Çizgi Film"
                val poster = item.poster
                val type = if (item.kind == "anime") TvType.Anime else TvType.Cartoon

                newTvSeriesSearchResponse(title, link, type) {
                    addPoster(poster, mainUrl)
                }
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val isMovie = url.contains("/film/")
        val isEpisodePage = url.contains("-bolum-izle")

        if (isEpisodePage) {
            // Find parent show link if present
            val parentShow = document.selectFirst("a[href*='/diziler/']")?.attr("href")
            if (!parentShow.isNullOrBlank()) {
                val showDoc = app.get(fixUrl(parentShow)).document
                return parseShowPage(showDoc, fixUrl(parentShow))
            }
        }

        return parseShowPage(document, url)
    }

    private suspend fun parseShowPage(document: org.jsoup.nodes.Document, url: String): LoadResponse {
        val title = document.selectFirst("h1, .anime-title, .film-title")?.text()?.replace("izle", "", ignoreCase = true)?.trim()
            ?: "Bilinmeyen Yapım"

        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: cleanPosterUrl(document.selectFirst(".poster img, .locandina img, img"), mainUrl)

        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst(".summary, .trama, .description, p.synopsis")?.text()?.trim()

        val year = document.selectFirst(".meta-year, .year")?.text()?.filter { it.isDigit() }?.toIntOrNull()

        val episodeElements = document.select("a[href*='-bolum-izle']")
        if (episodeElements.isNotEmpty()) {
            val episodes = episodeElements.mapNotNull { ep ->
                val epHref = fixUrlNull(ep.attr("href")) ?: return@mapNotNull null
                val epText = ep.text().trim()
                val seasonNum = Regex("(\\d+)\\s*\\.?\\s*Sezon", RegexOption.IGNORE_CASE).find(epText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("(\\d+)-sezon", RegexOption.IGNORE_CASE).find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epNum = Regex("(\\d+)\\s*\\.?\\s*Bölüm", RegexOption.IGNORE_CASE).find(epText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("(\\d+)-bolum", RegexOption.IGNORE_CASE).find(epHref)?.groupValues?.get(1)?.toIntOrNull()

                newEpisode(epHref) {
                    this.name = if (epText.isNotBlank()) epText else "Bölüm $epNum"
                    this.season = seasonNum
                    this.episode = epNum
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes.distinctBy { it.data }) {
                addPoster(poster, mainUrl)
                this.plot = description
                this.year = year
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            addPoster(poster, mainUrl)
            this.plot = description
            this.year = year
        }
    }

    data class ServerItem(
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("streamUrl") val streamUrl: String? = null,
        @JsonProperty("resolveUrl") val resolveUrl: String? = null,
        @JsonProperty("src") val src: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("lang") val lang: String? = null
    )

    data class NpResolveResponse(
        @JsonProperty("id") val id: String? = null
    )

    data class TauVideoUrl(
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    data class TauVideoData(
        @JsonProperty("urls") val urls: List<TauVideoUrl>? = null
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val html = document.html()
        var foundAny = false

        val b64Match = Regex("var servers\\s*=\\s*JSON\\.parse\\(atob\\(\"([^\"]+)\"\\)\\);").find(html)
        if (b64Match != null) {
            try {
                val b64Str = b64Match.groupValues[1]
                val decodedJson = String(Base64.decode(b64Str, Base64.DEFAULT), Charsets.UTF_8)
                val servers = AppUtils.parseJson<List<ServerItem>>(decodedJson)

                for (s in servers) {
                    // Dzen / NP resolve -> Tau video
                    val resolvePath = s.resolveUrl
                    if (!resolvePath.isNullOrBlank()) {
                        val resolveUrl = fixUrl(resolvePath)
                        try {
                            val resolveJson = app.get(
                                resolveUrl,
                                headers = mapOf(
                                    "Referer" to data,
                                    "X-Requested-With" to "XMLHttpRequest"
                                )
                            ).text
                            val resolveObj = AppUtils.parseJson<NpResolveResponse>(resolveJson)
                            val tauId = resolveObj.id
                            if (!tauId.isNullOrBlank()) {
                                val tauJson = app.get(
                                    "https://tau-video.xyz/api/video/$tauId",
                                    headers = mapOf("Referer" to "https://tau-video.xyz/")
                                ).text
                                val tauData = AppUtils.parseJson<TauVideoData>(tauJson)
                                tauData.urls?.forEach { u ->
                                    val streamUrl = u.url ?: return@forEach
                                    val quality = u.label?.filter { it.isDigit() }?.toIntOrNull() ?: Qualities.P1080.value
                                    callback.invoke(
                                        newExtractorLink(
                                            source = "ÇizgiMax",
                                            name = "ÇizgiMax [${u.label ?: "1080p"}] (${s.label ?: "Ana"})",
                                            url = streamUrl,
                                            type = ExtractorLinkType.VIDEO
                                        ) {
                                            this.headers = mapOf("Referer" to "https://tau-video.xyz/")
                                            this.quality = quality
                                        }
                                    )
                                    foundAny = true
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    // Iframe / embed source (e.g. oynat -> vidmoly)
                    val iframeSrc = s.src
                    if (!iframeSrc.isNullOrBlank()) {
                        val resolvedIframe = fixUrl(iframeSrc)
                        try {
                            if (resolvedIframe.contains("/oynat/")) {
                                val oynatDoc = app.get(resolvedIframe, headers = mapOf("Referer" to data)).document
                                oynatDoc.select("iframe").forEach { innerIframe ->
                                    val innerSrc = fixUrlNull(innerIframe.attr("src")) ?: return@forEach
                                    loadExtractor(innerSrc, resolvedIframe, subtitleCallback, callback)
                                    foundAny = true
                                }
                            } else {
                                loadExtractor(resolvedIframe, data, subtitleCallback, callback)
                                foundAny = true
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Direct iframes fallback
        document.select("iframe").forEach { iframe ->
            val src = fixUrlNull(iframe.attr("src")) ?: return@forEach
            if (!src.contains("ad") && !src.contains("beacon")) {
                loadExtractor(src, data, subtitleCallback, callback)
                foundAny = true
            }
        }

        return foundAny
    }
}
