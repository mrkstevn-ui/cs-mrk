package com.turk

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.util.Collections
import java.util.concurrent.CancellationException

/** Existing site adapters own their protocols. The boundary owns result validity. */
abstract class AdaptiveProvider : MainAPI() {
    protected abstract suspend fun resolveLinks(data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean
    protected abstract suspend fun searchProvider(query: String): List<SearchResponse>?
    protected abstract suspend fun loadProvider(url: String): LoadResponse?
    protected abstract suspend fun mainPageProvider(page: Int, request: MainPageRequest): HomePageResponse?

    protected open fun fallbackPage(data: String): String? = resolveHttpUrl(data)
        ?.takeIf { httpOrigin(it) == httpOrigin(mainUrl) }

    protected fun reportStage(stage: String, error: Exception? = null) {
        // Deliberately omit exception messages and URLs: they can contain signed tokens/cookies.
        Log.w("TurkProviders", "$name [$stage]${error?.let { ": ${it.javaClass.simpleName}" }.orEmpty()}")
    }

    final override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val results = try { searchProvider(query).orEmpty() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { reportStage("search", e); emptyList() }
        val valid = results.filter { it.name.isNotBlank() && resolveHttpUrl(it.url) != null }.distinctBy { it.url }
        if (valid.isNotEmpty()) return valid
        val fallbackUrl = schemaSearchUrl(javaClass.simpleName, mainUrl, query)
        if (fallbackUrl != null) {
            try {
                val doc = app.get(fallbackUrl, referer = "$mainUrl/", timeout = 10L).document.requireContent(name)
                val fallback = schemaCatalog(doc).filter { it.isMatch(query) }
                if (fallback.isNotEmpty()) return fallback
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { reportStage("schema-search", e) }
        }
        reportStage("search-empty")
        return emptyList()
    }

    final override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val result = try { mainPageProvider(page, request) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { reportStage("catalog", e); throw e }
        result?.items?.forEach { row ->
            row.list = row.list.filter { it.name.isNotBlank() && resolveHttpUrl(it.url) != null }.distinctBy { it.url }
        }
        if (result?.items?.any { it.list.isNotEmpty() } != true && page == 1 && TvType.Live !in supportedTypes) {
            try {
                val target = request.data.removeSuffix("/page/")
                if (resolveHttpUrl(target) != null && !target.endsWith("=")) {
                    val doc = app.get(target, referer = "$mainUrl/", timeout = 10L).document.requireContent(name)
                    val fallback = schemaCatalog(doc)
                    if (fallback.isNotEmpty()) return newHomePageResponse(request.name, fallback, hasNext = false)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { reportStage("schema-catalog", e) }
        }
        if (result?.items?.any { it.list.isNotEmpty() } != true) reportStage("catalog-empty")
        return result
    }

    final override suspend fun load(url: String): LoadResponse? {
        val result = try { loadProvider(url) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { reportStage("detail", e); throw e }
        if (result == null) reportStage("detail-empty")
        if (result is TvSeriesLoadResponse && result.episodes.isEmpty() && !result.comingSoon) {
            reportStage("episodes-empty")
            throw IllegalStateException("$name: series returned no explicit episodes")
        }
        return result
    }

    final override suspend fun loadLinks(data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val media = Collections.synchronizedSet(mutableSetOf<String>())
        val subtitles = Collections.synchronizedSet(mutableSetOf<String>())
        val emit: (ExtractorLink) -> Unit = { link ->
            // Keep original objects and headers: DRM/extractor-specific data and signed URLs survive.
            if (resolveHttpUrl(link.url) != null && media.add("${link.url}|${link.referer}|${link.headers.toSortedMap()}")) callback(link)
        }
        val emitSubtitle: (SubtitleFile) -> Unit = { sub ->
            if (resolveHttpUrl(sub.url) != null && subtitles.add("${sub.lang}|${sub.url}")) subtitleCallback(sub)
        }
        try { resolveLinks(data, isCasting, emitSubtitle, emit) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { reportStage("site-resolver", e) }
        if (media.isEmpty()) {
            val page = fallbackPage(data)
            if (page != null) {
                try { HtmlMediaResolver(name).resolve(page, "$mainUrl/", emitSubtitle, emit) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { reportStage("fallback-resolver", e) }
            }
        }
        if (media.isEmpty()) reportStage("no-media-produced")
        return media.isNotEmpty()
    }
}
