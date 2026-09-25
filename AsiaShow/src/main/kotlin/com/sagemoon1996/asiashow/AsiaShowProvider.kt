package com.sagemoon1996.asiashow

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AsiaShowProvider : MainAPI() {

    override var mainUrl = "https://asiashow.net"

    override var name = "AsiaShow"

    override var lang = "ar"

    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "$mainUrl/%D8%A3%D8%AE%D8%B1-%D8%A7%D9%84%D8%AD%D9%84%D9%82%D8%A7%D8%AA/" to "آخر الحلقات",
        "$mainUrl/series" to "المسلسلات",
        "$mainUrl/movies/" to "الأفلام",
        "$mainUrl/country/cn/" to "الصينية",
        "$mainUrl/country/jp/" to "اليابانية",
        "$mainUrl/country/th/" to "التايلاندية"
    )

    private fun absoluteUrl(
        element: Element
    ): String {

        val absolute = element
            .attr("abs:href")
            .trim()

        if (absolute.startsWith("http")) {
            return absolute
        }

        val href = element
            .attr("href")
            .trim()

        return when {
            href.startsWith("http") -> href
            href.startsWith("/") -> "$mainUrl$href"
            href.isNotBlank() -> "$mainUrl/${href.trimStart('/')}"
            else -> ""
        }
    }

    private fun posterUrl(
        element: Element
    ): String? {

        val image = element.selectFirst(
            "img[data-src], img[data-lazy-src], img[src]"
        ) ?: return null

        return image.attr("data-src")
            .ifBlank {
                image.attr("data-lazy-src")
            }
            .ifBlank {
                image.attr("src")
            }
            .trim()
            .takeIf { it.startsWith("http") }
    }

    private fun parseSearchResult(
        element: Element
    ): SearchResponse? {

        val link = element.selectFirst("a[href]")
            ?: return null

        val href = absoluteUrl(link)

        if (!href.startsWith(mainUrl)) {
            return null
        }

        if (
            href.contains("/category/") ||
            href.contains("/country/") ||
            href.contains("/episodes/")
        ) {
            return null
        }

        val title = (
            element.selectFirst(
                ".title, .entry-title, h2, h3, h4"
            )?.text()
                ?: link.attr("title")
                ?: link.text()
        ).trim()

        if (title.isBlank()) {
            return null
        }

        val poster = posterUrl(element)

        val isMovie =
            href.contains("/movies/", ignoreCase = true) ||
            title.contains("فيلم", ignoreCase = true)

        return if (isMovie) {

            newMovieSearchResponse(
                name = title,
                url = href,
                type = TvType.Movie
            ) {
                posterUrl = poster
            }

        } else {

            newTvSeriesSearchResponse(
                title,
                href,
                TvType.TvSeries
            ) {
                posterUrl = poster
            }
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        /*
         * AsiaShow currently uses a JavaScript "Load more"
         * button instead of a confirmed /page/2/ URL.
         *
         * We therefore only request the real page here and do not
         * invent pagination.
         */

        if (page > 1) {
            return newHomePageResponse(
                request.name,
                emptyList(),
                hasNext = false
            )
        }

        val document = app.get(
            request.data,
            referer = mainUrl
        ).document

        val items = document
            .select("article, .item, .post, .movie, .series")
            .mapNotNull { parseSearchResult(it) }
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = false
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val encodedQuery = URLEncoder.encode(
            query,
            "UTF-8"
        )

        val document = app.get(
            "$mainUrl/?s=$encodedQuery",
            referer = mainUrl
        ).document

        return document
            .select("article, .item, .post, .movie, .series")
            .mapNotNull { parseSearchResult(it) }
            .distinctBy { it.url }
    }

    private fun episodeNumber(
        text: String,
        href: String
    ): Int? {

        val regex = Regex(
            """(?:الحلقة|episode|ep)[^\d]*(\d+)""",
            RegexOption.IGNORE_CASE
        )

        return regex.find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: regex.find(href)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    private fun parseEpisodeLinks(
        document: org.jsoup.nodes.Document
    ): List<Episode> {

        return document
            .select("a[href]")
            .mapNotNull { link ->

                val href = absoluteUrl(link)

                if (!href.startsWith(mainUrl)) {
                    return@mapNotNull null
                }

                if (!href.contains("/episodes/")) {
                    return@mapNotNull null
                }

                val text = link.text().trim()

                val number = episodeNumber(
                    text,
                    href
                ) ?: return@mapNotNull null

                newEpisode(href) {

                    name = if (text.isBlank()) {
                        "الحلقة $number"
                    } else {
                        text
                    }

                    season = 1
                    episode = number
                }
            }
            .distinctBy { it.data }
            .sortedBy { it.episode }
    }

    private fun extractTitle(
        document: org.jsoup.nodes.Document
    ): String? {

        val ogTitle = document
            .selectFirst("meta[property=og:title]")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        if (ogTitle != null) {
            return ogTitle
                .replace(
                    Regex("""\s*[–-]\s*اسيا شو.*$"""),
                    ""
                )
                .trim()
        }

        return document
            .selectFirst(
                ".entry-title, h1, h2"
            )
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractPoster(
        document: org.jsoup.nodes.Document
    ): String? {

        return document
            .selectFirst(
                "meta[property=og:image]"
            )
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.startsWith("http") }
            ?: document
                .selectFirst(
                    ".poster img, .cover img, img"
                )
                ?.let {
                    it.attr("data-src")
                        .ifBlank {
                            it.attr("data-lazy-src")
                        }
                        .ifBlank {
                            it.attr("src")
                        }
                }
                ?.trim()
                ?.takeIf { it.startsWith("http") }
    }

    private fun extractDescription(
        document: org.jsoup.nodes.Document
    ): String? {

        return document
            .selectFirst(
                "meta[property=og:description]"
            )
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document
                .selectFirst(
                    ".description, .desc, .entry-content"
                )
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document = app.get(
            url,
            referer = mainUrl
        ).document

        val title = extractTitle(
            document
        ) ?: return null

        val poster = extractPoster(
            document
        )

        val description = extractDescription(
            document
        )

        /*
         * Episode pages on AsiaShow use /episodes/.
         */
        val isEpisode =
            url.contains(
                "/episodes/",
                ignoreCase = true
            )

        if (isEpisode) {

            val number = episodeNumber(
                title,
                url
            ) ?: 1

            val episode = newEpisode(url) {
                name = title
                season = 1
                episode = number
            }

            return newTvSeriesLoadResponse(
                name = title,
                url = url,
                type = TvType.TvSeries,
                episodes = listOf(episode)
            ) {
                posterUrl = poster
                plot = description
            }
        }

        val episodes = parseEpisodeLinks(
            document
        )

        val isMovie =
            url.contains(
                "/movies/",
                ignoreCase = true
            ) ||
                title.contains(
                    "فيلم",
                    ignoreCase = true
                )

        if (isMovie) {

            return newMovieLoadResponse(
                name = title,
                url = url,
                type = TvType.Movie,
                dataUrl = url
            ) {
                posterUrl = poster
                plot = description
            }
        }

        return newTvSeriesLoadResponse(
            name = title,
            url = url,
            type = TvType.TvSeries,
            episodes = episodes
        ) {
            posterUrl = poster
            plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(
            data,
            referer = mainUrl
        ).document

        /*
         * AsiaShow stores server URLs in data-etk-src
         * as Base64 encoded strings.
         *
         * Example:
         * aHR0cHM6Ly92aWRtb2x5Lm5ldC...
         *
         * This decodes to the real Vidmoly embed URL.
         */

        val serverUrls = document
            .select("[data-etk-src]")
            .mapNotNull { element ->

                val encoded = element
                    .attr("data-etk-src")
                    .trim()

                if (encoded.isBlank()) {
                    return@mapNotNull null
                }

                try {

                    base64Decode(
                        encoded
                    )
                        .trim()
                        .takeIf {
                            it.startsWith("http")
                        }

                } catch (_: Exception) {
                    null
                }
            }
            .distinct()

        var foundLinks = false

        for (serverUrl in serverUrls) {

            try {

                loadExtractor(
                    url = serverUrl,
                    referer = serverUrl,
                    subtitleCallback = subtitleCallback
                ) { link ->

                    foundLinks = true
                    callback(link)
                }

            } catch (_: Exception) {
            }
        }

        /*
         * Fallback:
         * Some AsiaShow pages expose the current player iframe
         * directly. Try it only if the server buttons did not
         * produce a link.
         */

        if (!foundLinks) {

            val iframeUrls = document
                .select(
                    "iframe[src], iframe[data-src]"
                )
                .mapNotNull { iframe ->

                    val iframeUrl = iframe
                        .attr("src")
                        .ifBlank {
                            iframe.attr("data-src")
                        }
                        .trim()

                    iframeUrl.takeIf {
                        it.startsWith("http")
                    }
                }
                .distinct()

            for (iframeUrl in iframeUrls) {

                try {

                    loadExtractor(
                        url = iframeUrl,
                        referer = iframeUrl,
                        subtitleCallback = subtitleCallback
                    ) { link ->

                        foundLinks = true
                        callback(link)
                    }

                } catch (_: Exception) {
                }
            }
        }

        return foundLinks
    }
}
