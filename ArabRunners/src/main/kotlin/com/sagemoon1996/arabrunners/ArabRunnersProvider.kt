package com.sagemoon1996.arabrunners

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class ArabRunnersProvider : MainAPI() {

    override var mainUrl = "https://arabrunnersteam.org"
    override var name = "ArabRunners"
    override var lang = "ar"

    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Movie
    )

    private val runningManUrl =
        "$mainUrl/category/%D8%A7%D9%84%D9%83%D9%84/%D8%A7%D9%84%D8%B1%D8%AC%D9%84-%D8%A7%D9%84%D8%AC%D8%A7%D8%B1%D9%8A/"

    override val mainPage = mainPageOf(
        "$mainUrl/" to "آخر الحلقات",
        runningManUrl to "الرجل الجاري"
    )

    private fun normalizeUrl(url: String): String? {
        val value = url.trim()

        return when {
            value.startsWith("http://") ||
                value.startsWith("https://") -> value

            value.startsWith("//") -> "https:$value"

            value.startsWith("/") ->
                "$mainUrl$value"

            else -> null
        }
    }

    private fun getTitle(link: Element): String? {
        val title = link.selectFirst(
            "img[alt], h1, h2, h3, h4, .entry-title, .post-title, .title"
        )?.let {
            if (it.tagName() == "img") {
                it.attr("alt")
            } else {
                it.text()
            }
        }?.trim()?.takeIf {
            it.isNotBlank()
        }

        return title
            ?: link.attr("title").trim().takeIf { it.isNotBlank() }
            ?: link.text()
                .trim()
                .replace(Regex("\\s+"), " ")
                .takeIf { it.isNotBlank() }
    }

    private fun getPoster(link: Element): String? {
        val image = link.selectFirst(
            "img[src], img[data-src], img[data-lazy-src]"
        ) ?: return null

        return image.attr("src")
            .ifBlank {
                image.attr("data-src")
            }
            .ifBlank {
                image.attr("data-lazy-src")
            }
            .trim()
            .takeIf {
                it.startsWith("http")
            }
    }

    private fun isValidPostUrl(url: String): Boolean {
        val normalized = normalizeUrl(url) ?: return false

        if (!normalized.startsWith(mainUrl)) {
            return false
        }

        val blocked = listOf(
            "/category/",
            "/tag/",
            "/author/",
            "/page/",
            "/feed/",
            "/wp-",
            "/privacy",
            "/terms",
            "/login"
        )

        return blocked.none {
            normalized.contains(it, ignoreCase = true)
        }
    }

    private fun makeSearchResponses(
        document: org.jsoup.nodes.Document
    ): List<SearchResponse> {

        return document
            .select(
                "article a[href]," +
                    " .post a[href]," +
                    " .item a[href]," +
                    " .post-item a[href]," +
                    " h1 a[href]," +
                    " h2 a[href]," +
                    " h3 a[href]," +
                    " h4 a[href]," +
                    " .entry-title a[href]," +
                    " .post-title a[href]"
            )
            .mapNotNull { link ->

                val href = normalizeUrl(
                    link.attr("href").trim()
                ) ?: return@mapNotNull null

                if (!isValidPostUrl(href)) {
                    return@mapNotNull null
                }

                val title = getTitle(link)
                    ?: return@mapNotNull null

                val poster = getPoster(link)

                newMovieSearchResponse(
                    title,
                    href,
                    TvType.Movie
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

        val urls = listOf(
            "$mainUrl/?s=$encodedQuery",
            "$mainUrl/search/?s=$encodedQuery",
            "$mainUrl/search/?q=$encodedQuery"
        )

        for (url in urls) {
            try {
                val document = app.get(url).document

                val results = makeSearchResponses(document)

                if (results.isNotEmpty()) {
                    return results
                }
            } catch (_: Exception) {
            }
        }

        return emptyList()
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document = app.get(url).document

        val title = document
            .selectFirst(
                "h1, h2.entry-title, h1.entry-title, .post-title"
            )
            ?.text()
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?: document
                .selectFirst("meta[property='og:title']")
                ?.attr("content")
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
            ?: return null

        val poster = document
            .selectFirst("meta[property='og:image']")
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
                        .trim()
                }

        val plot = document
            .selectFirst(
                "meta[name='description'], meta[property='og:description']"
            )
            ?.attr("content")
            ?.trim()

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            url
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
            .mapNotNull { element ->
                normalizeUrl(
                    element.attr("src").trim()
                )
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
                    source = "ArabRunners",
                    name = "ArabRunners",
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

                normalizeUrl(
                    iframe.attr("src")
                        .ifBlank {
                            iframe.attr("data-src")
                        }
                        .trim()
                )
            }
            .distinct()

        for (link in iframeLinks) {

            if (
                !link.contains(
                    "71stream.one",
                    ignoreCase = true
                )
            ) {
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

                continue
            }

            try {
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
                                    finalUrl.contains(
                                        "cloudflarestorage.com",
                                        true
                                    ) ||
                                    finalUrl.contains(
                                        "r2.cloudflarestorage.com",
                                        true
                                    )
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

            } catch (_: Exception) {
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

            try {

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
                            (
                                finalUrl.contains(".mp4", true) ||
                                    finalUrl.contains(
                                        "cloudflarestorage.com",
                                        true
                                    ) ||
                                    finalUrl.contains(
                                        "r2.cloudflarestorage.com",
                                        true
                                    )
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

            } catch (_: Exception) {
            }
        }

        return loaded
    }
}
