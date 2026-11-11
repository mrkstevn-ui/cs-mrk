package com.turk

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup

class SezonlukDizi : AdaptiveProvider() {
    override var mainUrl = "https://sezonlukdizi.cc"
    override var name = "SezonlukDizi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/diziler.asp?s=" to "Popüler Yabancı Diziler",
        "$mainUrl/diziler.asp?durum=3&s=" to "Devam Eden Diziler",
        "$mainUrl/diziler.asp?kat=1&s=" to "Mini Diziler",
        "$mainUrl/diziler.asp?tur=animasyon&s=" to "Animasyon Dizileri",
        "$mainUrl/diziler.asp?tur=bilimkurgu&s=" to "Bilim Kurgu Dizileri"
    )

    override suspend fun mainPageProvider(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}$page"
        val res = app.get(
            url,
            headers = mapOf(
                "User-Agent" to TURK_USER_AGENT,
                "Referer" to "$mainUrl/"
            )
        ).text
        val doc = Jsoup.parse(res, url).requireContent(name)
        val items = mutableListOf<SearchResponse>()

        for (a in doc.select("a.column[href^='/diziler/']")) {
            val href = fixUrl(a.attr("href"))
            val desc = a.selectFirst(".description")?.text()?.trim()
            val rawTitle = a.attr("title").replace(" izle", "").trim()
            val title = if (rawTitle.isNotEmpty()) rawTitle else (desc ?: "")
            if (title.isBlank()) continue
            val img = a.selectFirst("img")

            items.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                addPoster(img, mainUrl)
            })
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun searchProvider(query: String): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        try {
            val resp = app.post(
                "$mainUrl/ajax/arama.asp",
                data = mapOf("q" to query.trim()),
                headers = mapOf(
                    "User-Agent" to TURK_USER_AGENT,
                    "Referer" to "$mainUrl/",
                    "X-Requested-With" to "XMLHttpRequest"
                )
            )
            val res = try {
                String(resp.body.bytes(), java.nio.charset.Charset.forName("ISO-8859-9"))
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                reportStage("adapter", e)
                resp.text
            }

            val json = JSONObject(res)
            val results = json.optJSONObject("results") ?: return items
            val diziler = results.optJSONObject("diziler")?.optJSONArray("results") ?: return items

            for (i in 0 until diziler.length()) {
                val item = diziler.getJSONObject(i)
                val title = item.optString("title").replace(Regex("""\(\d+\)"""), "").trim()
                val url = fixUrl(item.optString("url"))
                if (!isSearchMatch(title, url, query)) continue
                val img = item.optString("image")

                items.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    addPoster(img, mainUrl)
                })
            }
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            reportStage("adapter", e)
            // Fallback: search via GET diziler.asp?q=
            try {
                val res = app.get(
                    "$mainUrl/diziler.asp?q=${encodeQuery(query)}",
                    headers = mapOf(
                        "User-Agent" to TURK_USER_AGENT,
                        "Referer" to "$mainUrl/"
                    )
                ).text
                val doc = Jsoup.parse(res, "$mainUrl/diziler.asp").requireContent(name).cleanNoise()
                for (a in doc.select("a.column[href^='/diziler/']")) {
                    val href = fixUrl(a.attr("href"))
                    val desc = a.selectFirst(".description")?.text()?.trim()
                    val rawTitle = a.attr("title").replace(" izle", "").trim()
                    val title = if (rawTitle.isNotEmpty()) rawTitle else (desc ?: "")
                    if (title.isBlank()) continue
                    if (!isSearchMatch(title, href, query)) continue
                    val img = a.selectFirst("img")

                    items.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        addPoster(img, mainUrl)
                    })
                }
            } catch (e2: Exception) {
                if (e2 is java.util.concurrent.CancellationException) throw e2
                reportStage("adapter", e2)
                // Ignore
            }
        }
        return items
    }

    override suspend fun loadProvider(url: String): LoadResponse {
        val res = app.get(
            url,
            headers = mapOf(
                "User-Agent" to TURK_USER_AGENT,
                "Referer" to "$mainUrl/"
            )
        ).text
        val doc = Jsoup.parse(res, url).requireContent(name)

        val title = doc.selectFirst("h1")?.text()?.replace(" izle", "")?.trim() ?: "Bilinmeyen Dizi"
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: cleanPosterUrl(doc.selectFirst("img[data-src*='/i/dizi'], .afis img, .ui.image img, img"), mainUrl)
        val plot = doc.selectFirst("#ozet, .ozet, .description p")?.text()?.trim()

        val slug = url.substringAfter("/diziler/").substringBefore(".html")
        val bolumlerUrl = "$mainUrl/bolumler/$slug.html"
        val episodes = mutableListOf<Episode>()

        try {
            val bolumlerRes = app.get(
                bolumlerUrl,
                headers = mapOf(
                    "User-Agent" to TURK_USER_AGENT,
                    "Referer" to url
                )
            ).text
            val bolumDoc = Jsoup.parse(bolumlerRes)

            for (tr in bolumDoc.select("tr")) {
                val epLink = tr.selectFirst("a[href*='-sezon-'][href*='-bolum.html']") ?: continue
                val epHref = fixUrl(epLink.attr("href"))
                val allLinks = tr.select("a")
                val epNameCandidate = allLinks.getOrNull(1)?.text()?.trim()
                val epName = if (!epNameCandidate.isNullOrBlank() && epNameCandidate != epLink.text().trim()) {
                    "${epLink.text().trim()} - $epNameCandidate"
                } else {
                    epLink.text().trim()
                }

                val match = Regex("""(\d+)-sezon-(\d+)-bolum""").find(epHref)
                val season = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val episode = match?.groupValues?.get(2)?.toIntOrNull() ?: 1

                episodes.add(
                    newEpisode(epHref) {
                        this.name = epName
                        this.season = season
                        this.episode = episode
                    }
                )
            }
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            reportStage("adapter", e)
            // Ignore
        }

        if (episodes.isEmpty()) {
            for (a in doc.select("a[href*='-sezon-'][href*='-bolum.html']")) {
                val epHref = fixUrl(a.attr("href"))
                val epName = a.text().trim()
                val match = Regex("""(\d+)-sezon-(\d+)-bolum""").find(epHref)
                val season = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val episode = match?.groupValues?.get(2)?.toIntOrNull() ?: 1

                episodes.add(
                    newEpisode(epHref) {
                        this.name = epName
                        this.season = season
                        this.episode = episode
                    }
                )
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
            addPoster(poster, mainUrl)
            this.plot = plot
        }.withMetadata(doc)
    }

    override suspend fun resolveLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false

        try {
            val res = app.get(
                data,
                headers = mapOf(
                    "User-Agent" to TURK_USER_AGENT,
                    "Referer" to "$mainUrl/"
                )
            ).text
            val bid = Regex("""bid=["'](\d+)["']""").find(res)?.groupValues?.get(1)
                ?: Regex("""data-id=["'](\d+)["']""").find(res)?.groupValues?.get(1)
                ?: return false

            // Try both subtitle (1) and dubbing (0)
            for (dil in listOf("1", "0")) {
                try {
                    val altRes = app.post(
                        "$mainUrl/ajax/dataAlternatif22.asp",
                        data = mapOf("bid" to bid, "dil" to dil),
                        headers = mapOf(
                            "User-Agent" to TURK_USER_AGENT,
                            "Referer" to data,
                            "X-Requested-With" to "XMLHttpRequest"
                        )
                    ).text

                    val altJson = JSONObject(altRes)
                    if (altJson.optString("status") != "success") continue
                    val dataArr = altJson.optJSONArray("data") ?: continue

                    for (i in 0 until dataArr.length()) {
                        val alt = dataArr.getJSONObject(i)
                        val altId = alt.optString("id")

                        try {
                            val embedRes = app.post(
                                "$mainUrl/ajax/dataEmbed22.asp",
                                data = mapOf("id" to altId),
                                headers = mapOf(
                                    "User-Agent" to TURK_USER_AGENT,
                                    "Referer" to data,
                                    "X-Requested-With" to "XMLHttpRequest"
                                )
                            ).text

                            val iframeSrc = Regex("""src=["']([^"']+)["']""").find(embedRes)?.groupValues?.get(1)

                            if (iframeSrc == null || iframeSrc.contains("reCAPTCHA") || iframeSrc.contains("engel.html")) {
                                continue
                            }

                            val fullIframeUrl = when {
                                iframeSrc.startsWith("//") -> "https:$iframeSrc"
                                iframeSrc.startsWith("/") -> "$mainUrl$iframeSrc"
                                else -> iframeSrc
                            }

                            if (fullIframeUrl.contains("byse")) {
                                try {
                                    ByseExtractor().getUrl(fullIframeUrl, data, subtitleCallback) { link ->
                                        callback.invoke(link)
                                        foundLinks = true
                                    }
                                } catch (e: Exception) {
                                    if (e is java.util.concurrent.CancellationException) throw e
                                    reportStage("adapter", e)
                                }
                            } else {
                                try {
                                    loadExtractor(fullIframeUrl, data, subtitleCallback) { link ->
                                        callback.invoke(link)
                                        foundLinks = true
                                    }
                                } catch (e: Exception) {
                                    if (e is java.util.concurrent.CancellationException) throw e
                                    reportStage("adapter", e)
                                }
                            }
                        } catch (e: Exception) {
                            if (e is java.util.concurrent.CancellationException) throw e
                            reportStage("adapter", e)
                            // Ignore single embed error
                        }
                    }
                } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException) throw e
                    reportStage("adapter", e)
                    // Ignore dil error
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
