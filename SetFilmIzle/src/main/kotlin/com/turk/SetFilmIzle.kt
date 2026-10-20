package com.turk

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.Random

class SetFilmIzle : MainAPI() {
    override var mainUrl = "https://www.setfilmizle.ltd"
    override var name = "SetFilmIzle"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/film/page/" to "Son Eklenen Filmler",
        "$mainUrl/dizi/page/" to "Yabancı Diziler",
        "$mainUrl/trend/page/" to "Trend Filmler",
        "$mainUrl/imdb-en-iyiler/page/" to "IMDb En İyiler",
        "$mainUrl/turkce-dublaj-filmler/page/" to "Türkçe Dublaj Filmler",
        "$mainUrl/yerli-filmler/page/" to "Yerli Filmler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}$page/"
        val res = app.get(url, headers = mapOf("Referer" to "$mainUrl/")).text
        val doc = Jsoup.parse(res)
        val items = mutableListOf<SearchResponse>()

        for (cardLink in doc.select("a.card-link")) {
            val href = cardLink.attr("href")
            val article = cardLink.selectFirst("article.card") ?: continue
            val title = article.selectFirst(".card-ad, h2, h3")?.text()?.trim()
                ?: article.selectFirst("img")?.attr("alt")?.trim() ?: continue
            val poster = article.selectFirst("img")?.let {
                it.attr("src").ifEmpty { it.attr("data-src") }
            }
            val isTv = href.contains("/dizi/")
            val tvType = if (isTv) TvType.TvSeries else TvType.Movie

            if (isTv) {
                items.add(newTvSeriesSearchResponse(title, href, tvType) {
                    this.posterUrl = poster
                })
            } else {
                items.add(newMovieSearchResponse(title, href, tvType) {
                    this.posterUrl = poster
                })
            }
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.trim().replace(" ", "+")}"
        val res = app.get(url, headers = mapOf("Referer" to "$mainUrl/")).text
        val doc = Jsoup.parse(res)
        val items = mutableListOf<SearchResponse>()

        for (cardLink in doc.select("a.card-link")) {
            val href = cardLink.attr("href")
            val article = cardLink.selectFirst("article.card") ?: continue
            val title = article.selectFirst(".card-ad, h2, h3")?.text()?.trim()
                ?: article.selectFirst("img")?.attr("alt")?.trim() ?: continue
            val poster = article.selectFirst("img")?.let {
                it.attr("src").ifEmpty { it.attr("data-src") }
            }
            val isTv = href.contains("/dizi/")
            val tvType = if (isTv) TvType.TvSeries else TvType.Movie

            if (isTv) {
                items.add(newTvSeriesSearchResponse(title, href, tvType) {
                    this.posterUrl = poster
                })
            } else {
                items.add(newMovieSearchResponse(title, href, tvType) {
                    this.posterUrl = poster
                })
            }
        }

        return items
    }

    override suspend fun load(url: String): LoadResponse {
        val res = app.get(url, headers = mapOf("Referer" to "$mainUrl/")).text
        val doc = Jsoup.parse(res)

        val title = doc.selectFirst("h1.stf-title, h1")?.text()?.trim() ?: "Bilinmeyen Başlık"
        val poster = doc.selectFirst(".fplayer-before img, .poster-art img")?.let {
            it.attr("src").ifEmpty { it.attr("data-src") }
        }
        val plot = doc.selectFirst(".hcard-ozet, .film-ozet, .entry-content p")?.text()?.trim()
        val year = Regex("""\b(19\d\d|20\d\d)\b""").find(res)?.value?.toIntOrNull()

        if (url.contains("/dizi/")) {
            val episodes = mutableListOf<Episode>()
            val epElements = doc.select("a.fep")
            for (epElem in epElements) {
                val epHref = epElem.attr("href")
                val epTitle = epElem.selectFirst(".fep-title")?.text()?.trim() ?: "Bölüm"
                val seasonMatch = Regex("""(\d+)-sezon-(\d+)-bolum""").find(epHref)
                val season = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epNum = seasonMatch?.groupValues?.get(2)?.toIntOrNull() ?: 1

                episodes.add(
                    newEpisode(epHref) {
                        this.name = epTitle
                        this.season = season
                        this.episode = epNum
                    }
                )
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }

    private fun xorDecrypt(cipherB64: String, keyB64: String): String {
        val cipher = Base64.decode(cipherB64, Base64.DEFAULT)
        val key = Base64.decode(keyB64, Base64.DEFAULT)
        val sb = java.lang.StringBuilder()
        for (i in cipher.indices) {
            val c = cipher[i].toInt() and 0xFF
            val k = key[i % key.size].toInt() and 0xFF
            sb.append((c xor k).toChar())
        }
        return sb.toString()
    }

    private fun calculateSpHeader(sp: String, spT: Long): String {
        val nVal = spT
        val rand = Random().nextInt(2147483647)
        val rStr = java.lang.Long.toString(rand.toLong(), 36)
        val hashInput = "$sp|$nVal|$rStr"
        var tVal = 2166136261L
        for (ch in hashInput) {
            tVal = tVal xor ch.code.toLong()
            tVal = (tVal * 16777619L) and 0xFFFFFFFFL
        }
        val hStr = java.lang.Long.toHexString(tVal)
        return "$nVal.$rStr.$hStr"
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
            val postId = Regex("""data-post-id=["'](\d+)["']""").find(res)?.groupValues?.get(1) ?: return false
            val nonce = Regex("""video:\s*["']([a-f0-9]+)["']""").find(res)?.groupValues?.get(1) ?: return false

            val playerNames = mutableListOf("SetPlay")
            Regex("""data-player-name=["']([^"']+)["']""").findAll(res).forEach {
                val p = it.groupValues[1]
                if (!playerNames.contains(p)) playerNames.add(p)
            }

            for (pName in playerNames) {
                try {
                    val ajaxRes = app.post(
                        "$mainUrl/wp-admin/admin-ajax.php",
                        data = mapOf(
                            "action" to "get_video_url",
                            "nonce" to nonce,
                            "post_id" to postId,
                            "player_name" to pName,
                            "part_key" to ""
                        ),
                        headers = mapOf(
                            "Referer" to data,
                            "X-Requested-With" to "XMLHttpRequest"
                        )
                    ).text

                    val ajaxJson = JSONObject(ajaxRes)
                    if (!ajaxJson.optBoolean("success")) continue
                    val streamObj = ajaxJson.optJSONObject("data")?.optJSONObject("stream") ?: continue
                    val streamUrl = streamObj.optString("url")
                    if (streamUrl.isBlank()) continue

                    if (streamUrl.contains("setplay.shop/player/stfplay.php")) {
                        val setplayHtml = app.get(streamUrl, headers = mapOf("Referer" to "$mainUrl/")).text
                        val cerceveMatch = Regex("""SPG\.cerceve\(["'][^"']+["'],\s*["']([^"']+)["'],\s*["']([^"']+)["']\)""").find(setplayHtml)
                        if (cerceveMatch != null) {
                            val cipher = cerceveMatch.groupValues[1].replace("\\/", "/")
                            val key = cerceveMatch.groupValues[2].replace("\\/", "/")
                            val decryptedFastplayUrl = xorDecrypt(cipher, key)

                            if (decryptedFastplayUrl.startsWith("http")) {
                                val fastplayHtml = app.get(decryptedFastplayUrl, headers = mapOf("Referer" to "https://setplay.shop/")).text
                                val sp = Regex(""""sp":"([^"]+)"""").find(fastplayHtml)?.groupValues?.get(1) ?: ""
                                val spT = Regex(""""spT":(\d+)""").find(fastplayHtml)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                                val manifestPath = Regex("""src:\s*"([^"]+)"""").find(fastplayHtml)?.groupValues?.get(1) ?: ""

                                // Subtitles
                                val subsMatch = Regex("""subtitles:\s*(\[.*?\]),""", setOf(RegexOption.DOT_MATCHES_ALL)).find(fastplayHtml)
                                if (subsMatch != null) {
                                    try {
                                        val subsArray = org.json.JSONArray(subsMatch.groupValues[1])
                                        for (sIdx in 0 until subsArray.length()) {
                                            val sub = subsArray.getJSONObject(sIdx)
                                            val subFile = sub.optString("file")
                                            val subLang = sub.optString("label", "Türkçe")
                                            if (subFile.startsWith("http")) {
                                                subtitleCallback.invoke(
                                                    SubtitleFile(subLang, subFile)
                                                )
                                            }
                                        }
                                    } catch (e: Exception) {
                                        // Ignore
                                    }
                                }

                                if (manifestPath.isNotBlank() && sp.isNotBlank() && spT > 0L) {
                                    val xSp = calculateSpHeader(sp, spT)
                                    val manifestFullUrl = if (manifestPath.startsWith("http")) manifestPath else "https://fastplay.mom$manifestPath"

                                    callback.invoke(
                                        ExtractorLink(
                                            name = "SetFilmIzle (FastPlay)",
                                            source = "SetFilmIzle",
                                            url = manifestFullUrl,
                                            referer = decryptedFastplayUrl,
                                            quality = Qualities.P1080.value,
                                            type = ExtractorLinkType.M3U8,
                                            headers = mapOf(
                                                "X-Sp" to xSp,
                                                "Referer" to decryptedFastplayUrl
                                            )
                                        )
                                    )
                                    foundLinks = true
                                }
                            }
                        }
                    } else {
                        // Standard generic player (Vidmoly, Filemoon, RapidVid etc)
                        loadExtractor(streamUrl, data, subtitleCallback) { link ->
                            callback.invoke(link)
                            foundLinks = true
                        }
                    }
                } catch (e: Exception) {
                    // Ignore per-player errors
                }
            }
        } catch (e: Exception) {
            // Ignore
        }

        return foundLinks
    }
}
