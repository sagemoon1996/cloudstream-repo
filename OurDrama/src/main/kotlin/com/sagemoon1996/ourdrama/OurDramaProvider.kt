package com.sagemoon1996.ourdrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class OurDramaProvider : MainAPI() {

    override var mainUrl = "https://s.ourdrama.pro"
    override var name = "OurDrama"
    override var lang = "ar"

    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "$mainUrl/serie/cate/مسلسلات-أسيوية" to "مسلسلات آسيوية",
        "$mainUrl/serie/cate/مسلسلات-كورية" to "مسلسلات كورية",
        "$mainUrl/serie/cate/مسلسلات-صينية" to "مسلسلات صينية",
        "$mainUrl/serie/cate/مسلسلات-يابانية" to "مسلسلات يابانية",
        "$mainUrl/serie/cate/مسلسلات-تايوانية" to "مسلسلات تايوانية",
        "$mainUrl/serie/cate/مسلسلات-تايلاندية" to "مسلسلات تايلاندية"
    )

    private fun absoluteUrl(url: String): String {
        return when {
            url.startsWith("http://") -> url
            url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/${url.trimStart('/')}"
        }
    }

    private fun extractPoster(element: Element): String? {
        val image = element.selectFirst(
            "img[data-src], img[data-lazy-src], img[src]"
        ) ?: return null

        val dataSrc = image.attr("data-src").trim()
        if (
            dataSrc.startsWith("http") &&
            !dataSrc.contains("/images/pixel.gif")
        ) {
            return dataSrc
        }

        val lazySrc = image.attr("data-lazy-src").trim()
        if (
            lazySrc.startsWith("http") &&
            !lazySrc.contains("/images/pixel.gif")
        ) {
            return lazySrc
        }

        val src = image.attr("src").trim()
        if (
            src.startsWith("http") &&
            !src.contains("/images/pixel.gif")
        ) {
            return src
        }

        return null
    }

    private fun parseResults(
        document: Document
    ): List<SearchResponse> {

        return document.select("article.post-movie")
            .mapNotNull { article ->

                val link = article.selectFirst("h4 a[href]")
                    ?: return@mapNotNull null

                val title = link.text()
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val href = link.attr("href")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                newTvSeriesSearchResponse(
                    title,
                    absoluteUrl(href),
                    TvType.TvSeries
                ) {
                    posterUrl = extractPoster(article)
                }
            }
            .distinctBy { it.url }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val baseUrl = request.data.trimEnd('/')

        val url = if (page == 1) {
            baseUrl
        } else {
            "$baseUrl?page=$page"
        }

        val document = app.get(url).document

        return newHomePageResponse(
            request.name,
            parseResults(document)
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val response = app.post(
            "$mainUrl/searchq",
            data = mapOf(
                "searchq" to query
            ),
            headers = mapOf(
                "Content-Type" to
                    "application/x-www-form-urlencoded; charset=UTF-8",
                "X-Requested-With" to "XMLHttpRequest"
            ),
            referer = "$mainUrl/"
        )

        val results = parseResults(response.document)

        if (results.isNotEmpty()) {
            return results
        }

        val encodedQuery = URLEncoder.encode(
            query,
            "UTF-8"
        )

        return runCatching {
            parseResults(
                app.get(
                    "$mainUrl/searchq?searchq=$encodedQuery",
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest"
                    ),
                    referer = "$mainUrl/"
                ).document
            )
        }.getOrDefault(emptyList())
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document = app.get(url).document

        val title = document.selectFirst(
            "h1, h2.entry-title, h1.entry-title"
        )?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(
                "meta[property='og:title']"
            )?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = document.selectFirst(
            "meta[property='og:image']"
        )?.attr("content")
            ?.trim()
            ?.takeIf { it.startsWith("http") }
            ?: document.selectFirst(
                "img[data-src], img[data-lazy-src], img[src]"
            )?.let {

                val dataSrc = it.attr("data-src").trim()

                when {
                    dataSrc.startsWith("http") ->
                        dataSrc

                    it.attr("data-lazy-src")
                        .trim()
                        .startsWith("http") ->
                        it.attr("data-lazy-src").trim()

                    it.attr("src")
                        .trim()
                        .startsWith("http") &&
                        !it.attr("src")
                            .contains("/images/pixel.gif") ->
                        it.attr("src").trim()

                    else -> null
                }
            }

        val plot = document.selectFirst(
            "meta[name='description'], meta[property='og:description']"
        )?.attr("content")?.trim()

        val year = document.selectFirst(
            ".post-date"
        )?.text()
            ?.trim()
            ?.toIntOrNull()

        /*
         * OurDrama puts the complete episode list on the series page.
         * The site itself displays newest -> oldest.
         * Keep that order instead of sorting it again.
         */
        val episodes = document.select(
            "a[href*='/episode/']"
        )
            .mapNotNull { link ->

                val episodeUrl = link.attr("href")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val fullUrl = absoluteUrl(episodeUrl)

                val episodeText = link.text()
                    .trim()

                val episodeNumber =
                    Regex(
                        """(?:الحلقة|episode)\s*(?:رقم\s*)?(\d+)""",
                        RegexOption.IGNORE_CASE
                    )
                        .find(episodeText)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                    ?: Regex(
                        """(?:الحلقة|episode)[^\d]*(\d+)""",
                        RegexOption.IGNORE_CASE
                    )
                        .find(fullUrl)
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
                    ?: Regex(
                        """/season-(\d+)/""",
                        RegexOption.IGNORE_CASE
                    )
                        .find(fullUrl)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                    ?: 1

                Triple(
                    fullUrl,
                    season,
                    episodeNumber
                )
            }
            .distinctBy { it.first }
            .map { (episodeUrl, season, episodeNumber) ->

                newEpisode(episodeUrl) {

                    name = "Episode $episodeNumber"

                    this.season = season

                    this.episode = episodeNumber
                }
            }

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

    private fun extractCsrfToken(
        document: Document
    ): String? {

        document.selectFirst(
            "meta[name='csrf-token']"
        )?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val html = document.html()

        val patterns = listOf(

            Regex(
                """['"]X-CSRF-TOKEN['"]\s*:\s*['"]([^'"]+)['"]"""
            ),

            Regex(
                """X-CSRF-TOKEN\s*['"]?\s*[:=]\s*['"]([^'"]+)['"]"""
            )
        )

        for (pattern in patterns) {

            pattern.find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }

        return null
    }

    private fun extractIframeUrls(
        codePlay: String
    ): List<String> {

        val document = Jsoup.parseBodyFragment(
            codePlay
        )

        return document.select(
            "iframe[src], iframe[data-src]"
        )
            .mapNotNull { iframe ->

                val src = iframe.attr("src")
                    .ifBlank {
                        iframe.attr("data-src")
                    }
                    .trim()

                when {
                    src.startsWith("http://") ->
                        src

                    src.startsWith("https://") ->
                        src

                    src.startsWith("//") ->
                        "https:$src"

                    else -> null
                }
            }
            .distinct()
    }

    /*
     * Extract the file code from:
     * https://ourdrama.cc/v/xxxxxxxx
     */
    private fun extractFileCode(
        iframeUrl: String
    ): String? {

        return Regex(
            """https?://[^/]+/v/([A-Za-z0-9_-]+)"""
        )
            .find(iframeUrl)
            ?.groupValues
            ?.getOrNull(1)
    }

    /*
     * Try to find an HLS master URL in a response/page.
     *
     * We do NOT hardcode the observed HLS host because it is dynamic.
     */
    private fun extractHlsUrl(
        text: String
    ): String? {

        val normalized = text
            .replace("\\/", "/")
            .replace("\\u002F", "/")

        val patterns = listOf(

            Regex(
                """https?://[^"'\\\s<>]+/master\.txt"""
            ),

            Regex(
                """https?://[^"'\\\s<>]+/master\.m3u8"""
            ),

            Regex(
                """["'](?:file|src|source|hls|playlist)["']\s*[:=]\s*["'](https?://[^"']+)["']""",
                RegexOption.IGNORE_CASE
            )
        )

        for (pattern in patterns) {

            pattern.find(normalized)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.let { found ->

                    if (
                        found.contains("/master.txt") ||
                        found.contains(".m3u8")
                    ) {
                        return found
                    }
                }
        }

        return null
    }

    /*
     * Fetch the embed page and try to obtain the dynamically
     * generated HLS URL.
     */
    private suspend fun extractHlsFromEmbed(
        iframeUrl: String,
        episodeUrl: String
    ): String? {

        val iframeResponse = app.get(
            iframeUrl,
            referer = episodeUrl
        )

        val iframeHtml = iframeResponse.text

        extractHlsUrl(
            iframeHtml
        )?.let {
            return it
        }

        val fileCode = extractFileCode(
            iframeUrl
        ) ?: return null

        /*
         * OurDrama's embed player exposes the file through
         * /dl?op=view.
         *
         * We parse the returned page/JSON rather than
         * inventing the final HLS URL.
         */
        val viewUrl =
            "https://ourdrama.cc/dl" +
                "?op=view" +
                "&file_code=$fileCode" +
                "&embed=1" +
                "&referer=" +
                "&adb=1"

        val viewResponse = runCatching {
            app.get(
                viewUrl,
                referer = iframeUrl
            )
        }.getOrNull()

        if (viewResponse != null) {

            val viewText = viewResponse.text

            extractHlsUrl(
                viewText
            )?.let {
                return it
            }

            /*
             * Some responses may be JSON containing
             * another player URL/script.
             */
            runCatching {
                val json = JSONObject(viewText)

                val possibleValues = listOf(
                    "url",
                    "file",
                    "src",
                    "source",
                    "hls",
                    "playlist",
                    "code"
                )

                for (key in possibleValues) {

                    val value = json.optString(
                        key
                    ).trim()

                    if (value.isNotBlank()) {

                        extractHlsUrl(
                            value
                        )?.let {
                            return@runCatching it
                        }
                    }
                }
            }
        }

        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val episodeUrl = data

        val episodeDocument = app.get(
            episodeUrl,
            referer = "$mainUrl/"
        ).document

        val csrfToken = extractCsrfToken(
            episodeDocument
        ) ?: return false

        val serverCodes = episodeDocument.select(
            ".server-list-menu .getplay a[data-code]"
        )
            .mapNotNull { server ->

                val code = server.attr(
                    "data-code"
                )
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val order = server.attr(
                    "data-orders"
                )
                    .trim()
                    .toIntOrNull()
                    ?: Int.MAX_VALUE

                order to code
            }
            .distinctBy {
                it.second
            }
            .sortedBy {
                it.first
            }

        if (serverCodes.isEmpty()) {
            return false
        }

        var callbackCount = 0

        for ((_, serverCode) in serverCodes) {

            try {

                val response = app.post(
                    "$mainUrl/ajax-request",

                    data = mapOf(
                        "action" to "iframe_server",
                        "code" to serverCode
                    ),

                    headers = mapOf(
                        "X-CSRF-TOKEN" to csrfToken,
                        "X-Requested-With" to "XMLHttpRequest",
                        "Content-Type" to
                            "application/x-www-form-urlencoded; charset=UTF-8"
                    ),

                    referer = episodeUrl
                )

                val responseText = response.text

                println(
                    "OURDRAMA AJAX RESPONSE: $responseText"
                )

                val json = runCatching {
                    JSONObject(responseText)
                }.getOrElse {
                    throw ErrorLoadingException(
                        "OurDrama: AJAX response is not JSON"
                    )
                }

                if (!json.optBoolean(
                        "status",
                        false
                    )
                ) {
                    continue
                }

                val codePlay = json.optString(
                    "codeplay"
                )
                    .trim()
                    .takeIf {
                        it.isNotBlank()
                    }
                    ?: continue

                val iframeUrls = extractIframeUrls(
                    codePlay
                )

                if (iframeUrls.isEmpty()) {
                    continue
                }

                for (iframeUrl in iframeUrls) {

                    /*
                     * First try CloudStream's registered
                     * extractors.
                     */
                    val extractorLoaded =
                        loadExtractor(
                            iframeUrl,
                            episodeUrl,
                            subtitleCallback
                        ) { link ->

                            callbackCount++

                            callback(link)
                        }

                    if (
                        extractorLoaded &&
                        callbackCount > 0
                    ) {
                        return true
                    }

                    /*
                     * Fallback for OurDrama's own
                     * dynamic embed player.
                     */
                    val hlsUrl =
                        extractHlsFromEmbed(
                            iframeUrl,
                            episodeUrl
                        )

                    if (
                        !hlsUrl.isNullOrBlank()
                    ) {

                        callback(
                            ExtractorLink(
                                source = name,
                                name = "OurDrama",
                                url = hlsUrl,
                                referer = iframeUrl,
                                quality = Qualities.P720.value,
                                isM3u8 = true
                            )
                        )

                        callbackCount++

                        return true
                    }
                }

            } catch (e: ErrorLoadingException) {

                throw e

            } catch (_: Exception) {

                continue
            }
        }

        println(
            "OURDRAMA CALLBACK COUNT: $callbackCount"
        )

        return callbackCount > 0
    }
}
