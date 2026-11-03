package com.turk

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.json.JSONObject
import org.json.JSONArray
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import java.util.regex.Pattern

class HDFilmCehennemi : AdaptiveProvider() {
    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var name = "HDFilmcehennemi"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val standardHeaders = mapOf(
        "User-Agent" to TURK_USER_AGENT
    )

    // Afişleri yüksek çözünürlüğe çeviren fonksiyon
    private fun fixHdPosterUrl(url: String?): String? {
        if (url.isNullOrEmpty() || url.startsWith("data:")) return null
        return url.replace("/images/thumb/poster/", "/images/poster/")
                  .replace("/images/list/poster/", "/images/poster/")
    }

    // HTML kartından SearchResponse üreten yardımcı fonksiyon (Lazy-load uyumlu)
    private fun toSearchResult(element: Element): SearchResponse? {
        val link = element.attr("href")
        if (link.isEmpty() || link.endsWith("/film-robotu-1/")) return null

        val img = element.selectFirst("img") ?: return null
        val title = img.attr("alt").trim().takeIf { it.isNotEmpty() } ?: return null

        val poster = fixHdPosterUrl(cleanPosterUrl(img, mainUrl))
        val isSeries = link.contains("/dizi/")
        val type = if (isSeries) TvType.TvSeries else TvType.Movie

        return if (isSeries) {
            newTvSeriesSearchResponse(title, link, type) {
                addPoster(poster, mainUrl)
            }
        } else {
            newMovieSearchResponse(title, link, type) {
                addPoster(poster, mainUrl)
            }
        }
    }

    // 1. ANA SAYFA KATEGORİLERİ
    override val mainPage = mainPageOf(
        "$mainUrl/filmler/" to "Son Eklenen Filmler",
        "$mainUrl/dizi-izle/" to "Yeni Eklenen Diziler",
        "$mainUrl/kesfet/" to "Keşfet",
        "$mainUrl/yil/2026/" to "2026 Filmleri",
        "$mainUrl/tur/aksiyon-filmleri-izle/" to "Aksiyon Filmleri",
        "$mainUrl/tur/bilim-kurgu-filmleri-izle/" to "Bilim Kurgu Filmleri"
    )

    override suspend fun mainPageProvider(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        val doc = app.get(targetUrl, headers = standardHeaders).document.requireContent(name)

        val movies = doc.select("a[href^=\"$mainUrl/\"], a[href^=\"/\"]").filter {
            val href = it.attr("href")
            (href.contains("-izle") || href.contains("/dizi/")) && (it.selectFirst("img") != null || it.html().contains("poster"))
        }.mapNotNull { element ->
            toSearchResult(element)
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, movies)
    }

    // 2. ARAMA MOTORU
    override suspend fun searchProvider(query: String): List<SearchResponse> {
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
                if (!isSearchMatch(title, link, query)) continue
                val img = doc.selectFirst("img")
                val poster = fixHdPosterUrl(cleanPosterUrl(img, mainUrl))

                val isSeries = link.contains("/dizi/")
                val type = if (isSeries) TvType.TvSeries else TvType.Movie

                if (isSeries) {
                    searchResponses.add(newTvSeriesSearchResponse(title, link, type) {
                        addPoster(poster, mainUrl)
                    })
                } else {
                    searchResponses.add(newMovieSearchResponse(title, link, type) {
                        addPoster(poster, mainUrl)
                    })
                }
            }
            searchResponses
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            reportStage("adapter", e)
            emptyList()
        }
    }

    // 3. FİLM VE DİZİ DETAY SAYFASI
    override suspend fun loadProvider(url: String): LoadResponse {
        val doc = app.get(url, headers = standardHeaders).document.requireContent(name)
        val title = doc.selectFirst("h1")?.text()?.replace(" izle", "")?.trim() ?: "Film"
        val img = doc.selectFirst("div.poster img, img.poster, img")
        val poster = fixHdPosterUrl(cleanPosterUrl(img, mainUrl)) ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
        val plot = doc.selectFirst("article.post-content, div.film-summary, p.description")?.text()

        val isSeries = url.contains("/dizi/") && !url.contains("/bolum-")

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            val epLinks = doc.select("a[href*=\"/sezon-\"][href*=\"/bolum-\"]")
            for (ep in epLinks) {
                val epHref = fixUrl(ep.attr("href"))
                val epText = ep.text().trim()
                val sNum = Regex("""sezon-(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val eNum = Regex("""bolum-(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                episodes.add(newEpisode(epHref) {
                    this.name = epText.ifEmpty { "$sNum. Sezon $eNum. Bölüm" }
                    this.season = sNum
                    this.episode = eNum
                })
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
                addPoster(poster, mainUrl)
                this.plot = plot
            }.withMetadata(doc)
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            addPoster(poster, mainUrl)
            this.plot = plot
        }.withMetadata(doc)
    }

    // 4. CANLI VİDEO AKIŞI ÇÖZÜCÜ (Deobfuscator & HLS Extractor)
    override suspend fun resolveLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data, headers = standardHeaders).text
        val doc = Jsoup.parse(html)
        var foundAny = false

        // 1. Yeni Sistem: Alpine.js videoPlayerData JSON parsing
        val jsonMatch = Regex("""videoPlayerData\s*\(\s*JSON\.parse\(\s*'([^']+)'\s*\)""").find(html)?.groupValues?.get(1)
        if (!jsonMatch.isNullOrBlank()) {
            try {
                val rawJson = jsonMatch.replace("\\u0022", "\"").replace("\\/", "/")
                val jsonObj = JSONObject(rawJson)
                for (key in jsonObj.keys()) {
                    val arr = jsonObj.optJSONArray(key) ?: continue
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val serviceName = item.optString("service_name", "HDFilmcehennemi")
                        val link = item.optString("link")
                        val tmplB64 = item.optString("template")
                        val tmpl = if (tmplB64.isNotBlank()) String(base64DecodeArray(tmplB64), Charsets.UTF_8) else ""
                        val iframeSrcMatch = Regex("""(?:data-src|src)="([^"]+)"""").find(tmpl)?.groupValues?.get(1)
                        val playerUrl = fixUrlNull(iframeSrcMatch?.replace("{url}", link)) ?: continue

                        if (playerUrl.contains("vidload.top/player/") || playerUrl.contains("/player/")) {
                            try {
                                val pHtml = app.get(playerUrl, headers = mapOf("Referer" to "$mainUrl/")).text
                                var cleaned = pHtml
                                repeat(3) {
                                    cleaned = cleaned.replace("\\\\", "\\").replace("\\/", "/")
                                }
                                cleaned = cleaned.replace("\\u0022", "\"").replace("\"", "")
                                val m3u8Match = Regex("""https?://[^\s,;']+\.m3u8[^\s,;']*""").find(cleaned)?.value
                                if (m3u8Match != null) {
                                    callback.invoke(
                                        newExtractorLink(
                                            source = name,
                                            name = "$name - $serviceName",
                                            url = m3u8Match,
                                            type = ExtractorLinkType.M3U8
                                        ) {
                                            this.headers = mapOf(
                                                "Referer" to playerUrl,
                                                "User-Agent" to TURK_USER_AGENT
                                            )
                                        }
                                    )
                                    foundAny = true
                                }
                            } catch (e: Exception) {
                                if (e is java.util.concurrent.CancellationException) throw e
                                reportStage("adapter", e)
                            }
                        } else {
                            loadExtractor(playerUrl, data, subtitleCallback, callback)
                            foundAny = true
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                reportStage("adapter", e)
            }
        }

        // 2. Eski Sistem Fallback: data-video butonları
        val videoButtons = doc.select("button[data-video], [data-video]")
        for (btn in videoButtons) {
            val videoId = btn.attr("data-video").trim()
            val sourceLabel = btn.text().trim().ifEmpty { "Varsayılan" }
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
                if (!json.optBoolean("success")) continue

                val iframeHtml = json.getJSONObject("data").getString("html")
                val iframeDoc = Jsoup.parse(iframeHtml)
                val playerUrl = iframeDoc.selectFirst("iframe")?.attr("data-src")
                    ?: iframeDoc.selectFirst("iframe")?.attr("src") ?: continue

                if (playerUrl.contains("hdfilmcehennemi.mobi/video/embed/") || playerUrl.contains("/rplayer/")) {
                    resolveMobiEmbed(playerUrl, sourceLabel, subtitleCallback, callback)
                    foundAny = true
                } else {
                    loadExtractor(playerUrl, data, subtitleCallback, callback)
                    foundAny = true
                }
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                reportStage("adapter", e)
            }
        }

        // 3. Genel iframe Fallback
        if (!foundAny) {
            doc.select("iframe").forEach { iframe ->
                val src = fixUrlNull(iframe.attr("data-src").ifEmpty { iframe.attr("src") }) ?: return@forEach
                if (!src.contains("youtube.com") && !src.contains("google.com")) {
                    if (src.contains("hdfilmcehennemi.mobi/video/embed/") || src.contains("/rplayer/")) {
                        resolveMobiEmbed(src, "Ana Sunucu", subtitleCallback, callback)
                        foundAny = true
                    } else {
                        loadExtractor(src, data, subtitleCallback, callback)
                        foundAny = true
                    }
                }
            }
        }

        return foundAny
    }

    // hdfilmcehennemi.mobi ve rplayer oynatıcı şifresini çözen motor
    private suspend fun resolveMobiEmbed(
        embedUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedHtml = app.get(embedUrl, referer = "$mainUrl/").text

        // 1. Altyazıları ayıkla
        val tracksPattern = Pattern.compile("tracks:\\s*\\[(.*?)\\]", Pattern.DOTALL)
        val tracksMatcher = tracksPattern.matcher(embedHtml)
        if (tracksMatcher.find()) {
            val tracksJsonStr = "[" + tracksMatcher.group(1) + "]"
            try {
                val tracksArray = JSONArray(tracksJsonStr)
                for (i in 0 until tracksArray.length()) {
                    val trackObj = tracksArray.getJSONObject(i)
                    val subFile = trackObj.optString("file")
                    val subLabel = trackObj.optString("label").ifEmpty { "Altyazı" }
                    if (subFile.isNotEmpty()) {
                        val fullSubUrl = if (subFile.startsWith("http")) subFile else fixUrl(subFile)
                        subtitleCallback.invoke(SubtitleFile(subLabel, fullSubUrl))
                    }
                }
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                reportStage("adapter", e)
            }
        }

        // 2. Sources içindeki değişken adını bul (sources: [{file: VAR_NAME}])
        val fileVarPattern = Pattern.compile("sources:\\s*\\[\\{\\s*file:\\s*([a-zA-Z0-9_]+)")
        val fileVarMatcher = fileVarPattern.matcher(embedHtml)
        if (!fileVarMatcher.find()) return
        val targetVar = fileVarMatcher.group(1)

        // 3. Şifre çözücü script bloğunu bul (Gerekirse packer'ı aç)
        val doc = Jsoup.parse(embedHtml)
        val scripts = doc.select("script")
        var deobfuscatorScript: String? = null

        for (s in scripts) {
            var content = s.data()
            if (content.contains("eval(function(")) {
                content = getAndUnpack(content)
            }
            if (content.contains("join(") && (content.contains(".split(") || content.contains("charCodeAt")) && content.contains(targetVar)) {
                deobfuscatorScript = content
                break
            }
        }

        if (deobfuscatorScript.isNullOrEmpty()) {
            deobfuscatorScript = scripts.find { it.data().contains(targetVar) }?.data()
            if (deobfuscatorScript?.contains("eval(function(") == true) {
                deobfuscatorScript = getAndUnpack(deobfuscatorScript!!)
            }
        }

        if (deobfuscatorScript.isNullOrEmpty()) return

        // 4. Mozilla Rhino ile güvenli JavaScript ortamında ISO-8859-1 ile binary atob çözümü
        val rhino = Context.enter()
        rhino.optimizationLevel = -1
        try {
            val scope: Scriptable = rhino.initStandardObjects()
            val polyfill = """
                var atob = function(str) {
                    var bytes = android.util.Base64.decode(str, 0);
                    return "" + new java.lang.String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
                };
                var btoa = function(str) {
                    var bytes = new java.lang.String(str).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
                    return "" + android.util.Base64.encodeToString(bytes, 2).trim();
                };
            """.trimIndent()

            rhino.evaluateString(scope, polyfill + "\n" + deobfuscatorScript, "decoder", 1, null)
            val resolvedUrl = scope.get(targetVar, scope)?.toString()

            if (!resolvedUrl.isNullOrEmpty() && resolvedUrl.startsWith("http")) {
                val refererUrl = if (embedUrl.contains("mobi")) "https://hdfilmcehennemi.mobi/" else "$mainUrl/"
                val streamHeaders = mapOf(
                    "Referer" to refererUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Origin" to refererUrl.removeSuffix("/")
                )

                // ExoPlayer'ın HLS olduğunu kesin tanıması için ?ext=.m3u8 ekliyoruz (Error 3003 engelleyici)
                val hlsUrl = if (resolvedUrl.contains("?")) "$resolvedUrl&ext=.m3u8" else "$resolvedUrl?ext=.m3u8"

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name ($label - 1080p HD)",
                        url = hlsUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = refererUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = streamHeaders
                    }
                )

                // master.txt split audio içeren oynatıcılar için alternatif doğrudan video kanalı
                if (resolvedUrl.contains("master.txt")) {
                    val directSublist = resolvedUrl.substringBeforeLast("/") + "/sublist_2.txt?ext=.m3u8"
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name ($label - Hızlı Akış)",
                            url = directSublist,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = refererUrl
                            this.quality = Qualities.Unknown.value
                            this.headers = streamHeaders
                        }
                    )
                }
            }
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            reportStage("adapter", e)
        } finally {
            Context.exit()
        }
    }
}
