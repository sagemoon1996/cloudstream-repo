package com.sagemoon1996.arabrunners

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class ArabRunnersProvider : MainAPI() {

    override var mainUrl = "https://arabrunnersteam.org"

    override var name = "Arab Runners Team"

    override var lang = "ar"

    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "آخر الحلقات المضافة",
        "$mainUrl/category/%d8%a7%d9%84%d9%83%d9%84/%d8%a7%d9%84%d8%b1%d8%ac%d9%84-%d8%a7%d9%84%d8%ac%d8%a7%d8%b1%d9%8a/" to "الرجل الجاري"
    )

    private fun getTitle(element: Element): String? {
        return element.selectFirst(
            "img[alt], h1, h2, h3, h4, .title, .entry-title"
        )?.let {
            if (it.tagName() == "img") {
                it.attr("alt")
            } else {
                it.text()
            }
        }?.trim()?.takeIf {
            it.isNotBlank()
        } ?: element.text()
            .trim()
            .replace(Regex("\\s+"), " ")
            .takeIf {
                it.isNotBlank()
            }
    }

    private fun getPoster(element: Element): String? {
        return element.selectFirst(
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
            .select("article, .post, .item, .post-item")
            .mapNotNull { element ->

                val link = element
                    .selectFirst("a[href]")
                    ?.attr("href")
                    ?.trim()
                    ?: return@mapNotNull null

                if (!link.startsWith(mainUrl)) {
                    return@mapNotNull null
                }

                val title = getTitle(element)
                    ?: return@mapNotNull null

                val poster = getPoster(element)

                newTvSeriesSearchResponse(
                    title,
                    link,
                    TvType.TvSeries
                ) {
                    posterUrl = poster
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

        return newHomePageResponse(
            request.name,
            makeSearchResponses(document),
            hasNext = makeSearchResponses(document).isNotEmpty()
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val encodedQuery = URLEncoder.encode(
            query,
            "UTF-8"
        )

        val url = "$mainUrl/?s=$encodedQuery"

        return makeSearchResponses(
            app.get(url).document
        )
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document = app.get(url).document

        val title = document
            .selectFirst(
                "h1.entry-title, h1.post-title, h1"
            )
            ?.text()
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?: document
                .selectFirst("meta[property='og:title']")
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
                    "img[src], img[data-src], img[data-lazy-src]"
                )
                ?.let {
                    it.attr("src")
                        .ifBlank {
                            it.attr("data-src")
                        }
                        .ifBlank {
                            it.attr("data-lazy-src")
                        }
                }

        val plot = document
            .selectFirst(
                "meta[name='description'], meta[property='og:description']"
            )
            ?.attr("content")
            ?.trim()

        val episodeLinks = document
            .select("a[href]")
            .mapNotNull { link ->

                val episodeUrl = link
                    .attr("href")
                    .trim()

                val episodeText = link
                    .text()
                    .trim()

                if (
                    episodeUrl.isBlank() ||
                    !episodeUrl.startsWith(mainUrl)
                ) {
                    return@mapNotNull null
                }

                val episodeNumber =
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

                val season =
                    Regex(
                        """(?:الموسم|season)[^\d]*(\d+)""",
                        RegexOption.IGNORE_CASE
                    )
                        .find(episodeText)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?: 1

                newEpisode(episodeUrl) {
                    name = episodeText.ifBlank {
                        "Episode $episodeNumber"
                    }
                    this.season = season
                    this.episode = episodeNumber
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
            episodeLinks
        ) {
            posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        if (
            !data.startsWith("http://") &&
            !data.startsWith("https://")
        ) {
            return false
        }

        loadExtractor(
            data,
            mainUrl,
            subtitleCallback,
            callback
        )

        return true
    }
}
