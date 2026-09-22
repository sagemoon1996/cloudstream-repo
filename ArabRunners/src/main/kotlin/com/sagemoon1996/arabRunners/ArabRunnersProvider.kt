package com.sagemoon1996.arabRunners

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

@CloudstreamPlugin
class ArabRunnersPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ArabRunnersProvider())
    }
}

class ArabRunnersProvider : MainAPI() {

    override var mainUrl = "https://arabrunnersteam.org"

    override var name = "Arab Runners"

    override var lang = "ar"

    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "آخر الحلقات المضافة",
        "$mainUrl/category/%d8%a7%d9%84%d9%83%d9%84/%d8%a7%d9%84%d8%b1%d8%ac%d9%84-%d8%a7%d9%84%d8%ac%d8%a7%d8%b1%d9%8a/" to "الرجل الجاري"
    )

    private fun getTitle(link: Element): String? {
        return link.selectFirst(
            "img[alt], h1, h2, h3, h4, .title, .entry-title"
        )?.let {
            if (it.tagName() == "img") {
                it.attr("alt")
            } else {
                it.text()
            }
        }?.trim()?.takeIf {
            it.isNotBlank()
        } ?: link.text()
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
            .select("a[href]")
            .mapNotNull { link ->

                val href = link
                    .attr("href")
                    .trim()

                if (
                    href.isBlank() ||
                    !href.contains("arabrunnersteam.org")
                ) {
                    return@mapNotNull null
                }

                if (
                    href.contains("/category/") ||
                    (
                        href.endsWith("/") &&
                        link.selectFirst("img") == null &&
                        link.text().trim().length < 3
                    )
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

        return makeSearchResponses(
            app.get(url).document
        )
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
            ?.takeIf {
                it.isNotBlank()
            }
            ?: document
                .selectFirst(
                    "img[src], img[data-src]"
                )
                ?.let {
                    it.attr("src")
                        .ifBlank {
                            it.attr("data-src")
                        }
                }

        val plot = document
            .selectFirst(
                "meta[name='description'], meta[property='og:description']"
            )
            ?.attr("content")
            ?.trim()

        val episodes = document
            .select("a[href]")
            .mapNotNull { link ->

                val episodeUrl = link
                    .attr("href")
                    .trim()

                if (
                    episodeUrl.isBlank() ||
                    !episodeUrl.contains("arabrunnersteam.org")
                ) {
                    return@mapNotNull null
                }

                val text = link.text().trim()

                val episode =
                    Regex(
                        """(?:الحلقة|episode|ep)[^\d]*(\d+)""",
                        RegexOption.IGNORE_CASE
                    )
                        .find(text)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?: Regex(
                            """(?:الحلقة|episode|ep)[^\d]*(\d+)""",
                            RegexOption.IGNORE_CASE
                        )
                            .find(episodeUrl)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                        ?: return@mapNotNull null

                newEpisode(episodeUrl) {
                    name = text.ifBlank {
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
            episodes
        ) {
            posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document

        var loaded = false

        val directSources = document
            .select(
                "video[src], video source[src], source[src]"
            )
            .mapNotNull {
                it.attr("src")
                    .trim()
                    .takeIf { src ->
                        src.startsWith("http")
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
                    source = "Arab Runners",
                    name = "Arab Runners",
                    url = source,
                    type = type
                ) {
                    referer = data
                    quality = Qualities.Unknown.value
                }
            )

            loaded = true
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

        for (link in iframeLinks) {
            try {
                loadExtractor(
                    link,
                    data,
                    subtitleCallback,
                    callback
                )

                loaded = true
            } catch (_: Exception) {
            }
        }

        return loaded
    }
}
