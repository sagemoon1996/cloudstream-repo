package com.sagemoon1996.fivetv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class FiveTVProvider : MainAPI() {

    override var mainUrl = "https://new61.5tv.lol"

    override var name = "FiveTV"

    override var lang = "ar"

    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "الرئيسية",
        "$mainUrl/latest-episodes/" to "آخر الحلقات",
        "$mainUrl/new-rows/" to "آخر الإضافات",
        "$mainUrl/latest-series/" to "جديد الدراما",
        "$mainUrl/latest-movies/" to "جديد الأفلام",
        "$mainUrl/category/%d8%a7%d9%84%d8%af%d8%b1%d8%a7%d9%85%d8%a7-%d8%a7%d9%84%d8%aa%d8%a7%d9%8a%d9%84%d8%a7%d9%86%d8%af%d9%8a%d8%a9/" to "الدراما التايلاندية",
        "$mainUrl/schedule/" to "الجدول الأسبوعي"
    )

    /*
     * سيلكتور واحد يحاول يلقط رابط البوستر من عنصر "img" عادي بكل
     * الأتربيوتات الممكنة اللي تستعملها مواقع الـ lazy-load، وإذا ما
     * لقاش، يفتش على background-image جوه attribute "style"
     * (برشا قوالب ووردبريس تحط صورة الكارد كخلفية CSS موش كـ <img>).
     */
    private fun extractPosterUrl(element: Element): String? {

        element.selectFirst(
            "img[src], img[data-src], img[data-lazy-src], " +
                "img[data-original], img[data-srcset], source[srcset]"
        )?.let { image ->

            val direct = image.attr("src")
                .ifBlank { image.attr("data-src") }
                .ifBlank { image.attr("data-lazy-src") }
                .ifBlank { image.attr("data-original") }
                .ifBlank {
                    image.attr("data-srcset")
                        .ifBlank { image.attr("srcset") }
                        .split(",")
                        .firstOrNull()
                        ?.trim()
                        ?.split(" ")
                        ?.firstOrNull()
                        .orEmpty()
                }

            if (direct.startsWith("http")) {
                return direct
            }
        }

        val bgRegex = Regex(
            """background-image\s*:\s*url\((['"]?)([^'")]+)\1\)"""
        )

        val styledElement = element.selectFirst("[style*=background-image]")
            ?: element.takeIf {
                it.attr("style").contains("background-image")
            }

        styledElement?.attr("style")?.let { style ->
            bgRegex.find(style)
                ?.groupValues
                ?.getOrNull(2)
                ?.trim()
                ?.takeIf { it.startsWith("http") }
                ?.let { return it }
        }

        return null
    }

    /*
     * فهذا الموقع، نفس المسلسل/الفيلم يقدر يكون مرتبط بـ 2 روابط
     * <a> منفصلة تأشر لنفس الصفحة (وحدة فيها الصورة بَرك، وأخرى فيها
     * العنوان بَرك). الكود القديم كان يوقف عند أول رابط يلقاه، وإذا
     * صادف الرابط اللي بلا صورة (أو صورة بأتربيوت ما يعرفهاش)، البوستر
     * يولي فارغ. هوني نلمو (group) كل الروابط اللي تأشر لنفس الصفحة،
     * ونفتشو على البوستر والعنوان عبر المجموعة كاملة (بما فيها الأب
     * المباشر تاع كل رابط، لأن الصورة تقدر تكون خارج الـ <a> نفسه).
     */
    private fun findPoster(elements: List<Element>): String? {

        for (el in elements) {

            extractPosterUrl(el)?.let { return it }

            el.parent()?.let { parent ->
                extractPosterUrl(parent)?.let { return it }
            }
        }

        return null
    }

    private fun findTitle(elements: List<Element>): String? {

        // أولوية 1: عنوان واضح من عنصر نصي حقيقي (h1-h4, .title, .entry-title)
        elements.firstNotNullOfOrNull { el ->
            el.selectFirst("h1, h2, h3, h4, .title, .entry-title")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }?.let { return it }

        // أولوية 2: نص الرابط نفسه (لو مافيهوش صورة بَرك)
        elements.firstNotNullOfOrNull { el ->
            el.text()
                .trim()
                .replace(Regex("\\s+"), " ")
                .takeIf { it.isNotBlank() }
        }?.let { return it }

        // أولوية 3 (آخر حل): alt تاع الصورة، حتى لو فيه سنة/تصنيف زايد
        elements.firstNotNullOfOrNull { el ->
            el.selectFirst("img[alt]")
                ?.attr("alt")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }?.let { return it }

        return null
    }

    private fun makeSearchResponses(
        document: org.jsoup.nodes.Document
    ): List<SearchResponse> {

        val links = document.select(
            "a[href*='/series/'], a[href*='/movie/']"
        )

        val grouped = links.groupBy {
            it.attr("href").trim()
        }

        return grouped.mapNotNull { (href, elements) ->

            if (href.isBlank()) {
                return@mapNotNull null
            }

            val title = findTitle(elements)
                ?: return@mapNotNull null

            val poster = findPoster(elements)

            when {
                href.contains("/movie/") -> {
                    newMovieSearchResponse(
                        title,
                        href,
                        TvType.Movie
                    ) {
                        posterUrl = poster
                    }
                }

                href.contains("/series/") -> {
                    newTvSeriesSearchResponse(
                        title,
                        href,
                        TvType.TvSeries
                    ) {
                        posterUrl = poster
                    }
                }

                else -> null
            }
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

        val urls = listOf(
            "$mainUrl/search/?q=$encodedQuery",
            "$mainUrl/search/?s=$encodedQuery",
            "$mainUrl/?s=$encodedQuery"
        )

        for (url in urls) {

            val document = app.get(url).document

            val results = makeSearchResponses(document)

            if (results.isNotEmpty()) {
                return results
            }
        }

        return emptyList()
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document = app.get(url).document

        val title = document
            .selectFirst("h1, h2.entry-title")
            ?.text()
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?: document
                .selectFirst("meta[property='og:title']")
                ?.attr("content")
                ?.trim()
            ?: return null

        val poster = document
            .selectFirst("meta[property='og:image']")
            ?.attr("content")
            ?.takeIf {
                it.isNotBlank()
            }
            ?: document
                .selectFirst("img[src], img[data-src]")
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

        val year = document
            .select("a[href*='/year/']")
            .firstOrNull()
            ?.text()
            ?.trim()
            ?.toIntOrNull()

        if (url.contains("/movie/")) {

            return newMovieLoadResponse(
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

        val episodes = document
            .select("a[href*='/episode/']")
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

                val season =
                    Regex(
                        """(?:الموسم|season)\s*(\d+)""",
                        RegexOption.IGNORE_CASE
                    )
                        .find(episodeText)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?: Regex(
                            """/season-(\d+)/""",
                            RegexOption.IGNORE_CASE
                        )
                            .find(episodeUrl)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                        ?: 1

                val episode =
                    Regex(
                        """(?:الحلقة|episode)[^\d]*(\d+)""",
                        RegexOption.IGNORE_CASE
                    )
                        .find(episodeText)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?: Regex(
                            """-(\d+)/?$"""
                        )
                            .find(episodeUrl)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                        ?: return@mapNotNull null

                newEpisode(episodeUrl) {

                    name = episodeText.ifBlank {
                        "Episode $episode"
                    }

                    this.season = season
                    this.episode = episode
                }
            }
            .distinctBy {
                it.data
            }
            .sortedWith(
                compareBy<Episode> {
                    it.season ?: 1
                }.thenBy {
                    it.episode ?: 0
                }
            )

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

        val document = app.get(data).document

        var loaded = false

        val directSources = document
            .select(
                "video[src], video source[src], source[src]"
            )
            .mapNotNull { element ->

                element.attr("src")
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
                    source = "FiveTV",
                    name = "FiveTV",
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

            if (
                !link.contains(
                    "71stream.one",
                    ignoreCase = true
                )
            ) {

                loadExtractor(
                    link,
                    data,
                    subtitleCallback,
                    callback
                )

                loaded = true
                continue
            }

            val streamDocument = app
                .get(link)
                .document

            val appElement = streamDocument
                .selectFirst("#app")

            val appData = appElement
                ?.attr("data-page")
                ?.replace("\\/", "/")

            if (appData != null) {

                val m3u8 = Regex(
                    """https://cdnvid\.dramalvr\.com/hls/[^"]+\.m3u8"""
                )
                    .find(appData)
                    ?.value

                if (m3u8 != null) {

                    callback(
                        newExtractorLink(
                            source = "71Stream",
                            name = "71Stream HLS",
                            url = m3u8,
                            type = ExtractorLinkType.M3U8
                        ) {
                            referer = link
                            quality = Qualities.Unknown.value
                        }
                    )

                    loaded = true
                }

                val mp4 = Regex(
                    """https://[^"'\\\s<>]+\.mp4(?:\?[^"'\\\s<>]*)?"""
                )
                    .find(appData)
                    ?.value

                if (mp4 != null) {

                    callback(
                        newExtractorLink(
                            source = "71Stream",
                            name = "71Stream MP4",
                            url = mp4,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            referer = link
                            quality = Qualities.Unknown.value
                        }
                    )

                    loaded = true
                }
            }

            val hashId = Regex(
                """71stream\.one/embed/([A-Za-z0-9_-]+)"""
            )
                .find(link)
                ?.groupValues
                ?.getOrNull(1)

            if (hashId != null) {

                val downloadUrl =
                    "https://71stream.one/download/$hashId?quality=Original"

                try {

                    val response = app.get(
                        downloadUrl,
                        allowRedirects = true
                    )

                    val finalUrl = response.url

                    if (
                        finalUrl.startsWith("http") &&
                        finalUrl != downloadUrl &&
                        (
                            finalUrl.contains(".mp4", true) ||
                            finalUrl.contains("cloudflarestorage.com", true) ||
                            finalUrl.contains("r2.cloudflarestorage.com", true)
                        )
                    ) {

                        callback(
                            newExtractorLink(
                                source = "71Stream",
                                name = "71Stream MP4",
                                url = finalUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                referer = link
                                quality = Qualities.Unknown.value
                            }
                        )

                        loaded = true
                    }

                } catch (_: Exception) {
                }
            }
        }

        val pageHtml = document
            .html()
            .replace("\\/", "/")

        val hidden71StreamLinks = Regex(
            """https?://71stream\.one/embed/[A-Za-z0-9_-]+"""
        )
            .findAll(pageHtml)
            .map {
                it.value
            }
            .distinct()
            .toList()

        for (link in hidden71StreamLinks) {

            if (iframeLinks.contains(link)) {
                continue
            }

            val streamDocument = app
                .get(link)
                .document

            val appData = streamDocument
                .selectFirst("#app")
                ?.attr("data-page")
                ?.replace("\\/", "/")

            if (appData != null) {

                val m3u8 = Regex(
                    """https://cdnvid\.dramalvr\.com/hls/[^"]+\.m3u8"""
                )
                    .find(appData)
                    ?.value

                if (m3u8 != null) {

                    callback(
                        newExtractorLink(
                            source = "71Stream",
                            name = "71Stream HLS",
                            url = m3u8,
                            type = ExtractorLinkType.M3U8
                        ) {
                            referer = link
                            quality = Qualities.Unknown.value
                        }
                    )

                    loaded = true
                }

                val mp4 = Regex(
                    """https://[^"'\\\s<>]+\.mp4(?:\?[^"'\\\s<>]*)?"""
                )
                    .find(appData)
                    ?.value

                if (mp4 != null) {

                    callback(
                        newExtractorLink(
                            source = "71Stream",
                            name = "71Stream MP4",
                            url = mp4,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            referer = link
                            quality = Qualities.Unknown.value
                        }
                    )

                    loaded = true
                }
            }

            val hashId = Regex(
                """71stream\.one/embed/([A-Za-z0-9_-]+)"""
            )
                .find(link)
                ?.groupValues
                ?.getOrNull(1)

            if (hashId != null) {

                try {

                    val response = app.get(
                        "https://71stream.one/download/$hashId?quality=Original",
                        allowRedirects = true
                    )

                    val finalUrl = response.url

                    if (
                        finalUrl.startsWith("http") &&
                        finalUrl != link &&
                        (
                            finalUrl.contains(".mp4", true) ||
                            finalUrl.contains("cloudflarestorage.com", true) ||
                            finalUrl.contains("r2.cloudflarestorage.com", true)
                        )
                    ) {

                        callback(
                            newExtractorLink(
                                source = "71Stream",
                                name = "71Stream MP4",
                                url = finalUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                referer = link
                                quality = Qualities.Unknown.value
                            }
                        )

                        loaded = true
                    }

                } catch (_: Exception) {
                }
            }
        }

        return loaded
    }
}
