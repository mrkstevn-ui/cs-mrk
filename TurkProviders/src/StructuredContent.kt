package com.turk

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.nodes.Document

/** Only traverse semantic containers, never arbitrary script variables or advertisements. */
fun Document.structuredObjects(): List<JSONObject> {
    val result = mutableListOf<JSONObject>()
    fun collect(value: Any?, depth: Int) {
        if (depth > 8 || result.size >= 200) return
        when (value) {
            is JSONArray -> for (i in 0 until minOf(value.length(), 200)) collect(value.opt(i), depth + 1)
            is JSONObject -> {
                result.add(value)
                for (key in listOf("@graph", "mainEntity", "itemListElement", "item", "episode", "containsSeason")) {
                    collect(value.opt(key), depth + 1)
                }
            }
        }
    }
    select("script[type=application/ld+json]").take(20).forEach { script ->
        val text = script.data()
        if (text.length <= 512_000) {
            try { collect(JSONTokener(text).nextValue(), 0) } catch (_: Exception) { /* Try next block. */ }
        }
    }
    return result
}

fun JSONObject.schemaTypes(): Set<String> {
    val value = opt("@type")
    return when (value) {
        is JSONArray -> (0 until value.length()).map { value.optString(it).substringAfterLast('/') }.toSet()
        is String -> setOf(value.substringAfterLast('/'))
        else -> emptySet()
    }
}

private fun JSONObject.nonBlank(key: String): String? = optString(key).takeIf { it.isNotBlank() && it != "null" }

private fun imageValue(value: Any?): String? = when (value) {
    is String -> value.takeIf { it.isNotBlank() }
    is JSONObject -> value.nonBlank("url") ?: value.nonBlank("contentUrl")
    is JSONArray -> (0 until value.length()).firstNotNullOfOrNull { imageValue(value.opt(it)) }
    else -> null
}

data class ContentMetadata(
    val title: String?, val poster: String?, val description: String?, val year: Int?, val types: Set<String>
)

fun Document.contentMetadata(): ContentMetadata {
    val canonical = resolveHttpUrl(selectFirst("link[rel=canonical]")?.attr("href"), location()) ?: location()
    fun samePage(raw: String?): Boolean = resolveHttpUrl(raw, location())?.substringBefore('#')?.trimEnd('/') == canonical.substringBefore('#').trimEnd('/')
    val candidates = structuredObjects().filter {
        it.schemaTypes().any { type -> type in setOf("Movie", "TVSeries", "TVEpisode", "CreativeWorkSeries") }
    }
    // Prefer an entity explicitly belonging to this page over catalog/recommendation records.
    val obj = candidates.firstOrNull { samePage(it.nonBlank("url")) || samePage(it.nonBlank("@id")) }
        ?: candidates.firstOrNull { it.nonBlank("url") == null && it.nonBlank("@id") == null }
    fun meta(selector: String) = selectFirst(selector)?.attr("content")?.takeIf { it.isNotBlank() }
    val title = obj?.nonBlank("name") ?: meta("meta[property=og:title]")
        ?: selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
    val poster = imageValue(obj?.opt("image")) ?: meta("meta[property=og:image]")
    val date = obj?.nonBlank("datePublished") ?: meta("meta[property=video:release_date]")
    return ContentMetadata(
        title, resolveHttpUrl(poster, location()),
        obj?.nonBlank("description") ?: meta("meta[property=og:description]") ?: meta("meta[name=description]"),
        date?.take(4)?.toIntOrNull()?.takeIf { it in 1888..2100 }, obj?.schemaTypes().orEmpty()
    )
}

fun Document.requireContent(provider: String): Document {
    val titleText = title().lowercase()
    if (selectFirst("#challenge-running, #challenge-form, script[src*='/cdn-cgi/challenge-platform/']") != null ||
        titleText in setOf("just a moment...", "attention required! | cloudflare", "verify you are human")) {
        throw IllegalStateException("$provider: access challenge; content was not returned")
    }
    return this
}

/** Fill missing fields only; preserve the site's own, more specific parsing. */
fun <T : com.lagradost.cloudstream3.LoadResponse> T.withMetadata(document: Document): T {
    val metadata = document.contentMetadata()
    if (name.isBlank() || name.startsWith("Bilinmeyen") || name == "Film" || name == "Rare Film") {
        name = metadata.title ?: throw IllegalStateException("Missing content title")
    }
    if (posterUrl.isNullOrBlank()) {
        posterUrl = metadata.poster
        posterHeaders = getPosterHeaders(document.location())
    } else {
        posterUrl = resolveHttpUrl(posterUrl, document.location())
    }
    if (plot.isNullOrBlank()) plot = metadata.description
    if (year == null) year = metadata.year
    // A changed episode selector must not turn a known series into a movie.
    if (this is com.lagradost.cloudstream3.MovieLoadResponse && "TVSeries" in metadata.types) {
        throw IllegalStateException("Series metadata returned without an episode adapter result")
    }
    return this
}

data class EpisodeRecord(val url: String, val title: String, val season: Int?, val number: Int?)

fun Document.episodeRecords(seriesUrl: String): List<EpisodeRecord> {
    val slug = seriesUrl.substringBefore('?').trimEnd('/').substringAfterLast('/')
    val records = mutableListOf<EpisodeRecord>()
    val ownedEpisodePath = Regex("^" + Regex.escape(slug) + """-(?:\d+-sezon-\d+-bolum|ozel-bolum(?:-\d+)?)(?:/|\.html)?$""")
    select("a[href*='/bolum/']").forEach { link ->
        val url = resolveHttpUrl(link.attr("href"), location()) ?: return@forEach
        if (httpOrigin(url) != httpOrigin(seriesUrl)) return@forEach
        val path = java.net.URI(url).path
        if (!ownedEpisodePath.matches(path.substringAfter("/bolum/", ""))) return@forEach
        val match = Regex("""(\d+)-sezon-(\d+)-bolum""").find(path)
        records.add(EpisodeRecord(url, link.text().trim().ifBlank { "Bölüm" },
            match?.groupValues?.get(1)?.toIntOrNull(), match?.groupValues?.get(2)?.toIntOrNull()))
    }
    // Explicit semantic episode records are accepted, never expanded from a total count.
    structuredObjects().filter { "TVEpisode" in it.schemaTypes() }.forEach { item ->
        val url = resolveHttpUrl(item.optString("url"), location()) ?: return@forEach
        val series = item.optJSONObject("partOfSeries")?.optString("url")
        if (httpOrigin(url) != httpOrigin(seriesUrl)) return@forEach
        if (!series.isNullOrBlank() && resolveHttpUrl(series, location())?.trimEnd('/') != seriesUrl.trimEnd('/')) return@forEach
        if (series.isNullOrBlank() && !ownedEpisodePath.matches(java.net.URI(url).path.substringAfter("/bolum/", ""))) return@forEach
        val season = item.optJSONObject("partOfSeason")?.optString("seasonNumber")?.toIntOrNull()
        records.add(EpisodeRecord(url, item.optString("name").ifBlank { "Bölüm" }, season,
            item.optString("episodeNumber").toIntOrNull()))
    }
    return records.distinctBy { it.url }.sortedWith(compareBy({ it.season ?: Int.MAX_VALUE }, { it.number ?: Int.MAX_VALUE }))
}

/** Retain provenance: catalog ItemLists are never a source of playable media. */
fun Document.primaryVideoObjects(): List<JSONObject> {
    val canonical = resolveHttpUrl(selectFirst("link[rel=canonical]")?.attr("href"), location()) ?: location()
    fun belongsToPage(obj: JSONObject): Boolean {
        return listOfNotNull(obj.nonBlank("url"), obj.nonBlank("@id")).any {
            resolveHttpUrl(it, location())?.substringBefore('#')?.trimEnd('/') == canonical.substringBefore('#').trimEnd('/')
        }
    }
    val result = mutableListOf<JSONObject>()
    fun collect(value: Any?, depth: Int, graph: Boolean) {
        if (depth > 5 || result.size >= 8) return
        when (value) {
            is JSONArray -> for (i in 0 until minOf(value.length(), 100)) collect(value.opt(i), depth + 1, true)
            is JSONObject -> {
                val identity = value.nonBlank("url") ?: value.nonBlank("@id")
                val primary = belongsToPage(value) || (!graph && identity == null)
                if (primary && "VideoObject" in value.schemaTypes()) result.add(value)
                // Follow explicit mainEntity only; Movie.video commonly describes a trailer.
                if (primary || "WebPage" in value.schemaTypes() && belongsToPage(value)) {
                    collect(value.opt("mainEntity"), depth + 1, false)
                }
                collect(value.opt("@graph"), depth + 1, true)
            }
        }
    }
    select("script[type=application/ld+json]").take(20).forEach {
        val data = it.data()
        if (data.length <= 512_000) try { collect(JSONTokener(data).nextValue(), 0, false) } catch (_: Exception) { }
    }
    return result
}
