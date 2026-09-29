package com.sagemoon1996.ourdrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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
        if (dataSrc.startsWith("http") &&
            !dataSrc.contains("/images/pixel.gif")
        ) {
            return dataSrc
        }

        val lazySrc = image.attr("data-lazy-src").trim()
        if (lazySrc.startsWith("http") &&
            !lazySrc.contains("/images/pixel.gif")
        ) {
            return lazySrc
        }

        val src = image.attr("src").trim()
        if (src.startsWith("http") &&
            !src.contains("/images/pixel.gif")
        ) {
            return src
        }

        return null
    }

    private fun parseResults(document: Document): List<SearchResponse> {
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

        println("OURDRAMA LOAD START: $url")

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

        val poster =
            document.selectFirst(
                "meta[property='og:image']"
            )?.attr("content")
                ?.trim()
                ?.takeIf { it.startsWith("http") }
                ?: extractPosterFromDocument(document)

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

                val href = link.attr("href")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val episodeUrl = absoluteUrl(href)

                val text = link.text().trim()

                val episodeNumber =
                    Regex(
                        """(?:الحلقة|episode)[^\d]*(\d+)""",
                        RegexOption.IGNORE_CASE
                    )
                        .find(text)
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
                    ?: return@mapNotNull null

                val season =
                    Regex(
                        """(?:الموسم|season)[^\d]*(\d+)""",
                        RegexOption.IGNORE_CASE
                    )
                        .find(text)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                    ?: 1

                Triple(
                    episodeUrl,
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

        println("OURDRAMA LOAD EPISODES: ${episodes.size}")

        episodes.forEach {
            println("OURDRAMA EPISODE URL: ${it.data}")
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

    private fun extractPosterFromDocument(
        document: Document
    ): String? {

        val image = document.selectFirst(
            "img[data-src], img[data-lazy-src], img[src]"
        ) ?: return null

        val dataSrc = image.attr("data-src").trim()
        if (dataSrc.startsWith("http") &&
            !dataSrc.contains("/images/pixel.gif")
        ) {
            return dataSrc
        }

        val lazySrc = image.attr("data-lazy-src").trim()
        if (lazySrc.startsWith("http") &&
            !lazySrc.contains("/images/pixel.gif")
        ) {
            return lazySrc
        }

        val src = image.attr("src").trim()
        if (src.startsWith("http") &&
            !src.contains("/images/pixel.gif")
        ) {
            return src
        }

        return null
    }

    private fun extractCsrfToken(
        document: Document
    ): String? {

        val html = document.html()

        val patterns = listOf(

            Regex(
                """['"]X-CSRF-TOKEN['"]\s*:\s*['"]([^'"]+)['"]"""
            ),

            Regex(
                """X-CSRF-TOKEN\s*:\s*['"]([^'"]+)['"]"""
            ),

            Regex(
                """X-CSRF-TOKEN\s*=\s*['"]([^'"]+)['"]"""
            )
        )

        for (pattern in patterns) {

            val token = pattern.find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()

            if (!token.isNullOrBlank()) {
                return token
            }
        }

        return null
    }

    private fun extractIframeUrls(
        codePlay: String
    ): List<String> {

        val document = Jsoup.parseBodyFragment(codePlay)

        return document.select(
            ".watch-embed-player iframe[src], iframe[src], iframe[data-src]"
        )
            .mapNotNull { iframe ->

                val src = iframe.attr("src")
                    .ifBlank {
                        iframe.attr("data-src")
                    }
                    .trim()

                when {
                    src.startsWith("https://") -> src
                    src.startsWith("http://") -> src
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

                server.attr("data-code")
                    .trim()
                    .takeIf { it.isNotBlank() }
            }
            .distinct()

        if (serverCodes.isEmpty()) {
            return false
        }

        for (serverCode in serverCodes) {

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

                val json = runCatching {
                    org.json.JSONObject(response.text)
                }.getOrNull() ?: continue

                if (!json.optBoolean("status", false)) {
                    continue
                }

                val codePlay = json.optString(
                    "codeplay"
                )
                    .trim()

                if (codePlay.isBlank()) {
                    continue
                }

                val iframeUrls = extractIframeUrls(
                    codePlay
                )

                if (iframeUrls.isEmpty()) {
                    continue
                }

                for (iframeUrl in iframeUrls) {

                    val result = runCatching {
                        loadExtractor(
                            iframeUrl,
                            episodeUrl,
                            subtitleCallback
                        ) { link ->
                            println("OURDRAMA EXTRACTOR LINK: $link")
                            callback(link)
                        }
                    }.getOrDefault(false)

                    println("OURDRAMA EXTRACTOR RESULT: $result")
                }

            } catch (_: Exception) {
                continue
            }
        }

        return false
    }
}
