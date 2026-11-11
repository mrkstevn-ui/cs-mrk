package com.turk

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class WebteIzle : AdaptiveProvider() {
    override var mainUrl = "https://webteizle.info"
    override var name = "WebteIzle"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie)

    private val cfInterceptor = WebViewResolver(Regex(""".*webteizle.*"""))

    override val mainPage = mainPageOf(
        "$mainUrl/film-izle/" to "Güncel",
        "$mainUrl/yeni-filmler/" to "Yeni",
        "$mainUrl/tavsiye-filmler/" to "Tavsiye",
        "$mainUrl/filtre/SAYFA?tur=Aksiyon" to "Aksiyon",
        "$mainUrl/filtre/SAYFA?tur=Bilim-Kurgu" to "Bilim Kurgu",
        "$mainUrl/filtre/SAYFA?tur=Dram" to "Dram",
        "$mainUrl/filtre/SAYFA?tur=Komedi" to "Komedi",
        "$mainUrl/filtre/SAYFA?tur=Korku" to "Korku",
        "$mainUrl/filtre/SAYFA?tur=Gerilim" to "Gerilim",
        "$mainUrl/filtre/SAYFA?tur=Animasyon" to "Animasyon"
    )

    override suspend fun mainPageProvider(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if ("SAYFA" in request.data) {
            request.data.replace("SAYFA", "$page")
        } else {
            "${request.data}$page"
        }

        val document = app.get(
            url,
            interceptor = cfInterceptor,
            headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to TURK_USER_AGENT)
        ).document.requireContent(name)

        val items = document.select("div.golgever").mapNotNull { card ->
            toSearchResult(card)
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    private fun toSearchResult(card: Element): SearchResponse? {
        val title = card.selectFirst("div.filmname")?.text()?.trim()
            ?: card.selectFirst("a")?.attr("title")?.trim()
            ?: return null
        val link = card.selectFirst("a")?.attr("href") ?: return null
        val href = fixUrlNull(link) ?: return null
        val posterUrl = cleanPosterUrl(card.selectFirst("img"), mainUrl)

        return newMovieSearchResponse(title, href, TvType.Movie) {
            addPoster(posterUrl, mainUrl)
        }
    }

    override suspend fun searchProvider(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/filtre?a=${encodeQuery(query)}"
        val document = app.get(
            searchUrl,
            interceptor = cfInterceptor,
            headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to TURK_USER_AGENT)
        ).document.requireContent(name)

        return document.select("div.golgever").mapNotNull { card ->
            toSearchResult(card)
        }.filter { it.isMatch(query) }.distinctBy { it.url }
    }

    override suspend fun loadProvider(url: String): LoadResponse {
        val document = app.get(
            url,
            interceptor = cfInterceptor,
            headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to TURK_USER_AGENT)
        ).document.requireContent(name)

        val title = document.selectFirst("[property='og:title']")?.attr("content")?.substringBefore(" izle")?.trim()
            ?: document.selectFirst("h1")?.text()?.substringBefore(" izle")?.trim()
            ?: "Webteizle Film"

        val poster = cleanPosterUrl(document.selectFirst("div.card img"), mainUrl)
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")
        val year = document.selectXpath("//td[contains(text(), 'Vizyon')]/following-sibling::td").text().trim().split(" ").lastOrNull()?.toIntOrNull()
        val description = document.selectFirst("blockquote")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")
        val tags = document.selectXpath("//a[@itemgroup='genre']").map { it.text().trim() }
        val rating = document.selectFirst("div.detail")?.text()?.trim()?.replace(",", ".")?.toRatingInt()
        val duration = document.selectXpath("//td[contains(text(), 'Süre')]/following-sibling::td").text().trim().split(" ").firstOrNull()?.toIntOrNull()
        val trailer = document.selectFirst("button#fragman")?.attr("data-ytid")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            addPoster(poster, mainUrl)
            this.year = year
            this.plot = description
            this.tags = tags
            this.rating = rating
            this.duration = duration
            if (!trailer.isNullOrBlank()) {
                addTrailer("https://www.youtube.com/embed/$trailer")
            }
        }
    }

    data class EmbedData(
        @JsonProperty("id") val id: Int = 0,
        @JsonProperty("baslik") val baslik: String = "",
        @JsonProperty("kalitesi") val kalitesi: Int = 0
    )

    data class DataAlternatif(
        @JsonProperty("status") val status: String = "",
        @JsonProperty("data") val data: List<EmbedData> = emptyList()
    )

    override suspend fun resolveLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(
            data,
            interceptor = cfInterceptor,
            headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to TURK_USER_AGENT)
        ).document.requireContent(name)

        val filmId = document.selectFirst("button#wip")?.attr("data-id") ?: return false
        var foundAny = false

        val dilList = mutableListOf<String>()
        if (document.selectFirst("div.golge a[href*=dublaj], a[href*=dublaj]") != null) {
            dilList.add("0")
        }
        if (document.selectFirst("div.golge a[href*=altyazi], a[href*=altyazi]") != null) {
            dilList.add("1")
        }
        if (dilList.isEmpty()) {
            dilList.add("0")
            dilList.add("1")
        }

        for (dil in dilList) {
            val dilAd = if (dil == "0") "Dublaj" else "Altyazı"

            val playerJson = try {
                app.post(
                    "$mainUrl/ajax/dataAlternatif3.asp",
                    interceptor = cfInterceptor,
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to data,
                        "User-Agent" to TURK_USER_AGENT
                    ),
                    data = mapOf(
                        "filmid" to filmId,
                        "dil" to dil,
                        "s" to "",
                        "b" to "",
                        "bot" to "0"
                    )
                ).text
            } catch (_: Exception) {
                null
            } ?: continue

            val playerData = AppUtils.tryParseJson<DataAlternatif>(playerJson) ?: continue

            for (embed in playerData.data) {
                try {
                    val embedDoc = app.post(
                        "$mainUrl/ajax/dataEmbed.asp",
                        interceptor = cfInterceptor,
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to data,
                            "User-Agent" to TURK_USER_AGENT
                        ),
                        data = mapOf("id" to embed.id.toString())
                    ).document

                    var iframe = fixUrlNull(embedDoc.selectFirst("iframe")?.attr("src"))
                    if (iframe == null) {
                        val scriptSource = embedDoc.html()
                        val matchResult = Regex("""(vidmoly)\('([\d\w]+)'""").find(scriptSource)
                        if (matchResult != null) {
                            val vidId = matchResult.groupValues[2]
                            iframe = "https://vidmoly.to/embed-$vidId.html"
                        } else {
                            val dzenMatch = Regex("""var\s+vid\s*=\s*['"]([^'"]+)['"]""").find(scriptSource)
                            val videoId = dzenMatch?.groupValues?.get(1)
                            if (videoId != null) {
                                iframe = "https://dzen.ru/embed/$videoId"
                            }
                        }
                    }

                    if (!iframe.isNullOrBlank()) {
                        loadExtractor(iframe, "$mainUrl/", subtitleCallback) { link ->
                            foundAny = true
                            callback(link.copy(name = "$dilAd - ${link.name}"))
                        }
                    }
                } catch (_: Exception) {
                    // Continue to next embed
                }
            }
        }

        return foundAny
    }
}
