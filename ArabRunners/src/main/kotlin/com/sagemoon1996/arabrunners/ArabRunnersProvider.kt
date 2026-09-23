package com.sagemoon1996.arabrunners

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLDecoder

class ArabRunnersProvider : MainAPI() {

    override var mainUrl = "https://arabrunnersteam.org"
    override var name = "Arab Runners Team"
    override var lang = "ar"
    override val hasMainPage = true

    override val supportedTypes = setOf(TvType.TvSeries)

    private val runningManCategory =
        "$mainUrl/category/%d8%a7%d9%84%d9%83%d9%84/%d8%a7%d9%84%d8%b1%d8%ac%d9%84-%d8%a7%d9%84%d8%ac%d8%a7%d8%b1%d9%8a/"

    private val runningManPoster =
        "https://arabrunnersteam.org/wp-content/uploads/2025/09/kGhSem2uEuOiPP9hfc02OMcJZOJ.webp"

    override val mainPage = mainPageOf(
        runningManCategory to "الرجل الجاري"
    )

    private fun getKnownEpisodes(): List<Int> {
        return (820 downTo 110).toList()
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        if (page != 1) {
            return newHomePageResponse(
                request.name,
                emptyList(),
                hasNext = false
            )
        }

        return newHomePageResponse(
            request.name,
            listOf(
                newTvSeriesSearchResponse(
                    name = "الرجل الجاري",
                    url = runningManCategory,
                    type = TvType.TvSeries
                ) {
                    posterUrl = runningManPoster
                }
            ),
            hasNext = false
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val normalizedQuery = query.trim().lowercase()

        if (
            !normalizedQuery.contains("الرجل الجاري") &&
            !normalizedQuery.contains("running man") &&
            !normalizedQuery.contains("runningman")
        ) {
            return emptyList()
        }

        return listOf(
            newTvSeriesSearchResponse(
                name = "الرجل الجاري",
                url = runningManCategory,
                type = TvType.TvSeries
            ) {
                posterUrl = runningManPoster
            }
        )
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val decodedInputUrl = try {
            URLDecoder.decode(url, "UTF-8").trimEnd('/')
        } catch (_: Exception) {
            url.trimEnd('/')
        }

        val decodedCategoryUrl = try {
            URLDecoder.decode(runningManCategory, "UTF-8").trimEnd('/')
        } catch (_: Exception) {
            runningManCategory.trimEnd('/')
        }

        if (
            !decodedInputUrl.contains("running-man") &&
            !decodedInputUrl.contains("الرجل-الجاري") &&
            decodedInputUrl != decodedCategoryUrl
        ) {
            return null
        }

        val episodeList = getKnownEpisodes().map { episodeNumber ->
            newEpisode("RunningMan$episodeNumber") {
                name = "الرجل الجاري الحلقة $episodeNumber"
                episode = episodeNumber
                posterUrl = runningManPoster
            }
        }

        return newTvSeriesLoadResponse(
            "الرجل الجاري",
            runningManCategory,
            TvType.TvSeries,
            episodeList
        ) {
            posterUrl = runningManPoster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val videoId = if (data.contains("v=")) {
            data.substringAfter("v=").substringBefore("&")
        } else {
            data.trim()
        }

        if (videoId.isBlank()) {
            return false
        }

        val streamUrl =
            "$mainUrl/ArabPlayer/stream.php?v=$videoId"

        val embedUrl =
            "$mainUrl/ArabPlayer/embed.php?v=$videoId"

        val userAgent =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        val response = try {
            app.get(
                streamUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to embedUrl
                )
            )
        } catch (_: Exception) {
            return false
        }

        val playlist = response.text

        if (!playlist.trimStart().startsWith("#EXTM3U")) {
            return false
        }

        val regex = Regex(
            """#EXT-X-STREAM-INF:([^\r\n]+)\r?\n([^\r\n]+)"""
        )

        var foundLink = false

        regex.findAll(playlist).forEach { match ->

            val attributes = match.groupValues[1]
            val rawUrl = match.groupValues[2].trim()

            if (!rawUrl.contains("proxy.php?u=")) {
                return@forEach
            }

            val height = Regex(
                """RESOLUTION=\d+x(\d+)"""
            ).find(attributes)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: Qualities.Unknown.value

            val quality = when (height) {
                720 -> Qualities.P720.value
                480 -> Qualities.P480.value
                360 -> Qualities.P360.value
                240 -> Qualities.P240.value
                else -> height
            }

            val proxyUrl =
                if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
                    rawUrl
                } else {
                    "$mainUrl/ArabPlayer/$rawUrl"
                }

            val link = newExtractorLink(
                source = name,
                name = "ArabPlayer ${height}p",
                url = proxyUrl,
                type = ExtractorLinkType.M3U8
            ) {
                referer = embedUrl
                this.quality = quality

                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to embedUrl,
                    "Origin" to mainUrl
                )
            }

            callback(link)
            foundLink = true
        }

        return foundLink
    }
}
