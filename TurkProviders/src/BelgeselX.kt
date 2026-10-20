package com.turk

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class BelgeselX : MainAPI() {
    override var mainUrl = "https://belgeselx.com"
    override var name = "BelgeselX"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Documentary, TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/son-eklenenler" to "Son Eklenen Belgeseller",
        "$mainUrl/en-cok-izlenen-belgeseller" to "En Çok İzlenenler",
        "$mainUrl/haftanin-trendleri" to "Haftanın Trendleri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val items = mutableListOf<SearchResponse>()

        document.select("a[href*='/belgesel/']").forEach { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@forEach
            if (href.endsWith("/belgesel/") || href.endsWith("/belgesel")) return@forEach
            val title = a.selectFirst("h2, h3, .title, .card-title")?.text()?.trim()
                ?: a.selectFirst("img")?.attr("alt")?.trim()
                ?: a.text().trim().takeIf { it.isNotBlank() } ?: return@forEach

            val poster = fixUrlNull(a.selectFirst("img")?.let { it.attr("src").ifBlank { it.attr("data-src") } })

            items.add(newTvSeriesSearchResponse(title, href, TvType.Documentary) {
                this.posterUrl = poster
            })
        }

        return newHomePageResponse(request.name, items.distinctBy { it.url })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val qLower = query.lowercase().trim()
        val pagesToScan = listOf(
            "$mainUrl/son-eklenenler",
            "$mainUrl/en-cok-izlenen-belgeseller",
            "$mainUrl/haftanin-trendleri"
        )
        val results = mutableListOf<SearchResponse>()

        for (pageUrl in pagesToScan) {
            try {
                val document = app.get(pageUrl).document
                document.select("a[href*='/belgesel/']").forEach { a ->
                    val href = fixUrlNull(a.attr("href")) ?: return@forEach
                    if (href.endsWith("/belgesel/") || href.endsWith("/belgesel")) return@forEach
                    val title = a.selectFirst("h2, h3, .title, .card-title")?.text()?.trim()
                        ?: a.selectFirst("img")?.attr("alt")?.trim()
                        ?: a.text().trim()

                    if (title.lowercase().contains(qLower) || href.lowercase().contains(qLower)) {
                        val poster = fixUrlNull(a.selectFirst("img")?.let { it.attr("src").ifBlank { it.attr("data-src") } })
                        results.add(newTvSeriesSearchResponse(title, href, TvType.Documentary) {
                            this.posterUrl = poster
                        })
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1, meta[property='og:title']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }?.replace("belgeselx.com", "", ignoreCase = true)?.replace("izle", "", ignoreCase = true)?.trim()
            ?: "Bilinmeyen Belgesel"

        val poster = fixUrlNull(
            document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst(".px-player-ambient")?.attr("style")?.let {
                    Regex("url\\(['\"]?([^'\")]+)['\"]?\\)").find(it)?.groupValues?.get(1)
                }
        )

        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst(".summary, .description, p")?.text()?.trim()

        val episodes = mutableListOf<Episode>()
        // Parse diziGetir('998','0','5','4','Samanyoluna Bir Bakış','27404','Mart 2015','1','1','329'...)
        val html = document.html()
        val regex = Regex("diziGetir\\s*\\(\\s*'(\\d+)'\\s*,\\s*'(\\d+)'\\s*,\\s*'(\\d+)'\\s*,\\s*'(\\d+)'\\s*,\\s*'([^']*)'\\s*,\\s*'[^']*'\\s*,\\s*'[^']*'\\s*,\\s*'(\\d+)'\\s*,\\s*'(\\d+)'")
        val matches = regex.findAll(html).toList()

        if (matches.isNotEmpty()) {
            matches.forEach { m ->
                val epId = m.groupValues[1]
                val ic1 = m.groupValues[2]
                val ic2 = m.groupValues[3]
                val ic3 = m.groupValues[4]
                val epName = m.groupValues[5].ifBlank { "Bölüm" }
                val seasonNum = m.groupValues[6].toIntOrNull() ?: 1
                val epNum = m.groupValues[7].toIntOrNull() ?: 1

                val epData = "$epId,$ic1,$ic2,$ic3,$url"
                episodes.add(newEpisode(epData) {
                    this.name = epName
                    this.season = seasonNum
                    this.episode = epNum
                })
            }
            return newTvSeriesLoadResponse(title, url, TvType.Documentary, episodes.distinctBy { it.data }) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        // Single documentary / movie
        val defaultEpData = "default,0,0,0,$url"
        return newMovieLoadResponse(title, url, TvType.Documentary, defaultEpData) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    private val srcMap = mapOf(
        "0" to "new5",
        "2" to "new1",
        "5" to "new4",
        "3" to "new2",
        "4" to "new3"
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split(",")
        if (parts.size < 5) return false
        val epId = parts[0]
        val icList = listOf(parts[1], parts[2], parts[3])
        val pageUrl = parts[4]
        var foundAny = false

        icList.forEachIndexed { idx, ic ->
            val sira = idx + 1
            val f = srcMap[ic] ?: "default"
            val videoDataUrl = "$mainUrl/video/data/$f.php?id=$epId&sira=$sira"
            try {
                val respHtml = app.get(videoDataUrl, referer = pageUrl).text
                val iframes = Regex("<iframe[^>]+src=[\"']([^\"']+)[\"']").findAll(respHtml).map { it.groupValues[1] }
                for (iframeSrc in iframes) {
                    val resolved = fixUrl(iframeSrc)
                    loadExtractor(resolved, videoDataUrl, subtitleCallback, callback)
                    foundAny = true
                }

                // Check for direct video links in script/html
                Regex("[\"'](https?://[^\"']+\\.mp4[^\"']*)[\"']").findAll(respHtml).forEach { mp4Match ->
                    val mp4Url = mp4Match.groupValues[1]
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name - Kaynak $sira [MP4]",
                            url = mp4Url,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.headers = mapOf("Referer" to pageUrl)
                        }
                    )
                    foundAny = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return foundAny
    }
}
