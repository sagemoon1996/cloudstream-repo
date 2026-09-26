package com.sagemoon1996.arabdrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Document

class ArabdramaProvider : MainAPI() {

    override var mainUrl = "https://www.arab-drama.me"
    override var name = "Arabdrama"

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${query.urlEncode()}"
        val document = app.get(url).document

        return document
            .select("a[href*='/show-']")
            .mapNotNull { element ->

                val href = element.attr("href")
                    .takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val title = element.text()
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?: element.selectFirst("img")
                        ?.attr("alt")
                        ?.trim()
                    ?: return@mapNotNull null

                val poster = element.selectFirst("img")?.let {
                    it.attr("data-src").ifBlank {
                        it.attr("src")
                    }
                }

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

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val data = getDatawatch(document)
            ?: return null

        val show = data.showInfo.firstOrNull()
            ?: return null

        val title = show.dramaName
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1")
                ?.text()
                ?.trim()
            ?: return null

        val episodes = data.epsUrls
            .mapNotNull { episodeData ->

                val episodeUrl = episodeData.watchUrl
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val episodeNumber =
                    episodeData.episodeNumber?.toIntOrNull()

                newEpisode(episodeUrl) {
                    name = episodeData.episodeName
                    this.episode = episodeNumber
                }
            }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            posterUrl = show.coverImage?.let { fixUrl(it) }
            plot = show.description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(
            data,
            referer = mainUrl
        ).document

        val episodeData = getDatawatch(document)
            ?: return false

        val episode = episodeData.epInfo.firstOrNull()
            ?: return false

        val servers = episode.streamServers

        if (servers.isEmpty()) {
            return false
        }

        var found = false

        servers.forEachIndexed { index, encodedServer ->

            val serverUrl = decodeServerUrl(encodedServer)
                ?: return@forEachIndexed

            if (
                !serverUrl.startsWith("http://") &&
                !serverUrl.startsWith("https://")
            ) {
                return@forEachIndexed
            }

            if (
                serverUrl.contains(".m3u8", ignoreCase = true) ||
                serverUrl.contains(".mpd", ignoreCase = true) ||
                serverUrl.contains(".mp4", ignoreCase = true)
            ) {

                val type = when {
                    serverUrl.contains(".m3u8", ignoreCase = true) ->
                        ExtractorLinkType.M3U8

                    serverUrl.contains(".mpd", ignoreCase = true) ->
                        ExtractorLinkType.DASH

                    else ->
                        ExtractorLinkType.VIDEO
                }

                callback(
                    newExtractorLink(
                        source = "Arabdrama",
                        name = "Arabdrama Server ${index + 1}",
                        url = serverUrl,
                        type = type
                    ) {
                        referer = data
                    }
                )

                found = true
            } else {

                val extracted = runCatching {
                    loadExtractor(
                        serverUrl,
                        data,
                        subtitleCallback,
                        callback
                    )
                }.getOrDefault(false)

                if (extracted) {
                    found = true
                }
            }
        }

        return found
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

            if (decoded == current) {
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
        return when {
            url.startsWith("http://") ||
            url.startsWith("https://") -> url

            url.startsWith("/") -> "$mainUrl$url"

            else -> "$mainUrl/$url"
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
