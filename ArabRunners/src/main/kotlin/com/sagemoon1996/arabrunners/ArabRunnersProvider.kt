package com.sagemoon1996.arabrunners

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.nodes.Document
import java.net.URLEncoder

class ArabRunnersProvider : MainAPI() {

    override var mainUrl = "https://arabrunnersteam.org"
    override var name = "Arab Runners Team"
    override var lang = "ar"
    override val hasMainPage = true

    override val supportedTypes = setOf(TvType.TvSeries)

    private val runningManCategory =
        "$mainUrl/category/%d8%a7%d9%84%d9%83%d9%84/%d8%a7%d9%84%d8%b1%d8%ac%d9%84-%d8%a7%d9%84%d8%ac%d8%a7%d8%b1%d9%8a/"

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

    private fun extractEpisodeNumber(url: String): Int? {
        return Regex(
            """الرجل-الجاري-الحلقة-(\d+)""",
            RegexOption.IGNORE_CASE
        )
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun extractEpisodeLinks(
        document: Document
    ): List<Pair<Int, String>> {

        return document
            .select("a[href*='/movies/']")
            .mapNotNull { link ->

                val href = link.attr("abs:href").trim()

                if (href.isBlank()) {
                    return@mapNotNull null
                }

                val episodeNumber =
                    extractEpisodeNumber(href)
                        ?: return@mapNotNull null

                episodeNumber to href
            }
            .distinctBy { it.first }
    }

    private suspend fun getAllEpisodes(): List<Pair<Int, String>> {

        val episodes = mutableListOf<Pair<Int, String>>()

        var page = 1

        while (true) {

            val pageUrl =
                if (page == 1) {
                    runningManCategory
                } else {
                    "${runningManCategory.trimEnd('/')}/page/$page/"
                }

            val document = app.get(pageUrl).document

            val pageEpisodes =
                extractEpisodeLinks(document)

            episodes.addAll(pageEpisodes)

            val hasNext =
                document
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
                "الرجل الجاري",
                runningManCategory,
                TvType.TvSeries
            )
        )
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        if (
            url.trimEnd('/') !=
            runningManCategory.trimEnd('/')
        ) {
            return null
        }

        val episodes = getAllEpisodes()

        val episodeList =
            episodes.map { (episodeNumber, _) ->

                newEpisode(
                    "RunningMan$episodeNumber"
                ) {
                    name =
                        "الرجل الجاري الحلقة $episodeNumber"

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

        val response =
            app.get(
                infoUrl,
                referer =
                    "$mainUrl/ArabPlayer/embed.php?v=$data"
            )

        val info =
            try {
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

        val streamUrl =
            if (
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
                referer =
                    "$mainUrl/ArabPlayer/embed.php?v=$data"

                quality =
                    Qualities.Unknown.value
            }
        )

        return true
    }
}
