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

    private fun extractPoster(element: Element): String? {
        val image = element.selectFirst(
            "img[data-src], img[data-lazy-src], img[src]"
        )

        if (image != null) {
            val dataSrc = image.attr("data-src").trim()
            if (dataSrc.startsWith("http")) {
                return dataSrc
            }

            val lazySrc = image.attr("data-lazy-src").trim()
            if (lazySrc.startsWith("http")) {
                return lazySrc
            }

            val src = image.attr("src").trim()
            if (
                src.startsWith("http") &&
                !src.contains("/images/pixel.gif")
            ) {
                return src
            }
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

                val poster = extractPoster(article)

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

        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        val document = app.post(
            "$mainUrl/searchq",
            data = mapOf(
                "searchq" to query
            ),
            headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded"
            ),
            referer = "$mainUrl/"
        ).document

        return parseResults(document)
            .ifEmpty {
                runCatching {
                    parseResults(
                        app.get(
                            "$mainUrl/searchq?searchq=$encodedQuery",
                            referer = "$mainUrl/"
                        ).document
                    )
                }.getOrDefault(emptyList())
            }
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
                    dataSrc.startsWith("http") -> dataSrc

                    it.attr("data-lazy-src")
                        .trim()
                        .startsWith("http") ->
                        it.attr("data-lazy-src").trim()

                    it.attr("src")
                        .trim()
                        .startsWith("http") &&
                        !it.attr("src").contains("/images/pixel.gif") ->
                        it.attr("src").trim()

                    else -> null
                }
            }

        val plot = document.selectFirst(
            "meta[name='description'], meta[property='og:description']"
        )?.attr("content")
            ?.trim()

        val year = document.selectFirst(
            ".post-date"
        )?.text()
            ?.trim()
            ?.toIntOrNull()

        val episodes = document.select(
            "a[href*='/episode/']"
        )
            .mapNotNull { link ->

                val episodeUrl = link.attr("href")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val episodeText = link.text().trim()

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
                            """(?:الحلقة|episode)[^\d]*(\d+)""",
                            RegexOption.IGNORE_CASE
                        )
                            .find(episodeUrl)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                        ?: Regex(
                            """-(\d+)(?:/)?$"""
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
                        ?: Regex(
                            """/season-(\d+)/""",
                            RegexOption.IGNORE_CASE
                        )
                            .find(episodeUrl)
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
            .distinctBy { it.data }
            .sortedWith(
                compareBy<Episode> { it.season ?: 1 }
                    .thenBy { it.episode ?: 0 }
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

        val document = Jsoup.parseBodyFragment(codePlay)

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
                    src.startsWith("http://") -> src
                    src.startsWith("https://") -> src
                    src.startsWith("//") -> "https:$src"
                    else -> null
                }
            }
            .distinct()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        /*
         * DIAGNOSTIC TEST ONLY
         *
         * This is intentionally the first executable line.
         * We are testing whether CloudStream actually enters loadLinks().
         */
        throw ErrorLoadingException(
            "OURDRAMA: loadLinks WAS CALLED"
        )

        /*
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

                val code = server.attr("data-code")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val order = server.attr("data-orders")
                    .trim()
                    .toIntOrNull()
                    ?: Int.MAX_VALUE

                order to code
            }
            .distinctBy { it.second }
            .sortedBy { it.first }

        if (serverCodes.isEmpty()) {
            return false
        }

        var loaded = false

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

                if (!json.optBoolean("status", false)) {
                    continue
                }

                val codePlay = json.optString(
                    "codeplay"
                ).takeIf {
                    it.isNotBlank()
                } ?: throw ErrorLoadingException(
                    "OurDrama: AJAX has no codeplay"
                )

                val iframeUrls = extractIframeUrls(
                    codePlay
                )

                if (iframeUrls.isEmpty()) {
                    throw ErrorLoadingException(
                        "OurDrama: codeplay has no iframe"
                    )
                }

                for (iframeUrl in iframeUrls) {

                    val extractorLoaded = loadExtractor(
                        iframeUrl,
                        episodeUrl,
                        subtitleCallback,
                        callback
                    )

                    if (!extractorLoaded) {
                        throw ErrorLoadingException(
                            "OurDrama: no extractor for $iframeUrl"
                        )
                    }

                    loaded = true
                }

                if (loaded) {
                    break
                }

            } catch (e: ErrorLoadingException) {
                throw e
            } catch (_: Exception) {
                continue
            }
        }

        return loaded
        */
    }
}
