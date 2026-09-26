package com.sagemoon1996.arabdrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder

class ArabdramaProvider : MainAPI() {

    override var mainUrl = "https://www.arab-drama.me"
    override var name = "Arabdrama"
    override var lang = "ar"

    override val hasMainPage = false

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) {
            return emptyList()
        }

        val document = try {
            app.post(
                "$mainUrl/searchq",
                data = mapOf("searchq" to query.trim()),
                referer = mainUrl
            ).document
        } catch (_: Exception) {
            return emptyList()
        }

        return document
            .select("div.show div.cover > a[href*='/show-'], div.show div.cover > a[href*='/movie-']")
            .mapNotNull { parseShowResult(it) }
            .distinctBy { it.url }
    }

    private fun parseShowResult(element: Element): SearchResponse? {
        val href = element.attr("href")
            .trim()
            .takeIf { it.isNotBlank() }
            ?: return null

        val url = fixUrl(href)

        val title = element.attr("title")
            .trim()
            .takeIf { it.isNotBlank() }
            ?: element.selectFirst("img")
                ?.attr("alt")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = element.selectFirst("img")
            ?.attr("src")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it) }

        val isMovie =
            url.contains("/movie-", ignoreCase = true) ||
            url.contains("/film-", ignoreCase = true) ||
            title.contains("فيلم", ignoreCase = true)

        return if (isMovie) {
            newMovieSearchResponse(
                name = title,
                url = url,
                type = TvType.Movie,
                fix = false
            ) {
                posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(
                title,
                url,
                TvType.TvSeries,
                false
            ) {
                posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = try {
            app.get(
                url,
                referer = mainUrl
            ).document
        } catch (_: Exception) {
            return null
        }

        val data = getDatawatch(document) ?: return null
        val show = data.showInfo.firstOrNull()

        val title =
            show?.dramaName?.trim()?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("meta[property=og:title]")
                    ?.attr("content")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: document.selectFirst(".entry-title")
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: return null

        val poster = show?.coverImage
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it) }

        val description = show?.description
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val episodes = data.epsUrls
            .mapNotNull { episodeData ->
                val episodeUrl = episodeData.watchUrl
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val episodeNumber = episodeData.episodeNumber
                    ?.trim()
                    ?.toIntOrNull()
                    ?: return@mapNotNull null

                newEpisode(fixUrl(episodeUrl)) {
                    name = episodeData.episodeName
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: "الحلقة $episodeNumber"

                    season = 1
                    episode = episodeNumber
                }
            }
            .sortedBy { it.episode }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            posterUrl = poster
            plot = description
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

        val episodeData = getDatawatch(document) ?: return false
        val episode = episodeData.epInfo.firstOrNull() ?: return false

        if (episode.streamServers.isEmpty()) {
            return false
        }

        var foundLinks = false

        episode.streamServers.forEachIndexed { index, rawServer ->

            val serverUrl = decodeServerUrl(rawServer)
                ?: return@forEachIndexed

            when {
                serverUrl.contains(".m3u8", ignoreCase = true) -> {
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

                serverUrl.contains(".mpd", ignoreCase = true) -> {
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

                serverUrl.contains(".mp4", ignoreCase = true) -> {
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
                    runCatching {
                        if (
                            loadExtractor(
                                url = serverUrl,
                                referer = data,
                                subtitleCallback = subtitleCallback
                            ) { link ->
                                foundLinks = true
                                callback(link)
                            }
                        ) {
                            foundLinks = true
                        }
                    }
                }
            }
        }

        return foundLinks
    }

    private fun getDatawatch(document: Document): ArabdramaData? {
        val encoded = document
            .selectFirst("#datawatch")
            ?.text()
            ?.trim()
            ?: return null

        if (encoded.isBlank()) {
            return null
        }

        val decoded = decodeBase64Flexible(encoded) ?: return null

        return runCatching {
            parseJson<ArabdramaData>(decoded)
        }.getOrNull()
    }

    private fun decodeServerUrl(value: String): String? {
        var current = value
            .trim()
            .removePrefix("\"")
            .removeSuffix("\"")

        repeat(8) {
            current = current
                .trim()
                .removePrefix("\"")
                .removeSuffix("\"")

            if (
                current.startsWith("http://") ||
                current.startsWith("https://")
            ) {
                return current
            }

            val urlDecoded = runCatching {
                URLDecoder.decode(current, "UTF-8")
            }.getOrNull()

            if (!urlDecoded.isNullOrBlank() && urlDecoded != current) {
                current = urlDecoded
                return@repeat
            }

            val decoded = decodeBase64Flexible(current) ?: return null

            if (decoded.isBlank() || decoded == current) {
                return null
            }

            current = decoded
        }

        return current.takeIf {
            it.startsWith("http://") ||
                it.startsWith("https://")
        }
    }

    private fun decodeBase64Flexible(value: String): String? {
        val cleaned = value
            .trim()
            .removePrefix("\"")
            .removeSuffix("\"")

        if (cleaned.isBlank()) {
            return null
        }

        return runCatching {
            base64Decode(cleaned)
        }.getOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: runCatching {
                base64Decode(
                    cleaned
                        .replace('-', '+')
                        .replace('_', '/')
                )
            }.getOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
    }

    private fun fixUrl(url: String): String {
        val value = url.trim()

        return when {
            value.startsWith("http://") ||
            value.startsWith("https://") -> value

            value.startsWith("//") -> "https:$value"

            value.startsWith("/") -> "$mainUrl$value"

            else -> "$mainUrl/${value.trimStart('/')}"
        }
    }

    data class ArabdramaData(
        val show_info: List<ShowInfo> = emptyList(),
        val ep_info: List<EpisodeInfo> = emptyList(),
        val eps_urls: List<EpisodeUrl> = emptyList()
    ) {
        val showInfo get() = show_info
        val epInfo get() = ep_info
        val epsUrls get() = eps_urls
    }

    data class ShowInfo(
        val drama_name: String? = null,
        val drama_description: String? = null,
        val drama_cover_image_url: String? = null
    ) {
        val dramaName get() = drama_name
        val description get() = drama_description
        val coverImage get() = drama_cover_image_url
    }

    data class EpisodeInfo(
        val episode_number: Int? = null,
        val stream_servers: List<String> = emptyList()
    ) {
        val streamServers get() = stream_servers
    }

    data class EpisodeUrl(
        val episode_number: String? = null,
        val episode_name: String? = null,
        val watch_url: String? = null
    ) {
        val episodeNumber get() = episode_number
        val episodeName get() = episode_name
        val watchUrl get() = watch_url
    }
}
