package com.turk

import com.lagradost.cloudstream3.*
import org.jsoup.nodes.Document

/** Semantic catalog fallback: no guessing from arbitrary anchors or page-wide images. */
fun MainAPI.schemaCatalog(document: Document): List<SearchResponse> {
    val base = document.location()
    return document.structuredObjects().mapNotNull { item ->
        val types = item.schemaTypes()
        val series = types.any { it in setOf("TVSeries", "CreativeWorkSeries") }
        if (!series && "Movie" !in types) return@mapNotNull null
        val title = item.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
        val url = resolveHttpUrl(item.optString("url"), base) ?: return@mapNotNull null
        if (httpOrigin(url) != httpOrigin(base)) return@mapNotNull null
        val image = when (val raw = item.opt("image")) {
            is String -> raw
            is org.json.JSONObject -> raw.optString("url")
            else -> null
        }
        if (series) newTvSeriesSearchResponse(title, url, TvType.TvSeries) { addPoster(image, base, base) }
        else newMovieSearchResponse(title, url, TvType.Movie) { addPoster(image, base, base) }
    }.distinctBy { it.url }
}

/** Explicit URL profiles; JSON API providers continue to own their request contracts. */
fun schemaSearchUrl(provider: String, base: String, query: String): String? = when (provider) {
    "KultFilmler", "DiziYou", "SetFilmIzle", "RareFilmm", "FilmKovasi" -> "$base/?s=${encodeQuery(query)}"
    "Dizigom" -> "$base/ara?q=${encodeQuery(query)}"
    "DiziGecesi", "FullHDFilmizlesene" -> "$base/arama/${encodePathSegment(query)}"
    else -> null
}
