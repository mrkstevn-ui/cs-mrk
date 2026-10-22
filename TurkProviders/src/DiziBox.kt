package com.turk

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class DiziBox : MainAPI() {
    override var mainUrl = "https://www.dizibox.live"
    override var name = "DiziBOX"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Eklenen Bölümler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val items = mutableListOf<SearchResponse>()

        // Episode links on homepage
        document.select("article, .post, .episode-item, a[href*='-sezon-']").forEach { el ->
            val href = fixUrlNull(if (el.tagName() == "a") el.attr("href") else el.selectFirst("a[href*='-sezon-']")?.attr("href")) ?: return@forEach
            val title = el.selectFirst("h2, h3, .title, .entry-title")?.text()?.trim()
                ?: el.text().trim().takeIf { it.isNotBlank() } ?: return@forEach
            val img = el.selectFirst("img")

            items.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                addPoster(img, mainUrl)
            })
        }

        return newHomePageResponse(request.name, items.distinctBy { it.url })
    }

    data class DwlsResult(
        @JsonProperty("ID") val id: Int? = null,
        @JsonProperty("post_title") val postTitle: String? = null,
        @JsonProperty("permalink") val permalink: String? = null,
        @JsonProperty("image") val image: String? = null
    )

    data class DwlsResponse(
        @JsonProperty("results") val results: List<DwlsResult>? = null
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php?action=dwls_search&query=${query.replace(" ", "+")}"
        return try {
            val resp = app.get(
                ajaxUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to "$mainUrl/"
                )
            ).text
            val data = AppUtils.parseJson<DwlsResponse>(resp)
            data.results?.mapNotNull { r ->
                val link = r.permalink ?: return@mapNotNull null
                val title = r.postTitle ?: "Dizi"
                newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                    addPoster(r.image, mainUrl)
                }
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // If the URL is an episode, check if there is a parent series link
        val parentSeriesUrl = document.selectFirst("a[href*='/diziler/']")?.attr("href")
        val isEpisodePage = url.contains("-sezon-") && !url.contains("/diziler/")

        if (isEpisodePage && !parentSeriesUrl.isNullOrBlank()) {
            val showDoc = app.get(fixUrl(parentSeriesUrl)).document
            return parseShowPage(showDoc, fixUrl(parentSeriesUrl))
        }

        return parseShowPage(document, url)
    }

    private suspend fun parseShowPage(document: org.jsoup.nodes.Document, url: String): LoadResponse {
        val title = document.selectFirst("h1, .entry-title")?.text()?.replace("izle", "", ignoreCase = true)?.trim()
            ?: "Bilinmeyen Dizi"

        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: cleanPosterUrl(document.selectFirst(".poster img, .cover img, img"), mainUrl)

        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst(".entry-content p, .story, .summary")?.text()?.trim()

        val episodes = mutableListOf<Episode>()
        document.select("a[href*='-sezon-']").forEach { ep ->
            val epHref = fixUrlNull(ep.attr("href")) ?: return@forEach
            val epText = ep.text().trim()
            val seasonNum = Regex("(\\d+)\\s*\\.?\\s*Sezon", RegexOption.IGNORE_CASE).find(epText)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("(\\d+)-sezon", RegexOption.IGNORE_CASE).find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val epNum = Regex("(\\d+)\\s*\\.?\\s*Bölüm", RegexOption.IGNORE_CASE).find(epText)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("(\\d+)-bolum", RegexOption.IGNORE_CASE).find(epHref)?.groupValues?.get(1)?.toIntOrNull()

            episodes.add(newEpisode(epHref) {
                this.name = if (epText.isNotBlank()) epText else "Bölüm $epNum"
                this.season = seasonNum
                this.episode = epNum
            })
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
            addPoster(poster, mainUrl)
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var foundAny = false

        // Extract king.php player
        val kingIframe = document.selectFirst("iframe[src*='king.php']")?.attr("src")
        if (!kingIframe.isNullOrBlank()) {
            val resolvedKing = fixUrl(kingIframe)
            try {
                val kingDoc = app.get(
                    resolvedKing,
                    headers = mapOf(
                        "Referer" to data,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    )
                ).document

                val molySrc = kingDoc.selectFirst("iframe[src*='molystream.org/embed/']")?.attr("src")
                if (!molySrc.isNullOrBlank()) {
                    val resolvedMoly = fixUrl(molySrc)
                    val embedId = Regex("embed/([a-zA-Z0-9\\-]+)").find(resolvedMoly)?.groupValues?.get(1)
                    if (embedId != null) {
                        val sheilaUrl = "https://dbx.molystream.org/embed/sheila/$embedId"
                        callback.invoke(
                            newExtractorLink(
                                source = "DiziBOX - MolyStream",
                                name = "DiziBOX [1080p]",
                                url = sheilaUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.headers = mapOf(
                                    "Referer" to resolvedMoly,
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                )
                            }
                        )
                        foundAny = true
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Fallback: extract other iframes (e.g. vidmoly etc.)
        document.select("iframe").forEach { iframe ->
            val src = fixUrlNull(iframe.attr("src")) ?: return@forEach
            if (!src.contains("king.php") && !src.contains("devtools")) {
                loadExtractor(src, data, subtitleCallback, callback)
                foundAny = true
            }
        }

        return foundAny
    }
}
