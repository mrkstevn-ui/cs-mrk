package com.turk

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class YabanciDizi : AdaptiveProvider() {
    override var mainUrl = "https://yabancidizi.news"
    override var name = "YabanciDizi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val cfInterceptor = WebViewResolver(Regex(""".*yabancidizi.*"""))

    override val mainPage = mainPageOf(
        "$mainUrl/dizi/tur/aile-izle" to "Aile",
        "$mainUrl/dizi/tur/aksiyon-izle-1" to "Aksiyon",
        "$mainUrl/dizi/tur/bilim-kurgu-izle-1" to "Bilim Kurgu",
        "$mainUrl/dizi/tur/dram-izle" to "Dram",
        "$mainUrl/dizi/tur/komedi-izle" to "Komedi",
        "$mainUrl/dizi/tur/korku-izle" to "Korku",
        "$mainUrl/dizi/tur/suc" to "Suç",
        "$mainUrl/dizi/tur/kore-dizileri" to "Kore Dizileri"
    )

    override suspend fun mainPageProvider(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = "${request.data}/$page"
        val document = app.get(
            targetUrl,
            interceptor = cfInterceptor,
            headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to TURK_USER_AGENT)
        ).document.requireContent(name)

        val items = document.select("div.mofy-movbox").mapNotNull { card ->
            toSearchResult(card)
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    private fun toSearchResult(card: Element): SearchResponse? {
        val title = card.selectFirst("div.mofy-movbox-text a")?.text()?.trim() ?: return null
        val link = card.selectFirst("a")?.attr("href") ?: return null
        val href = fixUrlNull(link) ?: return null
        val posterUrl = cleanPosterUrl(card.selectFirst("img"), mainUrl)

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            addPoster(posterUrl, mainUrl)
        }
    }

    data class ResultItem(
        @JsonProperty("s_type") val sType: String? = null,
        @JsonProperty("s_link") val sLink: String? = null,
        @JsonProperty("s_name") val sName: String? = null,
        @JsonProperty("s_image") val sImage: String? = null,
        @JsonProperty("s_year") val sYear: String? = null
    )

    data class SearchData(
        @JsonProperty("result") val result: List<ResultItem> = emptyList(),
        @JsonProperty("type") val type: String? = null
    )

    data class JsonSearchResponse(
        @JsonProperty("success") val success: Int = 0,
        @JsonProperty("data") val data: SearchData? = null
    )

    data class AjaxSeries(
        @JsonProperty("data") val data: String = "",
        @JsonProperty("success") val success: Int = 0
    )

    override suspend fun searchProvider(query: String): List<SearchResponse> {
        val response = app.post(
            "$mainUrl/search?qr=${encodeQuery(query)}",
            interceptor = cfInterceptor,
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "$mainUrl/",
                "User-Agent" to TURK_USER_AGENT
            )
        )

        val parsed = response.parsedSafe<JsonSearchResponse>() ?: return emptyList()
        if (parsed.success != 1 || parsed.data == null) return emptyList()

        val results = mutableListOf<SearchResponse>()
        for (item in parsed.data.result) {
            val title = item.sName?.trim() ?: continue
            val sLink = item.sLink?.trim() ?: continue
            val posterUrl = item.sImage?.let { "$mainUrl/uploads/series/$it" } ?: ""

            if (item.sType == "1") {
                val href = fixUrlNull("$mainUrl/film/$sLink") ?: continue
                results.add(newMovieSearchResponse(title, href, TvType.Movie) {
                    addPoster(posterUrl, mainUrl)
                })
            } else {
                val href = fixUrlNull("$mainUrl/dizi/$sLink") ?: continue
                results.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    addPoster(posterUrl, mainUrl)
                })
            }
        }

        return results.filter { it.isMatch(query) }.distinctBy { it.url }
    }

    override suspend fun loadProvider(url: String): LoadResponse {
        val document = app.get(
            url,
            interceptor = cfInterceptor,
            headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to TURK_USER_AGENT)
        ).document.requireContent(name)

        val title = document.selectFirst("h1.page-title")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore(" izle")?.trim()
            ?: "Yabancı Dizi"

        val poster = cleanPosterUrl(document.selectFirst("div#series-profile-wrapper img"), mainUrl)
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")
        val year = document.selectFirst("h1 span")?.text()?.substringAfter("(")?.substringBefore(")")?.toIntOrNull()
        val description = document.selectFirst("div.series-summary-wrapper p")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")
        val tags = document.select("div.ui.list a").filter { !it.attr("href").contains("/oyuncu/") }.map { it.text().trim() }

        if (url.contains("/dizi/")) {
            val episodes = mutableListOf<Episode>()
            document.select("div.tabular-content").forEach { tab ->
                val epSeason = tab.parent()?.attr("data-season")?.toIntOrNull() ?: 1
                var epIndex = 0
                tab.select("div.item").forEach { epEl ->
                    val epHref = fixUrlNull(epEl.selectFirst("h6 a, a")?.attr("href")) ?: return@forEach
                    epIndex++
                    episodes.add(
                        newEpisode(epHref) {
                            this.name = "$epSeason. Sezon $epIndex. Bölüm"
                            this.season = epSeason
                            this.episode = epIndex
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
                addPoster(poster, mainUrl)
                this.year = year
                this.plot = description
                this.tags = tags
            }.withMetadata(document)
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                addPoster(poster, mainUrl)
                this.year = year
                this.plot = description
                this.tags = tags
            }.withMetadata(document)
        }
    }

    override suspend fun resolveLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(
            data,
            interceptor = cfInterceptor,
            headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to TURK_USER_AGENT)
        ).document.requireContent(name)

        var foundAny = false
        val timestamp = System.currentTimeMillis() - 50000

        val tabs = document.select("div#series-tabs a")
        val safeTabs = if (tabs.isEmpty()) document.select("a[data-eid]") else tabs

        for (tab in safeTabs) {
            val dataEid = tab.attr("data-eid").takeIf { it.isNotBlank() } ?: continue
            val dataType = tab.attr("data-type").ifBlank { "1" }
            val dilAd = if (dataType == "2") "Dublaj" else "Altyazı"

            val ajaxResp = try {
                app.post(
                    "$mainUrl/ajax/service",
                    interceptor = cfInterceptor,
                    referer = data,
                    headers = mapOf(
                        "User-Agent" to TURK_USER_AGENT,
                        "Accept" to "application/json, text/javascript, */*; q=0.01",
                        "Cookie" to "udys=$timestamp",
                        "X-Requested-With" to "XMLHttpRequest"
                    ),
                    data = mapOf("lang" to dataType, "episode" to dataEid, "type" to "langTab")
                ).parsedSafe<AjaxSeries>()
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                reportStage("adapter", e)
                null
            } ?: continue

            val doc = Jsoup.parse(ajaxResp.data)
            for (item in doc.select("div.item")) {
                val itemName = item.text().trim()
                val dataLink = item.attr("data-link").trim()
                if (dataLink.isBlank()) continue
                val safeLink = dataLink.replace("/", "_").replace("+", "-")

                try {
                    when {
                        itemName.contains("VidMoly", ignoreCase = true) -> {
                            val vdmDoc = app.get(
                                "$mainUrl/api/moly/$safeLink",
                                interceptor = cfInterceptor,
                                referer = "$mainUrl/",
                                headers = mapOf("User-Agent" to TURK_USER_AGENT)
                            ).document
                            val iframe = resolveHttpUrl(vdmDoc.selectFirst("iframe")?.attr("src"), "$mainUrl/")
                            if (!iframe.isNullOrBlank()) {
                                loadExtractor(iframe, "$mainUrl/", subtitleCallback) { link ->
                                    foundAny = true
                                    callback(link.withPrefix(dilAd))
                                }
                            }
                        }
                        itemName.contains("Okru", ignoreCase = true) || itemName.contains("Ok.ru", ignoreCase = true) -> {
                            val okrDoc = app.get(
                                "$mainUrl/api/ruplay/$safeLink",
                                interceptor = cfInterceptor,
                                referer = "$mainUrl/",
                                headers = mapOf("User-Agent" to TURK_USER_AGENT)
                            ).document
                            val iframe = resolveHttpUrl(okrDoc.selectFirst("iframe")?.attr("src"), "$mainUrl/")
                            if (!iframe.isNullOrBlank()) {
                                loadExtractor(iframe, "$mainUrl/", subtitleCallback) { link ->
                                    foundAny = true
                                    callback(link.withPrefix(dilAd))
                                }
                            }
                        }
                        itemName.contains("Mac", ignoreCase = true) || itemName.contains("Drive", ignoreCase = true) -> {
                            val macDoc = app.get(
                                "$mainUrl/api/drive/$safeLink",
                                interceptor = cfInterceptor,
                                referer = "$mainUrl/",
                                headers = mapOf("User-Agent" to TURK_USER_AGENT)
                            ).document
                            val iframe = resolveHttpUrl(macDoc.selectFirst("iframe")?.attr("src"), "$mainUrl/")
                            if (!iframe.isNullOrBlank()) {
                                if (decryptAndEmitMac(iframe, dilAd, callback)) {
                                    foundAny = true
                                }
                            }
                        }
                        else -> {
                            val fallbackDoc = app.get(
                                "$mainUrl/api/drive/$safeLink",
                                interceptor = cfInterceptor,
                                referer = "$mainUrl/",
                                headers = mapOf("User-Agent" to TURK_USER_AGENT)
                            ).document
                            val iframe = resolveHttpUrl(fallbackDoc.selectFirst("iframe")?.attr("src"), "$mainUrl/")
                            if (!iframe.isNullOrBlank()) {
                                loadExtractor(iframe, "$mainUrl/", subtitleCallback) { link ->
                                    foundAny = true
                                    callback(link.withPrefix(dilAd))
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException) throw e
                    reportStage("adapter", e)
                }
            }
        }

        return foundAny
    }

    private suspend fun decryptAndEmitMac(
        subFrame: String,
        dilAd: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val iDoc = app.get(
                subFrame,
                interceptor = cfInterceptor,
                referer = "$mainUrl/",
                headers = mapOf("User-Agent" to TURK_USER_AGENT)
            ).text

            val cryptData = Regex("""CryptoJS\.AES\.decrypt\("([^"]+)""").find(iDoc)?.groupValues?.get(1) ?: return false
            val cryptPass = Regex("""CryptoJS\.AES\.decrypt\("[^"]+",\s*"([^"]+)""").find(iDoc)?.groupValues?.get(1) ?: return false

            val decrypted = decryptCryptoJs(cryptPass, cryptData) ?: return false
            val vidUrl = Regex("""file:\s*['"]([^'"]+)['"]""").find(decrypted)?.groupValues?.get(1) ?: return false

            callback(
                newExtractorLink(
                    source = "$dilAd - $name",
                    name = "$dilAd - $name",
                    url = vidUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("User-Agent" to TURK_USER_AGENT, "Referer" to mainUrl)
                }
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        fun decryptCryptoJs(password: String, ciphertextBase64: String): String? {
            return try {
                val raw = Base64.decode(ciphertextBase64, Base64.DEFAULT)
                if (raw.size < 16) return null
                val prefix = String(raw, 0, 8, Charsets.US_ASCII)
                val salt = if (prefix == "Salted__") raw.copyOfRange(8, 16) else ByteArray(8)
                val cipherBytes = if (prefix == "Salted__") raw.copyOfRange(16, raw.size) else raw

                val md5 = MessageDigest.getInstance("MD5")
                val key = ByteArray(32)
                val iv = ByteArray(16)
                var currentHash = ByteArray(0)
                val passBytes = password.toByteArray(Charsets.UTF_8)
                val totalNeeded = key.size + iv.size
                val keyAndIv = ByteArray(totalNeeded)
                var generated = 0

                while (generated < totalNeeded) {
                    md5.reset()
                    if (currentHash.isNotEmpty()) md5.update(currentHash)
                    md5.update(passBytes)
                    md5.update(salt)
                    currentHash = md5.digest()
                    val toCopy = minOf(currentHash.size, totalNeeded - generated)
                    System.arraycopy(currentHash, 0, keyAndIv, generated, toCopy)
                    generated += toCopy
                }

                System.arraycopy(keyAndIv, 0, key, 0, 32)
                System.arraycopy(keyAndIv, 32, iv, 0, 16)

                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }
    }
}
