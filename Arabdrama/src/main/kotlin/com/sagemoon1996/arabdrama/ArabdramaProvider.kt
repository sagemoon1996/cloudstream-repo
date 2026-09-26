package com.sagemoon1996.arabdrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Document
import java.net.URLEncoder

class ArabdramaProvider : MainAPI() {

    override var mainUrl = "https://www.arab-drama.me"
    override var name = "Arabdrama"
    override var lang = "ar"

    override val hasMainPage = false

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val encodedQuery = URLEncoder.encode(
            query,
            "UTF-8"
        )

        val url = "$mainUrl/search?q=$encodedQuery"

        val document = app.get(
            url,
            referer = mainUrl
        ).document

        return document
            .select("a[href*='/show-']")
            .mapNotNull { element ->

                val href = element
                    .attr("href")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val title =
                    element.text()
                        .trim()
                        .takeIf { it.isNotBlank() }
                        ?: element
                            .selectFirst("img")
                            ?.attr("alt")
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null

                val poster = element
                    .selectFirst("img")
                    ?.let { image ->
                        image.attr("data-src")
                            .ifBlank {
                                image.attr("src")
                            }
                            .trim()
                    }
                    ?.takeIf { it.isNotBlank() }
                    ?.let { fixUrl(it) }

                newTvSeriesSearchResponse(
                    title,
                    fixUrl(href),
                    TvType.TvSeries
                ) {
                    posterUrl = poster
                }
            }
            .distinctBy { it.url }
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document = app.get(
            url,
            referer = mainUrl
        ).document

        val data = getDatawatch(document)
            ?: return null

        val show = data.showInfo.firstOrNull()
            ?: return null

        val title =
            show.dramaName
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: document
                    .selectFirst("meta[property=og:title]")
                    ?.attr("content")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: document
                    .selectFirst(".entry-title")
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: return null

        val episodes = data.epsUrls
            .mapNotNull { episodeData ->

                val episodeUrl = episodeData.watchUrl
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val number = episodeData
                    .episodeNumber
                    ?.toIntOrNull()
                    ?: return@mapNotNull null

                newEpisode(
                    fixUrl(episodeUrl)
                ) {

                    name = episodeData.episodeName
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: "الحلقة $number"

                    season = 1
                    episode = number
                }
            }
            .sortedBy { it.episode }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {

            posterUrl = show.coverImage
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { fixUrl(it) }

            plot = show.description
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = try {
            app.get(
                data,
                referer = mainUrl
            ).document
        } catch (_: Exception) {
            return false
        }

        val episodeData = getDatawatch(document)
            ?: return false

        val episode = episodeData.epInfo
            .firstOrNull()
            ?: return false

        val servers = episode.streamServers

        if (servers.isEmpty()) {
            return false
        }

        var foundLinks = false

        servers.forEachIndexed { index, encodedServer ->

            val serverUrl = decodeServerUrl(
                encodedServer
            ) ?: return@forEachIndexed

            if (
                !serverUrl.startsWith("http://") &&
                !serverUrl.startsWith("https://")
            ) {
                return@forEachIndexed
            }

            val lowerUrl = serverUrl.lowercase()

            when {

                lowerUrl.contains(".m3u8") -> {

                    runCatching {

                        callback(
                            newExtractorLink(
                                source = name,
                                name = "Server ${index + 1}",
                                url = serverUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                referer = data
                            }
                        )

                        foundLinks = true
                    }
                }

                lowerUrl.contains(".mpd") -> {

                    runCatching {

                        callback(
                            newExtractorLink(
                                source = name,
                                name = "Server ${index + 1}",
                                url = serverUrl,
                                type = ExtractorLinkType.DASH
                            ) {
                                referer = data
                            }
                        )

                        foundLinks = true
                    }
                }

                lowerUrl.contains(".mp4") -> {

                    runCatching {

                        callback(
                            newExtractorLink(
                                source = name,
                                name = "Server ${index + 1}",
                                url = serverUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                referer = data
                            }
                        )

                        foundLinks = true
                    }
                }

                else -> {

                    try {

                        val extracted = loadExtractor(
                            url = serverUrl,
                            referer = data,
                            subtitleCallback = subtitleCallback
                        ) { link ->

                            foundLinks = true
                            callback(link)
                        }

                        if (extracted) {
                            foundLinks = true
                        }

                    } catch (_: Exception) {
                    }
                }
            }
        }

        return foundLinks
    }

    private fun getDatawatch(
        document: Document
    ): ArabdramaData? {

        val encoded = document
            .selectFirst("#datawatch")
            ?.text()
            ?.trim()
            ?: return null

        if (encoded.isBlank()) {
            return null
        }

        val decoded = runCatching {
            base64Decode(encoded)
        }.getOrNull()
            ?: return null

        return runCatching {
            parseJson<ArabdramaData>(decoded)
        }.getOrNull()
    }

    private fun decodeServerUrl(
        value: String
    ): String? {

        var current = value.trim()

        repeat(5) {

            if (
                current.startsWith("http://") ||
                current.startsWith("https://")
            ) {
                return current
            }

            val decoded = runCatching {
                base64Decode(current)
            }.getOrNull()
                ?: return null

            if (
                decoded.isBlank() ||
                decoded == current
            ) {
                return null
            }

            current = decoded.trim()
        }

        return current.takeIf {
            it.startsWith("http://") ||
                it.startsWith("https://")
        }
    }

    private fun fixUrl(
        url: String
    ): String {

        val value = url.trim()

        return when {

            value.startsWith("http://") ||
            value.startsWith("https://") ->
                value

            value.startsWith("//") ->
                "https:$value"

            value.startsWith("/") ->
                "$mainUrl$value"

            else ->
                "$mainUrl/${value.trimStart('/')}"
        }
    }

    data class ArabdramaData(
        val show_info: List<ShowInfo> = emptyList(),
        val ep_info: List<EpisodeInfo> = emptyList(),
        val eps_urls: List<EpisodeUrl> = emptyList()
    ) {

        val showInfo: List<ShowInfo>
            get() = show_info

        val epInfo: List<EpisodeInfo>
            get() = ep_info

        val epsUrls: List<EpisodeUrl>
            get() = eps_urls
    }

    data class ShowInfo(
        val drama_name: String? = null,
        val drama_description: String? = null,
        val drama_cover_image_url: String? = null
    ) {

        val dramaName: String?
            get() = drama_name

        val description: String?
            get() = drama_description

        val coverImage: String?
            get() = drama_cover_image_url
    }

    data class EpisodeInfo(
        val episode_number: Int? = null,
        val stream_servers: List<String> = emptyList()
    ) {

        val streamServers: List<String>
            get() = stream_servers
    }

    data class EpisodeUrl(
        val episode_number: String? = null,
        val episode_name: String? = null,
        val watch_url: String? = null
    ) {

        val episodeNumber: String?
            get() = episode_number

        val episodeName: String?
            get() = episode_name

        val watchUrl: String?
            get() = watch_url
    }
}
