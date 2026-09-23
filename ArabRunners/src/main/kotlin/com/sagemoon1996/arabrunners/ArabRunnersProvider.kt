package com.sagemoon1996.arabrunners

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
        "$mainUrl/wp-content/uploads/2025/09/kGhSem2uEuOiPP9hfc02OMcJZOJ.webp"

    override val mainPage = mainPageOf(
        runningManCategory to "الرجل الجاري"
    )

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
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
        val height: Int? = null,
        val label: String? = null,
        val src: String? = null
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

        val cleanUrl = url.trimEnd('/')
        val categoryUrl = runningManCategory.trimEnd('/')

        if (cleanUrl != categoryUrl) {
            return null
        }

        val episodes = getKnownEpisodes().map { episodeNumber ->
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
            episodes
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

        if (!data.startsWith("RunningMan")) {
            return false
        }

        val videoId = data.trim()

        val infoUrl =
            "$mainUrl/ArabPlayer/info.php?v=${URLEncoder.encode(videoId, "UTF-8")}"

        val referer =
            "$mainUrl/ArabPlayer/embed.php?v=$videoId"

        val headers = mapOf(
            "User-Agent" to
                "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            "Accept" to "*/*",
            "Origin" to mainUrl
        )

        val response = try {
            app.get(
                infoUrl,
                referer = referer,
                headers = headers
            )
        } catch (_: Exception) {
            return false
        }

        val info = try {
            json.decodeFromString<ArabPlayerInfo>(response.text)
        } catch (_: Exception) {
            return false
        }

        if (!info.ok || info.type?.lowercase() != "hls") {
            return false
        }

        val qualities = info.qualities.filter {
            !it.src.isNullOrBlank()
        }

        var found = false

        qualities.forEach { quality ->

            val src = quality.src ?: return@forEach

            val streamUrl =
                if (
                    src.startsWith("http://") ||
                    src.startsWith("https://")
                ) {
                    src
                } else {
                    "$mainUrl/ArabPlayer/${src.trimStart('/')}"
                }

            val qualityValue =
                quality.height
                    ?: quality.label
                        ?.removeSuffix("p")
                        ?.toIntOrNull()
                    ?: Qualities.Unknown.value

            callback(
                newExtractorLink(
                    source = name,
                    name = "ArabPlayer ${quality.label ?: ""}".trim(),
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = referer
                    this.headers = headers
                    this.quality = qualityValue
                }
            )

            found = true
        }

        return found
    }
}
