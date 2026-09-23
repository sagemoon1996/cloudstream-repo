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
                name = "Running Man $episodeNumber"
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

    private fun decodeBase64Url(
        encoded: String
    ): String? {

        return try {
            val clean = encoded.trim()

            val table =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="

            val bytes = mutableListOf<Byte>()

            var buffer = 0
            var bits = 0

            for (char in clean) {

                if (char == '=') {
                    break
                }

                val index = table.indexOf(char)

                if (index < 0) {
                    return null
                }

                buffer = (buffer shl 6) or index
                bits += 6

                if (bits >= 8) {
                    bits -= 8
                    bytes.add((buffer shr bits).toByte())
                }
            }

            val decoded =
                String(
                    bytes.toByteArray(),
                    Charsets.UTF_8
                ).trim()

            if (
                decoded.startsWith("https://") ||
                decoded.startsWith("http://")
            ) {
                decoded
            } else {
                null
            }

        } catch (_: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val videoId =
            if (data.contains("v=")) {
                data
                    .substringAfter("v=")
                    .substringBefore("&")
                    .trim()
            } else {
                data.trim()
            }

        if (videoId.isBlank()) {
            return false
        }

        val embedUrl =
            "$mainUrl/ArabPlayer/embed.php?v=$videoId"

        val streamUrl =
            "$mainUrl/ArabPlayer/stream.php?v=$videoId"

        val userAgent =
            "Mozilla/5.0 (Linux; Android 10; K) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

        val headers = mapOf(
            "User-Agent" to userAgent,
            "Accept" to "*/*",
            "Referer" to embedUrl,
            "Origin" to mainUrl
        )

        val response = try {
            app.get(
                streamUrl,
                headers = headers
            )
        } catch (_: Exception) {
            return false
        }

        val masterContent =
            response.text

        if (
            masterContent.isBlank() ||
            !masterContent.contains("#EXTM3U")
        ) {
            return false
        }

        var currentQuality =
            Qualities.Unknown.value

        var foundLinks = false

        masterContent
            .lines()
            .forEach { line ->

                val trimmed =
                    line.trim()

                if (
                    trimmed.startsWith(
                        "#EXT-X-STREAM-INF"
                    )
                ) {

                    val resolution =
                        Regex(
                            """RESOLUTION=(\d+)x(\d+)"""
                        ).find(trimmed)

                    if (resolution != null) {

                        currentQuality =
                            resolution
                                .groupValues[2]
                                .toIntOrNull()
                                ?: Qualities.Unknown.value
                    }

                    return@forEach
                }

                if (
                    !trimmed.contains(
                        "proxy.php?u="
                    )
                ) {
                    return@forEach
                }

                val encodedPart =
                    try {
                        trimmed
                            .substringAfter("u=")
                            .substringBefore("&")
                            .substringBefore(" ")
                            .trim()
                    } catch (_: Exception) {
                        return@forEach
                    }

                if (encodedPart.isBlank()) {
                    return@forEach
                }

                val decodedParam =
                    try {
                        URLDecoder.decode(
                            encodedPart,
                            "UTF-8"
                        )
                    } catch (_: Exception) {
                        encodedPart
                    }

                val yandexUrl =
                    decodeBase64Url(decodedParam)
                        ?: return@forEach

                callback(
                    ExtractorLink(
                        source = name,
                        name =
                            if (
                                currentQuality !=
                                Qualities.Unknown.value
                            ) {
                                "ArabPlayer ${currentQuality}p"
                            } else {
                                "ArabPlayer HD"
                            },
                        url = yandexUrl,
                        referer = embedUrl,
                        quality = currentQuality,
                        type = ExtractorLinkType.M3U8,
                        headers = headers
                    )
                )

                foundLinks = true
            }

        return foundLinks
    }
}
