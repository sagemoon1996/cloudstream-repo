package com.sagemoon1996.aradrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AradramaProvider : MainAPI() {

    override var mainUrl = "https://aradramatv.cc"
    override var name = "Aradrama"
    override var lang = "ar"

    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "$mainUrl/category/ongoing/" to "الدراما التي تبث حاليا",
        "$mainUrl/category/recent-completed/" to "الدراما المنتهية مؤخرا",
        "$mainUrl/category/%d8%af%d8%b1%d8%a7%d9%85%d8%a7-%d8%aa%d9%85-%d8%a7%d8%b9%d8%a7%d8%af%d8%a9-%d8%b1%d9%81%d8%b9%d9%87%d8%a7/" to "دراما تم إعادة رفعها",
        "$mainUrl/category/%d8%a7%d9%84%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d9%84%d8%a2%d8%b3%d9%8a%d9%88%d9%8a%d8%a9/" to "الأفلام الآسيوية",
        "$mainUrl/category/serie/korea/" to "الدراما الكورية",
        "$mainUrl/category/serie/chinese-taiwan/" to "الدراما الصينية والتايوانية",
        "$mainUrl/category/serie/japanese/" to "الدراما اليابانية",
        "$mainUrl/category/serie/tailand/" to "الدراما التايلاندية",
        "$mainUrl/category/serie/" to "كل الدراما"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = if (page == 1) {
            request.data
        } else {
            "${request.data.trimEnd('/')}/page/$page/"
        }

        val document = app.get(
            url,
            referer = mainUrl
        ).document

        val items = document
            .select("article")
            .mapNotNull { parseSearchResult(it) }
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = items.isNotEmpty()
        )
    }

    private fun absoluteUrl(
        element: Element
    ): String {

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

    private fun parseSearchResult(
        element: Element
    ): SearchResponse? {

        val link = element.selectFirst("a[href]")
            ?: return null

        val href = absoluteUrl(link)

        if (!href.startsWith(mainUrl)) {
            return null
        }

        val title = (
            element.selectFirst(
                ".title, .tt, .entry-title, h2, h3, h4"
            )?.text()
                ?: link.attr("title")
                ?: link.text()
        ).trim()

        if (title.isBlank()) {
            return null
        }

        val poster = element
            .selectFirst("img[data-src], img[src]")
            ?.let {
                it.attr("data-src")
                    .ifBlank { it.attr("src") }
            }
            ?.takeIf { it.startsWith("http") }

        val isMovie =
            href.contains("/الافلام/", ignoreCase = true) ||
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
            .select("article")
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

    private fun parseEpisodes(
        document: Document
    ): List<Episode> {

        return document
            .select("article a[href]")
            .mapNotNull { link ->

                val href = absoluteUrl(link)

                if (!href.startsWith(mainUrl)) {
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
    }

    private fun findEpisodeListUrl(
        document: Document,
        seriesUrl: String
    ): String? {

        val seriesSlug = seriesUrl
            .trimEnd('/')
            .substringAfterLast('/')
            .lowercase()

        return document
            .select("a[href]")
            .map { link ->
                link to absoluteUrl(link)
            }
            .firstOrNull { (link, href) ->

                if (!href.startsWith(mainUrl)) {
                    return@firstOrNull false
                }

                val text = link.text()
                    .trim()
                    .lowercase()

                val hrefLower = href.lowercase()

                val isEpisodePage =
                    hrefLower.contains(
                        "/category/episodes/"
                    )

                val hasSeriesSlug =
                    seriesSlug.isNotBlank() &&
                    hrefLower.contains(seriesSlug)

                val hasEpisodeText =
                    text.contains("مشاهدة حلقات") ||
                    text.contains("حلقات المسلسل")

                isEpisodePage &&
                    (hasSeriesSlug || hasEpisodeText)
            }
            ?.second
    }

    private suspend fun getSeriesEpisodes(
        document: Document,
        seriesUrl: String
    ): List<Episode> {

        val episodeListUrl = findEpisodeListUrl(
            document,
            seriesUrl
        )

        if (episodeListUrl == null) {
            return parseEpisodes(document)
                .sortedBy { it.episode }
        }

        val episodeDocument = try {
            app.get(
                episodeListUrl,
                referer = seriesUrl
            ).document
        } catch (_: Exception) {
            return emptyList()
        }

        return parseEpisodes(episodeDocument)
            .sortedBy { it.episode }
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document = app.get(
            url,
            referer = mainUrl
        ).document

        val title =
            document.selectFirst(
                "h1.entry-title, .entry-title, h1"
            )?.text()?.trim()
                ?: document.selectFirst(
                    "meta[property=og:title]"
                )?.attr("content")?.trim()
                ?: return null

        val poster =
            document.selectFirst(
                "meta[property=og:image]"
            )?.attr("content")
                ?.takeIf { it.startsWith("http") }
                ?: document.selectFirst(
                    ".poster img, .cover img, img"
                )?.let {
                    it.attr("data-src")
                        .ifBlank { it.attr("src") }
                }
                ?.takeIf { it.startsWith("http") }

        val description =
            document.selectFirst(
                "meta[property=og:description]"
            )?.attr("content")?.trim()
                ?: document.selectFirst(
                    ".description, .desc, .entry-content"
                )?.text()?.trim()

        val isMovie =
            url.contains(
                "/الافلام/",
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

        val episodes = getSeriesEpisodes(
            document,
            url
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

    /*
     * هذا الجزء هو الجزء الذي كان يخدم.
     * لم نغير طريقة استخراج السيرفرات/Filemoon.
     */
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

        val serverElements = document
            .select("li.server[data-url]")

        val serverUrls = serverElements
            .mapNotNull { server ->
                server.attr("data-url")
                    .trim()
                    .takeIf { it.startsWith("http") }
            }
            .distinct()

        var foundLinks = false

        val filemoonUrls = serverUrls.filter {
            it.contains(
                "bysevepoin.com",
                ignoreCase = true
            )
        }

        for (serverUrl in filemoonUrls) {

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

        val otherUrls = serverUrls.filterNot {
            filemoonUrls.contains(it)
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

        val iframeUrls = document
            .select("iframe[src], iframe[data-src]")
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

        return foundLinks
    }
}
