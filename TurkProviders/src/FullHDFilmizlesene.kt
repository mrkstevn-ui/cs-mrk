package com.turk

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.nio.charset.StandardCharsets

class FullHDFilmizlesene : MainAPI() {
    override var mainUrl = "https://www.fullhdfilmizlesene.now"
    override var name = "FullHDFilmizlesene"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/en-cok-izlenen-filmler" to "En Çok İzlenenler",
        "$mainUrl/populer-filmler" to "Popüler Filmler",
        "$mainUrl/trend-filmler" to "Trend Filmler",
        "$mainUrl/filmizle/imdb-puani-yuksek-filmler" to "IMDb Yüksek Filmler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (page <= 1) {
            request.data
        } else {
            "${request.data}/$page"
        }

        val document = app.get(targetUrl, headers = mapOf("Referer" to "$mainUrl/")).document
        val items = document.select("li.film").mapNotNull { card ->
            toSearchResult(card)
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    private fun toSearchResult(card: Element): SearchResponse? {
        val linkEl = card.selectFirst("a.tt") ?: card.selectFirst("a")
        val href = fixUrlNull(linkEl?.attr("href")) ?: return null
        if (!href.contains("/film/")) return null

        val title = card.selectFirst(".film-title")?.text()?.trim()
            ?: linkEl?.text()?.replace("izle", "", ignoreCase = true)?.trim()
            ?: return null

        val img = card.selectFirst("img")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            addPoster(img, mainUrl)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = query.trim().replace(" ", "+")
        val searchUrl = "$mainUrl/arama/$encoded"
        val document = app.get(searchUrl, headers = mapOf("Referer" to "$mainUrl/")).document

        return document.select("li.film").mapNotNull { card ->
            toSearchResult(card)
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mapOf("Referer" to "$mainUrl/")).document

        val title = document.selectFirst("h1, .film-title, meta[property='og:title']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }?.replace("Film izle", "", ignoreCase = true)
            ?.replace("izle", "", ignoreCase = true)
            ?.trim() ?: "Bilinmeyen Film"

        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: cleanPosterUrl(document.selectFirst(".ply-cover img, .afis img, img"), mainUrl)

        val description = document.selectFirst("meta[property='og:description'], meta[name='description']")?.attr("content")?.trim()
            ?: document.selectFirst(".film-ozeti, .ozet")?.text()?.trim()

        val year = document.selectFirst(".film-yil, .yil")?.text()?.filter { it.isDigit() }?.toIntOrNull()

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
        val filmPage = app.get(data, headers = mapOf("Referer" to "$mainUrl/")).text

        // Extract tokens from var scx = {...}
        val scxMatch = Regex("""var\s+scx\s*=\s*(\{.*?\});""", setOf(RegexOption.DOT_MATCHES_ALL)).find(filmPage)?.groupValues?.get(1)
        val tokens = if (scxMatch != null) {
            Regex(""""([a-zA-Z0-9+/=]{20,})"""").findAll(scxMatch).map { it.groupValues[1] }.toList()
        } else {
            emptyList()
        }

        var foundLinks = false

        for (token in tokens) {
            try {
                val decodedEmbedUrl = rot13AndBase64(token)
                if (!decodedEmbedUrl.startsWith("http")) continue

                if (decodedEmbedUrl.contains("rapidvid", ignoreCase = true)) {
                    val rapidvidHtml = app.get(decodedEmbedUrl, headers = mapOf("Referer" to "$mainUrl/")).text
                    val cipherMatch = Regex("""av\(['"]([^'"]+)['"]\)""").find(rapidvidHtml)?.groupValues?.get(1)
                    if (cipherMatch != null) {
                        val m3u8Url = deobfuscateRapidvid(cipherMatch)
                        if (m3u8Url.startsWith("http")) {
                            callback(
                                ExtractorLink(
                                    source = name,
                                    name = "$name RapidVid 1080p",
                                    url = m3u8Url,
                                    referer = decodedEmbedUrl,
                                    quality = Qualities.P1080.value,
                                    type = ExtractorLinkType.M3U8,
                                    headers = mapOf(
                                        "Referer" to "https://rapidvid.org/",
                                        "Origin" to "https://rapidvid.org"
                                    )
                                )
                            )
                            foundLinks = true
                        }
                    }
                } else {
                    val loaded = loadExtractor(decodedEmbedUrl, "$mainUrl/", subtitleCallback, callback)
                    if (loaded) {
                        foundLinks = true
                    }
                }
            } catch (e: Exception) {
                // Continue to next alternative
            }
        }

        return foundLinks
    }

    private fun rot13AndBase64(input: String): String {
        val rot = input.map { c ->
            when (c) {
                in 'a'..'z' -> if (c < 'n') c + 13 else c - 13
                in 'A'..'Z' -> if (c < 'N') c + 13 else c - 13
                else -> c
            }
        }.joinToString("")
        val decodedBytes = Base64.decode(rot, Base64.DEFAULT)
        return String(decodedBytes, StandardCharsets.UTF_8).trim()
    }

    private fun deobfuscateRapidvid(cipher: String): String {
        val reversed = cipher.reversed()
        val step1Bytes = Base64.decode(reversed, Base64.DEFAULT)
        val step1Str = String(step1Bytes, StandardCharsets.ISO_8859_1)

        val key = "K9L"
        val sb = StringBuilder()
        for (i in step1Str.indices) {
            val r = key[i % 3].code
            val shift = (r % 5) + 1
            val n = step1Str[i].code - shift
            sb.append(n.toChar())
        }

        val step2Bytes = Base64.decode(sb.toString(), Base64.DEFAULT)
        return String(step2Bytes, StandardCharsets.UTF_8).trim()
    }
}
