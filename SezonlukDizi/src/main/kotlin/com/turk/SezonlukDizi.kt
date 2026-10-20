package com.turk

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup

class SezonlukDizi : MainAPI() {
    override var mainUrl = "https://sezonlukdizi.cc"
    override var name = "SezonlukDizi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/diziler.asp?sayfa=" to "Popüler Yabancı Diziler",
        "$mainUrl/diziler.asp?durum=3&sayfa=" to "Devam Eden Diziler",
        "$mainUrl/diziler.asp?kat=1&sayfa=" to "Mini Diziler",
        "$mainUrl/diziler.asp?tur=animasyon&sayfa=" to "Animasyon Dizileri",
        "$mainUrl/diziler.asp?tur=bilimkurgu&sayfa=" to "Bilim Kurgu Dizileri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}$page"
        val res = app.get(url, headers = mapOf("Referer" to "$mainUrl/")).text
        val doc = Jsoup.parse(res)
        val items = mutableListOf<SearchResponse>()

        for (a in doc.select("a.column[href^='/diziler/']")) {
            val href = fixUrl(a.attr("href"))
            val desc = a.selectFirst(".description")?.text()?.trim()
            val rawTitle = a.attr("title").replace(" izle", "").trim()
            val title = if (rawTitle.isNotEmpty()) rawTitle else (desc ?: "")
            if (title.isBlank()) continue
            val img = a.selectFirst("img")
            val posterRaw = img?.attr("data-src")?.ifEmpty { img.attr("src") }
            val poster = if (!posterRaw.isNullOrBlank() && !posterRaw.startsWith("data:")) fixUrl(posterRaw) else null

            items.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            })
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        try {
            val res = app.post(
                "$mainUrl/ajax/arama.asp",
                data = mapOf("q" to query.trim()),
                headers = mapOf(
                    "Referer" to "$mainUrl/",
                    "X-Requested-With" to "XMLHttpRequest"
                )
            ).text

            val json = JSONObject(res)
            val results = json.optJSONObject("results") ?: return items
            val diziler = results.optJSONObject("diziler")?.optJSONArray("results") ?: return items

            for (i in 0 until diziler.length()) {
                val item = diziler.getJSONObject(i)
                val title = item.optString("title").replace(Regex("""\(\d+\)"""), "").trim()
                val url = fixUrl(item.optString("url"))
                val img = item.optString("image")
                val poster = if (img.isNotBlank()) fixUrl(img) else null

                items.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = poster
                })
            }
        } catch (e: Exception) {
            // Fallback to text search on main page
        }
        return items
    }

    override suspend fun load(url: String): LoadResponse {
        val res = app.get(url, headers = mapOf("Referer" to "$mainUrl/")).text
        val doc = Jsoup.parse(res)

        val title = doc.selectFirst("h1")?.text()?.replace(" izle", "")?.trim() ?: "Bilinmeyen Dizi"
        val poster = doc.selectFirst(".afis img, .ui.image img")?.let {
            val src = it.attr("data-src").ifEmpty { it.attr("src") }
            if (src.startsWith("data:")) null else fixUrl(src)
        }
        val plot = doc.selectFirst("#ozet, .ozet, .description p")?.text()?.trim()

        val slug = url.substringAfter("/diziler/").substringBefore(".html")
        val bolumlerUrl = "$mainUrl/bolumler/$slug.html"
        val episodes = mutableListOf<Episode>()

        try {
            val bolumlerRes = app.get(bolumlerUrl, headers = mapOf("Referer" to url)).text
            val bolumDoc = Jsoup.parse(bolumlerRes)

            for (tr in bolumDoc.select("tr")) {
                val epLink = tr.selectFirst("a[href*='-sezon-'][href*='-bolum.html']") ?: continue
                val epHref = fixUrl(epLink.attr("href"))
                val epName = tr.select("a").getOrNull(1)?.text()?.trim() ?: epLink.text().trim()

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
            // Ignore
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
            this.posterUrl = poster
            this.plot = plot
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
            val res = app.get(data, headers = mapOf("Referer" to "$mainUrl/")).text
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
                                    "Referer" to data,
                                    "X-Requested-With" to "XMLHttpRequest"
                                )
                            ).text

                            val iframeSrc = Regex("""src=["']([^"']+)["']""").find(embedRes)?.groupValues?.get(1) ?: continue
                            if (iframeSrc.contains("reCAPTCHA") || iframeSrc.contains("engel.html")) continue

                            val fullIframeUrl = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else if (iframeSrc.startsWith("/")) "$mainUrl$iframeSrc" else iframeSrc

                            loadExtractor(fullIframeUrl, data, subtitleCallback) { link ->
                                callback.invoke(link)
                                foundLinks = true
                            }
                        } catch (e: Exception) {
                            // Ignore single embed error
                        }
                    }
                } catch (e: Exception) {
                    // Ignore dil error
                }
            }
        } catch (e: Exception) {
            // Ignore
        }

        return foundLinks
    }
}
