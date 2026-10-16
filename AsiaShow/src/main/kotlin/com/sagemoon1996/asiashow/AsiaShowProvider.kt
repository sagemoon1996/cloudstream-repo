package com.sagemoon1996.asiashow

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class AsiaShowProvider : MainAPI() {

    override var mainUrl = "https://asiashow.net"
    override var name = "AsiaShow"
    override var lang = "ar"
    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "آخر الحلقات",
        "$mainUrl/series/" to "المسلسلات",
        "$mainUrl/movies/" to "الأفلام",
        "$mainUrl/country/cn/" to "الصينية",
        "$mainUrl/country/kr/" to "الكورية",
        "$mainUrl/country/jp/" to "اليابانية",
        "$mainUrl/country/th/" to "التايلاندية"
    )

    private val searchPostTypes =
        """{"movies":{"filter_key":"keywords","taxonomies":[]},"series":{"filter_key":"keywords","taxonomies":[]}}"""

    private fun absoluteUrl(element: Element): String {
        val href = element.attr("href").trim()
        val absolute = element.attr("abs:href").trim()

        val result = when {
            absolute.startsWith("http", true) -> absolute
            href.startsWith("http", true) -> href
            href.startsWith("//") -> "https:$href"
            href.startsWith("/") -> "$mainUrl$href"
            href.isNotBlank() -> "$mainUrl/${href.trimStart('/')}"
            else -> ""
        }

        return result.substringBefore("#")
    }

    private fun elementImage(element: Element): String? {
        val img = element.selectFirst(
            "img, picture img, [data-src], [data-lazy-src]"
        ) ?: return null

        val value = sequenceOf(
            img.attr("data-src"),
            img.attr("data-lazy-src"),
            img.attr("data-original"),
            img.attr("data-original-src"),
            img.attr("src"),
            img.attr("content")
        ).map { it.trim() }
            .firstOrNull {
                it.startsWith("http", true) ||
                    it.startsWith("//")
            } ?: return null

        return when {
            value.startsWith("//") -> "https:$value"
            else -> value
        }
    }

    private fun cardTitle(element: Element): String {
        val selectors = listOf(
            ".etk-card-title",
            ".entry-title",
            ".post-title",
            ".title",
            ".card-title",
            ".data-title",
            "[title]",
            "h2",
            "h3",
            "h4",
            "img[alt]"
        )

        for (selector in selectors) {
            val found = element.selectFirst(selector)
            val value = when {
                found == null -> ""
                found.hasAttr("alt") -> found.attr("alt").trim()
                found.hasAttr("title") &&
                    found.childrenSize() == 0 ->
                    found.attr("title").trim()
                else -> found.text().trim()
            }

            if (value.isNotBlank()) return value
        }

        return element.attr("aria-label").trim()
            .ifBlank { element.text().trim() }
    }

    private fun isInternal(url: String): Boolean {
        return url.startsWith("$mainUrl/") ||
            url == mainUrl ||
            url.startsWith("$mainUrl?")
    }

    private fun parseContentCard(link: Element): SearchResponse? {
        val url = absoluteUrl(link)

        if (!isInternal(url)) return null
        if (url.contains("/episodes/")) return null

        val isSeries = url.contains("/series/", true)
        val isMovie = url.contains("/movies/", true)

        if (!isSeries && !isMovie) return null

        val title = cardTitle(link)
        if (title.isBlank()) return null

        val poster = elementImage(link)

        return if (isSeries) {
            newTvSeriesSearchResponse(
                name = title,
                url = url,
                type = TvType.TvSeries
            ) {
                posterUrl = poster
            }
        } else {
            newMovieSearchResponse(
                name = title,
                url = url,
                type = TvType.Movie
            ) {
                posterUrl = poster
            }
        }
    }

    private fun parseContentCards(
        document: Document
    ): List<SearchResponse> {
        val links = document.select(
            "a[href*='/series/'], a[href*='/movies/']"
        )

        return links.mapNotNull { link ->
            parseContentCard(link)
        }.distinctBy { it.url }
    }

    private fun episodeNumber(title: String, url: String): Int? {
        val source = "$title $url"

        val patterns = listOf(
            Regex(
                """(?:الحلقة|حلقة|episode|ep)[^\d]{0,20}(\d+)""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """(?:episode|ep)[-_ ]?(\d+)""",
                RegexOption.IGNORE_CASE
            ),
            Regex("""الحلقة-(\d+)""")
        )

        for (pattern in patterns) {
            val number = pattern.find(source)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

            if (number != null) return number
        }

        return null
    }

    private fun parseEpisode(link: Element): Episode? {
        val url = absoluteUrl(link)

        if (!url.contains("/episodes/")) return null

        val title = cardTitle(link)
        val number = episodeNumber(title, url) ?: return null

        return newEpisode(url) {
            name = title.ifBlank { "الحلقة $number" }
            season = 1
            episode = number
        }
    }

    private fun parseEpisodes(document: Document): List<Episode> {
        val links = document.select("a[href*='/episodes/']")

        return links.mapNotNull { link ->
            parseEpisode(link)
        }.distinctBy {
            it.data
        }.sortedBy {
            it.episode ?: Int.MAX_VALUE
        }
    }

    private fun episodeToSeriesUrl(episodeUrl: String): String? {
        val path = episodeUrl
            .substringAfter("$mainUrl/", "")
            .trimEnd('/')

        if (!path.startsWith("episodes/")) return null

        val slug = path.removePrefix("episodes/")
        val seriesSlug = slug.replace(
            Regex("""-الحلقة-\d+$"""),
            ""
        )

        if (seriesSlug.isBlank() || seriesSlug == slug) return null

        return "$mainUrl/series/$seriesSlug/"
    }

    private fun parseLatestEpisodes(
        document: Document
    ): List<SearchResponse> {
        return document.select("a[href*='/episodes/']")
            .mapNotNull { link ->
                val episodeUrl = absoluteUrl(link)

                if (!isInternal(episodeUrl)) return@mapNotNull null

                val seriesUrl =
                    episodeToSeriesUrl(episodeUrl)
                        ?: return@mapNotNull null

                val title = cardTitle(link)
                    .ifBlank { link.text().trim() }

                val seriesTitle = title.replace(
                    Regex(
                        """\s*(?:الحلقة|حلقة|episode|ep)\s*[-:]?\s*\d+\s*$""",
                        RegexOption.IGNORE_CASE
                    ),
                    ""
                ).trim()

                if (seriesTitle.isBlank()) return@mapNotNull null

                newTvSeriesSearchResponse(
                    name = seriesTitle,
                    url = seriesUrl,
                    type = TvType.TvSeries
                ) {
                    posterUrl = elementImage(link)
                }
            }.distinctBy { it.url }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (page > 1) {
            return newHomePageResponse(
                request.name,
                emptyList(),
                hasNext = false
            )
        }

        val document = try {
            app.get(
                request.data,
                referer = mainUrl
            ).document
        } catch (_: Exception) {
            return newHomePageResponse(
                request.name,
                emptyList(),
                hasNext = false
            )
        }

        val isLatest = request.name == "آخر الحلقات" ||
            request.name == "اخر الحلقات" ||
            request.data == "$mainUrl/"

        val items = if (isLatest) {
            parseLatestEpisodes(document)
        } else {
            parseContentCards(document)
        }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.length < 2) return emptyList()

        val url = "$mainUrl/?vx=1" +
            "&action=quick_search" +
            "&search=${URLEncoder.encode(cleanQuery, "UTF-8")}" +
            "&post_types=${URLEncoder.encode(searchPostTypes, "UTF-8")}"

        return try {
            val response = app.get(
                url,
                referer = mainUrl
            )

            val json = JSONObject(response.text)
            val data = json.optJSONArray("data")
                ?: return emptyList()

            buildList {
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val title = item.optString("title").trim()
                    val rawUrl = item.optString("link").trim()

                    if (title.isBlank() || rawUrl.isBlank()) continue

                    val resultUrl = when {
                        rawUrl.startsWith("http", true) -> rawUrl
                        rawUrl.startsWith("/") -> "$mainUrl$rawUrl"
                        else -> "$mainUrl/$rawUrl"
                    }

                    when {
                        resultUrl.contains("/series/", true) -> {
                            add(
                                newTvSeriesSearchResponse(
                                    name = title,
                                    url = resultUrl,
                                    type = TvType.TvSeries
                                )
                            )
                        }

                        resultUrl.contains("/movies/", true) -> {
                            add(
                                newMovieSearchResponse(
                                    name = title,
                                    url = resultUrl,
                                    type = TvType.Movie
                                )
                            )
                        }
                    }
                }
            }.distinctBy { it.url }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun extractTitle(document: Document): String? {
        val metaTitle = document.selectFirst(
            "meta[property=og:title]"
        )?.attr("content")?.trim()

        val heading = document.selectFirst(
            "h1, .entry-title, .post-title"
        )?.text()?.trim()

        return sequenceOf(metaTitle, heading)
            .filterNotNull()
            .map {
                it.replace(
                    Regex("""\s*[–-]\s*(?:اسيا شو|آسيا شو|Asia Show).*${'$'}"""),
                    ""
                ).trim()
            }
            .firstOrNull { it.isNotBlank() }
    }

    private fun extractPoster(document: Document): String? {
        val meta = document.selectFirst(
            "meta[property=og:image]"
        )?.attr("content")?.trim()

        if (!meta.isNullOrBlank() && meta.startsWith("http")) {
            return meta
        }

        return document.selectFirst(
            ".poster img, .cover img, .entry-content img, article img, img"
        )?.let { elementImage(it) }
    }

    private fun extractDescription(document: Document): String? {
        val meta = document.selectFirst(
            "meta[property=og:description]"
        )?.attr("content")?.trim()

        if (!meta.isNullOrBlank()) return meta

        return document.selectFirst(
            ".description, .desc, .entry-content, .overview"
        )?.text()?.trim()?.takeIf { it.isNotBlank() }
    }

    override suspend fun load(url: String): LoadResponse? {
        if (!url.startsWith("http")) return null

        val document = try {
            app.get(
                url,
                referer = mainUrl
            ).document
        } catch (_: Exception) {
            return null
        }

        val title = extractTitle(document) ?: return null
        val poster = extractPoster(document)
        val description = extractDescription(document)

        if (url.contains("/movies/", true)) {
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

        val episodes = parseEpisodes(document)

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

    private fun decodeServerUrl(encoded: String): String? {
        return try {
            val decoded = base64Decode(encoded.trim()).trim()
            decoded.takeIf { it.startsWith("http", true) }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun loadUlt4vid(
        serverUrl: String,
        episodeUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val document = app.get(
                serverUrl,
                referer = episodeUrl
            ).document

            val mediaUrl = document.selectFirst(
                "video[data-link]"
            )?.attr("data-link")?.trim()
                ?.takeIf { it.startsWith("http", true) }
                ?: return false

            callback(
                newExtractorLink(
                    source = "Ult4vid",
                    name = "Ult4vid",
                    url = mediaUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    referer = serverUrl
                    quality = Qualities.Unknown.value
                }
            )

            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun loadOkRu(
        serverUrl: String,
        episodeUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val document = app.get(
                serverUrl,
                referer = episodeUrl
            ).document

            val options = document.selectFirst(
                "[data-module='OKVideo'][data-options]"
            )?.attr("data-options")?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return false

            val root = JSONObject(options)
            val flashvars = root.optJSONObject("flashvars")
                ?: return false
            val metadata = flashvars.optJSONObject("metadata")
                ?: return false

            val hlsUrl = metadata.optString("hlsManifestUrl")
                .trim()
                .takeIf { it.startsWith("http", true) }
                ?: return false

            callback(
                newExtractorLink(
                    source = "OK.ru",
                    name = "OK.ru",
                    url = hlsUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = serverUrl
                    quality = Qualities.Unknown.value
                }
            )

            true
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeUrl = data.trim()
        if (!episodeUrl.startsWith("http", true)) return false

        val document = try {
            app.get(
                episodeUrl,
                referer = mainUrl
            ).document
        } catch (_: Exception) {
            return false
        }

        val serverUrls = document.select(
            "a[data-etk-server-btn][data-etk-src], " +
                "a[data-etk-src], [data-etk-server-btn][data-etk-src]"
        ).mapNotNull { server ->
            decodeServerUrl(server.attr("data-etk-src"))
        }.distinct()

        if (serverUrls.isEmpty()) return false

        var foundLinks = false

        val ult4vidUrls = serverUrls.filter {
            it.contains("ult4vid", true)
        }

        for (serverUrl in ult4vidUrls) {
            if (loadUlt4vid(serverUrl, episodeUrl, callback)) {
                foundLinks = true
            }
        }

        val okRuUrls = serverUrls.filter {
            it.contains("ok.ru/", true)
        }

        for (serverUrl in okRuUrls) {
            if (loadOkRu(serverUrl, episodeUrl, callback)) {
                foundLinks = true
            }
        }

        val otherUrls = serverUrls.filterNot {
            it.contains("ult4vid", true) ||
                it.contains("ok.ru/", true)
        }

        for (serverUrl in otherUrls) {
            try {
                val loaded = loadExtractor(
                    url = serverUrl,
                    referer = episodeUrl,
                    subtitleCallback = subtitleCallback
                ) { link ->
                    foundLinks = true
                    callback(link)
                }

                if (loaded) foundLinks = true
            } catch (_: Exception) {
                // Continue with the remaining servers.
            }
        }

        return foundLinks
    }
}
