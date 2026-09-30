package com.sagemoon1996.takkiadrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    // ---------------------------------------------------------------
    // Cards (search / movies)
    // ---------------------------------------------------------------

    private fun parseDramaCards(
        document: Document
    ): List<SearchResponse> {
        return document
            .select(".drama-card")
            .mapNotNull { card ->

                val href = card.attr("href").trim()
                if (href.isBlank()) return@mapNotNull null

                val title = card
                    .selectFirst(".drama-title")
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: card.text().trim()

                if (title.isBlank()) return@mapNotNull null

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
            .distinctBy { it.url }
    }

    // /series/ page
    private fun parseSeriesCards(
        document: Document
    ): List<SearchResponse> {
        return document
            .select(".series-card")
            .mapNotNull { card ->

                val href = card.attr("href").trim()
                if (href.isBlank()) return@mapNotNull null

                val title = card.text().trim()
                if (title.isBlank()) return@mapNotNull null

                val poster = getPoster(card)

                newTvSeriesSearchResponse(
                    title,
                    href,
                    TvType.TvSeries
                ) {
                    posterUrl = poster
                }
            }
            .distinctBy { it.url }
    }

    // ---------------------------------------------------------------
    // Episodes (series /list/ page)
    // ---------------------------------------------------------------

    private fun parseEpisodeCards(
        document: Document
    ): List<Episode> {
        return document
            .select(".episode-card-landscape, .episode-card")
            .distinctBy { card -> card.attr("href").trim() }
            .mapNotNull { card ->

                val href = card.attr("href").trim()
                if (href.isBlank()) return@mapNotNull null

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
                    ?: Regex("""(\d+)""")
                        .find(episodeTitle)
                        ?.value
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

    // Follows /list/ pagination and merges with episodes found on the series page
    private suspend fun fetchAllEpisodes(
        listUrl: String,
        seriesDoc: Document
    ): List<Episode> {
        val map = LinkedHashMap<String, Episode>()

        parseEpisodeCards(seriesDoc).forEach {
            map.putIfAbsent(it.data, it)
        }

        var pageUrl: String? = listUrl
        var page = 1

        while (pageUrl != null && page <= 40) {
            val doc = try {
                app.get(pageUrl).document
            } catch (_: Exception) {
                break
            }

            parseEpisodeCards(doc).forEach {
                map.putIfAbsent(it.data, it)
            }

            pageUrl = doc
                .selectFirst("a[href*=\"/page/${page + 1}/\"]")
                ?.attr("href")
                ?.trim()
                ?.takeIf { it.isNotBlank() }

            page++
        }

        return map.values.sortedBy { it.episode ?: 0 }
    }

    // ---------------------------------------------------------------
    // Home page helpers
    // ---------------------------------------------------------------

    // Asian programs: direct episode entries using .drama-card
    private fun parseHomeEpisodeCards(
        document: Document
    ): List<SearchResponse> {
        return document
            .select(".drama-card")
            .mapNotNull { card ->

                val href = card.attr("href").trim()
                if (href.isBlank()) return@mapNotNull null

                val title = card
                    .selectFirst(".drama-title")
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: card.text().trim()

                if (title.isBlank()) return@mapNotNull null

                val poster = getPoster(card)

                newTvSeriesSearchResponse(
                    title,
                    href,
                    TvType.TvSeries
                ) {
                    posterUrl = poster
                }
            }
            .distinctBy { it.url }
    }

    // /newly/ is a mix of episodes + series.
    // Series cards are kept; episode cards are resolved to their series
    // through the series link inside the episode page (in parallel).
    private suspend fun parseNewlySeries(
        document: Document
    ): List<SearchResponse> = coroutineScope {
        document
            .select(".drama-card")
            .map { card ->
                async {
                    val href = card.attr("href").trim()
                    if (href.isBlank()) return@async null

                    val poster = getPoster(card)

                    when {
                        href.contains("/series/") -> {
                            val title = card
                                .selectFirst(".drama-title")
                                ?.text()?.trim()
                                ?.takeIf { it.isNotBlank() }
                                ?: card.text().trim()
                            if (title.isBlank()) return@async null

                            newTvSeriesSearchResponse(
                                title, href, TvType.TvSeries
                            ) { posterUrl = poster }
                        }

                        href.contains("/movies/") -> null

                        else -> try {
                            val epDoc = app.get(href).document
                            // skip the bare /series/ links (menu, breadcrumb)
                            val link = epDoc
                                .select("a[href*=\"/series/\"]")
                                .firstOrNull {
                                    it.attr("href")
                                        .substringAfter("/series/")
                                        .isNotBlank()
                                } ?: return@async null

                            val seriesUrl = link.attr("href").trim()
                            val title = link.text().trim()
                                .ifBlank { card.text().trim() }

                            newTvSeriesSearchResponse(
                                title, seriesUrl, TvType.TvSeries
                            ) { posterUrl = poster }
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
            }
            .awaitAll()
            .filterNotNull()
            .distinctBy { it.url }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        if (request.data.contains("/newly/", ignoreCase = true)) {
            val doc = app.get(getPageUrl(request.data, page)).document
            val hasNext = doc.selectFirst(
                "a[href*=\"/page/${page + 1}/\"]"
            ) != null
            return newHomePageResponse(
                request.name,
                parseNewlySeries(doc),
                hasNext = hasNext
            )
        }

        // Asian programs: try the known slug spellings until one has cards
        if (request.data.contains("/category/%d8%a7%d9%84%d8%a8%d8%b1%d8%a7%d9%85%d8%ac-", ignoreCase = true)) {
            val prefix = "$mainUrl/category/%d8%a7%d9%84%d8%a8%d8%b1%d8%a7%d9%85%d8%ac-"
            val slugs = listOf(
                "%d8%a7%d9%84%d8%a3%d8%b3%d9%8a%d9%88%d9%8a%d8%a9",
                "%d8%a7%d9%84%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9",
                "%d8%a7%d9%84%d8%b3%d9%8a%d9%88%d9%8a%d8%a9"
            )
            for (slug in slugs) {
                val doc = try {
                    app.get(getPageUrl("$prefix$slug/", page)).document
                } catch (_: Exception) { continue }
                val found = parseHomeEpisodeCards(doc)
                if (found.isNotEmpty()) {
                    val hasNext = doc.selectFirst(
                        "a[href*=\"/page/${page + 1}/\"]"
                    ) != null
                    return newHomePageResponse(
                        request.name, found, hasNext = hasNext
                    )
                }
            }
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        val url = getPageUrl(request.data, page)
        val document = app.get(url).document

        val items = when {

            request.data.contains("/series/", ignoreCase = true) -> {
                parseSeriesCards(document)
            }

            request.data.contains("/movies/", ignoreCase = true) -> {
                parseDramaCards(document)
            }

            request.data.contains(
                "/category/%d8%a7%d9%84%d8%a8%d8%b1%d8%a7%d9%85%d8%ac-",
                ignoreCase = true
            ) -> {
                parseHomeEpisodeCards(document)
            }

            else -> emptyList()
        }

        val hasNext = document
            .selectFirst("a[href*=\"/page/${page + 1}/\"]") != null

        return newHomePageResponse(
            request.name,
            items,
            hasNext = hasNext
        )
    }

    // ---------------------------------------------------------------
    // Search (unchanged)
    // ---------------------------------------------------------------

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

    // ---------------------------------------------------------------
    // Load
    // ---------------------------------------------------------------

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
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: throw ErrorLoadingException(
                        "Episodes list not found"
                    )

                val episodes = fetchAllEpisodes(listUrl, document)

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

                document
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

    // ---------------------------------------------------------------
    // loadLinks (unchanged)
    // ---------------------------------------------------------------

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
