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

    override val mainPage = mainPageOf(
        "$mainUrl/serie/cate/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D8%A3%D8%B3%D9%8A%D9%88%D9%8A%D8%A9" to "المسلسلات الآسيوية",
        "$mainUrl/serie/cate/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D9%83%D9%88%D8%B1%D9%8A%D8%A9" to "المسلسلات الكورية",
        "$mainUrl/serie/cate/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D8%B5%D9%8A%D9%86%D9%8A%D8%A9" to "المسلسلات الصينية",
        "$mainUrl/serie/cate/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D9%8A%D8%A7%D8%A8%D8%A7%D9%86%D9%8A%D8%A9" to "المسلسلات اليابانية",
        "$mainUrl/serie/cate/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D8%AA%D8%A7%D9%8A%D9%88%D8%A7%D9%86%D9%8A%D8%A9" to "المسلسلات التايوانية",
        "$mainUrl/serie/cate/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D8%AA%D8%A7%D9%8A%D9%84%D8%A7%D9%86%D8%AF%D9%8A%D8%A9" to "المسلسلات التايلاندية"
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

    private fun posterUrl(element: Element): String? {
        val image = element.selectFirst(
            "img[data-src], img[data-lazy-src], img[src]"
        ) ?: return null

        return image.attr("data-src")
            .ifBlank { image.attr("data-lazy-src") }
            .ifBlank { image.attr("src") }
            .trim()
            .takeIf {
                it.isNotBlank() &&
                    !it.contains("pixel.gif", ignoreCase = true)
            }
    }

    private fun parseSearchResult(element: Element): SearchResponse? {
        val titleLink = element.selectFirst("h4 a[href]") ?: return null

        val url = absoluteUrl(titleLink)

        if (!url.startsWith(mainUrl)) {
            return null
        }

        val title = titleLink.text().trim()

        if (title.isBlank()) {
            return null
        }

        return newTvSeriesSearchResponse(
            title,
            url,
            TvType.TvSeries
        ) {
            posterUrl = posterUrl(element)
        }
    }

    private fun parseResults(document: Document): List<SearchResponse> {
        return document
            .select("article.post-movie")
            .mapNotNull { parseSearchResult(it) }
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

        val document = app.get(
            url,
            referer = mainUrl
        ).document

        val results = parseResults(document)

        return newHomePageResponse(
            request.name,
            results,
            hasNext = results.isNotEmpty()
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

        return parseResults(document)
    }

    private fun getEpisodeNumber(
        text: String,
        url: String
    ): Int? {

        val textRegex = Regex(
            """(?:الحلقة|episode|ep)[^\d]*(\d+)""",
            RegexOption.IGNORE_CASE
        )

        return textRegex.find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex(
                """-(\d+)(?:-مترجم)?/?$"""
            )
                .find(url)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    private fun parseEpisodes(
        document: Document
    ): List<Episode> {

        return document
            .select("a[href*='/episode/']")
            .mapNotNull { link ->

                val episodeUrl = absoluteUrl(link)

                if (episodeUrl.isBlank()) {
                    return@mapNotNull null
                }

                val text = link.text().trim()

                val episode = getEpisodeNumber(
                    text,
                    episodeUrl
                ) ?: return@mapNotNull null

                newEpisode(episodeUrl) {
                    name = text.ifBlank {
                        "Episode $episode"
                    }
                    season = 1
                    this.episode = episode
                }
            }
            .distinctBy { it.data }
            .sortedBy { it.episode ?: 0 }
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document = app.get(
            url,
            referer = mainUrl
        ).document

        val title = document
            .selectFirst("h1")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document
                .selectFirst("meta[property='og:title']")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = document
            .selectFirst("meta[property='og:image']")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document
                .selectFirst("img[data-src], img[src]")
                ?.let {
                    it.attr("data-src")
                        .ifBlank { it.attr("src") }
                }
                ?.takeIf {
                    it.isNotBlank() &&
                        !it.contains("pixel.gif", true)
                }

        val plot = document
            .selectFirst(
                "meta[name='description'], meta[property='og:description']"
            )
            ?.attr("content")
            ?.trim()

        val year = document
            .selectFirst(".post-date")
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
            this.year = year
            this.plot = plot
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

        val vidmo = document
            .select(".server-list-menu .getplay a")
            .firstOrNull { element ->
                element.text()
                    .contains("Vidmo", ignoreCase = true)
            }
            ?: return false

        val code = vidmo
            .attr("data-code")
            .trim()

        if (code.isBlank()) {
            return false
        }

        val csrfToken =
            document
                .selectFirst("meta[name='csrf-token']")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: Regex(
                    """X-CSRF-TOKEN['"]\s*:\s*['"]([^'"]+)"""
                )
                    .find(document.html())
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim()

        if (csrfToken.isNullOrBlank()) {
            return false
        }

        val response = app.post(
            "$mainUrl/ajax-request",
            data = mapOf(
                "action" to "iframe_server",
                "code" to code
            ),
            headers = mapOf(
                "X-CSRF-TOKEN" to csrfToken,
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to data
            )
        )

        val responseText = response.text

        val codePlay = Regex(
            """"codeplay"\s*:\s*"((?:\\.|[^"])*)""""
        )
            .find(responseText)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace("\\/", "/")
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")

        if (codePlay.isNullOrBlank()) {
            return false
        }

        val iframeUrl = Regex(
            """<iframe[^>]+src=["']([^"']+)["']"""
        )
            .find(codePlay)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()

        if (iframeUrl.isNullOrBlank()) {
            return false
        }

        /*
         * OurDrama returns Vidmoly mirrors such as:
         * https://vidmoly.net/embed-XXXXXXXX.html
         *
         * The actual player redirects/uses:
         * https://vidmoly.biz/embed-XXXXXXXX.html
         *
         * Force the iframe onto Vidmoly.biz so CloudStream's
         * Vidmolybiz extractor handles it.
         */

        val embedPath = when {
            iframeUrl.startsWith("https://vidmoly.net/") ->
                iframeUrl.removePrefix("https://vidmoly.net/")

            iframeUrl.startsWith("http://vidmoly.net/") ->
                iframeUrl.removePrefix("http://vidmoly.net/")

            iframeUrl.startsWith("https://vidmoly.biz/") ->
                iframeUrl.removePrefix("https://vidmoly.biz/")

            iframeUrl.startsWith("http://vidmoly.biz/") ->
                iframeUrl.removePrefix("http://vidmoly.biz/")

            iframeUrl.startsWith("/") ->
                iframeUrl.trimStart('/')

            else ->
                iframeUrl.substringAfterLast("/")
        }

        if (embedPath.isBlank()) {
            return false
        }

        val finalUrl = "https://vidmoly.biz/$embedPath"

        return loadExtractor(
            finalUrl,
            data,
            subtitleCallback,
            callback
        )
    }
}
