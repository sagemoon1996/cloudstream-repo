package com.sagemoon1996.arabrunners

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class ArabRunnersProvider : MainAPI() {

    override var mainUrl = "https://arabrunnersteam.org"

    override var name = "Arab Runners Team"

    override var lang = "ar"

    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/category/%d8%a7%d9%84%d9%83%d9%84/%d8%a7%d9%84%d8%b1%d8%ac%d9%84-%d8%a7%d9%84%d8%ac%d8%a7%d8%b1%d9%8a/" to "الرجل الجاري"
    )

    private val runningManCategory =
        "$mainUrl/category/%d8%a7%d9%84%d9%83%d9%84/%d8%a7%d9%84%d8%b1%d8%ac%d9%84-%d8%a7%d9%84%d8%ac%d8%a7%d8%b1%d9%8a/"

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

    private fun getPoster(element: Element): String? {
        return element.selectFirst(
            "img[src], img[data-src], img[data-lazy-src]"
        )?.let { image ->
            image.attr("src")
                .ifBlank { image.attr("data-src") }
                .ifBlank { image.attr("data-lazy-src") }
                .takeIf { it.startsWith("http") }
        }
    }

    private fun extractEpisodeNumber(text: String, url: String): Int? {
        return Regex(
            """(?:الحلقة|episode)[^\d]*(\d+)""",
            RegexOption.IGNORE_CASE
        )
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""الحلقة-(\d+)""")
                .find(url)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    private fun extractEpisodeLinks(document: Document): List<Pair<Int, String>> {
        return document
            .select("a[href*='/movies/']")
            .mapNotNull { link ->

                val href = link
                    .attr("href")
                    .trim()

                if (!href.startsWith(mainUrl)) {
                    return@mapNotNull null
                }

                val text = link.text().trim()
                val alt = link.selectFirst("img")?.attr("alt")?.trim().orEmpty()

                val combinedText = "$text $alt $href"

                if (!combinedText.contains("الرجل الجاري", ignoreCase = true) &&
                    !combinedText.contains("running man", ignoreCase = true)
                ) {
                    return@mapNotNull null
                }

                val episodeNumber = extractEpisodeNumber(
                    combinedText,
                    href
                ) ?: return@mapNotNull null

                episodeNumber to href
            }
            .distinctBy { it.first }
    }

    private suspend fun getAllEpisodes(): List<Pair<Int, String>> {
        val episodes = mutableListOf<Pair<Int, String>>()

        var page = 1

        while (true) {

            val pageUrl = if (page == 1) {
                runningManCategory
            } else {
                "${runningManCategory.trimEnd('/')}/page/$page/"
            }

            val document = app
                .get(pageUrl)
                .document

            val pageEpisodes = extractEpisodeLinks(document)

            if (pageEpisodes.isNotEmpty()) {
                episodes.addAll(pageEpisodes)
            }

            val hasNext = document
                .select("a[href]")
                .any { link ->
                    link.text()
                        .trim()
                        .replace(Regex("\\s+"), " ") == "التالي"
                }

            if (!hasNext) {
                break
            }

            page++
        }

        return episodes
            .distinctBy { it.first }
            .sortedByDescending { it.first }
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
                    "الرجل الجاري",
                    runningManCategory,
                    TvType.TvSeries
                )
            ),
            hasNext = false
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val normalizedQuery = query
            .trim()
            .lowercase()

        if (
            !normalizedQuery.contains("الرجل الجاري") &&
            !normalizedQuery.contains("running man") &&
            !normalizedQuery.contains("runningman")
        ) {
            return emptyList()
        }

        return listOf(
            newTvSeriesSearchResponse(
                "الرجل الجاري",
                runningManCategory,
                TvType.TvSeries
            )
        )
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        if (url.trimEnd('/') != runningManCategory.trimEnd('/')) {
            return null
        }

        val episodes = getAllEpisodes()

        val episodeList = episodes.map { (episodeNumber, episodeUrl) ->

            newEpisode("RunningMan$episodeNumber") {
                name = "الرجل الجاري الحلقة $episodeNumber"
                episode = episodeNumber
            }
        }

        return newTvSeriesLoadResponse(
            "الرجل الجاري",
            runningManCategory,
            TvType.TvSeries,
            episodeList
        )
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
                URLEncoder.encode(data, "UTF-8")
            }"

        val response = app.get(
            infoUrl,
            referer = "$mainUrl/ArabPlayer/embed.php?v=$data"
        )

        val info = try {
            json.decodeFromString<ArabPlayerInfo>(
                response.text
            )
        } catch (_: Exception) {
            return false
        }

        if (!info.ok) {
            return false
        }

        if (
            info.type?.lowercase() != "hls" ||
            info.src.isNullOrBlank()
        ) {
            return false
        }

        val streamUrl = if (
            info.src.startsWith("http://") ||
            info.src.startsWith("https://")
        ) {
            info.src
        } else {
            "$mainUrl/ArabPlayer/${info.src.trimStart('/')}"
        }

        callback(
            newExtractorLink(
                source = name,
                name = "ArabPlayer",
                url = streamUrl,
                type = ExtractorLinkType.M3U8
            ) {
                referer = "$mainUrl/ArabPlayer/embed.php?v=$data"
                quality = Qualities.Unknown.value
            }
        )

        return true
    }
}
