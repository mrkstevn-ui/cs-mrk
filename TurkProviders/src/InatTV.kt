package com.turk

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject

class InatTV : LiveProvider() {
    override var mainUrl = "https://patronkale2.cfd"
    override var name = "İnatTV"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Live)

    override var apiUrl = "https://patronsports2.cfd"

    data class ChannelDef(
        val id: String,
        val name: String,
        val category: String,
        val logo: String? = null
    )

    private val channelList = listOf(
        // Yurtdışı & Spor
        ChannelDef("eu1", "Euro Sport 1 HD", "Yurtdışı Spor"),
        ChannelDef("eu2", "Euro Sport 2 HD", "Yurtdışı Spor"),
        ChannelDef("nbatv", "NBA TV HD", "Yurtdışı Spor"),
        ChannelDef("patron", "BeIN Sports 1 HD", "Spor"),
        ChannelDef("b2", "BeIN Sports 2 HD", "Spor"),
        ChannelDef("b3", "BeIN Sports 3 HD", "Spor"),
        ChannelDef("b4", "BeIN Sports 4 HD", "Spor"),
        ChannelDef("b5", "BeIN Sports 5 HD", "Spor"),
        ChannelDef("bm1", "BeIN Max 1 HD", "Spor"),
        ChannelDef("bm2", "BeIN Max 2 HD", "Spor"),
        ChannelDef("ss", "S Sport 1 HD", "Spor"),
        ChannelDef("ss2", "S Sport 2 HD", "Spor"),
        ChannelDef("t1", "Tivibu Spor 1 HD", "Spor"),
        ChannelDef("t2", "Tivibu Spor 2 HD", "Spor"),
        ChannelDef("t3", "Tivibu Spor 3 HD", "Spor"),
        ChannelDef("t4", "Tivibu Spor 4 HD", "Spor"),
        ChannelDef("smarts", "Smart Spor 1 HD", "Spor"),
        ChannelDef("sms2", "Smart Spor 2 HD", "Spor"),

        // Ulusal & Açık Kanallar
        ChannelDef("trt1", "TRT 1 HD", "Ulusal"),
        ChannelDef("trtspor", "TRT Spor HD", "Ulusal"),
        ChannelDef("trtspor2", "TRT Spor Yıldız HD", "Ulusal"),
        ChannelDef("as", "A Spor HD", "Ulusal"),
        ChannelDef("atv", "ATV HD", "Ulusal"),
        ChannelDef("tv8", "TV 8 HD", "Ulusal"),
        ChannelDef("tv85", "TV 8,5 HD", "Ulusal")
    )

    override val mainPage = mainPageOf(
        "$mainUrl/matches" to "Günün Canlı Maçları",
        "$mainUrl/all" to "7/24 Tüm Kanallar",
        "$mainUrl/foreign" to "Yurtdışı & Spor Kanalları",
        "$mainUrl/national" to "Ulusal Kanallar"
    )

    override suspend fun mainPageProvider(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<SearchResponse>()

        when (request.data) {
            "$mainUrl/matches" -> {
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

                        items.add(
                            newLiveSearchResponse(title, targetUrl, TvType.Live) {
                                addPoster(logo, mainUrl)
                            }
                        )
                    }
                } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException) throw e
                    reportStage("adapter", e)
                    // Ignore matches error
                }
            }
            "$mainUrl/foreign" -> {
                channelList.filter { it.category == "Yurtdışı Spor" || it.category == "Spor" }.forEach { ch ->
                    items.add(
                        newLiveSearchResponse(ch.name, "$mainUrl/channel.html?id=${ch.id}", TvType.Live)
                    )
                }
            }
            "$mainUrl/national" -> {
                channelList.filter { it.category == "Ulusal" }.forEach { ch ->
                    items.add(
                        newLiveSearchResponse(ch.name, "$mainUrl/channel.html?id=${ch.id}", TvType.Live)
                    )
                }
            }
            else -> {
                channelList.forEach { ch ->
                    items.add(
                        newLiveSearchResponse(ch.name, "$mainUrl/channel.html?id=${ch.id}", TvType.Live)
                    )
                }
            }
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun searchProvider(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        val results = mutableListOf<SearchResponse>()

        // Search channels
        channelList.filter { it.name.lowercase().contains(q) || it.category.lowercase().contains(q) }.forEach { ch ->
            results.add(
                newLiveSearchResponse(ch.name, "$mainUrl/channel.html?id=${ch.id}", TvType.Live)
            )
        }

        // Search live matches
        try {
            val jsonArray = liveArray(matchesApi)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val home = obj.optString("HomeTeam")
                val away = obj.optString("AwayTeam")
                val league = obj.optString("league")
                val matchStr = "$home $away $league".lowercase()

                if (matchStr.contains(q)) {
                    val time = obj.optString("Time")
                    val rawUrl = obj.optString("URL")
                    val logo = resolveHttpUrl(obj.optString("HomeLogo"), "$apiUrl/")
                    val title = "$time | $home - $away ($league)"
                    val targetUrl = resolveHttpUrl(rawUrl, "$mainUrl/") ?: continue

                    results.add(
                        newLiveSearchResponse(title, targetUrl, TvType.Live) {
                            addPoster(logo, mainUrl)
                        }
                    )
                }
            }
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            reportStage("adapter", e)
            // Ignore
        }

        return results
    }

    override suspend fun loadProvider(url: String): LoadResponse {
        val channelId = queryValue(url, "id").orEmpty()
        val matchedChannel = channelList.firstOrNull { it.id == channelId }
        val title = matchedChannel?.name ?: "Canlı Yayın"

        return newLiveStreamLoadResponse(title, url, url)
    }

}
