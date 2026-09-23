package com.sagemoon1996.arabrunners

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLDecoder
import java.net.URLEncoder

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

    private val json = Json {
        ignoreUnknownKeys = true
    }

    @Serializable
    private data class ArabPlayerInfo(
        val ok: Boolean = false,
        val title: String? = null,
        val poster: String? = null,
        val type: String? = null,
        val src: String? = null,
        val qualities: List<ArabPlayerQuality> = emptyList()
    )

    @Serializable
    private data class ArabPlayerQuality(
        val height: Int = 0,
        val label: String = "",
        val src: String = ""
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

        val normalizedQuery =
            query.trim().lowercase()

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

        val decodedInputUrl =
            try {
                URLDecoder.decode(
                    url,
                    "UTF-8"
                ).trimEnd('/')
            } catch (_: Exception) {
                url.trimEnd('/')
            }

        val decodedCategoryUrl =
            try {
                URLDecoder.decode(
                    runningManCategory,
                    "UTF-8"
                ).trimEnd('/')
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

        val episodeList =
            getKnownEpisodes().map { episodeNumber ->

                newEpisode(
                    "RunningMan$episodeNumber"
                ) {
                    name =
                        "الرجل الجاري الحلقة $episodeNumber"

                    episode =
                        episodeNumber

                    posterUrl =
                        runningManPoster
                }
            }

        return newTvSeriesLoadResponse(
            "الرجل الجاري",
            runningManCategory,
            TvType.TvSeries,
            episodeList
        ) {
            posterUrl =
                runningManPoster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        if (!data.startsWith("RunningMan")) {
            return false
        }

        val infoUrl =
            "$mainUrl/ArabPlayer/info.php?v=${
                URLEncoder.encode(
                    data,
                    "UTF-8"
                )
            }"

        val response =
            try {
                app.get(
                    infoUrl,
                    referer =
                        "$mainUrl/ArabPlayer/embed.php?v=$data",
                    headers = mapOf(
                        "User-Agent" to
                            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36",
                        "Accept" to
                            "application/json, text/plain, */*",
                        "Origin" to mainUrl
                    )
                )
            } catch (_: Exception) {
                return false
            }

        val info =
            try {
                json.decodeFromString<ArabPlayerInfo>(
                    response.text
                )
            } catch (_: Exception) {
                return false
            }

        if (
            !info.ok ||
            info.type?.lowercase() != "hls"
        ) {
            return false
        }

        val qualities =
            info.qualities.filter {
                it.src.isNotBlank()
            }

        if (qualities.isEmpty()) {
            return false
        }

        var foundLink = false

        qualities.forEach { qualityInfo ->

            val streamUrl =
                if (
                    qualityInfo.src.startsWith("http://") ||
                    qualityInfo.src.startsWith("https://")
                ) {
                    qualityInfo.src
                } else {
                    "$mainUrl/ArabPlayer/${qualityInfo.src.trimStart('/')}"
                }

            callback(
                newExtractorLink(
                    source = name,
                    name = "ArabPlayer ${qualityInfo.label}",
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer =
                        "$mainUrl/ArabPlayer/embed.php?v=$data"

                    headers = mapOf(
                        "User-Agent" to
                            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36",
                        "Accept" to "*/*",
                        "Origin" to mainUrl
                    )

                    quality =
                        qualityInfo.height
                }
            )

            foundLink = true
        }

        return foundLink
    }
}
