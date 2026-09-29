package com.sagemoon1996.ourdrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
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
        "$mainUrl/serie/cate/مسلسلات-أسيوية" to "مسلسلات آسيوية",
        "$mainUrl/serie/cate/مسلسلات-كورية" to "مسلسلات كورية",
        "$mainUrl/serie/cate/مسلسلات-صينية" to "مسلسلات صينية",
        "$mainUrl/serie/cate/مسلسلات-يابانية" to "مسلسلات يابانية",
        "$mainUrl/serie/cate/مسلسلات-تايوانية" to "مسلسلات تايوانية",
        "$mainUrl/serie/cate/مسلسلات-تايلاندية" to "مسلسلات تايلاندية"
    )

    private fun absoluteUrl(url: String): String {
        return when {
            url.startsWith("http://") -> url
            url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/${url.trimStart('/')}"
        }
    }

    private fun extractPoster(element: Element): String? {
        val image = element.selectFirst(
            "img[data-src], img[data-lazy-src], img[src]"
        ) ?: return null

        val dataSrc = image.attr("data-src").trim()
        if (
            dataSrc.startsWith("http") &&
            !dataSrc.contains("/images/pixel.gif")
        ) {
            return dataSrc
        }

        val lazySrc = image.attr("data-lazy-src").trim()
        if (
            lazySrc.startsWith("http") &&
            !lazySrc.contains("/images/pixel.gif")
        ) {
            return lazySrc
        }

        val src = image.attr("src").trim()
        if (
            src.startsWith("http") &&
            !src.contains("/images/pixel.gif")
        ) {
            return src
        }

        return null
    }

    private fun extractPosterFromDocument(document: Document): String? {
        val image = document.selectFirst(
            "img[data-src], img[data-lazy-src], img[src]"
        ) ?: return null

        val dataSrc = image.attr("data-src").trim()
        if (
            dataSrc.startsWith("http") &&
            !dataSrc.contains("/images/pixel.gif")
        ) {
            return dataSrc
        }

        val lazySrc = image.attr("data-lazy-src").trim()
        if (
            lazySrc.startsWith("http") &&
            !lazySrc.contains("/images/pixel.gif")
        ) {
            return lazySrc
        }

        val src = image.attr("src").trim()
        if (
            src.startsWith("http") &&
            !src.contains("/images/pixel.gif")
        ) {
            return src
        }

        return null
    }

    private fun parseResults(document: Document): List<SearchResponse> {
        return document
            .select("article.post-movie")
            .mapNotNull { article ->

                val link = article.selectFirst("h4 a[href]")
                    ?: return@mapNotNull null

                val title = link.text()
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val href = link.attr("href")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                newTvSeriesSearchResponse(
                    title,
                    absoluteUrl(href),
                    TvType.TvSeries
                ) {
                    posterUrl = extractPoster(article)
                }
            }
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

        val document = app.get(url).document

        return newHomePageResponse(
            request.name,
            parseResults(document)
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val response = app.post(
            "$mainUrl/searchq",
            data = mapOf(
                "searchq" to query
            ),
            headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "X-Requested-With" to "XMLHttpRequest"
            ),
            referer = "$mainUrl/"
        )

        val results = parseResults(response.document)

        if (results.isNotEmpty()) {
            return results
        }

        val encodedQuery = URLEncoder.encode(
            query,
            "UTF-8"
        )

        return runCatching {
            parseResults(
                app.get(
                    "$mainUrl/searchq?searchq=$encodedQuery",
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest"
                    ),
                    referer = "$mainUrl/"
                ).document
            )
        }.getOrDefault(emptyList())
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document = app.get(
            url,
            referer = "$mainUrl/"
        ).document

        val title =
            document.selectFirst(
                "h1, h2.entry-title, h1.entry-title"
            )
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: document.selectFirst(
                    "meta[property='og:title']"
                )
                    ?.attr("content")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: return null

        val poster =
            document.selectFirst(
                "meta[property='og:image']"
            )
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.startsWith("http") }
                ?: extractPosterFromDocument(document)

        val plot =
            document.selectFirst(
                "meta[name='description'], meta[property='og:description']"
            )
                ?.attr("content")
                ?.trim()

        val year =
            document.selectFirst(".post-date")
                ?.text()
                ?.trim()
                ?.toIntOrNull()

        val episodes = document
            .select(
                "a[href*='/watch-'], a[href*='/episode/']"
            )
            .mapNotNull { link ->

                val href = link.attr("href")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val episodeUrl = absoluteUrl(href)

                val episodeText = link.text()
                    .trim()

                val episode =
                    Regex(
                        """(?:الحلقة|episode|ep)[^\d]*(\d+)""",
                        RegexOption.IGNORE_CASE
                    )
                        .find(episodeText)
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
                        ?: Regex(
                            """/(\d+)(?:/)?$"""
                        )
                            .find(episodeUrl)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                        ?: return@mapNotNull null

                val season =
                    Regex(
                        """(?:الموسم|season)[^\d]*(\d+)""",
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

                newEpisode(episodeUrl) {
                    name = episodeText.ifBlank {
                        "Episode $episode"
                    }

                    this.season = season
                    this.episode = episode
                }
            }
            .distinctBy { it.data }
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

    private fun extractCsrfToken(
        document: Document
    ): String? {

        val html = document.html()

        val patterns = listOf(
            Regex(
                """['"]X-CSRF-TOKEN['"]?\s*[:=]\s*['"]([^'"]+)['"]"""
            ),
            Regex(
                """X-CSRF-TOKEN\s*:\s*['"]([^'"]+)['"]"""
            ),
            Regex(
                """X-CSRF-TOKEN\s*=\s*['"]([^'"]+)['"]"""
            )
        )

        for (pattern in patterns) {
            val token = pattern
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()

            if (!token.isNullOrBlank()) {
                return token
            }
        }

        return null
    }

    private fun extractIframeUrls(
        codePlay: String
    ): List<String> {

        val document = Jsoup.parseBodyFragment(
            codePlay
        )

        return document
            .select("iframe[src], iframe[data-src]")
            .mapNotNull { iframe ->

                val src = iframe.attr("src")
                    .ifBlank {
                        iframe.attr("data-src")
                    }
                    .trim()

                when {
                    src.startsWith("https://") -> src
                    src.startsWith("http://") -> src
                    src.startsWith("//") -> "https:$src"
                    else -> null
                }
            }
            .distinct()
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
            referer = "$mainUrl/"
        ).document

        val csrfToken =
            extractCsrfToken(document)
                ?: return false

        val serverCodes = document
            .select(
                ".server-list-menu .getplay a[data-code]"
            )
            .mapNotNull { server ->

                server
                    .attr("data-code")
                    .trim()
                    .takeIf { it.isNotBlank() }
            }
            .distinct()

        if (serverCodes.isEmpty()) {
            return false
        }

        var loaded = false

        for (serverCode in serverCodes) {

            val response = runCatching {
                app.post(
                    "$mainUrl/ajax-request",
                    data = mapOf(
                        "action" to "iframe_server",
                        "code" to serverCode
                    ),
                    headers = mapOf(
                        "X-CSRF-TOKEN" to csrfToken,
                        "X-Requested-With" to "XMLHttpRequest",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                    ),
                    referer = episodeUrl
                )
            }.getOrNull() ?: continue

            val json = runCatching {
                JSONObject(response.text)
            }.getOrNull() ?: continue

            if (!json.optBoolean("status", false)) {
                continue
            }

            val codePlay = json
                .optString("codeplay")
                .trim()

            if (codePlay.isBlank()) {
                continue
            }

            val iframeUrls = extractIframeUrls(
                codePlay
            )

            if (iframeUrls.isEmpty()) {
                continue
            }

            for (iframeUrl in iframeUrls) {

                val hlsUrl = runCatching {
                    extractHlsFromEmbed(
                        iframeUrl
                    )
                }.getOrNull()

                if (hlsUrl.isNullOrBlank()) {
                    continue
                }

                callback(
                    newExtractorLink(
                        source = "OurDrama",
                        name = "OurDrama",
                        url = hlsUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        referer = iframeUrl
                        quality = Qualities.Unknown.value
                    }
                )

                loaded = true
            }
        }

        return loaded
    }

    private suspend fun extractHlsFromEmbed(
        iframeUrl: String
    ): String? {

        val iframeDocument = app.get(
            iframeUrl,
            referer = "$mainUrl/"
        ).document

        val packedScript = iframeDocument
            .select("script")
            .map { it.data() }
            .firstOrNull { script ->

                script.contains(
                    "riverstonelearninghub"
                ) &&
                    script.contains(
                        "eval(function(p,a,c,k,e,d)"
                    )
            }
            ?: return null

        val unpacked = unpackPacker(
            packedScript
        )
            ?: return null

        /*
         * The player uses:
         *
         * links.hls4 || links.hls3 || links.hls2
         *
         * hls4 is currently absent, so hls3 is preferred,
         * with hls2 as fallback.
         */
        val hls3 = Regex(
            """["']hls3["']\s*:\s*["']([^"']+)["']"""
        )
            .find(unpacked)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        if (hls3 != null) {
            return hls3
        }

        return Regex(
            """["']hls2["']\s*:\s*["']([^"']+)["']"""
        )
            .find(unpacked)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun unpackPacker(
        script: String
    ): String? {

        /*
         * Expected structure:
         *
         * eval(function(p,a,c,k,e,d){...}(
         *     'PACKED',
         *     36,
         *     483,
         *     'DICTIONARY'.split('|')
         * ))
         *
         * The actual OurDrama player uses base 36.
         */

        val match = Regex(
            """eval\(function\(p,a,c,k,e,d\)\{[\s\S]*?\}\(\s*(['"])([\s\S]*?)\1\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(['"])([\s\S]*?)\5\.split\(\s*['"]\|['"]\s*\)"""
        ).find(script)
            ?: return null

        val packed = match
            .groupValues
            .getOrNull(2)
            ?: return null

        val base = match
            .groupValues
            .getOrNull(3)
            ?.toIntOrNull()
            ?: return null

        val count = match
            .groupValues
            .getOrNull(4)
            ?.toIntOrNull()
            ?: return null

        val dictionaryRaw = match
            .groupValues
            .getOrNull(6)
            ?: return null

        if (base <= 1 || count <= 0) {
            return null
        }

        val dictionary = decodeJsString(
            dictionaryRaw
        ).split("|")

        var unpacked = decodeJsString(
            packed
        )

        /*
         * P.A.C.K.E.R. replaces dictionary indexes in
         * descending order. This is important because the
         * dictionary itself can contain tokens which would
         * otherwise be replaced too early.
         */
        for (index in count - 1 downTo 0) {

            if (index >= dictionary.size) {
                continue
            }

            val word = dictionary[index]

            if (word.isEmpty()) {
                continue
            }

            val token = index.toString(base)

            unpacked = unpacked.replace(
                Regex(
                    """\b${Regex.escape(token)}\b"""
                ),
                word
            )
        }

        return unpacked
    }

    private fun decodeJsString(
        value: String
    ): String {

        return value
            .replace("\\'", "'")
            .replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\\", "\\")
    }
}
