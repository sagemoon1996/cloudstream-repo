package com.sagemoon1996.arabrunners

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class ArabRunnersProvider : MainAPI() {

    override var mainUrl = "https://arabrunnersteam.org"

    override var name = "Arab Runners Team"

    override var lang = "ar"

    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    private val runningManUrl =
        "$mainUrl/category/%d8%a7%d9%84%d9%83%d9%84/%d8%a7%d9%84%d8%b1%d8%ac%d9%84-%d8%a7%d9%84%d8%ac%d8%a7%d8%b1%d9%8a/"

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = selectFirst("a[href]")?.attr("href") ?: return null

        if (!link.contains(mainUrl)) return null

        val title = selectFirst("h2, h3, .title, .entry-title")?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: text()
                .trim()
                .takeIf { it.isNotBlank() }
                ?: return null

        val poster = selectFirst("img")?.let {
            it.attr("data-src")
                .ifBlank { it.attr("src") }
                .ifBlank { it.attr("data-lazy-src") }
                .takeIf { url -> url.isNotBlank() }
        }

        return if (
            title.contains("الحلقة") ||
            title.contains("الموسم") ||
            title.contains("الرجل الجاري")
        ) {
            newTvSeriesSearchResponse(
                name = title,
                url = link,
                type = TvType.TvSeries,
                posterUrl = poster
            )
        } else {
            newMovieSearchResponse(
                name = title,
                url = link,
                type = TvType.Movie,
                posterUrl = poster
            )
        }
    }

    private fun extractPosts(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val selectors = listOf(
            "article",
            ".post",
            ".item",
            ".post-item",
            ".film-poster-ahref"
        )

        for (selector in selectors) {
            val elements = document.select(selector)
            if (elements.isNotEmpty()) {
                val results = elements.mapNotNull { it.toSearchResponse() }
                if (results.isNotEmpty()) return results
            }
        }

        return document.select("a[href]").mapNotNull { element ->
            val href = element.attr("href")
            val title = element.text().trim()

            if (
                href.startsWith(mainUrl) &&
                title.isNotBlank() &&
                (
                    title.contains("الحلقة") ||
                    title.contains("الرجل الجاري") ||
                    title.contains("كوريا") ||
                    title.contains("ذا زون")
                )
            ) {
                val poster = element.selectFirst("img")?.let {
                    it.attr("data-src")
                        .ifBlank { it.attr("src") }
                        .ifBlank { it.attr("data-lazy-src") }
                }

                newTvSeriesSearchResponse(
                    name = title,
                    url = href,
                    type = TvType.TvSeries,
                    posterUrl = poster
                )
            } else {
                null
            }
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: String
    ): HomePageResponse {
        val document = app.get(request).document

        val posts = extractPosts(document)

        return newHomePageResponse(
            HomePageList(
                name = if (request == mainUrl) {
                    "آخر الحلقات المضافة"
                } else {
                    "الرجل الجاري"
                },
                list = posts
            ),
            hasNext = posts.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(url).document

        return extractPosts(document)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst(
            "h1.entry-title, h1.post-title, h1"
        )?.text()
            ?.trim()
            ?: document.title().substringBefore(" - ").trim()

        val poster = document.selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("img")?.let {
                it.attr("data-src")
                    .ifBlank { it.attr("src") }
                    .ifBlank { it.attr("data-lazy-src") }
            }

        val plot = document.selectFirst(
            "meta[property=og:description]"
        )?.attr("content")

        val iframe = document.selectFirst(
            "iframe[src], iframe[data-src]"
        )?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }

        val playerLink = iframe?.let {
            when {
                it.startsWith("//") -> "https:$it"
                it.startsWith("/") -> "$mainUrl$it"
                else -> it
            }
        }

        val seasonNumber = Regex(
            "(?:الموسم|season)[^0-9]*(\\d+)",
            RegexOption.IGNORE_CASE
        ).find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 1

        val episodeNumber = Regex(
            "(?:الحلقة|episode)[^0-9]*(\\d+)",
            RegexOption.IGNORE_CASE
        ).find(title)?.groupValues?.get(1)?.toIntOrNull()

        if (episodeNumber != null) {
            val episodeUrl = playerLink ?: url

            return newTvSeriesLoadResponse(
                name = title,
                url = url,
                type = TvType.TvSeries,
                episodes = listOf(
                    newEpisode(episodeUrl) {
                        this.name = title
                        this.season = seasonNumber
                        this.episode = episodeNumber
                    }
                ) {
                    this.name = title
                },
                posterUrl = poster,
                plot = plot
            )
        }

        return com.lagradost.cloudstream3.newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.Movie,
            dataUrl = playerLink ?: url,
            posterUrl = poster,
            plot = plot
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (com.lagradost.cloudstream3.LoadedFile) -> Unit
    ): Boolean {
        if (
            data.startsWith("http://") ||
            data.startsWith("https://")
        ) {
            loadExtractor(
                data,
                mainUrl,
                subtitleCallback,
                callback
            )
        }

        return true
    }
}
