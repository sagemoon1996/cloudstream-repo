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
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

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

        val items = document.select("article, .post, .item, .bsx, .bs")
            .mapNotNull { element ->
                parseSearchResult(element)
            }
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = items.isNotEmpty()
        )
    }

    private fun parseSearchResult(element: Element): SearchResponse? {
        val link = element.selectFirst("a[href]") ?: return null

        val href = link.attr("href").trim()

        if (!href.startsWith("http")) {
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
            ?.let { image ->
                image.attr("data-src")
                    .ifBlank { image.attr("src") }
            }
            ?.takeIf { it.startsWith("http") }

        val isMovie =
            href.contains("/%d8%a7%d9%84%d8%a7%d9%81%d9%84%d8%a7%d9%85/", ignoreCase = true) ||
            href.contains("/الافلام/", ignoreCase = true) ||
            title.contains("فيلم", ignoreCase = true)

        return if (isMovie) {
            newMovieSearchResponse(
                name = title,
                url = href,
                type = TvType.Movie
            ) {
                this.posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(
                title,
                href,
                TvType.TvSeries
            ) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/?s=$encodedQuery"

        val document = app.get(
            url,
            referer = mainUrl
        ).document

        return document.select("article, .post, .item, .bsx, .bs")
            .mapNotNull { element ->
                parseSearchResult(element)
            }
            .distinctBy { it.url }
    }

    private fun episodeNumberFrom(
        text: String,
        href: String
    ): Int? {
        val fromText = Regex(
            """(?:الحلقة|episode|ep)[^\d]*(\d+)""",
            RegexOption.IGNORE_CASE
        ).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

        if (fromText != null) {
            return fromText
        }

        return Regex(
            """(?:الحلقة|episode|ep)[^\d]*(\d+)""",
            RegexOption.IGNORE_CASE
        ).find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun parseEpisodeLinks(
        document: Document
    ): List<Episode> {
        return document.select("a[href]")
            .mapNotNull { link ->
                val href = link.attr("href").trim()

                if (!href.startsWith(mainUrl)) {
                    return@mapNotNull null
                }

                val text = link.text().trim()

                val episode = episodeNumberFrom(
                    text = text,
                    href = href
                ) ?: return@mapNotNull null

                newEpisode(href) {
                    name = if (text.isBlank()) {
                        "الحلقة $episode"
                    } else {
                        text
                    }

                    this.season = 1
                    this.episode = episode
                }
            }
            .distinctBy { it.data }
    }

    private suspend fun getAllEpisodes(
        seriesDocument: Document
    ): List<Episode> {

        val episodeListUrl = seriesDocument
            .select("a[href*='/category/episodes/']")
            .map { it.attr("href").trim() }
            .firstOrNull { it.startsWith(mainUrl) }

        if (episodeListUrl == null) {
            return parseEpisodeLinks(seriesDocument)
                .sortedBy { it.episode }
        }

        val allEpisodes = mutableListOf<Episode>()
        val seenEpisodeUrls = mutableSetOf<String>()

        var page = 1

        while (page <= 50) {

            val pageUrl = if (page == 1) {
                episodeListUrl
            } else {
                "${episodeListUrl.trimEnd('/')}/page/$page/"
            }

            val document = try {
                app.get(
                    pageUrl,
                    referer = mainUrl
                ).document
            } catch (_: Exception) {
                break
            }

            val episodes = parseEpisodeLinks(document)

            val newEpisodes = episodes.filter {
                seenEpisodeUrls.add(it.data)
            }

            if (newEpisodes.isEmpty()) {
                break
            }

            allEpisodes.addAll(newEpisodes)

            page++
        }

        return allEpisodes
            .distinctBy { it.data }
            .sortedBy { it.episode }
    }

    override suspend fun load(url: String): LoadResponse? {
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
                }?.takeIf { it.startsWith("http") }

        val description =
            document.selectFirst(
                "meta[property=og:description]"
            )?.attr("content")?.trim()
                ?: document.selectFirst(
                    ".description, .desc, .entry-content"
                )?.text()?.trim()

        val isMovie =
            url.contains("/%d8%a7%d9%84%d8%a7%d9%81%d9%84%d8%a7%d9%85/", ignoreCase = true) ||
            url.contains("/الافلام/", ignoreCase = true) ||
            title.contains("فيلم", ignoreCase = true)

        if (isMovie) {
            return newMovieLoadResponse(
                name = title,
                url = url,
                type = TvType.Movie,
                dataUrl = url
            ) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        val episodes = getAllEpisodes(document)

        return newTvSeriesLoadResponse(
            name = title,
            url = url,
            type = TvType.TvSeries,
            episodes = episodes
        ) {
            this.posterUrl = poster
            this.plot = description
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

        val serverElements = document
            .select("li.server[data-url]")

        val serverUrls = serverElements
            .mapNotNull { server ->
                val serverUrl = server.attr("data-url").trim()

                if (serverUrl.startsWith("http")) {
                    serverUrl
                } else {
                    null
                }
            }
            .distinct()

        var foundLinks = false

        // Filemoon / ByseVepoin first.
        val filemoonServers = serverElements
            .filter { server ->
                server.text().contains(
                    "Filemoon",
                    ignoreCase = true
                ) ||
                    server.attr("data-url")
                        .contains(
                            "bysevepoin.com",
                            ignoreCase = true
                        )
            }
            .mapNotNull { server ->
                server.attr("data-url")
                    .trim()
                    .takeIf { it.startsWith("http") }
            }
            .distinct()

        for (serverUrl in filemoonServers) {
            try {
                loadExtractor(
                    url = serverUrl,
                    referer = data,
                    subtitleCallback = subtitleCallback
                ) { link ->
                    foundLinks = true
                    callback(link)
                }
            } catch (_: Exception) {
            }
        }

        // باقي السيرفرات.
        val otherServers = serverUrls.filterNot { url ->
            filemoonServers.contains(url)
        }

        for (serverUrl in otherServers) {
            try {
                loadExtractor(
                    url = serverUrl,
                    referer = data,
                    subtitleCallback = subtitleCallback
                ) { link ->
                    foundLinks = true
                    callback(link)
                }
            } catch (_: Exception) {
            }
        }

        // Fallback لأي iframe موجود في صفحة الحلقة.
        val iframeUrls = document
            .select("iframe[src], iframe[data-src]")
            .mapNotNull { iframe ->
                val iframeUrl = iframe
                    .attr("src")
                    .ifBlank {
                        iframe.attr("data-src")
                    }
                    .trim()

                if (iframeUrl.startsWith("http")) {
                    iframeUrl
                } else {
                    null
                }
            }
            .distinct()

        for (iframeUrl in iframeUrls) {
            try {
                loadExtractor(
                    url = iframeUrl,
                    referer = data,
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
