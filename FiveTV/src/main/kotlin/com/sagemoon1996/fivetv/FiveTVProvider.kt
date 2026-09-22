package com.sagemoon1996.fivetv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

class FiveTVProvider : MainAPI() {

    override var mainUrl = "https://new61.5tv.lol"
    override var name = "FiveTV"
    override var lang = "ar"

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encodedQuery").document

        return document
            .select("a[href*='/series/'], a[href*='/movie/']")
            .mapNotNull { link ->
                val href = link.attr("href").trim()
                if (href.isBlank()) return@mapNotNull null

                val title = link.selectFirst("img")?.attr("alt")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: link.text().trim().takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val poster = link.selectFirst("img")?.attr("src")
                    ?.takeIf { it.isNotBlank() }

                val type = if (href.contains("/movie/")) {
                    TvType.Movie
                } else {
                    TvType.TvSeries
                }

                newMovieSearchResponse(
                    title,
                    href,
                    type
                ) {
                    posterUrl = poster
                }
            }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: return null

        val poster = document
            .selectFirst("meta[property='og:image']")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("img")?.attr("src")

        val plot = document
            .selectFirst("meta[name='description']")
            ?.attr("content")
            ?.trim()

        val year = document
            .selectFirst("a[href*='/year/']")
            ?.text()
            ?.trim()
            ?.toIntOrNull()

        if (url.contains("/movie/")) {
            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                posterUrl = poster
                this.year = year
                this.plot = plot
            }
        }

        val episodes = document
            .select("a[href*='/episode/']")
            .mapNotNull { link ->
                val episodeUrl = link.attr("href").trim()
                if (episodeUrl.isBlank()) return@mapNotNull null

                val episodeText = link.text().trim()

                val season = Regex(
                    """(?:الموسم|season)\s*(\d+)""",
                    RegexOption.IGNORE_CASE
                )
                    .find(episodeText)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: Regex("""/season-(\d+)/""", RegexOption.IGNORE_CASE)
                        .find(episodeUrl)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                    ?: 1

                val episode = Regex(
                    """(?:الحلقة|episode)[^\d]*(\d+)""",
                    RegexOption.IGNORE_CASE
                )
                    .find(episodeText)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: Regex("""-(\d+)/?$""")
                        .find(episodeUrl)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                    ?: return@mapNotNull null

                Episode(
                    episodeUrl,
                    episodeText.ifBlank { "Episode $episode" },
                    season,
                    episode
                )
            }
            .distinctBy { it.data }
            .sortedWith(
                compareBy<Episode> { it.season ?: 1 }
                    .thenBy { it.episode ?: 0 }
            )

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            posterUrl = poster
            this.year = year
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document

        val iframeLinks = document
            .select("iframe[src], iframe[data-src]")
            .mapNotNull {
                it.attr("src")
                    .ifBlank { it.attr("data-src") }
                    .takeIf { url -> url.startsWith("http") }
            }
            .distinct()

        var loaded = false

        for (link in iframeLinks) {
            loadExtractor(
                link,
                data,
                subtitleCallback,
                callback
            )
            loaded = true
        }

        return loaded
    }
}
