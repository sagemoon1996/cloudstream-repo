package com.sagemoon1996.asiashow

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
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
        "$mainUrl/country/kr/" to "الكورية",
        "$mainUrl/country/jp/" to "اليابانية",
        "$mainUrl/country/th/" to "التايلاندية"
    )

    private val searchPostTypes =
        """{"movies":{"filter_key":"keywords","taxonomies":[]},"series":{"filter_key":"keywords","taxonomies":[]}}"""

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

    private fun elementImage(element: Element): String? {
        return element
            .selectFirst(
                "img[data-src], img[data-lazy-src], img[src]"
            )
            ?.let {
                it.attr("data-src")
                    .ifBlank { it.attr("data-lazy-src") }
                    .ifBlank { it.attr("src") }
                    .trim()
            }
            ?.takeIf { it.startsWith("http") }
    }

    private fun cardTitle(element: Element): String {
        return element
            .selectFirst(".etk-card-title")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: element
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

        val poster = elementImage(link)

        return when {
            url.contains("/series/", ignoreCase = true) -> {
                newTvSeriesSearchResponse(
                    name = title,
                    url = url,
                    type = TvType.TvSeries
                ) {
                    posterUrl = poster
                }
            }

            url.contains("/movies/", ignoreCase = true) -> {
                newMovieSearchResponse(
                    name = title,
                    url = url,
                    type = TvType.Movie
                ) {
                    posterUrl = poster
                }
            }

            else -> null
        }
    }

    private fun parseContentCards(
        document: Document
    ): List<SearchResponse> {

        return document
            .select(
                ".etk-card-link[href*='/series/'], " +
                    ".etk-card-link[href*='/movies/']"
            )
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

        val source = "$title $url"

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

        val scoped =
            document.select(
                ".ts-preview a.etk-card-link[href*='/episodes/']"
            )

        val links =
            if (scoped.isNotEmpty()) {
                scoped
            } else {
                document.select(
                    "a.etk-card-link[href*='/episodes/']"
                )
            }

        return links
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

    private fun episodeToSeriesUrl(
        episodeUrl: String
    ): String? {

        val path = episodeUrl
            .substringAfter("$mainUrl/")
            .trimEnd('/')

        if (!path.startsWith("episodes/")) {
            return null
        }

        val episodeSlug =
            path.removePrefix("episodes/")

        val seriesSlug =
            episodeSlug.replace(
                Regex("""-الحلقة-\d+$"""),
                ""
            )

        if (seriesSlug.isBlank() || seriesSlug == episodeSlug) {
            return null
        }

        return "$mainUrl/series/$seriesSlug/"
    }

    private fun parseLatestEpisodes(
        document: Document
    ): List<SearchResponse> {

        return document
            .select("a[href*='/episodes/']")
            .mapNotNull { link ->

                val episodeUrl = absoluteUrl(link)

                if (!episodeUrl.startsWith(mainUrl)) {
                    return@mapNotNull null
                }

                val seriesUrl =
                    episodeToSeriesUrl(episodeUrl)
                        ?: return@mapNotNull null

                val title =
                    cardTitle(link)
                        .ifBlank {
                            link.text().trim()
                        }

                val seriesTitle =
                    title
                        .replace(
                            Regex(
                                """\s*الحلقة\s*\d+\s*$""",
                                RegexOption.IGNORE_CASE
                            ),
                            ""
                        )
                        .trim()

                if (seriesTitle.isBlank()) {
                    return@mapNotNull null
                }

                newTvSeriesSearchResponse(
                    name = seriesTitle,
                    url = seriesUrl,
                    type = TvType.TvSeries
                ) {
                    posterUrl = elementImage(link)
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

        val normalizedRequestData = try {
            URLDecoder.decode(
                request.data,
                "UTF-8"
            )
        } catch (_: Exception) {
            request.data
        }

        val isLatest =
            request.name == "آخر الحلقات" ||
                normalizedRequestData.contains(
                    "/أخر-الحلقات/",
                    ignoreCase = true
                ) ||
                normalizedRequestData.contains(
                    "/آخر-الحلقات/",
                    ignoreCase = true
                ) ||
                request.data.contains(
                    "/%D8%A3%D8%AE%D8%B1-%D8%A7%D9%84%D8%AD%D9%84%D9%82%D8%A7%D8%AA/",
                    ignoreCase = true
                )

        val items =
            if (isLatest) {
                parseLatestEpisodes(document)
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

        if (query.trim().length < 3) {
            return emptyList()
        }

        val encodedQuery =
            URLEncoder.encode(
                query.trim(),
                "UTF-8"
            )

        val encodedPostTypes =
            URLEncoder.encode(
                searchPostTypes,
                "UTF-8"
            )

        val url =
            "$mainUrl/?vx=1" +
                "&action=quick_search" +
                "&search=$encodedQuery" +
                "&post_types=$encodedPostTypes"

        return try {

            val response = app.get(
                url,
                referer = mainUrl
            )

            val json =
                JSONObject(response.text)

            val data =
                json.optJSONArray("data")
                    ?: return emptyList()

            buildList {

                for (index in 0 until data.length()) {

                    val item =
                        data.optJSONObject(index)
                            ?: continue

                    val title =
                        item.optString("title")
                            .trim()

                    val rawUrl =
                        item.optString("link")
                            .trim()

                    if (
                        title.isBlank() ||
                        rawUrl.isBlank()
                    ) {
                        continue
                    }

                    val resultUrl =
                        if (rawUrl.startsWith("http")) {
                            rawUrl
                        } else {
                            "$mainUrl/${rawUrl.trimStart('/')}"
                        }

                    when {
                        resultUrl.contains(
                            "/series/",
                            ignoreCase = true
                        ) -> {

                            add(
                                newTvSeriesSearchResponse(
                                    name = title,
                                    url = resultUrl,
                                    type = TvType.TvSeries
                                )
                            )
                        }

                        resultUrl.contains(
                            "/movies/",
                            ignoreCase = true
                        ) -> {

                            add(
                                newMovieSearchResponse(
                                    name = title,
                                    url = resultUrl,
                                    type = TvType.Movie
                                )
                            )
                        }
                    }
                }
            }.distinctBy {
                it.url
            }

        } catch (_: Exception) {
            emptyList()
        }
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

        if (
            url.contains(
                "/movies/",
                ignoreCase = true
            )
        ) {

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
        episodeUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        return try {

            val document = app.get(
                serverUrl,
                referer = episodeUrl
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
                    quality = Qualities.Unknown.value
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

        val episodeUrl = data.trim()

        if (!episodeUrl.startsWith("http")) {
            return false
        }

        val document = try {

            app.get(
                episodeUrl,
                referer = mainUrl
            ).document

        } catch (_: Exception) {

            return false
        }

        val serverUrls =
            document
                .select(
                    "a[data-etk-server-btn][data-etk-src]"
                )
                .mapNotNull { server ->

                    decodeServerUrl(
                        server.attr("data-etk-src")
                    )
                }
                .distinct()

        if (serverUrls.isEmpty()) {
            return false
        }

        var foundLinks = false

        val ult4vidUrls =
            serverUrls.filter {
                it.contains(
                    "ult4vid",
                    ignoreCase = true
                )
            }

        for (serverUrl in ult4vidUrls) {

            if (
                loadUlt4vid(
                    serverUrl = serverUrl,
                    episodeUrl = episodeUrl,
                    callback = callback
                )
            ) {
                foundLinks = true
            }
        }

        val otherUrls =
            serverUrls.filterNot {
                ult4vidUrls.contains(it)
            }

        for (serverUrl in otherUrls) {

            try {

                val loaded =
                    loadExtractor(
                        url = serverUrl,
                        referer = episodeUrl,
                        subtitleCallback = subtitleCallback
                    ) { link ->

                        foundLinks = true
                        callback(link)
                    }

                if (loaded) {
                    foundLinks = true
                }

            } catch (_: Exception) {
            }
        }

        return foundLinks
    }
}
