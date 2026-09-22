package com.sagemoon1996.fivetv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

class FiveTVProvider : MainAPI() {

    override var mainUrl = "https://new61.5tv.lol"
    override var name = "FiveTV"
    override var lang = "ar"

    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "الرئيسية",
        "$mainUrl/latest-episodes/" to "آخر الحلقات",
        "$mainUrl/new-rows/" to "آخر الإضافات"
    )

    private fun getTitle(link: org.jsoup.nodes.Element): String? {
        val title = link.selectFirst(
            "img[alt], h1, h2, h3, h4, .title, .entry-title"
        )
            ?.let {
                if (it.tagName() == "img") {
                    it.attr("alt")
                } else {
                    it.text()
                }
            }
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        return title
            ?: link.text()
                .trim()
                .replace(Regex("\\s+"), " ")
                .takeIf { it.isNotBlank() }
    }

    private fun getPoster(
        link: org.jsoup.nodes.Element
    ): String? {
        return link.selectFirst(
            "img[src], img[data-src], img[data-lazy-src]"
        )?.let { image ->
            image.attr("src")
                .ifBlank {
                    image.attr("data-src")
                }
                .ifBlank {
                    image.attr("data-lazy-src")
                }
                .takeIf {
                    it.startsWith("http")
                }
        }
    }

    private fun makeSearchResponses(
        document: org.jsoup.nodes.Document
    ): List<SearchResponse> {

        return document
            .select(
                "a[href*='/series/'], " +
                "a[href*='/movie/']"
            )
            .mapNotNull { link ->

                val href = link
                    .attr("href")
                    .trim()

                if (href.isBlank()) {
                    return@mapNotNull null
                }

                val title = getTitle(link)
                    ?: return@mapNotNull null

                val poster = getPoster(link)

                when {
                    href.contains("/movie/") -> {
                        newMovieSearchResponse(
                            title,
                            href,
                            TvType.Movie
                        ) {
                            posterUrl = poster
                        }
                    }

                    href.contains("/series/") -> {
                        newTvSeriesSearchResponse(
                            title,
                            href,
                            TvType.TvSeries
                        ) {
                            posterUrl = poster
                        }
                    }

                    else -> null
                }
            }
            .distinctBy {
                it.url
            }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = if (page == 1) {
            request.data
        } else {
            "${request.data.trimEnd('/')}/page/$page/"
        }

        val document = app.get(url).document

        val results = makeSearchResponses(document)

        return newHomePageResponse(
            request.name,
            results
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val encodedQuery = URLEncoder.encode(
            query,
            "UTF-8"
        )

        val urls = listOf(
            "$mainUrl/search/?s=$encodedQuery",
            "$mainUrl/?s=$encodedQuery"
        )

        for (url in urls) {

            val document = app.get(url).document

            val results = makeSearchResponses(document)

            if (results.isNotEmpty()) {
                return results
            }
        }

        return emptyList()
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document = app.get(url).document

        val title = document
            .selectFirst(
                "h1, h2.entry-title"
            )
            ?.text()
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?: document
                .selectFirst(
                    "meta[property='og:title']"
                )
                ?.attr("content")
                ?.trim()
                ?: return null

        val poster = document
            .selectFirst(
                "meta[property='og:image']"
            )
            ?.attr("content")
            ?.takeIf {
                it.isNotBlank()
            }
            ?: document
                .selectFirst(
                    "img[src], img[data-src]"
                )
                ?.let {
                    it.attr("src")
                        .ifBlank {
                            it.attr("data-src")
                        }
                }

        val plot = document
            .selectFirst(
                "meta[name='description'], " +
                "meta[property='og:description']"
            )
            ?.attr("content")
            ?.trim()

        val year = document
            .select("a[href*='/year/']")
            .firstOrNull()
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
            .select(
                "a[href*='/episode/']"
            )
            .mapNotNull { link ->

                val episodeUrl = link
                    .attr("href")
                    .trim()

                if (episodeUrl.isBlank()) {
                    return@mapNotNull null
                }

                val episodeText = link
                    .text()
                    .trim()

                val season =
                    Regex(
                        """(?:الموسم|season)\s*(\d+)""",
                        RegexOption.IGNORE_CASE
                    )
                        .find(episodeText)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?: Regex(
                            """/season-(\d+)/""",
                            RegexOption.IGNORE_CASE
                        )
                            .find(episodeUrl)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                        ?: 1

                val episode =
                    Regex(
                        """(?:الحلقة|episode)[^\d]*(\d+)""",
                        RegexOption.IGNORE_CASE
                    )
                        .find(episodeText)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?: Regex(
                            """-(\d+)/?$"""
                        )
                            .find(episodeUrl)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                        ?: return@mapNotNull null

                newEpisode(episodeUrl) {
                    name = episodeText.ifBlank {
                        "Episode $episode"
                    }

                    this.season = season
                    this.episode = episode
                }
            }
            .distinctBy {
                it.data
            }
            .sortedWith(
                compareBy<Episode> {
                    it.season ?: 1
                }.thenBy {
                    it.episode ?: 0
                }
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
            .select(
                "iframe[src], iframe[data-src]"
            )
            .mapNotNull { iframe ->

                iframe
                    .attr("src")
                    .ifBlank {
                        iframe.attr("data-src")
                    }
                    .takeIf {
                        it.startsWith("http")
                    }
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
