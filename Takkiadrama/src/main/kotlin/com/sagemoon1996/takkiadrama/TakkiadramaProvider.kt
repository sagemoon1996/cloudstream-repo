package com.sagemoon1996.takkiadrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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
        "$mainUrl/newly/" to "المضافة حديثًا",
        "$mainUrl/episodes/" to "أحدث الحلقات",
        "$mainUrl/series/" to "المسلسلات",
        "$mainUrl/movies/" to "الأفلام",
        "$mainUrl/category/%d8%a7%d9%84%d8%a8%d8%b1%d8%a7%d9%85%d8%ac-%d8%a7%d9%84%d8%a3%d8%b3%d9%8a%d9%88%d9%8a%d8%a9/" to "البرامج الآسيوية"
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
            .distinctBy {
                it.url
            }
    }

    private fun parseSeriesCards(
        document: Document
    ): List<SearchResponse> {
        return document
            .select(".series-card")
            .mapNotNull { card ->

                val href = card
                    .attr("href")
                    .trim()

                if (href.isBlank()) {
                    return@mapNotNull null
                }

                val title = card
                    .selectFirst(".series-title")
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: card.text().trim()

                if (title.isBlank()) {
                    return@mapNotNull null
                }

                val poster = getPoster(card)

                newTvSeriesSearchResponse(
                    title,
                    href,
                    TvType.TvSeries
                ) {
                    posterUrl = poster
                }
            }
            .distinctBy {
                it.url
            }
    }

    private fun parseEpisodeCards(
        document: Document
    ): List<Episode> {
        return document
            .select(".episode-card-landscape")
            .distinctBy { card ->
                card
                    .attr("href")
                    .trim()
            }
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

    private fun parseHomeEpisodeCards(
        document: Document
    ): List<SearchResponse> {
        return document
            .select(".episode-card-landscape")
            .mapNotNull { card ->

                val href = card
                    .attr("href")
                    .trim()

                if (href.isBlank()) {
                    return@mapNotNull null
                }

                val title = card
                    .selectFirst(".episode-card-title")
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: "حلقة"

                val poster = getPoster(card)

                newTvSeriesSearchResponse(
                    title,
                    href,
                    TvType.TvSeries
                ) {
                    posterUrl = poster
                }
            }
            .distinctBy {
                it.url
            }
    }

    private suspend fun parseNewlySeries(
        document: Document
    ): List<SearchResponse> {

        val result = mutableListOf<SearchResponse>()

        val episodeUrls = document
            .select(".drama-card")
            .mapNotNull { card ->
                card
                    .attr("href")
                    .trim()
                    .takeIf { it.isNotBlank() }
            }
            .distinct()

        for (episodeUrl in episodeUrls) {

            val episodeDocument = try {
                app
                    .get(episodeUrl)
                    .document
            } catch (_: Exception) {
                continue
            }

            val seriesLink = episodeDocument
                .selectFirst("a[href*=\"/series/\"]")
                ?: continue

            val seriesUrl = seriesLink
                .attr("href")
                .trim()
                .takeIf { it.isNotBlank() }
                ?: continue

            val seriesTitle = seriesLink
                .text()
                .trim()
                .takeIf { it.isNotBlank() }
                ?: continue

            val poster = episodeDocument
                .selectFirst("img[data-img]")
                ?.attr("data-img")
                ?.takeIf { it.isNotBlank() }
                ?: episodeDocument
                    .selectFirst("img")
                    ?.attr("src")
                    ?.takeIf {
                        it.isNotBlank() &&
                            !it.contains("load.gif")
                    }

            result.add(
                newTvSeriesSearchResponse(
                    seriesTitle,
                    seriesUrl,
                    TvType.TvSeries
                ) {
                    posterUrl = poster
                }
            )
        }

        return result
            .distinctBy {
                it.url
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

        val items = when {

            request.data.contains(
                "/newly/",
                ignoreCase = true
            ) -> {
                parseNewlySeries(document)
            }

            request.data.contains(
                "/episodes/",
                ignoreCase = true
            ) -> {
                parseHomeEpisodeCards(document)
            }

            request.data.contains(
                "/series/",
                ignoreCase = true
            ) -> {
                parseSeriesCards(document)
            }

            request.data.contains(
                "/movies/",
                ignoreCase = true
            ) -> {
                parseDramaCards(document)
            }

            request.data.contains(
                "/category/%d8%a7%d9%84%d8%a8%d8%b1%d8%a7%d9%85%d8%ac-",
                ignoreCase = true
            ) -> {
                parseHomeEpisodeCards(document)
            }

            else -> {
                emptyList()
            }
        }

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

                val title = document
                    .selectFirst("h1")
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: "حلقة"

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

                val watchUrl = document
                    .selectFirst("a[href*=\"/watch/\"]")
                    ?.attr("href")
                    ?.takeIf { it.isNotBlank() }
                    ?: throw ErrorLoadingException(
                        "Watch link not found"
                    )

                val episodeNumber = Regex(
                    """الحلقة\s+(\d+)"""
                )
                    .find(title)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: 1

                val episode = newEpisode(url) {
                    name = title
                    season = 1
                    episode = episodeNumber
                    posterUrl = poster
                }

                newTvSeriesLoadResponse(
                    title,
                    url,
                    TvType.TvSeries,
                    listOf(episode)
                ) {
                    posterUrl = poster
                }
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
