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

    return when {
        noFragment.startsWith("http://") || noFragment.startsWith("https://") -> noFragment
        noFragment.startsWith("//") -> "https:$noFragment"
        baseUrl != null -> {
            val base = baseUrl.trimEnd('/')
            val path = noFragment.trimStart('/')
            "$base/$path"
        }
        else -> noFragment
    }
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
