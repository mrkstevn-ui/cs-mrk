package com.turk

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class FilmModu : MainAPI() {
    override var mainUrl = "https://filmmodu.cc"
    override var name = "FilmModu"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie)

    private val standardHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "https://filmmodu.cc/"
    )

    override val mainPage = mainPageOf(
        "yeni-filmler" to "Son Eklenenler",
        "filmizle/turkce-dublaj-filmler-hd-izle" to "Türkçe Dublaj",
        "filmizle/turkce-altyazili-filmler-hd-izle" to "Türkçe Altyazı",
        "filmizle/aksiyon-filmleri-izle" to "Aksiyon",
        "filmizle/bilim-kurgu-filmleri-izle" to "Bilim Kurgu",
        "filmizle/animasyon-filmleri-izle" to "Animasyon"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (request.data == "yeni-filmler") {
            if (page <= 1) mainUrl else "$mainUrl/yeni-filmler/$page"
        } else {
            if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}/$page"
        }

        val doc = app.get(url, headers = standardHeaders).document
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

        val poster = element.selectFirst("picture img")?.let { img ->
            val src = img.attr("data-src").ifEmpty { img.attr("src") }
            if (src.startsWith("data:")) null else src
        }?.replace("_f.", "_xl.")?.replace("_m.", "_xl.")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val response = app.post(
                "$mainUrl/arama/",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to "$mainUrl/"
                ),
                data = mapOf(
                    "action" to "ajax_search",
                    "arama_kelime" to query
                )
            )

            val doc = Jsoup.parse(response.text)
            doc.select("a.search_s").mapNotNull { a ->
                val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                val title = a.selectFirst(".title")?.text()?.ifEmpty { a.attr("title") } ?: a.attr("title")
                val poster = a.selectFirst("img")?.attr("src")?.replace("_m.", "_xl.")
                newMovieSearchResponse(title.trim(), href, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = standardHeaders).document

        val rawTitle = doc.selectFirst("h1")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst(".titles")?.text()
            ?: return null

        val cleanTitle = rawTitle.replace(" izle", "", ignoreCase = true)
            .replace(" Filmi Türkçe Dublaj 1080p Full HD", "", ignoreCase = true)
            .trim()

        val poster = doc.selectFirst("section.movie_meta picture img")?.let { img ->
            val src = img.attr("data-src").ifEmpty { img.attr("src") }
            if (src.startsWith("data:")) null else src
        }?.replace("_f.", "_xl.") ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val plot = doc.selectFirst("div[itemprop=description]")?.text()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        val year = doc.selectFirst("a.cast[href*=/yil/]")?.text()?.toIntOrNull()
        val tags = doc.select("a[rel=category tag]").map { it.text() }

        return newMovieLoadResponse(cleanTitle, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageHtml = app.get(data, headers = standardHeaders).text

        // 1. ilkpartkod kontrolü
        val ilkPattern = Pattern.compile("var\\s+ilkpartkod\\s*=\\s*'([^']+)'")
        val ilkMatcher = ilkPattern.matcher(pageHtml)
        if (ilkMatcher.find()) {
            val b64 = ilkMatcher.group(1)
            try {
                val decoded = String(Base64.decode(b64, Base64.DEFAULT))
                extractAndLoadPlayer(decoded, "Ana Sunucu", subtitleCallback, callback)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. pdata array kontrolü (tüm part ve alternatif sunucular)
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
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return true
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
            val bePlayerPattern = Pattern.compile("bePlayer\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*,\\s*['\"](\\{.+?\\})['\"]\\s*\\)")
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
