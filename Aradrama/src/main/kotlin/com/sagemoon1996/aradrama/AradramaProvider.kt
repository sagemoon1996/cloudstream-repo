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

    private fun getTitle(element: Element): String? {
        val title = element.selectFirst(
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

        return title ?: element.text()
            .trim()
            .replace(Regex("\\s+"), " ")
            .takeIf {
                it.isNotBlank()
            }
    }

    private fun getPoster(element: Element): String? {
        val image = element.selectFirst(
            "img[src], img[data-src], img[data-lazy-src], img[data-original]"
        ) ?: return null

        return image.attr("src")
            .ifBlank { image.attr("data-src") }
            .ifBlank { image.attr("data-lazy-src") }
            .ifBlank { image.attr("data-original") }
            .trim()
            .takeIf {
                it.startsWith("http")
            }
    }

    private fun makeSearchResponses(
        document: org.jsoup.nodes.Document
    ): List<SearchResponse> {

        return document
            .select(
                "article a[href], " +
                    ".post a[href], " +
                    ".item a[href], " +
                    ".post-item a[href], " +
                    ".movie-item a[href], " +
                    ".row-item a[href]"
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
                    href.contains("/page/") ||
                    href == mainUrl ||
                    href == "$mainUrl/"
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

        val baseUrl = request.data.trimEnd('/')

        val url = if (page == 1) {
            baseUrl
        } else {
            "$baseUrl/page/$page/"
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

    private fun extractEpisodeNumber(
        text: String,
        url: String
    ): Int? {

        val fromText = Regex(
            """(?:الحلقة|episode|ep)[^\d]*(\d+)""",
            RegexOption.IGNORE_CASE
        )
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        if (fromText != null) {
            return fromText
        }

        val fromUrl = Regex(
            """(?:الحلقة|episode|ep)[^\d]*(\d+)""",
            RegexOption.IGNORE_CASE
        )
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        if (fromUrl != null) {
            return fromUrl
        }

        return Regex(
            """-(\d+)/?$"""
        )
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun extractEpisodes(
        document: org.jsoup.nodes.Document
    ): List<Episode> {

        return document
            .select(
                "a[href*='/الحلقة-'], " +
                    "a[href*='%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9'], " +
                    "a[href*='/episode/'], " +
                    "a[href*='/episodes/']"
            )
            .mapNotNull { link ->

                val episodeUrl = link
                    .attr("href")
                    .trim()

                if (
                    episodeUrl.isBlank() ||
                    !episodeUrl.startsWith("http")
                ) {
                    return@mapNotNull null
                }

                val episodeText = link
                    .text()
                    .trim()

                val episode = extractEpisodeNumber(
                    episodeText,
                    episodeUrl
                ) ?: return@mapNotNull null

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
                ?.takeIf {
                    it.isNotBlank()
                }
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
                    "img[src], img[data-src], img[data-lazy-src], img[data-original]"
                )
                ?.let {
                    it.attr("src")
                        .ifBlank { it.attr("data-src") }
                        .ifBlank { it.attr("data-lazy-src") }
                        .ifBlank { it.attr("data-original") }
                        .trim()
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

        var episodes = extractEpisodes(document)

        if (episodes.isEmpty()) {

            val episodePageLink = document
                .selectFirst(
                    "a[href*='مشاهدة'], " +
                        "a[href*='episodes'], " +
                        "a[href*='الحلقات'], " +
                        "a[href*='episode']"
                )
                ?.attr("href")
                ?.trim()

            if (!episodePageLink.isNullOrBlank()) {

                val absoluteEpisodePage =
                    when {
                        episodePageLink.startsWith("http") ->
                            episodePageLink

                        episodePageLink.startsWith("//") ->
                            "https:$episodePageLink"

                        episodePageLink.startsWith("/") ->
                            "$mainUrl$episodePageLink"

                        else ->
                            null
                    }

                if (!absoluteEpisodePage.isNullOrBlank()) {

                    val episodeDocument = app
                        .get(absoluteEpisodePage)
                        .document

                    episodes = extractEpisodes(
                        episodeDocument
                    )
                }
            }
        }

        return if (episodes.isNotEmpty()) {

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {
                posterUrl = poster
                this.year = year
                this.plot = plot
            }

        } else {

            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                posterUrl = poster
                this.year = year
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app
            .get(
                data,
                referer = mainUrl
            )
            .document

        var found = false

        /*
         * 1. Direct video sources
         */
        val directSources = document
            .select(
                "video[src], " +
                    "video source[src], " +
                    "source[src]"
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

        for (sourceUrl in directSources) {

            val type =
                if (sourceUrl.contains(".m3u8", true)) {
                    ExtractorLinkType.M3U8
                } else {
                    ExtractorLinkType.VIDEO
                }

            callback(
                newExtractorLink(
                    source = "Aradrama",
                    name = "Aradrama",
                    url = sourceUrl,
                    type = type
                ) {
                    referer = data
                    quality = getQualityFromName(sourceUrl)
                }
            )

            found = true
        }

        /*
         * 2. Aradrama server list
         *
         * Example:
         * <li class="server" data-url="https://...">
         */
        val serverUrls = document
            .select(
                "li.server[data-url], " +
                    ".links-server li[data-url], " +
                    "[data-url]"
            )
            .mapNotNull { element ->

                element
                    .attr("data-url")
                    .trim()
                    .takeIf {
                        it.startsWith("http")
                    }
            }
            .distinct()

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
                // Try the next server
            }
        }

        /*
         * 3. Iframes
         */
        val iframeUrls = document
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
                    src.startsWith("http") ->
                        src

                    src.startsWith("//") ->
                        "https:$src"

                    src.startsWith("/") ->
                        "$mainUrl$src"

                    else ->
                        null
                }
            }
            .distinct()

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
                // Try the next iframe
            }
        }

        /*
         * 4. Sometimes the server URL is hidden
         * in HTML attributes/scripts.
         */
        val html = document.html()

        val hiddenUrls = Regex(
            """https?://[^"'\\\s<>]+"""
        )
            .findAll(html)
            .map {
                it.value
            }
            .filter { foundUrl ->

                foundUrl.contains(
                    "filemoon",
                    true
                ) ||
                    foundUrl.contains(
                        "bysevepoin",
                        true
                    ) ||
                    foundUrl.contains(
                        "vidmoly",
                        true
                    ) ||
                    foundUrl.contains(
                        "dood",
                        true
                    ) ||
                    foundUrl.contains(
                        "streamtape",
                        true
                    ) ||
                    foundUrl.contains(
                        "mixdrop",
                        true
                    ) ||
                    foundUrl.contains(
                        "luluvid",
                        true
                    ) ||
                    foundUrl.contains(
                        "hgcloud",
                        true
                    ) ||
                    foundUrl.contains(
                        "minochinos",
                        true
                    ) ||
                    foundUrl.contains(
                        "playmogo",
                        true
                    ) ||
                    foundUrl.contains(
                        "ok.ru",
                        true
                    ) ||
                    foundUrl.contains(
                        "vkvideo",
                        true
                    )
            }
            .distinct()
            .toList()

        for (hiddenUrl in hiddenUrls) {

            if (
                serverUrls.contains(hiddenUrl) ||
                iframeUrls.contains(hiddenUrl)
            ) {
                continue
            }

            try {

                val extracted = loadExtractor(
                    url = hiddenUrl,
                    referer = data,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )

                if (extracted) {
                    found = true
                }

            } catch (_: Exception) {
                // Continue with the remaining servers
            }
        }

        return found
    }
}
