package com.sagemoon1996.arabdrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element

class ArabdramaProvider : MainAPI() {

    override var mainUrl = "https://www.arab-drama.me"
    override var name = "Arabdrama"

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.AsianDrama
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${query.urlEncode()}"

        val document = app.get(url).document

        return document.select(
            "a[href*='/show-'], " +
            "a[href*='/watch-']"
        )
            .mapNotNull { element ->
                val href = element.attr("href").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val title = element.text().trim()
                    .takeIf { it.isNotBlank() }
                    ?: element.selectFirst("img")?.attr("alt")
                    ?: return@mapNotNull null

                val poster = element.selectFirst("img")
                    ?.let {
                        it.attr("data-src")
                            .ifBlank { it.attr("src") }
                    }

                val showUrl = when {
                    "/show-" in href -> fixUrl(href)
                    "/watch-" in href -> {
                        val match = Regex(
                            """(/show-\d+/[^/?#]+)"""
                        ).find(href)

                        match?.groupValues?.getOrNull(1)
                            ?.let { "$mainUrl$it" }
                            ?: return@mapNotNull null
                    }

                    else -> return@mapNotNull null
                }

                newTvSeriesSearchResponse(
                    title,
                    showUrl,
                    TvType.TvSeries
                ) {
                    this.posterUrl = poster
                }
            }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        /*
         * The show page contains a Base64 encoded JSON block.
         * We use it as the main source for title/poster/episodes.
         */
        val encodedData = document
            .selectFirst("body")
            ?.text()
            ?.let {
                Regex(
                    """eyJzaG93Ij[\w+/=]+"""
                ).find(it)?.value
            }

        val showData = encodedData?.let {
            runCatching {
                parseJson<ShowPageData>(
                    base64Decode(it)
                )
            }.getOrNull()
        }

        val show = showData?.show?.firstOrNull()

        val title = show?.dramaName
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: return null

        val poster = show?.dramaCoverImageUrl

        val year = show?.dramaReleaseDate
            ?.toIntOrNull()

        val plot = show?.dramaDescription

        val episodes = showData?.eps
            ?.mapNotNull { episode ->
                val episodeUrl = episode.infoSrc
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                newEpisode(episodeUrl) {
                    this.name = episode.episodeName
                    this.episode = episode.episodeNumber
                }
            }
            ?.sortedBy { it.episode }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes ?: emptyList()
        ) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
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

        /*
         * datawatch is the encoded JSON container used by
         * arab-drama.me on episode pages.
         */
        val encodedData = document
            .selectFirst("#datawatch")
            ?.text()
            ?.trim()
            ?: document
                .selectFirst("#datawatch")
                ?.html()
                ?.trim()

            ?: return false

        if (encodedData.isBlank()) {
            return false
        }

        val decoded = runCatching {
            base64Decode(encodedData)
        }.getOrNull() ?: return false

        val episodeData = runCatching {
            parseJson<EpisodePageData>(decoded)
        }.getOrNull() ?: return false

        val servers = episodeData
            .epInfo
            ?.streamServers
            .orEmpty()

        if (servers.isEmpty()) {
            return false
        }

        var loaded = false

        /*
         * Do NOT assume a fixed number of servers.
         * Every server returned by stream_servers is processed.
         */
        servers.forEachIndexed { index, server ->

            val rawUrl = server.url
                ?: server.src
                ?: server.link
                ?: server.serverUrl
                ?: server.value
                ?: return@forEachIndexed

            val serverUrl = decodeServerUrl(rawUrl)
                ?: return@forEachIndexed

            if (
                serverUrl.startsWith("https://") ||
                serverUrl.startsWith("http://")
            ) {

                /*
                 * First try CloudStream's registered extractor.
                 */
                val extracted = runCatching {
                    loadExtractor(
                        serverUrl,
                        data,
                        subtitleCallback,
                        callback
                    )
                }.getOrDefault(false)

                if (extracted) {
                    loaded = true
                    return@forEachIndexed
                }

                /*
                 * If it is already a direct media URL,
                 * expose it directly.
                 */
                if (
                    serverUrl.contains(".m3u8", ignoreCase = true) ||
                    serverUrl.contains(".mp4", ignoreCase = true) ||
                    serverUrl.contains(".mpd", ignoreCase = true)
                ) {

                    val type = when {
                        serverUrl.contains(
                            ".m3u8",
                            ignoreCase = true
                        ) -> ExtractorLinkType.M3U8

                        serverUrl.contains(
                            ".mpd",
                            ignoreCase = true
                        ) -> ExtractorLinkType.DASH

                        else -> ExtractorLinkType.VIDEO
                    }

                    callback.invoke(
                        newExtractorLink(
                            source = "Arabdrama",
                            name = "Arabdrama Server ${index + 1}",
                            url = serverUrl,
                            type = type
                        ) {
                            referer = data
                        }
                    )

                    loaded = true
                }
            }
        }

        return loaded
    }

    /*
     * Some server values can contain another encoded URL.
     * Try a small number of Base64 layers without assuming
     * a specific server format.
     */
    private fun decodeServerUrl(value: String): String? {

        var current = value.trim()

        repeat(3) {

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

    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("http://") ||
            url.startsWith("https://") -> url

            url.startsWith("/") -> "$mainUrl$url"

            else -> "$mainUrl/$url"
        }
    }

    data class ShowPageData(
        val show: List<ShowInfo>? = null,
        val eps: List<ShowEpisode>? = null
    )

    data class ShowInfo(
        val drama_id: Int? = null,
        val drama_name: String? = null,
        val drama_score: String? = null,
        val drama_country: String? = null,
        val drama_status: String? = null,
        val drama_type: String? = null,
        val drama_release_date: String? = null,
        val drama_description: String? = null,
        val drama_genres: String? = null,
        val drama_cover_image_url: String? = null,
        val wallpaper: String? = null,
        val drama_slug: String? = null,
        val show_episode_count: Int? = null
    ) {
        val dramaName: String?
            get() = drama_name

        val dramaDescription: String?
            get() = drama_description

        val dramaReleaseDate: String?
            get() = drama_release_date

        val dramaCoverImageUrl: String?
            get() = drama_cover_image_url
    }

    data class ShowEpisode(
        val episode_name: String? = null,
        val episode_number: Int? = null,
        val info_src: String? = null
    ) {
        val episodeName: String?
            get() = episode_name

        val episodeNumber: Int?
            get() = episode_number

        val infoSrc: String?
            get() = info_src
    }

    data class EpisodePageData(
        val show_info: List<ShowInfo>? = null,
        val ep_info: EpisodeInfo? = null
    )

    data class EpisodeInfo(
        val stream_servers: List<StreamServer>? = null
    ) {
        val streamServers: List<StreamServer>?
            get() = stream_servers
    }

    data class StreamServer(
        val url: String? = null,
        val src: String? = null,
        val link: String? = null,
        val server_url: String? = null,
        val value: String? = null
    ) {
        val serverUrl: String?
            get() = server_url
    }
}
