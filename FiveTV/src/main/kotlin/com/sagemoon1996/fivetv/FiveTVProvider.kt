package com.sagemoon1996.fivetv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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
            "$mainUrl/latest-episodes/",
            horizontalImages = false
        ),
        MainPageRequest(
            "أحدث الإضافات",
            "$mainUrl/new-rows/",
            horizontalImages = false
        ),
        MainPageRequest(
            "آخر المسلسلات",
            "$mainUrl/latest-series/",
            horizontalImages = false
        ),
        MainPageRequest(
            "آخر الأفلام",
            "$mainUrl/latest-movies/",
            horizontalImages = false
        )
    )

    private fun trace(stage: String, message: String) {
        println("[FiveTV][$stage] $message")
    }

    private fun absoluteUrl(url: String): String {
        val value = url.trim()

        if (value.isBlank()) return ""
        if (value.startsWith("//")) return "https:$value"

        if (
            value.startsWith("http://") ||
            value.startsWith("https://")
        ) {
            return value
        }

        return "$mainUrl/${value.trimStart('/')}"
    }

    private fun parseCards(
        document: org.jsoup.nodes.Document
    ): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        for (link in document.select(".ftv-home-poster-card__title[href]")) {
            val title = link.text().trim()
            val url = absoluteUrl(link.attr("href"))

            if (title.isBlank() || url.isBlank() || !seen.add(url)) {
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

            val isMovie = card?.hasClass("ftv-home-poster-card--movie") == true ||
                url.contains("/movie/")

            val isSeries = card?.hasClass("ftv-home-poster-card--series") == true ||
                url.contains("/series/")

            val item = when {
                isMovie -> newMovieSearchResponse(
                    title,
                    url,
                    TvType.Movie
                ) {
                    posterUrl = poster
                    this.year = year
                }

                isSeries -> newTvSeriesSearchResponse(
                    title,
                    url,
                    TvType.TvSeries
                ) {
                    posterUrl = poster
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
        val base = request.data.trimEnd('/')
        val url = if (page <= 1) {
            "$base/"
        } else {
            "$base/page/$page/"
        }

        return try {
            trace("HOME-1", "GET $url")

            val response = app.get(url)
            val results = parseCards(response.document)

            trace(
                "HOME-2",
                "HTTP=${response.code}, HTML=${response.text.length}, cards=${results.size}"
            )

            newHomePageResponse(
                request.name,
                results,
                hasNext = results.isNotEmpty()
            )
        } catch (e: Exception) {
            trace("HOME-ERR", "${e.javaClass.simpleName}: ${e.message}")

            newHomePageResponse(
                request.name,
                emptyList(),
                hasNext = false
            )
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")

        val urls = listOf(
            "$mainUrl/search/?q=$encoded",
            "$mainUrl/search/?s=$encoded",
            "$mainUrl/?s=$encoded"
        )

        for ((index, url) in urls.withIndex()) {
            try {
                trace("SEARCH-${index + 1}", "GET $url")

                val response = app.get(url)
                val results = parseCards(response.document)

                trace(
                    "SEARCH-${index + 1}",
                    "HTTP=${response.code}, HTML=${response.text.length}, results=${results.size}"
                )

                if (results.isNotEmpty()) return results
            } catch (e: Exception) {
                trace(
                    "SEARCH-ERR",
                    "${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }

        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            trace("LOAD-1", "GET $url")

            val response = app.get(url)
            val document = response.document

            val title = document
                .selectFirst(".ftv-content-hero__title")
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
            ).map {
                it.text().trim()
            }.filter {
                it.isNotBlank()
            }.distinct()

            trace(
                "LOAD-2",
                "HTTP=${response.code}, title=$title, year=$year"
            )

            when {
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
                    val episodeElements = document.select(
                        "a.ftv-series-episode-card[href]"
                    )

                    val episodes = episodeElements.mapNotNull { element ->
                        val episodeUrl = absoluteUrl(element.attr("href"))
                        if (episodeUrl.isBlank()) return@mapNotNull null

                        val text = element.text().trim()

                        val episodeNumber = Regex(
                            """الحلقة\s*(\d+)"""
                        ).find(text)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                            ?: Regex("""-(\d+)/?$""")
                                .find(element.attr("href"))
                                ?.groupValues
                                ?.getOrNull(1)
                                ?.toIntOrNull()

                        val seasonNumber = Regex(
                            """الموسم\s*(\d+)"""
                        ).find(text)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                            ?: 1

                        val episodeName = element
                            .selectFirst("strong")
                            ?.text()
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?: text

                        newEpisode(episodeUrl) {
                            name = episodeName
                            season = seasonNumber
                            episode = episodeNumber
                        }
                    }.distinctBy { it.data }

                    trace("LOAD-3", "Episodes=${episodes.size}")

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
                    trace("LOAD-ERR", "Unsupported URL: $url")
                    null
                }
            }
        } catch (e: Exception) {
            trace("LOAD-ERR", "${e.javaClass.simpleName}: ${e.message}")
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

        try {
            trace("LINK-1", "GET $data")

            val response = app.get(data)
            val document = response.document

            trace(
                "LINK-2",
                "HTTP=${response.code}, HTML=${response.text.length}"
            )

            document.select(
                "video[src], video source[src], source[src]"
            ).forEach {
                val value = it.attr("src")
                if (value.isNotBlank()) candidates.add(absoluteUrl(value))
            }

            document.select("iframe[src], iframe[data-src]").forEach {
                val value = it.attr("src").ifBlank { it.attr("data-src") }
                if (value.isNotBlank()) candidates.add(absoluteUrl(value))
            }

            document.select(
                "a[href*='.m3u8'], a[href*='.mp4'], " +
                    "a[href*='embed'], a[href*='player']"
            ).forEach {
                val value = it.attr("href")
                if (value.isNotBlank()) candidates.add(absoluteUrl(value))
            }

            candidates.remove(data)
            candidates.remove("")

            trace("LINK-3", "Candidates=${candidates.size}")

            for (candidate in candidates) {
                try {
                    trace("LINK-4", "Trying $candidate")

                    val extractorFound = loadExtractor(
                        candidate,
                        data,
                        subtitleCallback
                    ) { link ->
                        foundLink = true
                        callback(link)
                        trace("LINK-5", "Found link: ${link.url}")
                    }

                    trace("LINK-6", "Extractor result=$extractorFound")
                } catch (e: Exception) {
                    trace(
                        "LINK-ERR",
                        "${e.javaClass.simpleName}: ${e.message}"
                    )
                }
            }

            trace("LINK-END", "foundLink=$foundLink")
            return foundLink
        } catch (e: Exception) {
            trace("LINK-FATAL", "${e.javaClass.simpleName}: ${e.message}")
            return foundLink
        }
    }
}
