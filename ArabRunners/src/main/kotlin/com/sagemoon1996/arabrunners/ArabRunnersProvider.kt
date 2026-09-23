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

        val videoId = data
            .substringAfter("v=", data)
            .substringBefore("&")
            .trim()

        if (videoId.isBlank()) {
            return false
        }

        val embedUrl =
            "$mainUrl/ArabPlayer/embed.php?v=$videoId"

        val streamUrl =
            "$mainUrl/ArabPlayer/stream.php?v=$videoId"

        val userAgent =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        val streamResponse = try {
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

        val master = streamResponse.text

        if (!master.contains("#EXTM3U")) {
            return false
        }

        /*
         * Find every proxy.php quality from the MASTER playlist.
         */
        val masterLines = master
            .replace("\r", "")
            .lines()

        val qualityLinks = mutableListOf<Pair<Int, String>>()

        var pendingQuality = Qualities.Unknown.value

        for (line in masterLines) {

            val trimmed = line.trim()

            if (trimmed.startsWith("#EXT-X-STREAM-INF:")) {

                val height = Regex(
                    """RESOLUTION=\d+x(\d+)"""
                )
                    .find(trimmed)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()

                pendingQuality = height ?: Qualities.Unknown.value
                continue
            }

            if (
                trimmed.contains("proxy.php?u=") &&
                !trimmed.startsWith("#")
            ) {
                val absoluteProxyUrl =
                    if (
                        trimmed.startsWith("http://") ||
                        trimmed.startsWith("https://")
                    ) {
                        trimmed
                    } else {
                        "$mainUrl/ArabPlayer/$trimmed"
                    }

                qualityLinks.add(
                    pendingQuality to absoluteProxyUrl
                )

                pendingQuality = Qualities.Unknown.value
            }
        }

        if (qualityLinks.isEmpty()) {
            return false
        }

        var foundLink = false

        /*
         * Each quality proxy returns a MEDIA playlist.
         * We convert its TS segment URLs into CloudStream's
         * playlist representation to avoid the HLS parser issue.
         */
        for ((quality, proxyUrl) in qualityLinks) {

            val mediaResponse = try {
                app.get(
                    proxyUrl,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to embedUrl
                    )
                )
            } catch (_: Exception) {
                continue
            }

            val mediaPlaylist =
                mediaResponse.text
                    .replace("\r", "")

            if (!mediaPlaylist.contains("#EXTM3U")) {
                continue
            }

            val lines = mediaPlaylist.lines()

            val playlistItems = mutableListOf<PlayListItem>()

            var durationSeconds = 0.0

            for (line in lines) {

                val trimmed = line.trim()

                if (trimmed.startsWith("#EXTINF:")) {

                    durationSeconds =
                        trimmed
                            .substringAfter("#EXTINF:")
                            .substringBefore(",")
                            .toDoubleOrNull()
                            ?: 0.0

                    continue
                }

                if (
                    trimmed.isBlank() ||
                    trimmed.startsWith("#")
                ) {
                    continue
                }

                val segmentUrl =
                    if (
                        trimmed.startsWith("http://") ||
                        trimmed.startsWith("https://")
                    ) {
                        trimmed
                    } else {
                        "$mainUrl/ArabPlayer/$trimmed"
                    }

                playlistItems.add(
                    PlayListItem(
                        url = segmentUrl,
                        durationUs = (durationSeconds * 1_000_000L).toLong()
                    )
                )

                durationSeconds = 0.0
            }

            if (playlistItems.isEmpty()) {
                continue
            }

            val finalQuality =
                when (quality) {
                    720 -> Qualities.P720.value
                    480 -> Qualities.P480.value
                    360 -> Qualities.P360.value
                    240 -> Qualities.P240.value
                    else -> Qualities.Unknown.value
                }

            callback(
                ExtractorLinkPlayList(
                    source = name,
                    name = "ArabPlayer ${quality}p",
                    playlist = playlistItems,
                    referer = embedUrl,
                    quality = finalQuality,
                    isM3u8 = true,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to embedUrl,
                        "Origin" to mainUrl
                    )
                )
            )

            foundLink = true
        }

        return foundLink
    }
}
