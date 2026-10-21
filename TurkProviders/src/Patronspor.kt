package com.turk

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject

class Patronspor : MainAPI() {
    override var mainUrl = "https://patronspor.is"
    override var name = "Patronspor"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Live)

    private val matchesApi = "https://patronsports2.cfd/matches.php"
    private val channelsApi = "https://patronsports2.cfd/channels.php"
    private val domainApi = "https://patronsports2.cfd/domain.php"
    private var cachedBaseUrl = "https://2i4.d72577a9dd0ec71.cfd/"

    override val mainPage = mainPageOf(
        "$mainUrl/matches" to "Günün Canlı Maçları",
        "$mainUrl/channels" to "Canlı TV & Spor Kanalları",
        "$mainUrl/sports" to "Özel Spor Kanalları",
        "$mainUrl/national" to "Ulusal Kanallar"
    )

    private suspend fun fetchChannels(): List<SearchResponse> {
        val list = mutableListOf<SearchResponse>()
        try {
            val res = app.get(channelsApi, headers = mapOf("Referer" to "$mainUrl/")).text
            val jsonArray = JSONArray(res)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val name = obj.optString("Mac")
                val logo = obj.optString("Logo").let {
                    if (it.startsWith("http")) it else if (it.isNotBlank()) "https://patronsports2.cfd$it" else null
                }
                val rawUrl = obj.optString("URL")
                val targetUrl = if (rawUrl.startsWith("http")) rawUrl else "$mainUrl$rawUrl"

                list.add(
                    newLiveSearchResponse(name, targetUrl, TvType.Live) {
                        this.posterUrl = logo
                    }
                )
            }
        } catch (e: Exception) {
            // Ignore
        }
        return list
    }

    private suspend fun fetchMatches(): List<SearchResponse> {
        val list = mutableListOf<SearchResponse>()
        try {
            val res = app.get(matchesApi, headers = mapOf("Referer" to "$mainUrl/")).text
            val jsonArray = JSONArray(res)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val home = obj.optString("HomeTeam")
                val away = obj.optString("AwayTeam")
                val time = obj.optString("Time")
                val league = obj.optString("league")
                val rawUrl = obj.optString("URL")
                val logo = obj.optString("HomeLogo").takeIf { it.isNotBlank() }

                val title = if (away.isNotBlank()) "$time | $home - $away ($league)" else "$time | $home ($league)"
                val targetUrl = if (rawUrl.startsWith("http")) rawUrl else "$mainUrl$rawUrl"

                list.add(
                    newLiveSearchResponse(title, targetUrl, TvType.Live) {
                        this.posterUrl = logo
                    }
                )
            }
        } catch (e: Exception) {
            // Ignore
        }
        return list
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = when (request.data) {
            "$mainUrl/matches" -> fetchMatches()
            "$mainUrl/sports" -> fetchChannels().filter {
                val n = it.name.lowercase()
                !n.contains("trt 1") && !n.contains("atv") && !n.contains("tv 8")
            }
            "$mainUrl/national" -> fetchChannels().filter {
                val n = it.name.lowercase()
                n.contains("trt 1") || n.contains("atv") || n.contains("tv 8") || n.contains("a spor") || n.contains("trt spor")
            }
            else -> fetchChannels()
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        val all = mutableListOf<SearchResponse>()
        all.addAll(fetchMatches())
        all.addAll(fetchChannels())
        return all.filter { it.name.lowercase().contains(q) }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val title = if (url.contains("id=")) {
            url.substringAfter("id=")
        } else {
            "Canlı Yayın"
        }
        return newLiveStreamLoadResponse(title, url, url)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false

        try {
            val targetParam = data.substringAfter("id=", "")
            if (targetParam.startsWith("http://") || targetParam.startsWith("https://")) {
                callback.invoke(
                    ExtractorLink(
                        name = this.name,
                        source = this.name,
                        url = targetParam,
                        referer = "$mainUrl/",
                        quality = Qualities.P1080.value,
                        type = ExtractorLinkType.M3U8
                    )
                )
                return true
            }

            // Get dynamic active base URL from domainApi
            try {
                val domainRes = app.get(domainApi, headers = mapOf("Referer" to "$mainUrl/")).text
                val base = JSONObject(domainRes).optString("baseurl")
                if (base.isNotBlank()) {
                    cachedBaseUrl = base
                }
            } catch (e: Exception) {
                // Fallback to cached
            }

            val channelId = targetParam.ifEmpty { "patron" }
            val streamUrl = "${cachedBaseUrl.trimEnd('/')}/$channelId/mono.m3u8"

            callback.invoke(
                ExtractorLink(
                    name = this.name,
                    source = this.name,
                    url = streamUrl,
                    referer = "$mainUrl/",
                    quality = Qualities.P1080.value,
                    type = ExtractorLinkType.M3U8
                )
            )
            foundLinks = true
        } catch (e: Exception) {
            // Ignore
        }

        return foundLinks
    }
}
