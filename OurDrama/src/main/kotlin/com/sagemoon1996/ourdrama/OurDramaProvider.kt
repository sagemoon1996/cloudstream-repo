package com.sagemoon1996.ourdrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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

    private val headers = mapOf(
        "User-Agent" to
            "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36",
        "Accept" to
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8"
    )

    private val asianPages = mainPageOf(
        "$mainUrl/serie/cate/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D8%A3%D8%B3%D9%8A%D9%88%D9%8A%D8%A9" to "المسلسلات الآسيوية",
        "$mainUrl/serie/cate/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D9%83%D9%88%D8%B1%D9%8A%D8%A9" to "مسلسلات كورية",
        "$mainUrl/serie/cate/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D8%B5%D9%8A%D9%86%D9%8A%D8%A9" to "مسلسلات صينية",
        "$mainUrl/serie/cate/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D9%8A%D8%A7%D8%A8%D8%A7%D9%86%D9%8A%D8%A9" to "مسلسلات يابانية",
        "$mainUrl/serie/cate/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D8%AA%D8%A7%D9%8A%D9%88%D8%A7%D9%86%D9%8A%D8%A9" to "مسلسلات تايوانية",
        "$mainUrl/serie/cate/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D8%AA%D8%A7%D9%8A%D9%84%D8%A7%D9%86%D8%AF%D9%8A%D8%A9" to "مسلسلات تايلاندية"
    )

    override val mainPage = asianPages

    private fun absoluteUrl(url: String): String {
        return when {
            url.startsWith("https://") -> url
            url.startsWith("http://") -> url.replaceFirst("http://", "https://")
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    private fun getPoster(element: Element): String? {
        val image = element.selectFirst("img") ?: return null

        val source = listOf(
            image.attr("data-src"),
            image.attr("data-lazy-src"),
            image.attr("src")
        ).firstOrNull {
            it.isNotBlank() && !it.contains("pixel.gif")
        }

        return source?.let(::absoluteUrl)
    }

    private fun parseCard(element: Element): SearchResponse? {
        val link = element.selectFirst("h4 a[href]")
            ?: return null

        val title = link.text().trim()
        val url = link.attr("href").trim()

        if (title.isBlank() || url.isBlank()) {
            return null
        }

        return newTvSeriesSearchResponse(
            title = title,
            url = absoluteUrl(url),
            posterUrl = getPoster(element)
        )
    }

    private fun parseResults(document: Document): List<SearchResponse> {
        return document
            .select("article.post-movie")
            .mapNotNull(::parseCard)
            .distinctBy { it.url }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = if (page == 1) {
            request.data
        } else {
            "${request.data}?page=$page"
        }

        val document = app.get(
            url = url,
            headers = headers,
            referer = mainUrl
        ).document

        val results = parseResults(document)

        val hasNext = document.select(
            "a[href*='page=${page + 1}']"
        ).isNotEmpty()

        return newHomePageResponse(
            request.name,
            results,
            hasNext
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val encoded = URLEncoder
            .encode(query.trim(), "UTF-8")
            .replace("+", "%20")

        val document = app.get(
            "$mainUrl/?s=$encoded",
            headers = headers,
            referer = mainUrl
        ).document

        return parseResults(document)
    }

    private fun parseEpisodeNumber(element: Element): Int? {
        val text = element.text().trim()
        val href = element.attr("href")

        val patterns = listOf(
            Regex("""الحلقة\s*[-:]?\s*(\d+)"""),
            Regex("""الحلقة[-_](\d+)"""),
            Regex("""episode\s*[-:]?\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""ep\s*[-:]?\s*(\d+)""", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val number = pattern
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

            if (number != null) {
                return number
            }
        }

        for (pattern in patterns) {
            val number = pattern
                .find(href)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

            if (number != null) {
                return number
            }
        }

        return null
    }

    private fun parseEpisodes(document: Document): List<Episode> {
        return document
            .select("a[href*='/episode/']")
            .mapNotNull { element ->

                val href = element.attr("href").trim()

                if (href.isBlank()) {
                    return@mapNotNull null
                }

                val number = parseEpisodeNumber(element)
                    ?: return@mapNotNull null

                Episode(
                    name = element.text().trim()
                        .ifBlank { "الحلقة $number" },
                    season = 1,
                    episode = number,
                    data = absoluteUrl(href)
                )
            }
            .distinctBy { it.data }
            .sortedBy { it.episode }
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document = app.get(
            url = url,
            headers = headers,
            referer = mainUrl
        ).document

        val title =
            document.selectFirst("h1")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("meta[property='og:title']")
                    ?.attr("content")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: return null

        val poster =
            document.selectFirst(
                "meta[property='og:image']"
            )?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?: document.selectFirst(
                    "img[data-src]"
                )?.attr("data-src")
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::absoluteUrl)

        val plot =
            document.selectFirst(
                "meta[name='description']"
            )?.attr("content")
                ?.trim()

        val year =
            document.selectFirst(".post-date")
                ?.text()
                ?.trim()
                ?.toIntOrNull()

        val episodes = parseEpisodes(document)

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    private fun findCsrfToken(
        document: Document
    ): String? {

        for (script in document.select("script")) {

            val text = script.data()

            val token = Regex(
                """X-CSRF-TOKEN['"]?\s*:\s*['"]([^'"]+)['"]"""
            ).find(text)
                ?.groupValues
                ?.getOrNull(1)

            if (!token.isNullOrBlank()) {
                return token
            }
        }

        return null
    }

    private fun findVidmoCode(
        document: Document
    ): String? {

        return document
            .select(
                ".server-list-menu .getplay a[data-code]"
            )
            .firstOrNull {
                it.text()
                    .trim()
                    .contains(
                        "Vidmo",
                        ignoreCase = true
                    )
            }
            ?.attr("data-code")
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractIframe(
        response: String
    ): String? {

        val escaped = Regex(
            """<iframe[^>]+src=\\"([^"]+)\\""""
        ).find(response)
            ?.groupValues
            ?.getOrNull(1)

        if (!escaped.isNullOrBlank()) {
            return escaped.replace("\\/", "/")
        }

        return Regex(
            """<iframe[^>]+src="([^"]+)""""
        ).find(response)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace("\\/", "/")
    }

    private suspend fun requestVidmo(
        episodeUrl: String,
        document: Document
    ): String? {

        val token = findCsrfToken(document)
            ?: return null

        val code = findVidmoCode(document)
            ?: return null

        val body = (
            "action=iframe_server" +
                "&code=" +
                URLEncoder.encode(code, "UTF-8")
            ).toRequestBody(
                "application/x-www-form-urlencoded; charset=UTF-8"
                    .toMediaType()
            )

        val response = app.post(
            url = "$mainUrl/ajax-request",
            requestBody = body,
            headers = headers + mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "X-CSRF-TOKEN" to token,
                "Content-Type" to
                    "application/x-www-form-urlencoded; charset=UTF-8"
            ),
            referer = episodeUrl
        )

        if (response.code !in 200..299) {
            return null
        }

        return extractIframe(response.text)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val episodeUrl = data

        val document = app.get(
            url = episodeUrl,
            headers = headers,
            referer = mainUrl
        ).document

        val iframeUrl = requestVidmo(
            episodeUrl,
            document
        ) ?: return false

        return loadExtractor(
            url = iframeUrl,
            referer = episodeUrl,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }
}
