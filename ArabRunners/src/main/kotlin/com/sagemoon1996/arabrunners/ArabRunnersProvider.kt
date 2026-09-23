package com.sagemoon1996.arabrunners

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
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
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
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

    override suspend fun search(query: String): List<SearchResponse> {
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

    override suspend fun load(url: String): LoadResponse {
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

    // دالة فك تشفير Base64 الاستخراجية الخاصة بالروابط المخبأة في proxy.php?u=
    private fun decodeBase64StreamUrl(rawUrl: String): String {
        if (!rawUrl.contains("u=")) return rawUrl

        val encodedPart = rawUrl.substringAfter("u=").substringBefore("&")
        val cleanPart = try { URLDecoder.decode(encodedPart, "UTF-8").trim() } catch (_: Exception) { encodedPart.trim() }

        return try {
            val bytes = android.util.Base64.decode(cleanPart, android.util.Base64.DEFAULT)
            val decoded = String(bytes, Charsets.UTF-8).trim()
            if (decoded.startsWith("http://") || decoded.startsWith("https://")) decoded else rawUrl
        } catch (_: Exception) {
            try {
                val bytes = java.util.Base64.getDecoder().decode(cleanPart)
                val decoded = String(bytes, Charsets.UTF-8).trim()
                if (decoded.startsWith("http://") || decoded.startsWith("https://")) decoded else rawUrl
            } catch (_: Exception) {
                rawUrl
            }
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

        if (videoId.isBlank()) return false

        val embedUrl = "$mainUrl/ArabPlayer/embed.php?v=$videoId"
        val infoUrl = "$mainUrl/ArabPlayer/info.php?v=$videoId"
        val userAgent = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        // 1. طلب بيانات الحلقة من info.php عبر AJAX
        val response = try {
            app.get(
                infoUrl,
                referer = embedUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "application/json, text/plain, */*",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Origin" to mainUrl
                )
            )
        } catch (_: Exception) {
            return false
        }

        val responseText = response.text
        if (responseText.isBlank()) return false

        val info = try {
            json.decodeFromString<ArabPlayerInfo>(responseText)
        } catch (_: Exception) {
            null
        } ?: return false

        val sources = mutableListOf<Pair<String, String>>()

        info.qualities.forEach { qualityItem ->
            if (!qualityItem.src.isNullOrBlank()) {
                val label = qualityItem.label ?: if (qualityItem.height != null) "${qualityItem.height}p" else "Auto"
                sources.add(Pair(label, qualityItem.src))
            }
        }

        if (sources.isEmpty() && !info.src.isNullOrBlank()) {
            sources.add(Pair("Auto", info.src))
        }

        if (sources.isEmpty()) return false

        var foundLink = false

        for ((label, rawSrc) in sources) {
            val fullRawUrl = if (rawSrc.startsWith("http://") || rawSrc.startsWith("https://")) {
                rawSrc
            } else {
                "$mainUrl/ArabPlayer/${rawSrc.trimStart('/')}"
            }

            // فك تشفير رابط الـ CDN الأصلي المباشر من Base64
            val directStreamUrl = decodeBase64StreamUrl(fullRawUrl)
            val qualityHeight = label.replace("p", "").toIntOrNull() ?: Qualities.Unknown.value

            // محاولة جلب جودات المانفيست إن وجدت
            val m3u8Links = try {
                M3u8Helper.generateSeek264Links(
                    source = name,
                    streamUrl = directStreamUrl,
                    referer = embedUrl,
                    quality = qualityHeight
                )
            } catch (_: Exception) {
                emptyList()
            }

            if (m3u8Links.isNotEmpty()) {
                m3u8Links.forEach { link ->
                    callback(link)
                    foundLink = true
                }
            } else {
                callback(
                    ExtractorLink(
                        source = name,
                        name = "ArabPlayer $label".trim(),
                        url = directStreamUrl,
                        referer = embedUrl,
                        quality = qualityHeight,
                        type = ExtractorLinkType.M3U8,
                        headers = mapOf(
                            "User-Agent" to userAgent,
                            "Referer" to embedUrl,
                            "Origin" to mainUrl
                        )
                    )
                )
                foundLink = true
            }
        }

        return foundLink
    }
}
