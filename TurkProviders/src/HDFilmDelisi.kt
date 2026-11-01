package com.turk

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class HDFilmDelisi : MainAPI() {
    override var mainUrl = "https://hdfilmdelisi.one"
    override var name = "HDFilmDelisi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie)

    private val cfInterceptor = WebViewResolver(Regex(""".*hdfilmdelisi.*"""))

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Eklenen Filmler",
        "$mainUrl/populer" to "En Çok İzlenenler",
        "$mainUrl/imdb-top-250" to "IMDb Top 250",
        "$mainUrl/tur/aksiyon" to "Aksiyon Filmleri",
        "$mainUrl/tur/komedi" to "Komedi Filmleri",
        "$mainUrl/tur/korku" to "Korku Filmleri",
        "$mainUrl/tur/bilim-kurgu" to "Bilim Kurgu Filmleri",
        "$mainUrl/tur/gerilim" to "Gerilim Filmleri",
        "$mainUrl/tur/animasyon" to "Animasyon Filmleri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = request.data
        val document = app.get(
            targetUrl,
            interceptor = cfInterceptor,
            headers = mapOf(
                "User-Agent" to TURK_USER_AGENT,
                "Referer" to "$mainUrl/"
            )
        ).document

        val items = document.select("a[href*='/film/']").mapNotNull { card ->
            toSearchResult(card)
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    private fun toSearchResult(card: Element): SearchResponse? {
        val href = card.attr("href").takeIf { it.contains("/film/") } ?: return null
        val fullUrl = fixUrl(href)

        val img = card.selectFirst("img")
        val rawTitle = img?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: card.selectFirst("h2, h3, .title")?.text()
            ?: return null

        val title = rawTitle
            .replace("izle", "", ignoreCase = true)
            .replace("Full HD", "", ignoreCase = true)
            .replace("Türkçe Dublaj", "", ignoreCase = true)
            .replace("Türkçe Altyazılı", "", ignoreCase = true)
            .replace(Regex("""\(\d{4}\)"""), "")
            .trim()

        return newMovieSearchResponse(title, fullUrl, TvType.Movie) {
            addPoster(img, mainUrl)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.trim().replace(" ", "+")}"
        val document = app.get(
            searchUrl,
            interceptor = cfInterceptor,
            headers = mapOf(
                "User-Agent" to TURK_USER_AGENT,
                "Referer" to "$mainUrl/"
            )
        ).document.cleanNoise()

        val container = document.selectFirst(".search-results, .site-main, #main, #content, .content-area, .items, main") ?: document
        return container.select("a[href*='/film/']").mapNotNull { card ->
            toSearchResult(card)
        }.filter { it.isMatch(query) }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(
            url,
            interceptor = cfInterceptor,
            headers = mapOf(
                "User-Agent" to TURK_USER_AGENT,
                "Referer" to "$mainUrl/"
            )
        ).document

        val rawTitle = document.selectFirst("h1")?.text()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: "Film"

        val title = rawTitle
            .replace("izle", "", ignoreCase = true)
            .replace("Full HD", "", ignoreCase = true)
            .replace("Türkçe Dublaj", "", ignoreCase = true)
            .replace("Türkçe Altyazılı", "", ignoreCase = true)
            .replace(Regex("""\(\d{4}\)"""), "")
            .trim()

        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: cleanPosterUrl(document.selectFirst("img[src*='/posters/'], img"), mainUrl)

        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst("p.description, .overview, .plot")?.text()?.trim()

        val year = Regex("""\b(19\d\d|20\d\d)\b""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            addPoster(poster, mainUrl)
            this.plot = description
            this.year = year
        }
    }

    private fun deobfuscateVixolity(hex: String, shift: Int): String {
        val raw = StringBuilder()
        for (i in 0 until hex.length step 2) {
            val part = hex.substring(i, i + 2)
            raw.append(part.toInt(16).toChar())
        }
        val rev = raw.reverse().toString()
        val decodedBytes = Base64.decode(rev, Base64.DEFAULT)
        val decodedStr = String(decodedBytes, Charsets.ISO_8859_1)
        val sb = StringBuilder()
        for (ch in decodedStr) {
            sb.append((ch.code - shift).toChar())
        }
        return sb.toString()
    }

    private fun unescapeHex(st: String): String {
        val sb = StringBuilder()
        var i = 0
        val len = st.length
        while (i < len) {
            val ch = st[i]
            if (ch == '\\' && i + 1 < len) {
                val next = st[i + 1]
                if (next == 'x' && i + 3 < len) {
                    val hex = st.substring(i + 2, i + 4)
                    sb.append(hex.toInt(16).toChar())
                    i += 4
                    continue
                } else if (next == 'u' && i + 5 < len) {
                    val hex = st.substring(i + 2, i + 6)
                    sb.append(hex.toInt(16).toChar())
                    i += 6
                    continue
                } else {
                    sb.append(next)
                    i += 2
                    continue
                }
            }
            sb.append(ch)
            i++
        }
        return sb.toString()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false

        try {
            val response = app.get(
                data,
                interceptor = cfInterceptor,
                headers = mapOf(
                    "User-Agent" to TURK_USER_AGENT,
                    "Referer" to "$mainUrl/"
                )
            )
            val html = response.text

            // 1. Extract real Vixolity player from Next.js payload
            val videoUrl = Regex("""videoUrl[^:]*:\s*\\*["'](https?://[^"'\\]+)""").find(html)?.groupValues?.get(1)
            if (videoUrl != null) {
                try {
                    val vixDoc = app.get(
                        videoUrl,
                        headers = mapOf(
                            "Referer" to "$mainUrl/",
                            "User-Agent" to TURK_USER_AGENT
                        )
                    ).text

                    val sm = Regex("""\}\)\('([a-f0-9]+)',\s*(\d+)\);""").find(vixDoc)
                    if (sm != null) {
                        val hexStr = sm.groupValues[1]
                        val shift = sm.groupValues[2].toInt()
                        val unpacked = deobfuscateVixolity(hexStr, shift)
                        val fileMatch = Regex("""file:\s*['"]([^'"]+)['"]""").find(unpacked)?.groupValues?.get(1)
                        if (fileMatch != null) {
                            val streamUrl = unescapeHex(fileMatch)
                            val m3u8Links = M3u8Helper.generateM3u8(
                                source = this.name,
                                name = this.name,
                                streamUrl = streamUrl,
                                referer = "https://pl.vixolity.com/",
                                headers = mapOf(
                                    "Referer" to "https://pl.vixolity.com/",
                                    "User-Agent" to TURK_USER_AGENT
                                )
                            )
                            if (m3u8Links.isNotEmpty()) {
                                m3u8Links.forEach { link ->
                                    callback.invoke(link)
                                    foundLinks = true
                                }
                            } else {
                                callback.invoke(
                                    ExtractorLink(
                                        name = "${this.name} HLS",
                                        source = this.name,
                                        url = streamUrl,
                                        referer = "https://pl.vixolity.com/",
                                        quality = Qualities.P1080.value,
                                        type = ExtractorLinkType.M3U8,
                                        headers = mapOf(
                                            "Referer" to "https://pl.vixolity.com/",
                                            "User-Agent" to TURK_USER_AGENT
                                        )
                                    )
                                )
                                foundLinks = true
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore vixolity extraction error
                }
            }

            // 2. Fallback to standard iframes (skip honeypots and ads)
            val doc = Jsoup.parse(html)
            doc.select("iframe").forEach { iframe ->
                val src = fixUrlNull(iframe.attr("src").ifBlank { iframe.attr("data-src") })
                if (src != null && src.startsWith("http") && !src.contains("izlegec.com") && !src.contains("hdfilmdelisi.one/embed/")) {
                    val loaded = loadExtractor(src, "$mainUrl/", subtitleCallback, callback)
                    if (loaded) {
                        foundLinks = true
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }

        return foundLinks
    }
}
