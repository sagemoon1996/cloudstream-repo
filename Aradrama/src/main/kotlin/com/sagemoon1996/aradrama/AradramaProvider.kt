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

    private fun getTitle(link: Element): String? {
        val title = link.selectFirst(
            "img[alt], h1, h2, h3, h4, .title, .entry-title"
        )?.let {
            if (it.tagName() == "img") {
                it.attr("alt")
            } else {
                it.text()
            }
        }?.trim()?.takeIf {
            it.isNotBlank()
        }

        return title ?: link.text()
            .trim()
            .replace(Regex("\\s+"), " ")
            .takeIf {
                it.isNotBlank()
            }
    }

    private fun getPoster(link: Element): String? {
        return link.selectFirst(
            "img[src], img[data-src], img[data-lazy-src]"
        )?.let { image ->
            image.attr("src")
                .ifBlank {
                    image.attr("data-src")
                }
                .ifBlank {
                    image.attr("data-lazy-src")
                }
                .takeIf {
                    it.startsWith("http")
                }
        }
    }

    private fun makeSearchResponses(
        document: org.jsoup.nodes.Document
    ): List<SearchResponse> {

        return document
            .select(
                "article a[href], .post a[href], .item a[href], a[href*='/2026/'], a[href*='/2025/'], a[href*='/2024/'], a[href*='/2023/'], a[href*='/2022/'], a[href*='/2021/'], a[href*='/2020/']"
            )
            .mapNotNull { link ->

                val href = link
                    .attr("href")
                    .trim()

                if (
                    href.isBlank() ||
                    !href.startsWith(mainUrl)
                ) {
                    return@mapNotNull null
                }

                if (
                    href.contains("/category/") ||
                    href.contains("/tag/") ||
                    href.contains("/author/") ||
                    href.contains("/page/")
                ) {
                    return@mapNotNull null
                }

                val title = getTitle(link)
                    ?: return@mapNotNull null

                val poster = getPoster(link)

                newTvSeriesSearchResponse(
                    title,
                    href,
                    TvType.TvSeries
                ) {
                    posterUrl = poster
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

        val url = if (page == 1) {
            request.data
        } else {
            "${request.data.trimEnd('/')}/page/$page/"
        }

        val document = app.get(url).document

        return newHomePageResponse(
            request.name,
            makeSearchResponses(document)
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val encodedQuery = URLEncoder.encode(
            query,
            "UTF-8"
        )

        val url = "$mainUrl/?s=$encodedQuery"

        val document = app.get(url).document

        return makeSearchResponses(document)
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document = app.get(url).document

        val title = document
            .selectFirst(
                "h1.entry-title, h1, h2.entry-title"
            )
            ?.text()
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?: document
                .selectFirst(
                    "meta[property='og:title']"
                )
                ?.attr("content")
                ?.trim()
            ?: return null

        val poster = document
            .selectFirst(
                "meta[property='og:image']"
            )
            ?.attr("content")
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?: document
                .selectFirst(
                    "img[src], img[data-src], img[data-lazy-src]"
                )
                ?.let {
                    it.attr("src")
                        .ifBlank {
                            it.attr("data-src")
                        }
                        .ifBlank {
                            it.attr("data-lazy-src")
                        }
                }

        val plot = document
            .selectFirst(
                "meta[name='description'], meta[property='og:description']"
            )
            ?.attr("content")
            ?.trim()

        val year = Regex(
            """/(\d{4})/"""
        )
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        val episodeLinks = document
            .select(
                "a[href*='/الحلقة-'], a[href*='%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9'], a[href*='/episode/'], a[href*='/episodes/']"
            )
            .mapNotNull { link ->

                val episodeUrl = link
                    .attr("href")
                    .trim()

                if (episodeUrl.isBlank()) {
                    return@mapNotNull null
                }

                val episodeText = link
                    .text()
                    .trim()

                val episode =
                    Regex(
                        """(?:الحلقة|episode)[^\d]*(\d+)""",
                        RegexOption.IGNORE_CASE
                    )
                        .find(
                            episodeText
                        )
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?: Regex(
                            """(?:الحلقة|episode)[^\d]*(\d+)""",
                            RegexOption.IGNORE_CASE
                        )
                            .find(
                                episodeUrl
                            )
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                        ?: Regex(
                            """-(\d+)/?$"""
                        )
                            .find(
                                episodeUrl
                            )
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                        ?: return@mapNotNull null

                newEpisode(episodeUrl) {

                    name = episodeText.ifBlank {
                        "Episode $episode"
                    }

                    season = 1
                    this.episode = episode
                }
            }
            .distinctBy {
                it.data
            }
            .sortedBy {
                it.episode ?: 0
            }

        if (episodeLinks.isEmpty()) {

            val episodePageLink = document
                .selectFirst(
                    "a[href*='مشاهدة'], a[href*='episodes'], a[href*='الحلقات']"
                )
                ?.attr("href")
                ?.trim()

            if (!episodePageLink.isNullOrBlank()) {

                val episodeDocument = app
                    .get(episodePageLink)
                    .document

                val extraEpisodes = episodeDocument
                    .select(
                        "a[href*='/الحلقة-'], a[href*='%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9'], a[href*='/episode/'], a[href*='/episodes/']"
                    )
                    .mapNotNull { link ->

                        val episodeUrl = link
                            .attr("href")
                            .trim()

                        if (episodeUrl.isBlank()) {
                            return@mapNotNull null
                        }

                        val episodeText = link
                            .text()
                            .trim()

                        val episode =
                            Regex(
                                """(?:الحلقة|episode)[^\d]*(\d+)""",
                                RegexOption.IGNORE_CASE
                            )
                                .find(
                                    episodeText
                                )
                                ?.groupValues
                                ?.getOrNull(1)
                                ?.toIntOrNull()
                                ?: Regex(
                                    """(?:الحلقة|episode)[^\d]*(\d+)""",
                                    RegexOption.IGNORE_CASE
                                )
                                    .find(
                                        episodeUrl
                                    )
                                    ?.groupValues
                                    ?.getOrNull(1)
                                    ?.toIntOrNull()
                                ?: Regex(
                                    """-(\d+)/?$"""
                                )
                                    .find(
                                        episodeUrl
                                    )
                                    ?.groupValues
                                    ?.getOrNull(1)
                                    ?.toIntOrNull()
                                ?: return@mapNotNull null

                        newEpisode(episodeUrl) {

                            name = episodeText.ifBlank {
                                "Episode $episode"
                            }

                            season = 1
                            this.episode = episode
                        }
                    }
                    .distinctBy {
                        it.data
                    }
                    .sortedBy {
                        it.episode ?: 0
                    }

                return newTvSeriesLoadResponse(
                    title,
                    url,
                    TvType.TvSeries,
                    extraEpisodes
                ) {
                    posterUrl = poster
                    this.year = year
                    this.plot = plot
                }
            }
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodeLinks
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

        val document = app
            .get(data)
            .document

        var loaded = false

        val directSources = document
            .select(
                "video[src], video source[src], source[src]"
            )
            .mapNotNull { element ->

                element
                    .attr("src")
                    .trim()
                    .takeIf {
                        it.startsWith("http")
                    }
            }
            .distinct()

        for (source in directSources) {

            val type =
                if (source.contains(".m3u8", true)) {
                    ExtractorLinkType.M3U8
                } else {
                    ExtractorLinkType.VIDEO
                }

            callback(
                newExtractorLink(
                    source = "Aradrama",
                    name = "Aradrama",
                    url = source,
                    type = type
                ) {
                    referer = data
                    quality = Qualities.Unknown.value
                }
            )

            loaded = true
        }

        val serverLinks = document
            .select(
                "li.server[data-url]"
            )
            .mapNotNull { server ->

                server
                    .attr("data-url")
                    .trim()
                    .takeIf {
                        it.startsWith("http")
                    }
            }
            .distinct()

        for (serverUrl in serverLinks) {

            try {

                val extracted = loadExtractor(
                    serverUrl,
                    data,
                    subtitleCallback,
                    callback
                )

                if (extracted) {
                    loaded = true
                }

            } catch (_: Exception) {
            }
        }

        val iframeLinks = document
            .select(
                "iframe[src], iframe[data-src]"
            )
            .mapNotNull { iframe ->

                val src = iframe
                    .attr("src")
                    .ifBlank {
                        iframe.attr("data-src")
                    }
                    .trim()

                when {
                    src.startsWith("http") -> src
                    src.startsWith("//") -> "https:$src"
                    src.startsWith("/") -> "$mainUrl$src"
                    else -> null
                }
            }
            .distinct()

        for (iframeUrl in iframeLinks) {

            try {

                val extracted = loadExtractor(
                    iframeUrl,
                    data,
                    subtitleCallback,
                    callback
                )

                if (extracted) {
                    loaded = true
                }

            } catch (_: Exception) {
            }
        }

        return loaded
    }
}
