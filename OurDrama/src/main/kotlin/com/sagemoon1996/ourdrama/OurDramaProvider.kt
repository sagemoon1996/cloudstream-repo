package com.sagemoon1996.ourdrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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

    private val browserHeaders = mapOf(
        "User-Agent" to
                "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8"
    )

    private val asianCategories = listOf(
        "$mainUrl/serie/cate/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D8%A3%D8%B3%D9%8A%D9%88%D9%8A%D8%A9" to "المسلسلات الآسيوية",
        "$mainUrl/serie/cate/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D9%83%D9%88%D8%B1%D9%8A%D8%A9" to "مسلسلات كورية",
        "$mainUrl/serie/cate/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D8%B5%D9%8A%D9%86%D9%8A%D8%A9" to "مسلسلات صينية",
        "$mainUrl/serie/cate/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D9%8A%D8%A7%D8%A8%D8%A7%D9%86%D9%8A%D8%A9" to "مسلسلات يابانية",
        "$mainUrl/serie/cate/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D8%AA%D8%A7%D9%8A%D9%88%D8%A7%D9%86%D9%8A%D8%A9" to "مسلسلات تايوانية",
        "$mainUrl/serie/cate/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D8%AA%D8%A7%D9%8A%D9%84%D8%A7%D9%86%D8%AF%D9%8A%D8%A9" to "مسلسلات تايلاندية"
    )

    override val mainPage = mainPageOf(
        *asianCategories.toTypedArray()
    )

    private fun absoluteUrl(url: String): String {
        if (url.isBlank()) return url

        return when {
            url.startsWith("http://") -> url.replaceFirst("http://", "https://")
            url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    private fun posterUrl(element: Element): String? {
        val image = element.selectFirst("img") ?: return null

        val url = image.attr("data-src").ifBlank {
            image.attr("data-lazy-src")
        }.ifBlank {
            image.attr("src")
        }

        if (url.isBlank()) return null
        if (url.contains("pixel.gif")) return null

        return absoluteUrl(url)
    }

    private fun parseCard(element: Element): SearchResponse? {
        val anchor = element.selectFirst("h4 a[href]")
            ?: element.selectFirst("a[href]")
            ?: return null

        val title = anchor.text().trim()
        if (title.isBlank()) return null

        val href = absoluteUrl(anchor.attr("href"))

        val poster = posterUrl(element)

        return newTvSeriesSearchResponse(
            title = title,
            url = href,
            posterUrl = poster
        )
    }

    private fun parseResults(document: Document): List<SearchResponse> {
        return document
            .select("article.post-movie")
            .mapNotNull { parseCard(it) }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = if (page <= 1) {
            request.data
        } else {
            "${request.data}?page=$page"
        }

        val document = app.get(
            url,
            headers = browserHeaders,
            referer = mainUrl
        ).document

        val results = parseResults(document)

        return newHomePageResponse(
            request.name,
            results,
            hasNext = results.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {

        val encodedQuery = URLEncoder.encode(
            query.trim(),
            "UTF-8"
        )

        val url = "$mainUrl/?s=$encodedQuery"

        val document = app.get(
            url,
            headers = browserHeaders,
            referer = mainUrl
        ).document

        return parseResults(document)
    }

    private fun getEpisodeNumber(element: Element): Int? {

        val text = element.text()

        val patterns = listOf(
            Regex("""الحلقة\s*(\d+)"""),
            Regex("""episode\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""ep\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""[^\d](\d{1,4})[^\d]""")
        )

        for (pattern in patterns) {
            val match = pattern.find(text)
            val number = match?.groupValues?.getOrNull(1)?.toIntOrNull()

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

                val href = element.attr("href")
                if (href.isBlank()) return@mapNotNull null

                val url = absoluteUrl(href)

                val number = getEpisodeNumber(element)
                    ?: return@mapNotNull null

                val name = element.text().trim()
                    .ifBlank { "الحلقة $number" }

                Episode(
                    data = url,
                    name = name,
                    season = 1,
                    episode = number
                )
            }
            .distinctBy { it.data }
            .sortedBy { it.episode }
    }

    override suspend fun load(url: String): LoadResponse? {

        val document = app.get(
            url,
            headers = browserHeaders,
            referer = mainUrl
        ).document

        val title = document.selectFirst(
            "h1"
        )?.text()?.trim()
            ?: document.selectFirst(
                "meta[property='og:title']"
            )?.attr("content")?.trim()
            ?: return null

        val poster = document.selectFirst(
            "meta[property='og:image']"
        )?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(
                "img[data-src]"
            )?.attr("data-src")
            ?.takeIf { it.isNotBlank() }
            ?.let { absoluteUrl(it) }

        val plot = document.selectFirst(
            "meta[name='description']"
        )?.attr("content")
            ?.trim()

        val year = document.selectFirst(
            ".post-date"
        )?.text()
            ?.trim()
            ?.toIntOrNull()

        val episodes = parseEpisodes(document)

        return newTvSeriesLoadResponse(
            name = title,
            url = url,
            type = TvType.TvSeries,
            episodes = episodes
        ) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    private fun extractCsrfToken(document: Document): String? {

        val scripts = document.select("script")

        for (script in scripts) {
            val scriptText = script.data()

            val match = Regex(
                """['"]X-CSRF-TOKEN['"]\s*:\s*['"]([^'"]+)['"]"""
            ).find(scriptText)

            if (match != null) {
                return match.groupValues[1]
            }

            val directMatch = Regex(
                """X-CSRF-TOKEN\s*['"]?\s*:\s*['"]([^'"]+)['"]"""
            ).find(scriptText)

            if (directMatch != null) {
                return directMatch.groupValues[1]
            }
        }

        return null
    }

    private fun findServerCode(
        document: Document,
        serverName: String
    ): String? {

        return document
            .select(".server-list-menu .getplay a")
            .firstOrNull {
                it.text()
                    .trim()
                    .contains(serverName, ignoreCase = true)
            }
            ?.attr("data-code")
            ?.takeIf { it.isNotBlank() }
    }

    private suspend fun loadServerIframe(
        episodeUrl: String,
        document: Document,
        serverName: String
    ): String? {

        val token = extractCsrfToken(document)
            ?: return null

        val code = findServerCode(
            document,
            serverName
        ) ?: return null

        val response = app.post(
            "$mainUrl/ajax-request",
            headers = browserHeaders + mapOf(
                "Content-Type" to
                        "application/x-www-form-urlencoded; charset=UTF-8",
                "X-Requested-With" to "XMLHttpRequest",
                "X-CSRF-TOKEN" to token
            ),
            referer = episodeUrl,
            data = mapOf(
                "action" to "iframe_server",
                "code" to code
            )
        )

        val responseText = response.text

        val iframeUrl = Regex(
            """"codeplay"\s*:\s*"<iframe[^>]+src=\\"([^"]+)\\""""
        ).find(responseText)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace("\\/", "/")

        return iframeUrl
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val episodeUrl = data

        val document = app.get(
            episodeUrl,
            headers = browserHeaders,
            referer = mainUrl
        ).document

        val iframeUrl = loadServerIframe(
            episodeUrl = episodeUrl,
            document = document,
            serverName = "Vidmo"
        ) ?: return false

        return loadExtractor(
            url = iframeUrl,
            referer = episodeUrl,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }
}
