package com.turk

import com.lagradost.cloudstream3.*
import org.jsoup.nodes.Element

const val TURK_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

fun getPosterHeaders(mainUrl: String): Map<String, String> {
    val cleanRef = if (mainUrl.endsWith("/")) mainUrl else "$mainUrl/"
    return mapOf(
        "User-Agent" to TURK_USER_AGENT,
        "Referer" to cleanRef,
        "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
    )
}

fun fixPosterUrl(rawUrl: String?, baseUrl: String? = null): String? {
    if (rawUrl.isNullOrBlank()) return null
    val trimmed = rawUrl.trim()
    if (trimmed.startsWith("data:")) return null

    val firstUrl = if (trimmed.contains(",")) {
        trimmed.substringBefore(",").substringBefore(" ").trim()
    } else {
        trimmed.substringBefore(" ").trim()
    }

    val noFragment = firstUrl.substringBefore("#").trim()
    if (noFragment.isBlank() || noFragment.startsWith("data:")) return null

    return resolveHttpUrl(noFragment, baseUrl)
}

fun cleanPosterUrl(img: Element?, baseUrl: String? = null): String? {
    if (img == null) return null
    val candidates = listOf(
        img.attr("data-src"),
        img.attr("data-original"),
        img.attr("data-lazy-src"),
        img.attr("data-srcset"),
        img.attr("srcset"),
        img.attr("src")
    )
    val raw = candidates.firstOrNull {
        it.isNotBlank() && !it.startsWith("data:")
    } ?: return null

    return fixPosterUrl(raw, baseUrl)
}

fun SearchResponse.addPoster(rawUrl: String?, referer: String, baseUrl: String? = null) {
    val clean = fixPosterUrl(rawUrl, baseUrl ?: referer)
    this.posterUrl = clean
    if (!clean.isNullOrBlank()) {
        this.posterHeaders = getPosterHeaders(referer)
    }
}

fun SearchResponse.addPoster(img: Element?, referer: String, baseUrl: String? = null) {
    val clean = cleanPosterUrl(img, baseUrl ?: referer)
    this.posterUrl = clean
    if (!clean.isNullOrBlank()) {
        this.posterHeaders = getPosterHeaders(referer)
    }
}

fun LoadResponse.addPoster(rawUrl: String?, referer: String, baseUrl: String? = null) {
    val clean = fixPosterUrl(rawUrl, baseUrl ?: referer)
    this.posterUrl = clean
    if (!clean.isNullOrBlank()) {
        this.posterHeaders = getPosterHeaders(referer)
    }
}

fun LoadResponse.addPoster(img: Element?, referer: String, baseUrl: String? = null) {
    val clean = cleanPosterUrl(img, baseUrl ?: referer)
    this.posterUrl = clean
    if (!clean.isNullOrBlank()) {
        this.posterHeaders = getPosterHeaders(referer)
    }
}

fun normalizeForSearch(text: String): String {
    return text.lowercase(java.util.Locale("tr", "TR"))
        .replace('ı', 'i')
        .replace('ğ', 'g')
        .replace('ü', 'u')
        .replace('ş', 's')
        .replace('ö', 'o')
        .replace('ç', 'c')
        .replace(Regex("[^a-z0-9]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

fun matchesQuery(text: String?, query: String): Boolean {
    if (text.isNullOrBlank()) return false
    val q = normalizeForSearch(query)
    if (q.isBlank()) return true
    val t = normalizeForSearch(text)
    if (t.isBlank()) return false

    // 1. Direct or compacted containment
    if (t.contains(q)) return true
    val tCompacted = t.replace(" ", "")
    val qCompacted = q.replace(" ", "")
    if (tCompacted.contains(qCompacted)) return true

    // 2. Token-based check
    val qTokens = q.split(" ").filter { it.isNotBlank() }
    val stopwords = setOf("bir", "ve", "ile", "icin", "the", "and", "for", "der", "die", "das")
    val significantTokens = qTokens.filter { it.length >= 3 && it !in stopwords }

    if (significantTokens.isNotEmpty()) {
        val tTokens = t.split(" ").filter { it.isNotBlank() }
        val matches = significantTokens.count { sToken ->
            tTokens.any { tToken ->
                tToken == sToken ||
                (sToken.length >= 4 && tToken.contains(sToken)) ||
                (tToken.length >= 4 && sToken.contains(tToken))
            }
        }
        return matches == significantTokens.size
    }

    // For short queries (e.g. "It", "Up", "21")
    val tWords = t.split(" ")
    return qTokens.any { it in tWords }
}

fun isSearchMatch(title: String?, url: String?, query: String): Boolean {
    return matchesQuery(title, query) || matchesQuery(url, query)
}

fun SearchResponse.isMatch(query: String): Boolean {
    return isSearchMatch(this.name, this.url, query)
}

fun org.jsoup.nodes.Document.cleanNoise(): org.jsoup.nodes.Document {
    this.select("aside, .sidebar, #sidebar, .widget, .widget-area, .popular, .trend, .trends, .trending, .slider, .featured-slider, header, footer").remove()
    return this
}
