package com.sagemoon1996.takkiadrama

import com.lagradost.cloudstream3.*
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class TakkiadramaProvider : MainAPI() {

    override var mainUrl = "https://takkiadrama.asia"
    override var name = "Takkiadrama"
    override var lang = "ar"

    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "$mainUrl/series/" to "أحدث المسلسلات",
        "$mainUrl/movies/" to "أحدث الأفلام"
    )

    private fun getPageUrl(
        baseUrl: String,
        page: Int
    ): String {
        return if (page <= 1) {
            baseUrl
        } else {
            "${baseUrl.trimEnd('/')}/page/$page/"
        }
    }

    private fun getPoster(
        element: Element
    ): String? {
        return element
            .selectFirst("img[data-img]")
            ?.attr("data-img")
            ?.takeIf { it.isNotBlank() }
            ?: element
                .selectFirst("img")
                ?.attr("src")
                ?.takeIf {
                    it.isNotBlank() &&
                    !it.contains("load.gif")
                }
    }

    private fun parseDramaCards(
        document: Document
    ): List<SearchResponse> {
        return document
            .select(".drama-card")
            .mapNotNull { card ->

                val href = card
                    .attr("href")
                    .trim()

                if (href.isBlank()) {
                    return@mapNotNull null
                }

                val title = card
                    .selectFirst(".drama-title")
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: card.text().trim()

                if (title.isBlank()) {
                    return@mapNotNull null
                }

                val poster = getPoster(card)

                when {
                    href.contains("/series/") -> {
                        newTvSeriesSearchResponse(
                            title,
                            href,
                            TvType.TvSeries
                        ) {
                            posterUrl = poster
                        }
                    }

                    href.contains("/movies/") -> {
                        newMovieSearchResponse(
                            title,
                            href,
                            TvType.Movie
                        ) {
                            posterUrl = poster
                        }
                    }

                    else -> null
                }
            }
    }

    private fun parseEpisodeCards(
        document: Document
    ): List<Episode> {
        return document
            .select(".episode-card-landscape")
            .mapNotNull { card ->

                val href = card
                    .attr("href")
                    .trim()

                if (href.isBlank()) {
                    return@mapNotNull null
                }

                val episodeTitle = card
                    .selectFirst(".episode-card-title")
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: "حلقة"

                val episodeNumber = card
                    .selectFirst(".episode-card-number span")
                    ?.text()
                    ?.trim()
                    ?.toIntOrNull()

                val poster = getPoster(card)

                newEpisode(href) {
                    name = episodeTitle
                    season = 1
                    episode = episodeNumber ?: 1
                    posterUrl = poster
                }
            }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = getPageUrl(
            request.data,
            page
        )

        val document = app
            .get(url)
            .document

        val items = parseDramaCards(document)

        val hasNext = document
            .selectFirst(
                "a[href*=\"/page/${page + 1}/\"]"
            ) != null

        return newHomePageResponse(
            request.name,
            items,
            hasNext = hasNext
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val encodedQuery = URLEncoder.encode(
            query.trim(),
            "UTF-8"
        )

        val url = "$mainUrl/?s=$encodedQuery"

        val document = app
            .get(url)
            .document

        return parseDramaCards(document)
    }

    override suspend fun load(
        url: String
    ): LoadResponse {

        val document = app
            .get(url)
            .document

        return when {

            url.contains("/series/") -> {

                val title = document
                    .selectFirst("h1")
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: throw ErrorLoadingException(
                        "Series title not found"
                    )

                val poster = document
                    .selectFirst("img[data-img]")
                    ?.attr("data-img")
                    ?.takeIf { it.isNotBlank() }
                    ?: document
                        .selectFirst("img")
                        ?.attr("src")
                        ?.takeIf {
                            it.isNotBlank() &&
                            !it.contains("load.gif")
                        }

                val listUrl = document
                    .selectFirst("a[href*=\"/list/\"]")
                    ?.attr("href")
                    ?.takeIf { it.isNotBlank() }
                    ?: throw ErrorLoadingException(
                        "Episodes list not found"
                    )

                val listDocument = app
                    .get(listUrl)
                    .document

                val episodes = parseEpisodeCards(
                    listDocument
                )

                newTvSeriesLoadResponse(
                    title,
                    url,
                    TvType.TvSeries,
                    episodes
                ) {
                    posterUrl = poster
                }
            }

            url.contains("/movies/") -> {

                val title = document
                    .selectFirst("h1")
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: throw ErrorLoadingException(
                        "Movie title not found"
                    )

                val poster = document
                    .selectFirst("img[data-img]")
                    ?.attr("data-img")
                    ?.takeIf { it.isNotBlank() }
                    ?: document
                        .selectFirst("img")
                        ?.attr("src")
                        ?.takeIf {
                            it.isNotBlank() &&
                            !it.contains("load.gif")
                        }

                newMovieLoadResponse(
                    title,
                    url,
                    TvType.Movie,
                    url
                ) {
                    posterUrl = poster
                }
            }

            else -> {
                throw ErrorLoadingException(
                    "Unsupported Takkiadrama URL"
                )
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val sourceDocument = app
            .get(data)
            .document

        val watchUrl = sourceDocument
            .selectFirst("a[href*=\"/watch/\"]")
            ?.attr("href")
            ?.takeIf { it.isNotBlank() }
            ?: return false

        val watchDocument = app
            .get(watchUrl)
            .document

        val serverUrls = watchDocument
            .select("[data-server-url]")
            .mapNotNull { element ->
                element
                    .attr("data-server-url")
                    .trim()
                    .takeIf { it.isNotBlank() }
            }
            .distinct()

        var foundLink = false

        for (serverUrl in serverUrls) {

            if (!serverUrl.contains("71stream.one")) {
                continue
            }

            val serverDocument = app
                .get(
                    serverUrl,
                    referer = watchUrl
                )
                .document

            val appElement = serverDocument
                .selectFirst("#app[data-page]")
                ?: continue

            val pageData = appElement
                .attr("data-page")
                .trim()

            if (pageData.isBlank()) {
                continue
            }

            val json = try {
                JSONObject(pageData)
            } catch (_: Exception) {
                continue
            }

            val props = json
                .optJSONObject("props")
                ?: continue

            val videoUrl = props
                .optString("url")
                .takeIf { it.isNotBlank() }
                ?: continue

            val mime = props
                .optString("mime")
                .lowercase()

            val videoType = when {
                mime.contains("mpegurl") ||
                    mime.contains("m3u8") ||
                    videoUrl.contains(".m3u8") -> {
                    ExtractorLinkType.M3U8
                }

                else -> {
                    ExtractorLinkType.VIDEO
                }
            }

            val videoTitle = props
                .optString("title")
                .takeIf { it.isNotBlank() }
                ?: "71stream"

            callback(
                newExtractorLink(
                    "71stream",
                    videoTitle,
                    videoUrl,
                    type = videoType
                ) {
                    referer = serverUrl
                    quality = Qualities.Unknown.value
                }
            )

            foundLink = true
        }

        return foundLink
    }
}
