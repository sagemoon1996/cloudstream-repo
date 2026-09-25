package com.sagemoon1996.aradrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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
            "${request.data}page/$page/"
        }

        val document = app.get(
            url,
            referer = mainUrl
        ).document

        val items = document.select(
            "article, .post, .item, .bsx, .bs"
        ).mapNotNull { element ->
            parseSearchResult(element)
        }.distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = items.isNotEmpty()
        )
    }

    private fun parseSearchResult(element: Element): SearchResponse? {

        val link = element.selectFirst(
            "a[href]"
        ) ?: return null

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

        val poster = element.selectFirst(
            "img[data-src], img[src]"
        )?.let { image ->
            image.attr("data-src").ifBlank {
                image.attr("src")
            }
        }?.takeIf {
            it.startsWith("http")
        }

        val isMovie = href.contains(
            "الافلام",
            ignoreCase = true
        ) || title.contains(
            "فيلم",
            ignoreCase = true
        )

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

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val encodedQuery = URLEncoder.encode(
            query,
            "UTF-8"
        )

        val url = "$mainUrl/?s=$encodedQuery"

        val document = app.get(
            url,
            referer = mainUrl
        ).document

        return document.select(
            "article, .post, .item, .bsx, .bs"
        ).mapNotNull { element ->
            parseSearchResult(element)
        }.distinctBy { it.url }
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document = app.get(
            url,
            referer = mainUrl
        ).document

        val title = document.selectFirst(
            "h1.entry-title, .entry-title, h1"
        )?.text()?.trim()
            ?: document.selectFirst(
                "meta[property=og:title]"
            )?.attr("content")?.trim()
            ?: return null

        val poster = document.selectFirst(
            "meta[property=og:image]"
        )?.attr("content")?.takeIf {
            it.startsWith("http")
        } ?: document.selectFirst(
            ".poster img, .cover img, img"
        )?.let {
            it.attr("data-src").ifBlank {
                it.attr("src")
            }
        }?.takeIf {
            it.startsWith("http")
        }

        val description = document.selectFirst(
            "meta[property=og:description]"
        )?.attr("content")?.trim()
            ?: document.selectFirst(
                ".description, .desc, .entry-content"
            )?.text()?.trim()

        val episodes = document.select(
            "a[href]"
        ).mapNotNull { link ->

            val href = link.attr("href").trim()

            if (!href.startsWith(mainUrl)) {
                return@mapNotNull null
            }

            val text = link.text().trim()

            val episodeNumber = Regex(
                """(?:الحلقة|episode|ep)\s*[-:]?\s*(\d+)""",
                RegexOption.IGNORE_CASE
            ).find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: Regex(
                    """(?:الحلقة|episode|ep)[^\d]*(\d+)""",
                    RegexOption.IGNORE_CASE
                ).find(href)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()

            val episode = episodeNumber
                ?: return@mapNotNull null

            Episode(
                data = href,
                name = if (text.isBlank()) {
                    "الحلقة $episode"
                } else {
                    text
                },
                season = null,
                episode = episode
            )
        }.distinctBy { it.data }

        val isMovie = url.contains(
            "/الافلام/",
            ignoreCase = true
        ) || title.contains(
            "فيلم",
            ignoreCase = true
        )

        return if (isMovie) {
            newMovieLoadResponse(
                name = title,
                url = url,
                type = TvType.Movie,
                dataUrl = url
            ) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            newTvSeriesLoadResponse(
                name = title,
                url = url,
                type = TvType.TvSeries,
                episodes = episodes
            ) {
                this.posterUrl = poster
                this.plot = description
            }
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

        var found = false

        val serverUrls = document.select(
            "li.server[data-url]"
        ).mapNotNull { server ->

            val serverUrl = server.attr("data-url").trim()

            if (serverUrl.startsWith("http")) {
                serverUrl
            } else {
                null
            }

        }.distinct()

        for (serverUrl in serverUrls) {

            try {
                val extracted = loadExtractor(
                    url = serverUrl,
                    referer = data,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )

                if (extracted) {
                    found = true
                }

            } catch (_: Exception) {
            }
        }

        val iframeUrls = document.select(
            "iframe[src], iframe[data-src]"
        ).mapNotNull { iframe ->

            val iframeUrl = iframe.attr("src").ifBlank {
                iframe.attr("data-src")
            }.trim()

            if (iframeUrl.startsWith("http")) {
                iframeUrl
            } else {
                null
            }

        }.distinct()

        for (iframeUrl in iframeUrls) {

            try {
                val extracted = loadExtractor(
                    url = iframeUrl,
                    referer = data,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )

                if (extracted) {
                    found = true
                }

            } catch (_: Exception) {
            }
        }

        return found
    }
}
