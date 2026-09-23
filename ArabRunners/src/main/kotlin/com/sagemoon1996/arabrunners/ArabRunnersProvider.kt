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
        coerceInputValues = true
    }

    @Serializable
    private data class ArabPlayerInfo(
        val ok: Boolean? = false,
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

        // استخراج كود الحلقة سواء جاء مسبوقاً بـ RunningMan أو كـ ID صافي
        var videoId = data
        if (data.startsWith("http")) {
            val html = try { app.get(data).text } catch (_: Exception) { "" }
            val regex = Regex("""embed\.php\?v=([^" '&]+)""")
            val match = regex.find(html)
            if (match != null) {
                videoId = match.groupValues[1]
            }
        }

        if (videoId.isBlank()) return false

        val infoUrl = "$mainUrl/ArabPlayer/info.php?v=${URLEncoder.encode(videoId, "UTF-8")}"

        val response = try {
            app.get(
                infoUrl,
                referer = "$mainUrl/ArabPlayer/embed.php?v=$videoId",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36",
                    "Accept" to "application/json, text/plain, */*",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Origin" to mainUrl
                )
            )
        } catch (_: Exception) {
            return false
        }

        val info = try {
            json.decodeFromString<ArabPlayerInfo>(response.text)
        } catch (_: Exception) {
            null
        } ?: return false

        // السماح بالروابط طالما الجودات متوفرة
        val qualities = info.qualities.filter { !it.src.isNullOrBlank() }
        if (qualities.isEmpty()) {
            return false
        }

        var foundLink = false

        qualities.forEach { qualityInfo ->
            val src = qualityInfo.src ?: return@forEach

            val streamUrl = if (src.startsWith("http://") || src.startsWith("https://")) {
                src
            } else {
                "$mainUrl/ArabPlayer/${src.trimStart('/')}"
            }

            // استخراج دقة الشاشة بأمان بدون مشاكل تحويل الأنواع
            val parsedQuality = qualityInfo.height 
                ?: qualityInfo.label?.replace("p", "")?.toIntOrNull() 
                ?: Qualities.Unknown.value

            callback(
                newExtractorLink(
                    source = name,
                    name = "ArabPlayer ${qualityInfo.label ?: ""}".trim(),
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/ArabPlayer/embed.php?v=$videoId"
                    this.headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36",
                        "Accept" to "*/*",
                        "Origin" to mainUrl
                    )
                    this.quality = parsedQuality
                }
            )

            foundLink = true
        }

        return foundLink
    }
}
