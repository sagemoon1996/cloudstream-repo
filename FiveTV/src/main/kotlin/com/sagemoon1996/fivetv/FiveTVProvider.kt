package com.sagemoon1996.fivetv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.net.URLEncoder

class FiveTVProvider : MainAPI() {

    override var mainUrl = "https://new61.5tv.lol"
    override var name = "FiveTV"
    override var lang = "ar"
    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage: List<MainPageData> = listOf(
        MainPageData("آخر الحلقات", "$mainUrl/latest-episodes/"),
        MainPageData("أحدث الإضافات", "$mainUrl/new-rows/"),
        MainPageData("آخر المسلسلات", "$mainUrl/latest-series/"),
        MainPageData("آخر الأفلام", "$mainUrl/latest-movies/")
    )

    private val diagEntries = mutableListOf<String>()

    private fun trace(stage: String, message: String) {
        val entry = "$stage | $message".take(240)

        synchronized(diagEntries) {
            diagEntries.add("${System.currentTimeMillis()} | $entry")
            while (diagEntries.size > 100) {
                diagEntries.removeAt(0)
            }
        }

        println("[FiveTV-DIAG] $entry")
    }

    private fun hostOf(url: String): String {
        return try {
            url.substringAfter("://")
                .substringBefore("/")
                .substringBefore("?")
                .take(100)
        } catch (_: Exception) {
            "unknown-host"
        }
    }

    private fun absoluteUrl(url: String): String {
        val value = url.trim()

        if (value.isBlank()) return ""
        if (value.startsWith("//")) return "https:$value"

        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value
        }

        return "$mainUrl/${value.trimStart('/')}"
    }

    private fun parseCards(
        document: org.jsoup.nodes.Document
    ): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        val elements = document.select(
            ".ftv-home-poster-card__title[href]"
        )

        trace("S-CARDS", "poster-title-elements=${elements.size}")

        for (link in elements) {
            val title = link.text().trim()
            val url = absoluteUrl(link.attr("href"))

            if (title.isBlank() || url.isBlank() || !seen.add(url)) {
                trace("S-SKIP", "empty title/url or duplicate")
                continue
            }

            val card = link.parents().firstOrNull {
                it.hasClass("ftv-home-poster-card")
            } ?: link.parent()

            val image = card?.selectFirst("img")
            val posterRaw = image?.attr("src")
                ?.ifBlank { image.attr("data-src") }
                ?.ifBlank { image.attr("data-lazy-src") }

            val poster = posterRaw
                ?.takeIf { it.isNotBlank() }
                ?.let { absoluteUrl(it) }

            val year = card
                ?.selectFirst(".ftv-poster-badge--year")
                ?.text()
                ?.filter { it.isDigit() }
                ?.toIntOrNull()

            val isMovie =
                card?.hasClass("ftv-home-poster-card--movie") == true ||
                    url.contains("/movie/")

            val isSeries =
                card?.hasClass("ftv-home-poster-card--series") == true ||
                    url.contains("/series/")

            val item = when {
                isMovie -> newMovieSearchResponse(
                    title, url, TvType.Movie
                ) {
                    posterUrl = poster
                    this.year = year
                }

                isSeries -> newTvSeriesSearchResponse(
                    title, url, TvType.TvSeries
                ) {
                    posterUrl = poster
                    this.year = year
                }

                else -> {
                    trace("S-UNKNOWN", "type not recognized; url=${hostOf(url)}")
                    null
                }
            }

            if (item != null) results.add(item)
        }

        trace("S-CARDS-END", "parsed=${results.size}")
        return results
    }

    private fun parseEpisodeCards(
        document: org.jsoup.nodes.Document
    ): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        val selectors = listOf(
            ".ftv-episode-completion-item a[href]",
            ".ftv-home-episode-card a[href]",
            "a.ftv-series-episode-card[href]",
            "a[href*='/episode/']"
        )

        for (selector in selectors) {
            trace(
                "D-SELECTOR",
                "$selector count=${document.select(selector).size}"
            )
        }

        val elements = document.select(
            ".ftv-episode-completion-item a[href], " +
                ".ftv-home-episode-card a[href], " +
                "a.ftv-series-episode-card[href], " +
                "a[href*='/episode/']"
        )

        trace("D-ELEMENTS", "candidate-links=${elements.size}")

        for (link in elements) {
            val url = absoluteUrl(link.attr("href"))

            val card = link.parents().firstOrNull {
                it.hasClass("ftv-episode-completion-item") ||
                    it.hasClass("ftv-home-episode-card") ||
                    it.hasClass("ftv-series-episode-card")
            } ?: link.parent()

            val title = sequenceOf(
                link.selectFirst(
                    ".ftv-home-episode-card__title, " +
                        ".ftv-episode-completion-item__title, strong"
                )?.text(),
                link.text(),
                card?.selectFirst("img")?.attr("alt")
            ).filterNotNull()
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()

            if (!url.contains("/episode/")) {
                trace("D-SKIP", "not an episode URL: ${hostOf(url)}")
                continue
            }

            if (title.isBlank()) {
                trace("D-SKIP", "episode title empty")
                continue
            }

            if (!seen.add(url)) continue

            val image = card?.selectFirst("img")
                ?: link.selectFirst("img")

            val posterRaw = image?.attr("src")
                ?.ifBlank { image.attr("data-src") }
                ?.ifBlank { image.attr("data-lazy-src") }

            val poster = posterRaw
                ?.takeIf { it.isNotBlank() }
                ?.let { absoluteUrl(it) }

            results.add(
                newTvSeriesSearchResponse(
                    title,
                    url,
                    TvType.TvSeries
                ) {
                    posterUrl = poster
                }
            )

            trace("D-ITEM", "title=$title")
        }

        trace("D-END", "parsed=${results.size}")
        return results
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url = if (page <= 1) {
            "$base/"
        } else {
            "$base/page/$page/"
        }

        val started = System.currentTimeMillis()

        return try {
            trace("D1", "GET ${request.name}; page=$page; host=${hostOf(url)}")

            val response = app.get(url)
            val document = response.document

            trace(
                "D2",
                "HTTP=${response.code}; HTML=${response.text.length}; " +
                    "ms=${System.currentTimeMillis() - started}"
            )

            val results = if (base.endsWith("/latest-episodes")) {
                parseEpisodeCards(document)
            } else {
                parseCards(document)
            }

            trace("D3", "${request.name}; results=${results.size}")

            newHomePageResponse(
                request.name,
                results,
                hasNext = results.isNotEmpty()
            )
        } catch (e: Exception) {
            trace(
                "D-ERROR",
                "${request.name}; ${e.javaClass.simpleName}: ${e.message}"
            )

            newHomePageResponse(
                request.name,
                emptyList(),
                hasNext = false
            )
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.trim().equals("FIVETVDIAG", ignoreCase = true)) {
            val entries = synchronized(diagEntries) {
                diagEntries.toList()
            }

            if (entries.isEmpty()) {
                return listOf(
                    newMovieSearchResponse(
                        "DIAG: لا توجد بيانات بعد؛ جرّب قائمة أو حلقة أولًا",
                        "$mainUrl/diag-empty",
                        TvType.Movie
                    )
                )
            }

            return entries.takeLast(40).mapIndexed { index, entry ->
                newMovieSearchResponse(
                    entry,
                    "$mainUrl/diag/$index",
                    TvType.Movie
                )
            }
        }

        val encoded = URLEncoder.encode(query, "UTF-8")

        val urls = listOf(
            "$mainUrl/search/?q=$encoded",
            "$mainUrl/search/?s=$encoded",
            "$mainUrl/?s=$encoded"
        )

        for ((index, url) in urls.withIndex()) {
            try {
                trace("SEARCH-${index + 1}", "GET host=${hostOf(url)}")

                val response = app.get(url)
                val results = parseCards(response.document)

                trace(
                    "SEARCH-${index + 1}-RESULT",
                    "HTTP=${response.code}; HTML=${response.text.length}; " +
                        "results=${results.size}"
                )

                if (results.isNotEmpty()) return results
            } catch (e: Exception) {
                trace(
                    "SEARCH-ERROR",
                    "${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }

        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            trace("LOAD-1", "GET host=${hostOf(url)}")

            val response = app.get(url)
            val document = response.document

            val title = document
                .selectFirst(".ftv-content-hero__title, h1")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("meta[property=og:title]")
                    ?.attr("content")
                    ?.substringBefore("|")
                    ?.trim()
                ?: document.title().substringBefore("|").trim()

            val poster = document
                .selectFirst(".ftv-content-hero__poster img")
                ?.let {
                    it.attr("src")
                        .ifBlank { it.attr("data-src") }
                        .takeIf { value -> value.isNotBlank() }
                        ?.let { value -> absoluteUrl(value) }
                }
                ?: document.selectFirst("meta[property=og:image]")
                    ?.attr("content")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { absoluteUrl(it) }

            val description = document
                .selectFirst(".ftv-content-hero__description")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("meta[name=description]")
                    ?.attr("content")
                    ?.trim()

            val year = document
                .selectFirst(".ftv-content-hero__year a")
                ?.text()
                ?.filter { it.isDigit() }
                ?.toIntOrNull()

            val tags = document.select(
                ".ftv-content-chip--category, " +
                    ".ftv-content-chip--genre, " +
                    ".ftv-content-chip--quality"
            ).map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinct()

            trace("LOAD-2", "title=$title; year=$year")

            when {
                url.contains("/episode/") -> {
                    newTvSeriesLoadResponse(
                        title,
                        url,
                        TvType.TvSeries,
                        listOf(
                            newEpisode(url) {
                                name = title
                            }
                        )
                    ) {
                        posterUrl = poster
                        plot = description
                        this.year = year
                        this.tags = tags
                    }
                }

                url.contains("/movie/") -> {
                    newMovieLoadResponse(
                        title,
                        url,
                        TvType.Movie,
                        url
                    ) {
                        posterUrl = poster
                        plot = description
                        this.year = year
                        this.tags = tags
                    }
                }

                url.contains("/series/") -> {
                    val elements = document.select(
                        "a.ftv-series-episode-card[href]"
                    )

                    trace("LOAD-EP", "episode-elements=${elements.size}")

                    val episodes = elements.mapNotNull { element ->
                        val episodeUrl = absoluteUrl(element.attr("href"))
                        if (episodeUrl.isBlank()) return@mapNotNull null

                        val text = element.text().trim()

                        val episodeNumber = Regex(
                            """الحلقة\s*(\d+)"""
                        ).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                            ?: Regex("""-(\d+)/?$""")
                                .find(element.attr("href"))
                                ?.groupValues?.getOrNull(1)?.toIntOrNull()

                        val seasonNumber = Regex(
                            """الموسم\s*(\d+)"""
                        ).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                            ?: 1

                        val episodeName = element.selectFirst("strong")
                            ?.text()?.trim()?.takeIf { it.isNotBlank() }
                            ?: text

                        newEpisode(episodeUrl) {
                            name = episodeName
                            season = seasonNumber
                            episode = episodeNumber
                        }
                    }.distinctBy { it.data }

                    trace("LOAD-EP", "parsed-episodes=${episodes.size}")

                    newTvSeriesLoadResponse(
                        title,
                        url,
                        TvType.TvSeries,
                        episodes
                    ) {
                        posterUrl = poster
                        plot = description
                        this.year = year
                        this.tags = tags
                    }
                }

                else -> {
                    trace("LOAD-ERROR", "unsupported path")
                    null
                }
            }
        } catch (e: Exception) {
            trace(
                "LOAD-ERROR",
                "${e.javaClass.simpleName}: ${e.message}"
            )
            null
        }
    }

    private suspend fun extract71Stream(
        embedUrl: String
    ): String? {
        return try {
            val started = System.currentTimeMillis()
            trace("L-71-1", "GET host=${hostOf(embedUrl)}")

            val response = app.get(embedUrl)
            val appElement = response.document.selectFirst("#app[data-page]")
            val pageData = appElement?.attr("data-page")

            trace(
                "L-71-2",
                "HTTP=${response.code}; HTML=${response.text.length}; " +
                    "data-page=${!pageData.isNullOrBlank()}; " +
                    "ms=${System.currentTimeMillis() - started}"
            )

            if (pageData.isNullOrBlank()) return null

            val props = JSONObject(pageData).optJSONObject("props")
            val videoUrl = props?.optString("url").orEmpty()

            if (
                videoUrl.isBlank() ||
                videoUrl == "null" ||
                !videoUrl.startsWith("http")
            ) {
                trace("L-71-ERROR", "props.url missing or invalid")
                return null
            }

            trace(
                "L-71-3",
                "video host=${hostOf(videoUrl)}; " +
                    "m3u8=${videoUrl.contains(".m3u8", true)}"
            )

            videoUrl
        } catch (e: Exception) {
            trace(
                "L-71-ERROR",
                "${e.javaClass.simpleName}: ${e.message}"
            )
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLink = false
        val candidates = linkedSetOf<String>()
        val started = System.currentTimeMillis()

        try {
            trace("L1", "episode host=${hostOf(data)}")

            val response = app.get(data)
            val document = response.document

            trace(
                "L2",
                "HTTP=${response.code}; HTML=${response.text.length}; " +
                    "ms=${System.currentTimeMillis() - started}"
            )

            val serverElements = document.select("[data-server-url]")

            trace("L3", "data-server-url elements=${serverElements.size}")

            for ((index, element) in serverElements.withIndex()) {
                val raw = element.attr("data-server-url").trim()
                val candidate = absoluteUrl(raw)

                trace(
                    "L3-SERVER",
                    "index=$index; label=${element.text().trim().take(60)}; " +
                        "host=${hostOf(candidate)}; valid=${candidate.startsWith("http")}"
                )

                if (candidate.isNotBlank()) candidates.add(candidate)
            }

            val iframeElements = document.select(
                "iframe[src], iframe[data-src], " +
                    "video[src], video source[src], source[src]"
            )

            trace("L3-FALLBACK", "iframe/video/source elements=${iframeElements.size}")

            for (element in iframeElements) {
                val raw = element.attr("src")
                    .ifBlank { element.attr("data-src") }
                    .trim()

                val candidate = absoluteUrl(raw)
                if (candidate.isNotBlank()) candidates.add(candidate)
            }

            document.select(
                "a[href*='.m3u8'], a[href*='.mp4'], " +
                    "a[href*='embed'], a[href*='player']"
            ).forEach {
                val candidate = absoluteUrl(it.attr("href"))
                if (candidate.isNotBlank()) candidates.add(candidate)
            }

            candidates.remove(data)
            candidates.remove("")

            trace("L4", "unique candidates=${candidates.size}")

            for ((index, candidate) in candidates.withIndex()) {
                val candidateStarted = System.currentTimeMillis()
                val host = hostOf(candidate)

                trace("L5", "try index=$index; host=$host")

                try {
                    if (
                        candidate.contains("71stream.one", ignoreCase = true) &&
                        candidate.contains("/embed/", ignoreCase = true)
                    ) {
                        val directUrl = extract71Stream(candidate)

                        if (!directUrl.isNullOrBlank()) {
                            callback(
                                ExtractorLink(
                                    source = "FiveTV",
                                    name = "71Stream",
                                    url = directUrl,
                                    referer = candidate,
                                    quality = Qualities.Unknown.value,
                                    isM3u8 = directUrl.contains(
                                        ".m3u8",
                                        ignoreCase = true
                                    ),
                                    headers = mapOf("Referer" to candidate)
                                )
                            )

                            foundLink = true
                            trace("L6", "71Stream callback invoked")
                            continue
                        }

                        trace("L6", "71Stream direct extraction failed; trying extractor")
                    }

                    val extractorFound = loadExtractor(
                        candidate,
                        data,
                        subtitleCallback
                    ) { link ->
                        foundLink = true
                        trace(
                            "L6-CALLBACK",
                            "name=${link.name}; host=${hostOf(link.url)}"
                        )
                        callback(link)
                    }

                    trace(
                        "L7",
                        "host=$host; extractorResult=$extractorFound; " +
                            "elapsedMs=${System.currentTimeMillis() - candidateStarted}"
                    )
                } catch (e: Exception) {
                    trace(
                        "L-ERROR",
                        "host=$host; ${e.javaClass.simpleName}: ${e.message}; " +
                            "elapsedMs=${System.currentTimeMillis() - candidateStarted}"
                    )
                }
            }

            trace(
                "L-END",
                "foundLink=$foundLink; candidates=${candidates.size}; " +
                    "totalMs=${System.currentTimeMillis() - started}"
            )

            return foundLink
        } catch (e: Exception) {
            trace(
                "L-FATAL",
                "${e.javaClass.simpleName}: ${e.message}; " +
                    "foundLink=$foundLink"
            )
            return foundLink
        }
    }
}
