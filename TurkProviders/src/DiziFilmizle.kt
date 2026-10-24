package com.turk

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DiziFilmizle : MainAPI() {
    override var mainUrl = "https://dizifilmizle.com"
    override var name = "DiziFilmizle"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/turkce-dublaj-filmler" to "Yeni Filmler",
        "$mainUrl/yabanci-dizi-izle" to "Yabancı Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val items = mutableListOf<SearchResponse>()

        document.select("a[href*='/film/'], a[href*='/dizi/']").forEach { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@forEach
            val img = a.selectFirst("img")
            val title = img?.attr("alt")?.ifBlank { null }
                ?: a.selectFirst("h2, h3, .title")?.text()?.ifBlank { null }
                ?: return@forEach
            val isSeries = href.contains("/dizi/")

            val res = if (isSeries) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    addPoster(img, mainUrl)
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    addPoster(img, mainUrl)
                }
            }
            items.add(res)
        }

        return newHomePageResponse(request.name, items.distinctBy { it.url })
    }

    data class SearchItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("poster_url") val posterUrl: String? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("type") val type: String? = null
    )

    data class SearchApiResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("results") val results: List<SearchItem>? = null
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/api/search?q=${query.trim().replace(" ", "+")}"
        return try {
            val json = app.get(
                searchUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to "$mainUrl/"
                )
            ).text
            val data = AppUtils.parseJson<SearchApiResponse>(json)
            data.results?.mapNotNull { r ->
                val slug = r.slug ?: return@mapNotNull null
                val isSeries = r.type == "dizi"
                val href = if (isSeries) "$mainUrl/dizi/$slug" else "$mainUrl/film/$slug"
                val title = r.title ?: r.originalTitle ?: "Film"
                val poster = r.posterUrl

                if (isSeries) {
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        addPoster(poster, mainUrl)
                    }
                } else {
                    newMovieSearchResponse(title, href, TvType.Movie) {
                        addPoster(poster, mainUrl)
                    }
                }
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val isSeries = url.contains("/dizi/")

        val title = document.selectFirst("h1, meta[property='og:title']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }?.replace("izle", "", ignoreCase = true)?.trim() ?: "Bilinmeyen Yapım"

        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: cleanPosterUrl(document.selectFirst("picture img, .poster img, img"), mainUrl)

        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst(".story, .summary, p.description")?.text()?.trim()

        val year = document.selectFirst(".year, [class*='year']")?.text()?.filter { it.isDigit() }?.toIntOrNull()

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            document.select("a[href*='/sezon-'], a[href*='/bolum-']").forEach { ep ->
                val epHref = fixUrlNull(ep.attr("href")) ?: return@forEach
                val epText = ep.text().trim()
                val seasonNum = Regex("(\\d+)\\s*\\.?\\s*Sezon", RegexOption.IGNORE_CASE).find(epText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("sezon-(\\d+)", RegexOption.IGNORE_CASE).find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epNum = Regex("(\\d+)\\s*\\.?\\s*Bölüm", RegexOption.IGNORE_CASE).find(epText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("bolum-(\\d+)", RegexOption.IGNORE_CASE).find(epHref)?.groupValues?.get(1)?.toIntOrNull()

                episodes.add(newEpisode(epHref) {
                    this.name = if (epText.isNotBlank()) epText else "Bölüm $epNum"
                    this.season = seasonNum
                    this.episode = epNum
                })
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
                addPoster(poster, mainUrl)
                this.plot = description
                this.year = year
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            addPoster(poster, mainUrl)
            this.plot = description
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val html = document.html()
        var foundAny = false

        // Extract Vidmixi embed links
        val vidmixiUrls = Regex("https://vidmixi\\.com/embed/[a-zA-Z0-9]+").findAll(html).map { it.value }.toSet()
        for (embedUrl in vidmixiUrls) {
            resolveVidmixi(embedUrl, "DiziFilmizle", subtitleCallback, callback)
            foundAny = true
        }

        // Other iframes fallback
        document.select("iframe").forEach { iframe ->
            val src = fixUrlNull(iframe.attr("src")) ?: return@forEach
            if (!src.contains("vidmixi.com")) {
                loadExtractor(src, data, subtitleCallback, callback)
                foundAny = true
            }
        }

        return foundAny
    }

    private suspend fun resolveVidmixi(
        embedUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val html = app.get(embedUrl, referer = "$mainUrl/").text

            val bePlayerPattern = Pattern.compile("bePlayer\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*,\\s*['\"](\\{.+?\\})['\"]\\s*\\)")
            val matcher = bePlayerPattern.matcher(html)
            if (!matcher.find()) return

            val hash = matcher.group(1)
            val jsonStr = matcher.group(2)
            val jsonObj = JSONObject(jsonStr)

            val ct = Base64.decode(jsonObj.getString("ct"), Base64.DEFAULT)
            val iv = hexStringToByteArray(jsonObj.getString("iv"))
            val salt = hexStringToByteArray(jsonObj.getString("s"))

            val key = evpBytesToKey(hash.toByteArray(Charsets.UTF_8), salt, 32)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val decryptedBytes = cipher.doFinal(ct)
            val decryptedJson = JSONObject(String(decryptedBytes, Charsets.UTF_8))

            val videoLocation = decryptedJson.optString("videoLocation")
            if (videoLocation.isNotEmpty()) {
                val streamHeaders = mapOf(
                    "Referer" to "https://vidmixi.com/",
                    "Origin" to "https://vidmixi.com",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                )

                val hlsUrl = if (videoLocation.contains("?")) "$videoLocation&ext=.m3u8" else "$videoLocation?ext=.m3u8"

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name ($label - 1080p)",
                        url = hlsUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://vidmixi.com/"
                        this.quality = Qualities.P1080.value
                        this.headers = streamHeaders
                    }
                )
            }

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
            e.printStackTrace()
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
