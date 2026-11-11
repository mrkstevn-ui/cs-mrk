package com.turk

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject

class Patronspor : LiveProvider() {
    override var mainUrl = "https://patronsaha1.cfd"
    override var name = "Patronspor"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Live)

    override var apiUrl = "https://patronsports2.cfd"

    override val mainPage = mainPageOf(
        "$mainUrl/matches" to "Günün Canlı Maçları",
        "$mainUrl/channels" to "Canlı TV & Spor Kanalları",
        "$mainUrl/sports" to "Özel Spor Kanalları",
        "$mainUrl/national" to "Ulusal Kanallar"
    )

    private suspend fun fetchChannels(): List<SearchResponse> {
        val list = mutableListOf<SearchResponse>()
        try {
            val jsonArray = liveArray(channelsApi)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val name = obj.optString("Mac")
                val logo = resolveHttpUrl(obj.optString("Logo"), "$apiUrl/")
                val rawUrl = obj.optString("URL")
                val targetUrl = resolveHttpUrl(rawUrl, "$mainUrl/") ?: continue

                list.add(
                    newLiveSearchResponse(name, targetUrl, TvType.Live) {
                        addPoster(logo, mainUrl)
                    }
                )
            }
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            reportStage("adapter", e)
            // Ignore
        }
        return list
    }

    private suspend fun fetchMatches(): List<SearchResponse> {
        val list = mutableListOf<SearchResponse>()
        try {
            val jsonArray = liveArray(matchesApi)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val home = obj.optString("HomeTeam")
                val away = obj.optString("AwayTeam")
                val time = obj.optString("Time")
                val league = obj.optString("league")
                val rawUrl = obj.optString("URL")
                val logo = resolveHttpUrl(obj.optString("HomeLogo"), "$apiUrl/")

                val title = if (away.isNotBlank()) "$time | $home - $away ($league)" else "$time | $home ($league)"
                val targetUrl = resolveHttpUrl(rawUrl, "$mainUrl/") ?: continue

                list.add(
                    newLiveSearchResponse(title, targetUrl, TvType.Live) {
                        addPoster(logo, mainUrl)
                    }
                )
            }
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            reportStage("adapter", e)
            // Ignore
        }
        return list
    }

    override suspend fun mainPageProvider(page: Int, request: MainPageRequest): HomePageResponse {
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

    override suspend fun searchProvider(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        val all = mutableListOf<SearchResponse>()
        all.addAll(fetchMatches())
        all.addAll(fetchChannels())
        return all.filter { it.name.lowercase().contains(q) }.distinctBy { it.url }
    }

    override suspend fun loadProvider(url: String): LoadResponse {
        val title = if (url.contains("id=")) {
            queryValue(url, "id").orEmpty()
        } else {
            "Canlı Yayın"
        }
        return newLiveStreamLoadResponse(title, url, url)
    }

}
