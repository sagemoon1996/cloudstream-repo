package com.sagemoon1996.asiashow

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder

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

    private fun absoluteUrl(element: Element): String {
        val absolute = element.attr("abs:href").trim()

        if (absolute.startsWith("http")) {
            return absolute
        }

        val href = element.attr("href").trim()

        return when {
            href.startsWith("http") -> href
            href.startsWith("/") -> "$mainUrl$href"
            href.isNotBlank() -> "$mainUrl/${href.trimStart('/')}"
            else -> ""
        }
    }

    private fun posterFromCard(
        link: Element
    ): String? {
        return link
            .selectFirst(
                "img[data-src], img[data-lazy-src], img[src]"
            )
            ?.let {
                it.attr("data-src")
                    .ifBlank {
                        it.attr("data-lazy-src")
                    }
                    .ifBlank {
                        it.attr("src")
                    }
                    .trim()
            }
            ?.takeIf {
                it.startsWith("http")
            }
    }

    private fun cardTitle(
        link: Element
    ): String {
        return link
            .selectFirst(".etk-card-title")
            ?.text()
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?: link
                .attr("aria-label")
                .trim()
    }

    private fun parseContentCard(
        link: Element
    ): SearchResponse? {

        val url = absoluteUrl(link)

        if (!url.startsWith(mainUrl)) {
            return null
        }

        if (url.contains("/episodes/")) {
            return null
        }

        val title = cardTitle(link)

        if (title.isBlank()) {
            return null
        }

        val poster = posterFromCard(link)

        return when {
            url.contains("/series/", ignoreCase = true) ->
                newTvSeriesSearchResponse(
                    name = title,
                    url = url,
                    type = TvType.TvSeries
                ) {
                    posterUrl = poster
                }

            url.contains("/movies/", ignoreCase = true) ->
                newMovieSearchResponse(
                    name = title,
                    url = url,
                    type = TvType.Movie
                ) {
                    posterUrl = poster
                }

            else -> null
        }
    }

    private fun parseContentCards(
        document: Document
    ): List<SearchResponse> {
        return document
            .select(".etk-card-link[href]")
            .mapNotNull {
                parseContentCard(it)
            }
            .distinctBy {
                it.url
            }
    }

    private fun episodeNumber(
        title: String,
        url: String
    ): Int? {

        val decodedUrl = try {
            URLDecoder.decode(url, "UTF-8")
        } catch (_: Exception) {
            url
        }

        val source = "$title $decodedUrl"

        return Regex(
            """(?:الحلقة|episode|ep)[^\d]*(\d+)""",
            RegexOption.IGNORE_CASE
        )
            .find(source)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun parseEpisode(
        link: Element
    ): Episode? {

        val url = absoluteUrl(link)

        if (!url.contains("/episodes/")) {
            return null
        }

        val title = cardTitle(link)

        val number = episodeNumber(
            title,
            url
        ) ?: return null

        return newEpisode(url) {
            name = title.ifBlank {
                "الحلقة $number"
            }

            season = 1
            episode = number
        }
    }

    private fun parseEpisodes(
        document: Document
    ): List<Episode> {

        return document
            .select(
                ".ts-preview .etk-card-link[href*='/episodes/']"
            )
            .mapNotNull {
                parseEpisode(it)
            }
            .distinctBy {
                it.data
            }
            .sortedBy {
                it.episode ?: Int.MAX_VALUE
            }
    }

    private fun parseLatestEpisodeCards(
        document: Document
    ): List<SearchResponse> {

        return document
            .select(
                ".etk-card-link[href*='/episodes/']"
            )
            .mapNotNull { link ->

                val url = absoluteUrl(link)

                if (!url.startsWith(mainUrl)) {
                    return@mapNotNull null
                }

                val title = cardTitle(link)

                if (title.isBlank()) {
                    return@mapNotNull null
                }

                newTvSeriesSearchResponse(
                    name = title,
                    url = url,
                    type = TvType.TvSeries
                ) {
                    posterUrl = posterFromCard(link)
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

        val items =
            if (
                request.data.contains(
                    "/%D8%A3%D8%AE%D8%B1-%D8%A7%D9%84%D8%AD%D9%84%D9%82%D8%A7%D8%AA/",
                    ignoreCase = true
                )
            ) {
                parseLatestEpisodeCards(document)
            } else {
                parseContentCards(document)
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
        return emptyList()
    }

    private fun extractTitle(
        document: Document
    ): String? {

        return document
            .selectFirst(
                "meta[property=og:title]"
            )
            ?.attr("content")
            ?.trim()
            ?.replace(
                Regex("""\s*[–-]\s*اسيا شو.*$"""),
                ""
            )
            ?.takeIf {
                it.isNotBlank()
            }
            ?: document
                .selectFirst(
                    ".entry-title, h1"
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
                        .trim()
                }
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

        val title =
            extractTitle(document)
                ?: return null

        val poster =
            extractPoster(document)

        val description =
            extractDescription(document)

        if (url.contains("/movies/", ignoreCase = true)) {

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

        val episodes =
            parseEpisodes(document)

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

    private suspend fun loadUlt4vid(
        serverUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        return try {

            val document = app.get(
                serverUrl,
                referer = mainUrl
            ).document

            val mediaUrl =
                document
                    .selectFirst(
                        "video[data-link]"
                    )
                    ?.attr("data-link")
                    ?.trim()
                    ?.takeIf {
                        it.startsWith("http")
                    }
                    ?: return false

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

        val serverUrls =
            document
                .select("[data-etk-src]")
                .mapNotNull {
                    decodeServerUrl(
                        it.attr("data-etk-src")
                    )
                }
                .distinct()

        var foundLinks = false

        for (serverUrl in serverUrls) {

            if (
                serverUrl.contains(
                    "ult4vid",
                    ignoreCase = true
                )
            ) {

                if (
                    loadUlt4vid(
                        serverUrl,
                        callback
                    )
                ) {
                    foundLinks = true
                }

            } else {

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
        }

        return foundLinks
    }
}
