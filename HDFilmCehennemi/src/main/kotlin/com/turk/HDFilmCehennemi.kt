package com.turk

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.json.JSONObject

class HDFilmCehennemi : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var name = "HDFilmcehennemi"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie)

    private val standardHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl, headers = standardHeaders).document
        val movies = doc.select("a[href^=\"https://www.hdfilmcehennemi.nl/\"]").filter {
            it.selectFirst("img[src*=\"poster\"]") != null
        }.mapNotNull { element ->
            val link = element.attr("href")
            val img = element.selectFirst("img")
            val title = img?.attr("alt") ?: return@mapNotNull null
            val poster = img.attr("src")

            newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }

        return newHomePageResponse("Son Eklenenler", movies)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?q=$query"
        val searchHeaders = standardHeaders + mapOf(
            "Content-Type" to "application/json",
            "X-Requested-With" to "fetch"
        )

        return try {
            val res = app.get(searchUrl, headers = searchHeaders).text
            val json = JSONObject(res)
            val resultsArray = json.optJSONArray("results") ?: return emptyList()

            val searchResponses = mutableListOf<SearchResponse>()
            for (i in 0 until resultsArray.length()) {
                val htmlSnippet = resultsArray.getString(i)
                val doc = Jsoup.parse(htmlSnippet)
                val link = doc.selectFirst("a")?.attr("href") ?: continue
                val title = doc.selectFirst("h4.title")?.text() 
                    ?: doc.selectFirst("img")?.attr("alt") ?: "Film"
                val poster = doc.selectFirst("img")?.attr("src")

                searchResponses.add(newMovieSearchResponse(title, link, TvType.Movie) {
                    this.posterUrl = poster
                })
            }
            searchResponses
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = standardHeaders).document
        val title = doc.selectFirst("h1")?.text()?.replace(" izle", "")?.trim() ?: "Film"
        val poster = doc.selectFirst("div.poster img, img.poster")?.attr("src")
        val plot = doc.selectFirst("article.post-content, div.film-summary, p.description")?.text()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
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
        val doc = app.get(data, headers = standardHeaders).document
        val videoButtons = doc.select("button[data-video], [data-video]")

        for (btn in videoButtons) {
            val videoId = btn.attr("data-video").trim()
            if (videoId.isEmpty() || videoId == "0") continue

            val apiVideoUrl = "$mainUrl/video/$videoId/"
            val videoHeaders = standardHeaders + mapOf(
                "Content-Type" to "application/json",
                "X-Requested-With" to "fetch",
                "Referer" to data
            )

            try {
                val responseText = app.get(apiVideoUrl, headers = videoHeaders).text
                val json = JSONObject(responseText)
                if (json.optBoolean("success")) {
                    val iframeHtml = json.getJSONObject("data").getString("html")
                    val iframeDoc = Jsoup.parse(iframeHtml)
                    val playerUrl = iframeDoc.selectFirst("iframe")?.attr("data-src")
                        ?: iframeDoc.selectFirst("iframe")?.attr("src")

                    if (!playerUrl.isNullOrEmpty()) {
                        loadExtractor(playerUrl, data, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return true
    }
}
