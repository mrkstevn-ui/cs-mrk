package com.turk

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.util.regex.Pattern

class Dizigom : MainAPI() {
    override var mainUrl = "https://www.dizigom.news"
    override var name = "Dizigom"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    private val standardHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "https://www.dizigom.news/"
    )

    override val mainPage = mainPageOf(
        "dizi-izle" to "Diziler (Son Eklenenler)",
        "dizi-izle?sirala=populer" to "Popüler Diziler",
        "film-izle" to "Filmler",
        "animes" to "Animeler"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val path = request.data
        val url = if (path.contains("?")) {
            if (page <= 1) "$mainUrl/$path" else "$mainUrl/$path&page=$page"
        } else {
            if (page <= 1) "$mainUrl/$path/" else "$mainUrl/$path/?page=$page"
        }

        val doc = app.get(url, headers = standardHeaders).document
        val items = doc.select("a.group.block, a.group[href*='/diziler/'], a.group[href*='/film/'], a.group[href*='/anime/']").mapNotNull {
            toSearchResult(it)
        }.distinctBy { it.url }

        return newHomePageResponse(
            listOf(HomePageList(request.name, items)),
            hasNext = items.isNotEmpty()
        )
    }

    private fun toSearchResult(element: org.jsoup.nodes.Element): SearchResponse? {
        val href = fixUrlNull(element.attr("href")) ?: return null
        val img = element.selectFirst("img")
        val rawTitle = element.selectFirst("h3")?.text()
            ?: img?.attr("alt")?.replace(" Dizi izle", "")?.replace(" Anime izle", "")?.replace(" izle", "")
            ?: return null
        val title = rawTitle.trim()

        val poster = img?.attr("src")?.takeIf { !it.startsWith("data:") }

        val type = when {
            href.contains("/film/") -> TvType.Movie
            href.contains("/anime/") || href.contains("/animes") -> TvType.Anime
            else -> TvType.TvSeries
        }

        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, type) {
                this.posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(title, href, type) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val searchUrl = "$mainUrl/ara?q=${query.trim().replace(" ", "+")}"
            val doc = app.get(searchUrl, headers = standardHeaders).document
            doc.select("a.group.block, a.group[href*='/diziler/'], a.group[href*='/film/'], a.group[href*='/anime/']").mapNotNull {
                toSearchResult(it)
            }.distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = standardHeaders).document

        val title = doc.selectFirst("h1")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" izle")?.trim()
            ?: return null

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("img[alt*='Dizi izle'], img[alt*='izle']")?.attr("src")

        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")

        val tags = doc.select("a[href*='/tur/']").map { it.text().trim() }
        val year = Regex("""(\d{4})""").find(doc.selectFirst("p.text-content-muted")?.text() ?: "")?.groupValues?.get(1)?.toIntOrNull()

        val isMovie = url.contains("/film/")

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }

        // Dizi ve Anime Bölüm Ayrıştırma
        val type = if (url.contains("/anime/")) TvType.Anime else TvType.TvSeries
        val allEpisodes = mutableListOf<Episode>()

        // 1. Sezonu mevcut belgeden çek
        allEpisodes.addAll(parseEpisodesFromDoc(doc, 1))

        // Varsa diğer sezon linklerini çek
        val seasonLinks = doc.select("a[href*='?sezon=']").mapNotNull { fixUrlNull(it.attr("href")) }.distinct()
        for (sLink in seasonLinks) {
            val sNum = Regex("""sezon=(\d+)""").find(sLink)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (sNum == 1) continue // 1. Sezon zaten çekildi
            try {
                val sDoc = app.get(sLink, headers = standardHeaders).document
                allEpisodes.addAll(parseEpisodesFromDoc(sDoc, sNum))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val sortedEpisodes = allEpisodes.distinctBy { it.data }.sortedWith(
            compareBy({ it.season }, { it.episode })
        )

        return newTvSeriesLoadResponse(title, url, type, sortedEpisodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    private fun parseEpisodesFromDoc(doc: Document, defaultSeason: Int): List<Episode> {
        val list = mutableListOf<Episode>()
        val elements = doc.select("a[href*='-sezon-'][href*='-bolum']")

        for (el in elements) {
            val href = fixUrlNull(el.attr("href")) ?: continue
            val thumb = el.selectFirst("img")?.attr("src")
            val epName = el.selectFirst("p.truncate")?.text()?.trim() ?: ""
            val seasonText = el.selectFirst("p.text-xs")?.text() ?: ""

            val sNum = Regex("""(\d+)\.\s*Sezon""").find(seasonText)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)-sezon""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                ?: defaultSeason

            val eNum = Regex("""(\d+)\.\s*Bölüm""").find(seasonText)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)-bolum""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                ?: 1

            list.add(newEpisode(href) {
                this.name = if (epName.isNotEmpty() && !epName.contains("Sezon")) epName else "$sNum. Sezon $eNum. Bölüm"
                this.season = sNum
                this.episode = eNum
                this.posterUrl = thumb
            })
        }

        return list
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val pageHtml = app.get(data, headers = standardHeaders).text

            // 1. data-pv="SLUG" kontrolü (Pilavyer Player)
            val pvPattern = Pattern.compile("""data-pv="([^"]+)"""")
            val pvMatcher = pvPattern.matcher(pageHtml)
            if (!pvMatcher.find()) return false
            val slug = pvMatcher.group(1)

            // 2. Script hostu bul (örn: play2.pilavyerplay.top)
            val hostPattern = Pattern.compile("""src="https://([^/]+)/(?:assets/js/core|e/c)\.js"""")
            val hostMatcher = hostPattern.matcher(pageHtml)
            val host = if (hostMatcher.find()) hostMatcher.group(1) else "play2.pilavyerplay.top"

            val embedUrl = "https://$host/assets/js/s.php?s=$slug"

            val embedHtml = app.get(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Referer" to "$mainUrl/"
                )
            ).text

            // 3. window.__PLAYER__ nesnesini çöz
            val playerPattern = Pattern.compile("""window\.__PLAYER__\s*=\s*(\{.+?\});""", Pattern.DOTALL)
            val playerMatcher = playerPattern.matcher(embedHtml)
            if (!playerMatcher.find()) return false

            val json = JSONObject(playerMatcher.group(1))
            val streamUrl = json.optString("stream")

            if (streamUrl.isNotEmpty()) {
                val streamHeaders = mapOf(
                    "Referer" to "https://$host/",
                    "Origin" to "https://$host",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )

                // ExoPlayer 3003 hatasını önlemek için HLS uzantısı eklenir
                val hlsUrl = if (streamUrl.contains("?")) "$streamUrl&ext=.m3u8" else "$streamUrl?ext=.m3u8"

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name (1080p Çift Dil)",
                        url = hlsUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://$host/"
                        this.quality = Qualities.P1080.value
                        this.headers = streamHeaders
                    }
                )
            }

            // 4. Altyazıları yükle
            val subsArray = json.optJSONArray("subs")
            if (subsArray != null) {
                for (i in 0 until subsArray.length()) {
                    val subObj = subsArray.getJSONObject(i)
                    val subUrl = subObj.optString("src")
                    val subLabel = subObj.optString("label").ifEmpty { "Altyazı" }
                    if (subUrl.isNotEmpty()) {
                        subtitleCallback.invoke(SubtitleFile(subLabel, subUrl))
                    }
                }
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
