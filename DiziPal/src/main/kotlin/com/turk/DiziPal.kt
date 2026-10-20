package com.turk

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class DiziPal : MainAPI() {
    override var mainUrl = "https://dizipal1591.com"
    override var name = "DiziPal"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/trendler" to "Trendler & Popüler",
        "$mainUrl/film-izle?page=" to "Son Eklenen Filmler",
        "$mainUrl/yabanci-dizi-izle?page=" to "Son Eklenen Diziler",
        "$mainUrl/bugun-en-cok-izlenen-filmler" to "Bugün En Çok İzlenenler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (request.data.contains("?page=")) {
            "${request.data}$page"
        } else {
            request.data
        }

        val document = app.get(targetUrl, headers = mapOf("Referer" to "$mainUrl/")).document
        val items = document.select("a[href*='/movies/'], a[href*='/series/']").mapNotNull { card ->
            toSearchResult(card)
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    private fun toSearchResult(card: Element): SearchResponse? {
        val href = fixUrlNull(card.attr("href")) ?: return null
        if (!href.contains("/movies/") && !href.contains("/series/")) return null

        val ariaLabel = card.attr("aria-label").trim()
        val img = card.selectFirst("img")
        val title = if (ariaLabel.isNotBlank()) {
            ariaLabel.split(",").firstOrNull()?.trim()
        } else {
            img?.attr("alt")?.trim()?.ifBlank { null }
                ?: href.substringAfterLast("/").replace("-", " ").capitalizeWords()
        } ?: return null

        val posterUrl = fixUrlNull(img?.attr("src")?.ifBlank { img.attr("data-src") })
        val isSeries = href.contains("/series/")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    private fun String.capitalizeWords(): String =
        split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim().lowercase()
        val slug = cleanQuery
            .replace("ç", "c").replace("ğ", "g").replace("ı", "i")
            .replace("ö", "o").replace("ş", "s").replace("ü", "u")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

        val results = mutableListOf<SearchResponse>()

        // Try direct slug routes for movies and series
        for (candidate in listOf("$mainUrl/series/$slug", "$mainUrl/movies/$slug")) {
            try {
                val res = app.get(candidate, headers = mapOf("Referer" to "$mainUrl/"))
                if (res.code == 200) {
                    val doc = res.document
                    val title = doc.selectFirst("title")?.text()
                        ?.replace("Dizipal", "", ignoreCase = true)
                        ?.replace("1080P Türkçe Dublaj & Altyazılı izle", "", ignoreCase = true)
                        ?.replace("izle", "", ignoreCase = true)?.trim()
                        ?: cleanQuery.capitalizeWords()

                    val poster = fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content"))
                    val isSeries = candidate.contains("/series/")

                    val item = if (isSeries) {
                        newTvSeriesSearchResponse(title, candidate, TvType.TvSeries) {
                            this.posterUrl = poster
                        }
                    } else {
                        newMovieSearchResponse(title, candidate, TvType.Movie) {
                            this.posterUrl = poster
                        }
                    }
                    results.add(item)
                }
            } catch (e: Exception) {
                // Ignore 404
            }
        }

        if (results.isEmpty()) {
            // Search top trend/catalog pages for substring match
            try {
                val trendDoc = app.get("$mainUrl/trendler", headers = mapOf("Referer" to "$mainUrl/")).document
                trendDoc.select("a[href*='/movies/'], a[href*='/series/']").forEach { card ->
                    val cardResult = toSearchResult(card)
                    if (cardResult != null && cardResult.name.contains(query, ignoreCase = true)) {
                        results.add(cardResult)
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mapOf("Referer" to "$mainUrl/")).document
        val fullHtml = document.html()

        val rawTitle = document.selectFirst("title")?.text() ?: ""
        val title = rawTitle
            .replace("Dizipal", "", ignoreCase = true)
            .replace("1080P Türkçe Dublaj & Altyazılı izle", "", ignoreCase = true)
            .replace("izle", "", ignoreCase = true)
            .replace("&amp;", "&")
            .trim()
            .ifBlank { "DiziPal" }

        val poster = fixUrlNull(
            document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("img")?.attr("src")
        )

        val description = document.selectFirst("meta[name='description'], meta[property='og:description']")?.attr("content")?.trim()

        if (url.contains("/series/")) {
            // Extract episodes from Next.js state stream
            val epMatches = Regex("""/bolum/([a-zA-Z0-9_-]+-(\d+)x(\d+)[a-zA-Z0-9_-]*)""").findAll(fullHtml)
            val episodes = epMatches.mapNotNull { match ->
                val epSlug = match.groupValues[1]
                val sNum = match.groupValues[2].toIntOrNull() ?: 1
                val epNum = match.groupValues[3].toIntOrNull()
                val epUrl = "$mainUrl/bolum/$epSlug"

                newEpisode(epUrl) {
                    this.name = "$sNum. Sezon $epNum. Bölüm"
                    this.season = sNum
                    this.episode = epNum
                }
            }.distinctBy { it.data }.toList()

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data, headers = mapOf("Referer" to "$mainUrl/")).text

        val ciphertext = Regex("""ciphertext[^a-zA-Z0-9]+([a-zA-Z0-9+/=]+)""").find(html)?.groupValues?.get(1) ?: return false
        val ivHex = Regex("""iv[^a-zA-Z0-9]+([a-f0-9]{32})""").find(html)?.groupValues?.get(1) ?: return false
        val saltHex = Regex("""salt[^a-zA-Z0-9]+([a-f0-9]{256,})""").find(html)?.groupValues?.get(1) ?: return false
        val kList = Regex("""k[^a-zA-Z0-9]+([a-zA-Z0-9]{50,})""").findAll(html).map { it.groupValues[1] }.toList()
        val k = kList.firstOrNull() ?: return false

        val decryptedRaw = try {
            decryptDizipal(ciphertext, ivHex, saltHex, k)
        } catch (e: Exception) {
            return false
        }

        val iframeUrl = if (decryptedRaw.startsWith("//")) "https:$decryptedRaw" else decryptedRaw
        if (!iframeUrl.startsWith("http")) return false

        // Fetch iframe content
        val iframeHtml = app.get(iframeUrl, headers = mapOf("Referer" to "$mainUrl/")).text
        val playlistToken = Regex("""openPlayer\(['"]([^'"]+)['"]""").find(iframeHtml)?.groupValues?.get(1) ?: return false

        val encodedPlaylist = URLEncoder.encode(playlistToken, "UTF-8")
        val iframeUri = URI(iframeUrl)
        val sourceUrl = "${iframeUri.scheme}://${iframeUri.host}/source2.php?v=$encodedPlaylist"

        val sourceJson = app.get(
            sourceUrl,
            headers = mapOf(
                "Referer" to iframeUrl,
                "X-Requested-With" to "XMLHttpRequest"
            )
        ).text

        val mFile = Regex(""""file"\s*:\s*"([^"]+)"""").find(sourceJson)?.groupValues?.get(1)?.replace("\\/", "/") ?: return false
        val masterUrl = mFile.replace("m.php", "master.m3u8")

        val host = iframeUri.host
        callback(
            ExtractorLink(
                source = name,
                name = name,
                url = masterUrl,
                referer = iframeUrl,
                quality = Qualities.P1080.value,
                type = ExtractorLinkType.M3U8,
                headers = mapOf(
                    "Referer" to iframeUrl,
                    "Origin" to "https://$host"
                )
            )
        )

        return true
    }

    private fun decryptDizipal(ciphertext: String, ivHex: String, saltHex: String, k: String): String {
        val salt = hexToByteArray(saltHex)
        val iv = hexToByteArray(ivHex)

        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        val spec = PBEKeySpec(k.toCharArray(), salt, 999, 256)
        val tmp = factory.generateSecret(spec)
        val secretKey = SecretKeySpec(tmp.encoded, "AES")

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))

        val decodedCipher = Base64.decode(ciphertext.replace("\\/", "/"), Base64.DEFAULT)
        val decryptedBytes = cipher.doFinal(decodedCipher)
        return String(decryptedBytes, StandardCharsets.UTF_8).trim()
    }

    private fun hexToByteArray(hex: String): ByteArray {
        val cleanHex = if (hex.length % 2 != 0) "0$hex" else hex
        val bytes = ByteArray(cleanHex.length / 2)
        for (i in bytes.indices) {
            val index = i * 2
            bytes[i] = cleanHex.substring(index, index + 2).toInt(16).toByte()
        }
        return bytes
    }
}
