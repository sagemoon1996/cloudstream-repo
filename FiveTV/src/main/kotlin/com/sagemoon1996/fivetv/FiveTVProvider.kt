
package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class FiveTVProvider : MainAPI() {

    override var mainUrl = "https://new61.5tv.lol"
    override var name = "FiveTV"
    override val lang = "ar"
    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = listOf(
        MainPageRequest(
            "آخر الحلقات",
            "$mainUrl/latest-episodes/"
        ),
        MainPageRequest(
            "أحدث الإضافات",
            "$mainUrl/new-rows/"
        ),
        MainPageRequest(
            "آخر المسلسلات",
            "$mainUrl/latest-series/"
        ),
        MainPageRequest(
            "آخر الأفلام",
            "$mainUrl/latest-movies/"
        )
    )

    private val traceLog = StringBuilder()

    private fun trace(stage: String, message: String) {
        val line = "[FiveTV][$stage] $message"
        println(line)
        synchronized(traceLog) {
            traceLog.append(line).append('\n')
        }
    }

    private fun clearTrace() {
        synchronized(traceLog) {
            traceLog.clear()
        }
    }

    private fun getTrace(): String =
        synchronized(traceLog) { traceLog.toString() }

    private fun absoluteUrl(url: String): String {
        val value = url.trim()
        if (value.isBlank()) return ""
        if (value.startsWith("//")) return "https:$value"
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value
        }
        return "$mainUrl/${value.trimStart('/')}"
    }

    private fun Element.firstText(selector: String): String? =
        selectFirst(selector)?.text()?.trim()?.takeIf { it.isNotBlank() }

    private fun Element.firstImageUrl(): String? {
        val image = selectFirst("img") ?: return null
        val value = image.attr("src").ifBlank {
            image.attr("data-src")
        }.ifBlank {
            image.attr("data-lazy-src")
        }
        return value.takeIf { it.isNotBlank() }?.let { absoluteUrl(it) }
    }

    private fun Element.cardContainer(): Element {
        return parents().firstOrNull {
            it.hasClass("ftv-home-poster-card")
        } ?: parent() ?: this
    }

    private fun parseCards(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        val titleLinks = document.select(
            ".ftv-home-poster-card__title[href], " +
                ".ftv-search-grid .ftv-home-poster-card__title[href]"
        )

        for (titleLink in titleLinks) {
            val title = titleLink.text().trim()
            val url = absoluteUrl(titleLink.attr("href"))

            if (title.isBlank() || url.isBlank() || !seen.add(url)) continue

            val card = titleLink.cardContainer()
            val poster = card.firstImageUrl()
                ?: card.selectFirst(".ftv-home-poster-card__image img")
                    ?.let { image ->
                        image.attr("src").ifBlank { image.attr("data-src") }
                    }
                    ?.takeIf { it.isNotBlank() }
                    ?.let { absoluteUrl(it) }

            val year = card
                .selectFirst(".ftv-poster-badge--year")
                ?.text()
                ?.filter { it.isDigit() }
                ?.toIntOrNull()

            val isMovie = card.hasClass("ftv-home-poster-card--movie") ||
                url.contains("/movie/")

            val isSeries = card.hasClass("ftv-home-poster-card--series") ||
                url.contains("/series/")

            val item = when {
                isMovie -> newMovieSearchResponse(
                    title,
                    url,
                    TvType.Movie
                ) {
                    this.posterUrl = poster
                    this.year = year
                }

                isSeries -> newTvSeriesSearchResponse(
                    title,
                    url,
                    TvType.TvSeries
                ) {
                    this.posterUrl = poster
                    this.year = year
                }

                else -> null
            }

            if (item != null) results.add(item)
        }

        return results
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        clearTrace()

        val base = request.data.trimEnd('/')
        val url = if (page <= 1) {
            "$base/"
        } else {
            "$base/page/$page/"
        }

        return try {
            trace("HOME-1", "GET $url")

            val response = app.get(url)
            val document = response.document

            trace(
                "HOME-2",
                "HTTP=${response.code}, HTML=${response.text.length}"
            )

            val results = parseCards(document)

            trace("HOME-3", "Cards=${results.size}")

            newHomePageResponse(
                request.name,
                results,
                hasNext = results.isNotEmpty()
            )
        } catch (e: Exception) {
            trace(
                "HOME-ERR",
                "${e.javaClass.simpleName}: ${e.message}"
            )

            newHomePageResponse(
                request.name,
                emptyList(),
                hasNext = false
            )
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        clearTrace()

        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrls = listOf(
            "$mainUrl/search/?q=$encoded",
            "$mainUrl/search/?s=$encoded",
            "$mainUrl/?s=$encoded"
        )

        for ((index, url) in searchUrls.withIndex()) {
            try {
                trace("SEARCH-${index + 1}", "GET $url")

                val response = app.get(url)
                val document = response.document

                trace(
                    "SEARCH-${index + 1}-HTTP",
                    "HTTP=${response.code}, HTML=${response.text.length}"
                )

                val results = parseCards(document)

                trace(
                    "SEARCH-${index + 1}-RESULT",
                    "Cards=${results.size}"
                )

                if (results.isNotEmpty()) return results
            } catch (e: Exception) {
                trace(
                    "SEARCH-${index + 1}-ERR",
                    "${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }

        trace("SEARCH-END", "No results for query=$query")
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        clearTrace()

        return try {
            trace("LOAD-1", "GET $url")

            val response = app.get(url)
            val document = response.document

            trace(
                "LOAD-2",
                "HTTP=${response.code}, HTML=${response.text.length}"
            )

            val title = document
                .selectFirst(".ftv-content-hero__title")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("meta[property=og:title]")
                    ?.attr("content")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: document.title().substringBefore("|").trim()

            val poster = document
                .selectFirst(".ftv-content-hero__poster img")
                ?.let { image ->
                    image.attr("src")
                        .ifBlank { image.attr("data-src") }
                        .takeIf { it.isNotBlank() }
                        ?.let { absoluteUrl(it) }
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
                    ?.takeIf { it.isNotBlank() }

            val year = document
                .selectFirst(".ftv-content-hero__year a")
                ?.text()
                ?.filter { it.isDigit() }
                ?.toIntOrNull()

            val tags = document
                .select(
                    ".ftv-content-chip--category, " +
                        ".ftv-content-chip--genre, " +
                        ".ftv-content-chip--quality"
                )
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinct()

            trace(
                "LOAD-3",
                "Title=$title, Poster=${poster != null}, Year=$year"
            )

            when {
                url.contains("/movie/") -> {
                    newMovieLoadResponse(
                        title,
                        url,
                        TvType.Movie,
                        url
                    ) {
                        this.posterUrl = poster
                        this.plot = description
                        this.year = year
                        this.tags = tags
                    }
                }

                url.contains("/series/") -> {
                    val episodeElements = document.select(
                        "a.ftv-series-episode-card[href]"
                    )

                    trace(
                        "LOAD-4",
                        "Episode cards=${episodeElements.size}"
                    )

                    val episodes = episodeElements.mapNotNull { element ->
                        val episodeUrl = absoluteUrl(element.attr("href"))
                        if (episodeUrl.isBlank()) return@mapNotNull null

                        val fullText = element.text().trim()
                        val episodeNumber = Regex(
                            """الحلقة\s*(\d+)"""
                        ).find(fullText)
                            ?.groupValues?.getOrNull(1)
                            ?.toIntOrNull()
                            ?: Regex("""-(\d+)/?$""")
                                .find(element.attr("href"))
                                ?.groupValues?.getOrNull(1)
                                ?.toIntOrNull()

                        val seasonNumber = Regex(
                            """الموسم\s*(\d+)"""
                        ).find(fullText)
                            ?.groupValues?.getOrNull(1)
                            ?.toIntOrNull()
                            ?: 1

                        val episodeName = element
                            .selectFirst("strong")
                            ?.text()
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?: fullText

                        newEpisode(episodeUrl) {
                            this.name = episodeName
                            this.season = seasonNumber
                            this.episode = episodeNumber
                        }
                    }.distinctBy { it.data }

                    trace("LOAD-5", "Parsed episodes=${episodes.size}")

                    newTvSeriesLoadResponse(
                        title,
                        url,
                        episodes
                    ) {
                        this.posterUrl = poster
                        this.plot = description
                        this.year = year
                        this.tags = tags
                    }
                }

                else -> {
                    trace("LOAD-ERR", "Unsupported URL type: $url")
                    null
                }
            }
        } catch (e: Exception) {
            trace(
                "LOAD-ERR",
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
        clearTrace()

        var foundLink = false
        val candidates = linkedSetOf<String>()

        try {
            trace("LINK-1", "GET $data")

            val response = app.get(data)
            val document = response.document

            trace(
                "LINK-2",
                "HTTP=${response.code}, HTML=${response.text.length}"
            )

            document.select("video[src], video source[src], source[src]")
                .forEach { element ->
                    val raw = element.attr("src")
                    if (raw.isNotBlank()) candidates.add(absoluteUrl(raw))
                }

            document.select("iframe[src], iframe[data-src]")
                .forEach { element ->
                    val raw = element.attr("src")
                        .ifBlank { element.attr("data-src") }
                    if (raw.isNotBlank()) candidates.add(absoluteUrl(raw))
                }

            document.select(
                "a[href*='.m3u8'], a[href*='.mp4'], " +
                    "a[href*='embed'], a[href*='player']"
            ).forEach { element ->
                val raw = element.attr("href")
                if (raw.isNotBlank()) candidates.add(absoluteUrl(raw))
            }

            candidates.remove(data)
            candidates.remove("")

            trace(
                "LINK-3",
                "Candidates=${candidates.size}: ${candidates.joinToString()}"
            )

            for (candidate in candidates) {
                try {
                    trace("LINK-4", "Try extractor: $candidate")

                    val extractorFound = loadExtractor(
                        candidate,
                        data,
                        subtitleCallback
                    ) { extractorLink ->
                        foundLink = true
                        callback(extractorLink)
                        trace(
                            "LINK-5",
                            "Extractor callback: ${extractorLink.url}"
                        )
                    }

                    if (extractorFound) {
                        trace("LINK-6", "Extractor returned true")
                    } else {
                        trace("LINK-6", "Extractor returned false")
                    }
                } catch (e: Exception) {
                    trace(
                        "LINK-ERR",
                        "${e.javaClass.simpleName}: ${e.message}; URL=$candidate"
                    )
                }
            }

            trace("LINK-END", "foundLink=$foundLink")
            return foundLink
        } catch (e: Exception) {
            trace(
                "LINK-FATAL",
                "${e.javaClass.simpleName}: ${e.message}"
            )
            return foundLink
        }
    }
}
