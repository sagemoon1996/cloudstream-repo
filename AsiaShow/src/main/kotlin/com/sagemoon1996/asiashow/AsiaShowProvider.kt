package com.sagemoon1996.asiashow

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
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
        "$mainUrl/series/" to "المسلسلات",
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

    private fun posterFromElement(
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
            .takeIf {
                it.startsWith("http")
            }
    }

    private fun titleFromLink(
        link: Element
    ): String {

        val title = link
            .attr("title")
            .trim()

        if (title.isNotBlank()) {
            return title
        }

        return link
            .text()
            .trim()
            .replace(
                Regex("""^\s*(كوريا|الصين|اليابان|تايلاند|تايوان|ماليزيا|امريكا|بريطانيا|الهند|اندونيسيا|تركيا|بولندا|الفلبين)\s+"""),
                ""
            )
            .trim()
    }

    private fun parseContentLink(
        link: Element
    ): SearchResponse? {

        val href = absoluteUrl(link)

        if (!href.startsWith(mainUrl)) {
            return null
        }

        if (
            href.contains("/episodes/") ||
            href.contains("/category/") ||
            href.contains("/country/") ||
            href.contains("/genre/")
        ) {
            return null
        }

        val isSeries = href.contains(
            "/series/",
            ignoreCase = true
        )

        val isMovie = href.contains(
            "/movies/",
            ignoreCase = true
        )

        if (!isSeries && !isMovie) {
            return null
        }

        val title = titleFromLink(
            link
        )

        if (title.isBlank()) {
            return null
        }

        val container = link.parent()

        val poster =
            posterFromElement(link)
                ?: posterFromElement(
                    container
                )

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
            .select(
                "a[href*='/series/'], a[href*='/movies/']"
            )
            .mapNotNull {
                parseContentLink(it)
            }
            .distinctBy {
                it.url
            }

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
            query.trim(),
            "UTF-8"
        )

        val searchUrl =
            "$mainUrl/?s=$encodedQuery"

        val document = app.get(
            searchUrl,
            referer = mainUrl
        ).document

        return document
            .select(
                "a[href*='/series/'], a[href*='/movies/']"
            )
            .mapNotNull {
                parseContentLink(it)
            }
            .distinctBy {
                it.url
            }
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

    private fun parseEpisodes(
        document: Document
    ): List<Episode> {

        return document
            .select("a[href*='/episodes/']")
            .mapNotNull { link ->

                val href = absoluteUrl(link)

                if (!href.startsWith(mainUrl)) {
                    return@mapNotNull null
                }

                val text = link
                    .text()
                    .trim()

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
            .distinctBy {
                it.data
            }
            .sortedBy {
                it.episode
            }
    }

    private fun extractTitle(
        document: Document
    ): String? {

        val ogTitle = document
            .selectFirst(
                "meta[property=og:title]"
            )
            ?.attr("content")
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }

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
            ?.takeIf {
                it.isNotBlank()
            }
    }

    private fun extractPoster(
        document: Document
    ): String? {

        return document
            .selectFirst(
                "meta[property=og:image]"
            )
            ?.attr("content")
            ?.trim()
            ?.takeIf {
                it.startsWith("http")
            }
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
                ?.takeIf {
                    it.startsWith("http")
                }
    }

    private fun extractDescription(
        document: Document
    ): String? {

        return document
            .selectFirst(
                "meta[property=og:description]"
            )
            ?.attr("content")
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?: document
                .selectFirst(
                    ".description, .desc, .entry-content"
                )
                ?.text()
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
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

        val isMovie =
            url.contains(
                "/movies/",
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

        val episodes = parseEpisodes(
            document
        )

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

    private fun decodeServerUrl(
        encoded: String
    ): String? {

        if (encoded.isBlank()) {
            return null
        }

        return try {

            base64Decode(
                encoded.trim()
            )
                .trim()
                .takeIf {
                    it.startsWith("http")
                }

        } catch (_: Exception) {
            null
        }
    }

    private suspend fun loadDirectVideo(
        serverUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        return try {

            val document = app.get(
                serverUrl,
                referer = mainUrl
            ).document

            val mediaUrl = document
                .selectFirst(
                    "video[data-link]"
                )
                ?.attr("data-link")
                ?.trim()
                ?.takeIf {
                    it.startsWith("http")
                }

            if (mediaUrl == null) {
                false
            } else {

                callback(
                    newExtractorLink(
                        source = "Ult4vid",
                        name = "Ult4vid",
                        url = mediaUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        referer = serverUrl
                    }
                )

                true
            }

        } catch (_: Exception) {
            false
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

        val serverUrls = document
            .select("[data-etk-src]")
            .mapNotNull { element ->

                decodeServerUrl(
                    element.attr(
                        "data-etk-src"
                    )
                )

            }
            .distinct()

        var foundLinks = false

        /*
         * Ult4vid:
         * The embed page contains the current temporary
         * Cloudflare R2 MP4 directly in video[data-link].
         */
        val ult4vidUrls = serverUrls.filter {
            it.contains(
                "ult4vid",
                ignoreCase = true
            )
        }

        for (serverUrl in ult4vidUrls) {

            if (
                loadDirectVideo(
                    serverUrl = serverUrl,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            ) {
                foundLinks = true
            }
        }

        /*
         * Other servers:
         * Keep CloudStream's normal extractor system as fallback.
         */
        val otherUrls = serverUrls.filterNot {
            ult4vidUrls.contains(it)
        }

        for (serverUrl in otherUrls) {

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
         * Direct iframe fallback.
         */
        if (!foundLinks) {

            val iframeUrls = document
                .select(
                    "iframe[src], iframe[data-src]"
                )
                .mapNotNull { iframe ->

                    iframe
                        .attr("src")
                        .ifBlank {
                            iframe.attr("data-src")
                        }
                        .trim()
                        .takeIf {
                            it.startsWith("http")
                        }
                }
                .distinct()

            for (iframeUrl in iframeUrls) {

                if (
                    iframeUrl.contains(
                        "ult4vid",
                        ignoreCase = true
                    )
                ) {

                    if (
                        loadDirectVideo(
                            serverUrl = iframeUrl,
                            subtitleCallback = subtitleCallback,
                            callback = callback
                        )
                    ) {
                        foundLinks = true
                    }

                } else {

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
        }

        return foundLinks
    }
}
