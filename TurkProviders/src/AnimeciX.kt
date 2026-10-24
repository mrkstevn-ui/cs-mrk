package com.turk

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class AnimeciX : MainAPI() {
    override var mainUrl = "https://animecix.com"
    override var name = "AnimeciX"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val mainPage = mainPageOf(
        "$mainUrl/secure/last-episodes?page=" to "Son Eklenen Bölümler",
        "$mainUrl/secure/homepage/lists-guests" to "Öne Çıkanlar"
    )

    data class LastEpisodeVideo(
        @JsonProperty("url") val url: String? = null
    )

    data class LastEpisodeItem(
        @JsonProperty("title_id") val titleId: Int? = null,
        @JsonProperty("title_name") val titleName: String? = null,
        @JsonProperty("title_poster") val titlePoster: String? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null,
        @JsonProperty("episode_number") val episodeNumber: Int? = null
    )

    data class LastEpisodesResponse(
        @JsonProperty("data") val data: List<LastEpisodeItem>? = null
    )

    data class GuestListItem(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster") val poster: String? = null
    )

    data class GuestList(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("items") val items: List<GuestListItem>? = null
    )

    data class GuestListsResponse(
        @JsonProperty("lists") val lists: List<GuestList>? = null
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<SearchResponse>()
        try {
            if (request.data.contains("last-episodes")) {
                val url = "${request.data}$page"
                val json = app.get(url, headers = mapOf("Referer" to "$mainUrl/")).text
                val parsed = AppUtils.parseJson<LastEpisodesResponse>(json)
                parsed.data?.forEach { ep ->
                    val id = ep.titleId ?: return@forEach
                    val title = ep.titleName ?: "Anime"
                    val poster = ep.titlePoster
                    items.add(newAnimeSearchResponse(title, "$mainUrl/titles/$id", TvType.Anime) {
                        addPoster(poster, mainUrl)
                    })
                }
            } else {
                val json = app.get(request.data, headers = mapOf("Referer" to "$mainUrl/")).text
                val parsed = AppUtils.parseJson<GuestListsResponse>(json)
                parsed.lists?.firstOrNull()?.items?.forEach { itm ->
                    val id = itm.id ?: return@forEach
                    val title = itm.name ?: "Anime"
                    items.add(newAnimeSearchResponse(title, "$mainUrl/titles/$id", TvType.Anime) {
                        addPoster(itm.poster, mainUrl)
                    })
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return newHomePageResponse(request.name, items.distinctBy { it.url })
    }

    data class SearchItem(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("title_type") val titleType: String? = null
    )

    data class SearchResponseData(
        @JsonProperty("results") val results: List<SearchItem>? = null
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/secure/search/${query.trim()}"
        return try {
            val json = app.get(searchUrl, headers = mapOf("Referer" to "$mainUrl/")).text
            val data = AppUtils.parseJson<SearchResponseData>(json)
            data.results?.mapNotNull { r ->
                val id = r.id ?: return@mapNotNull null
                val title = r.name ?: "Anime"
                newAnimeSearchResponse(title, "$mainUrl/titles/$id", TvType.Anime) {
                    addPoster(r.poster, mainUrl)
                }
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    data class BatchEpisode(
        @JsonProperty("episodeId") val episodeId: String? = null,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster") val poster: String? = null
    )

    data class BatchSeason(
        @JsonProperty("number") val number: Int? = null,
        @JsonProperty("episodes") val episodes: List<BatchEpisode>? = null
    )

    data class BatchTitle(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("year") val year: Int? = null
    )

    data class BatchEpisodesResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("title") val title: BatchTitle? = null,
        @JsonProperty("seasons") val seasons: List<BatchSeason>? = null
    )

    override suspend fun load(url: String): LoadResponse {
        val titleId = Regex("titles/(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw ErrorLoadingException("Geçersiz Anime ID")

        val episodesApi = "$mainUrl/secure/batch-download/$titleId/episodes"
        val json = app.get(episodesApi, headers = mapOf("Referer" to "$mainUrl/")).text
        val parsed = AppUtils.parseJson<BatchEpisodesResponse>(json)

        val title = parsed.title?.name ?: "Anime"
        val poster = parsed.title?.poster
        val plot = parsed.title?.description
        val year = parsed.title?.year

        val episodeList = mutableListOf<Episode>()
        parsed.seasons?.forEach { s ->
            val seasonNum = s.number ?: 1
            s.episodes?.forEach { ep ->
                val epNum = ep.episode
                val epName = ep.name ?: "$seasonNum. Sezon $epNum. Bölüm"
                val epData = "$titleId/$seasonNum/$epNum"
                episodeList.add(newEpisode(epData) {
                    this.name = epName
                    this.season = seasonNum
                    this.episode = epNum
                    this.posterUrl = ep.poster
                })
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            addPoster(poster, mainUrl)
            this.plot = plot
            this.year = year
            addEpisodes(DubStatus.Subbed, episodeList)
        }
    }

    data class VideoStreamUrl(
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("size") val size: Long? = null
    )

    data class VideoItem(
        @JsonProperty("episodeId") val episodeId: String? = null,
        @JsonProperty("translator") val translator: String? = null,
        @JsonProperty("urls") val urls: List<VideoStreamUrl>? = null,
        @JsonProperty("embedUrl") val embedUrl: String? = null
    )

    data class BatchVideosResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("data") val data: List<VideoItem>? = null
    )

    data class TauVideoResponse(
        @JsonProperty("urls") val urls: List<VideoStreamUrl>? = null
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data format: "$titleId/$seasonNum/$epNum"
        val parts = data.split("/")
        if (parts.size < 3) return false
        val titleId = parts[0]
        val season = parts[1].toIntOrNull() ?: 1
        val episode = parts[2].toIntOrNull() ?: 1
        var foundAny = false

        try {
            // 1. Get XSRF token
            val bootstrapResp = app.get(
                "$mainUrl/secure/bootstrap-data?original_url=https%3A%2F%2Fanimecix.tv",
                headers = mapOf("Referer" to "$mainUrl/")
            )
            val cookies = bootstrapResp.cookies
            val xsrfToken = cookies["XSRF-TOKEN"] ?: ""

            // 2. Request videos with CSRF token
            val payload = mapOf(
                "episodes" to listOf(
                    mapOf("season" to season, "episode" to episode)
                )
            )
            val postResp = app.post(
                "$mainUrl/secure/batch-download/$titleId/videos",
                headers = mapOf(
                    "Referer" to "$mainUrl/",
                    "Content-Type" to "application/json",
                    "Accept" to "application/json, text/plain, */*",
                    "X-XSRF-TOKEN" to xsrfToken
                ),
                cookies = cookies,
                json = payload
            ).text

            val videoData = AppUtils.parseJson<BatchVideosResponse>(postResp)
            videoData.data?.firstOrNull()?.let { vItem ->
                // Direct MP4 URLs
                vItem.urls?.forEach { u ->
                    val streamUrl = u.url ?: return@forEach
                    val quality = u.label?.filter { it.isDigit() }?.toIntOrNull() ?: Qualities.P1080.value
                    callback.invoke(
                        newExtractorLink(
                            source = "AnimeciX",
                            name = "AnimeciX [${u.label ?: "1080p"}] (${vItem.translator ?: "Tau"})",
                            url = streamUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.headers = mapOf("Referer" to "https://tau-video.xyz/")
                            this.quality = quality
                        }
                    )
                    foundAny = true
                }

                // If embedUrl exists, also query tau-video api as fallback
                val embedUrl = vItem.embedUrl
                if (!embedUrl.isNullOrBlank() && embedUrl.contains("tau-video.xyz/embed/")) {
                    val tauId = embedUrl.substringAfter("tau-video.xyz/embed/").trim()
                    try {
                        val tauJson = app.get(
                            "https://tau-video.xyz/api/video/$tauId",
                            headers = mapOf("Referer" to embedUrl)
                        ).text
                        val tauData = AppUtils.parseJson<TauVideoResponse>(tauJson)
                        tauData.urls?.forEach { tu ->
                            val tUrl = tu.url ?: return@forEach
                            val quality = tu.label?.filter { it.isDigit() }?.toIntOrNull() ?: Qualities.P1080.value
                            callback.invoke(
                                newExtractorLink(
                                    source = "Tau Video",
                                    name = "Tau Video [${tu.label ?: "1080p"}]",
                                    url = tUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.headers = mapOf("Referer" to "https://tau-video.xyz/")
                                    this.quality = quality
                                }
                            )
                            foundAny = true
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return foundAny
    }
}
