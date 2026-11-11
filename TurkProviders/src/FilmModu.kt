package com.turk

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class FilmModu : AdaptiveProvider() {
    override var mainUrl = "https://filmmodu.cc"
    override var name = "FilmModu"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie)

    private val standardHeaders get() = mapOf(
        "User-Agent" to TURK_USER_AGENT,
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "yeni-filmler" to "Son Eklenenler",
        "filmizle/turkce-dublaj-filmler-hd-izle" to "Türkçe Dublaj",
        "filmizle/turkce-altyazili-filmler-hd-izle" to "Türkçe Altyazı",
        "filmizle/aksiyon-filmleri-izle" to "Aksiyon",
        "filmizle/bilim-kurgu-filmleri-izle" to "Bilim Kurgu",
        "filmizle/animasyon-filmleri-izle" to "Animasyon"
    )

    override suspend fun mainPageProvider(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (request.data == "yeni-filmler") {
            if (page <= 1) mainUrl else "$mainUrl/yeni-filmler/$page"
        } else {
            if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}/$page"
        }

        val doc = app.get(url, headers = standardHeaders).document.requireContent(name)
        val movies = doc.select("article.movie_box").mapNotNull { toSearchResult(it) }

        return newHomePageResponse(
            listOf(HomePageList(request.name, movies)),
            hasNext = movies.isNotEmpty()
        )
    }

    private fun toSearchResult(element: org.jsoup.nodes.Element): SearchResponse? {
        val a = element.selectFirst("a.image") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        val rawTitle = a.attr("title").ifEmpty { element.selectFirst("h3")?.text() ?: "" }
        val title = rawTitle.replace(" izle", "", ignoreCase = true).trim()

        val img = element.selectFirst("picture img, img")
        val poster = cleanPosterUrl(img, mainUrl)?.replace("_f.", "_xl.")?.replace("_m.", "_xl.")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            addPoster(poster, mainUrl)
        }
    }

    override suspend fun searchProvider(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        try {
            // 1. Yeni JSON Arama API'si (/arama/oneri)
            val homeDoc = app.get(mainUrl, headers = standardHeaders).document.requireContent(name)
            val csrfToken = homeDoc.selectFirst("meta[name='csrf-token']")?.attr("content").orEmpty()

            val response = app.post(
                "$mainUrl/arama/oneri",
                headers = mapOf(
                    "User-Agent" to TURK_USER_AGENT,
                    "X-CSRF-TOKEN" to csrfToken,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to "$mainUrl/",
                    "Accept" to "application/json",
                    "Content-Type" to "application/x-www-form-urlencoded"
                ),
                data = mapOf("q" to query.trim())
            ).text

            val json = JSONObject(response)
            val films = json.optJSONArray("films")
            if (films != null) {
                for (i in 0 until films.length()) {
                    val f = films.getJSONObject(i)
                    val title = f.optString("title").replace(" izle", "", ignoreCase = true).trim()
                    val url = fixUrlNull(f.optString("url")) ?: continue
                    if (!isSearchMatch(title, url, query)) continue
                    val poster = f.optString("poster").replace("_m.", "_xl.").replace("_s.", "_xl.")
                    val year = f.optInt("year").takeIf { it > 0 }

                    results.add(newMovieSearchResponse(title, url, TvType.Movie) {
                        addPoster(poster, mainUrl)
                        this.year = year
                    })
                }
            }

            val series = json.optJSONArray("series")
            if (series != null) {
                for (i in 0 until series.length()) {
                    val s = series.getJSONObject(i)
                    val title = s.optString("title").replace(" izle", "", ignoreCase = true).trim()
                    val url = fixUrlNull(s.optString("url")) ?: continue
                    if (!isSearchMatch(title, url, query)) continue
                    val poster = s.optString("poster").replace("_m.", "_xl.").replace("_s.", "_xl.")
                    val year = s.optInt("year").takeIf { it > 0 }

                    results.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                        addPoster(poster, mainUrl)
                        this.year = year
                    })
                }
            }
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            reportStage("adapter", e)
            // Fallback: Eski HTML arama
            try {
                val response = app.post(
                    "$mainUrl/arama/",
                    headers = mapOf(
                        "User-Agent" to TURK_USER_AGENT,
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to "$mainUrl/"
                    ),
                    data = mapOf(
                        "action" to "ajax_search",
                        "arama_kelime" to query
                    )
                )

                val doc = Jsoup.parse(response.text)
                doc.select("a.search_s").forEach { a ->
                    val href = fixUrlNull(a.attr("href")) ?: return@forEach
                    val title = a.selectFirst(".title")?.text()?.ifEmpty { a.attr("title") } ?: a.attr("title")
                    if (!isSearchMatch(title, href, query)) return@forEach
                    val poster = a.selectFirst("img")?.attr("src")?.replace("_m.", "_xl.")
                    results.add(newMovieSearchResponse(title.trim(), href, TvType.Movie) {
                        addPoster(poster, mainUrl)
                    })
                }
            } catch (e2: Exception) {
                if (e2 is java.util.concurrent.CancellationException) throw e2
                reportStage("adapter", e2)
                // Ignore
            }
        }
        return results.distinctBy { it.url }
    }

    override suspend fun loadProvider(url: String): LoadResponse? {
        val doc = app.get(url, headers = standardHeaders).document.requireContent(name)

        val rawTitle = doc.selectFirst("h1")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst(".titles")?.text()
            ?: return null

        val cleanTitle = rawTitle.replace(" izle", "", ignoreCase = true)
            .replace(" Filmi Türkçe Dublaj 1080p Full HD", "", ignoreCase = true)
            .trim()

        val poster = cleanPosterUrl(doc.selectFirst("section.movie_meta picture img, img"), mainUrl)?.replace("_f.", "_xl.")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val plot = doc.selectFirst("div[itemprop=description]")?.text()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        val year = doc.selectFirst("a.cast[href*=/yil/]")?.text()?.toIntOrNull()
        val tags = doc.select("a[rel=category tag]").map { it.text() }

        return newMovieLoadResponse(cleanTitle, url, TvType.Movie, url) {
            addPoster(poster, mainUrl)
            this.plot = plot
            this.year = year
            this.tags = tags
        }.withMetadata(doc)
    }

    override suspend fun resolveLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageRes = app.get(data, headers = standardHeaders)
        val pageDoc = pageRes.document.requireContent(name)
        val pageHtml = pageDoc.html()
        val cookies = pageRes.cookies
        var foundAny = false

        // 1. Yeni Sistem: #f_player div[data-k] ve /px API'si
        val csrfToken = pageDoc.selectFirst("meta[name='csrf-token']")?.attr("content").orEmpty()
        val dataKAttr = pageDoc.selectFirst("#f_player, .fm-oynatici")?.attr("data-k")
            ?: Regex("""data-k=["'](.*?)["']""").find(pageHtml)?.groupValues?.get(1)

        if (!dataKAttr.isNullOrBlank()) {
            try {
                val jsonArr = org.json.JSONArray(dataKAttr.replace("&quot;", "\""))
                for (i in 0 until jsonArr.length()) {
                    val item = jsonArr.getJSONObject(i)
                    val itemId = item.optLong("i")
                    val itemS = item.optString("s")
                    if (itemId == 0L || itemS.isBlank()) continue

                    try {
                        val pxResp = app.post(
                            "$mainUrl/px",
                            headers = mapOf(
                                "User-Agent" to TURK_USER_AGENT,
                                "Referer" to data,
                                "X-CSRF-TOKEN" to csrfToken,
                                "X-Requested-With" to "XMLHttpRequest",
                                "Accept" to "application/json",
                                "Content-Type" to "application/x-www-form-urlencoded"
                            ),
                            cookies = cookies,
                            data = mapOf("i" to itemId.toString(), "s" to itemS)
                        ).text

                        val pxJson = JSONObject(pxResp)
                        val playerUrl = pxJson.optString("u")
                        if (playerUrl.isNotBlank() && playerUrl.startsWith("http")) {
                            if (playerUrl.contains("vidmixi.com/embed/")) {
                                resolveVidmixi(playerUrl, "FilmModu", subtitleCallback, callback)
                                foundAny = true
                            } else {
                                loadExtractor(playerUrl, mainUrl, subtitleCallback, callback)
                                foundAny = true
                            }
                        }
                    } catch (e: Exception) {
                        if (e is java.util.concurrent.CancellationException) throw e
                        reportStage("adapter", e)
                    }
                }
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                reportStage("adapter", e)
            }
        }

        // 2. ilkpartkod kontrolü (eski sistem fallback)
        val ilkPattern = Pattern.compile("var\\s+ilkpartkod\\s*=\\s*'([^']+)'")
        val ilkMatcher = ilkPattern.matcher(pageHtml)
        if (ilkMatcher.find()) {
            val b64 = ilkMatcher.group(1)
            try {
                val decoded = String(Base64.decode(b64, Base64.DEFAULT))
                extractAndLoadPlayer(decoded, "Ana Sunucu", subtitleCallback, callback)
                foundAny = true
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                reportStage("adapter", e)
            }
        }

        // 3. pdata array kontrolü (tüm part ve alternatif sunucular)
        val pdataPattern = Pattern.compile("pdata\\['([^']+)'\\]\\s*=\\s*'([^']+)'")
        val pdataMatcher = pdataPattern.matcher(pageHtml)
        while (pdataMatcher.find()) {
            val partKey = pdataMatcher.group(1)
            var raw = pdataMatcher.group(2)
            if (raw.startsWith("yZWw9")) {
                raw = "PGlmcmFtZSB" + raw
            }
            try {
                val decoded = String(Base64.decode(raw, Base64.DEFAULT))
                val label = if (partKey.contains("atom")) "Atom" else if (partKey.contains("fast")) "Hızlı" else "Alternatif"
                extractAndLoadPlayer(decoded, label, subtitleCallback, callback)
                foundAny = true
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                reportStage("adapter", e)
            }
        }

        return foundAny
    }

    private suspend fun extractAndLoadPlayer(
        html: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val srcPattern = Pattern.compile("src=\"([^\"]+)\"")
        val matcher = srcPattern.matcher(html)
        if (!matcher.find()) return
        val playerUrl = matcher.group(1)

        if (playerUrl.contains("vidmixi.com/embed/")) {
            resolveVidmixi(playerUrl, label, subtitleCallback, callback)
        } else {
            loadExtractor(playerUrl, mainUrl, subtitleCallback, callback)
        }
    }

    private suspend fun resolveVidmixi(
        embedUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val html = app.get(embedUrl, referer = "$mainUrl/").text

            // bePlayer('HASH', '{"ct":"...","iv":"...","s":"..."}')
            val bePlayerPattern = Pattern.compile("""bePlayer\s*\(\s*['"]([^'"]+)['"]\s*,\s*['"](\{.+\})['"]\s*\)""", Pattern.DOTALL)
            val matcher = bePlayerPattern.matcher(html)
            if (!matcher.find()) return

            val hash = matcher.group(1)
            val jsonStr = matcher.group(2)
            val jsonObj = JSONObject(jsonStr)

            val ct = Base64.decode(jsonObj.getString("ct"), Base64.DEFAULT)
            val iv = hexStringToByteArray(jsonObj.getString("iv"))
            val salt = hexStringToByteArray(jsonObj.getString("s"))

            // EvpKDF ile key türetme
            val key = evpBytesToKey(hash.toByteArray(Charsets.UTF_8), salt, 32)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val decryptedBytes = cipher.doFinal(ct)
            val decryptedJson = JSONObject(String(decryptedBytes, Charsets.UTF_8))

            val videoLocation = decryptedJson.optString("video_location")
            if (videoLocation.isNotEmpty()) {
                val streamHeaders = mapOf(
                    "Referer" to "https://vidmixi.com/",
                    "Origin" to "https://vidmixi.com",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )

                // ExoPlayer 3003 hatasını önlemek için HLS uzantısı eklenir
                val hlsUrl = if (videoLocation.contains("?")) "$videoLocation&ext=.m3u8" else "$videoLocation?ext=.m3u8"

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name ($label)",
                        url = hlsUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://vidmixi.com/"
                        this.quality = Qualities.Unknown.value
                        this.headers = streamHeaders
                    }
                )
            }

            // Altyazılar
            val subArray = decryptedJson.optJSONArray("strSubtitles")
            if (subArray != null) {
                for (i in 0 until subArray.length()) {
                    val subObj = subArray.getJSONObject(i)
                    val subFile = subObj.optString("file")
                    val subLabel = subObj.optString("label").ifEmpty { "Altyazı" }
                    if (subFile.isNotEmpty()) {
                        val fullSubUrl = if (subFile.startsWith("http")) subFile else "https://vidmixi.com$subFile"
                        subtitleCallback.invoke(SubtitleFile(subLabel, fullSubUrl))
                    }
                }
            }
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            reportStage("adapter", e)
        }
    }

    private fun evpBytesToKey(password: ByteArray, salt: ByteArray?, keyLen: Int): ByteArray {
        val md = MessageDigest.getInstance("MD5")
        var currentHash = ByteArray(0)
        val result = ByteArrayOutputStream()
        while (result.size() < keyLen) {
            md.reset()
            md.update(currentHash)
            md.update(password)
            if (salt != null) md.update(salt)
            currentHash = md.digest()
            result.write(currentHash)
        }
        return result.toByteArray().copyOfRange(0, keyLen)
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
