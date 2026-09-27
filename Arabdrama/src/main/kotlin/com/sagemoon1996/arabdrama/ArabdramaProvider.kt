package com.sagemoon1996.arabdrama

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class ArabdramaProvider : MainAPI() {

    override var mainUrl = "https://www.arab-drama.me"
    override var name = "Arabdrama"
    override var lang = "ar"

    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "أحدث الحلقات المضافة"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val document = app.get(
            request.data,
            referer = mainUrl
        ).document

        return newHomePageResponse(
            request.name,
            parseMainPageDocument(document)
        )
    }

    private fun parseMainPageDocument(
        document: Document
    ): List<SearchResponse> {

        return document
            .select("a[href*='/watch-']:has(img)")
            .mapNotNull { element ->

                val rawHref = element.attr("href").trim()

                if (rawHref.isBlank()) {
                    return@mapNotNull null
                }

                val url = fixUrl(rawHref)

                val rawTitle = element.attr("title")
                    .trim()
                    .ifBlank {
                        element.text().trim()
                    }

                if (rawTitle.isBlank()) {
                    return@mapNotNull null
                }

                val title = rawTitle
                    .replace(
                        Regex("\\s+الحلقة\\s+\\d+.*$"),
                        ""
                    )
                    .trim()
                    .ifBlank {
                        rawTitle
                    }

                val posterUrl = element
                    .selectFirst("img")
                    ?.attr("src")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::fixUrl)

                newTvSeriesSearchResponse(
                    title,
                    url,
                    TvType.TvSeries,
                    false
                ) {
                    this.posterUrl = posterUrl
                }
            }
            .distinctBy { it.url }
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val trimmed = query.trim()

        if (trimmed.isBlank()) {
            return emptyList()
        }

        val document = runCatching {
            app.post(
                "$mainUrl/searchq",
                data = mapOf("searchq" to trimmed),
                referer = mainUrl
            ).document
        }.getOrNull() ?: return emptyList()

        return parseListingDocument(document)
    }

    private fun parseListingDocument(
        document: Document
    ): List<SearchResponse> {

        return document
            .select(
                "a[href*='/show-'][title], a[href*='/movie-'][title]"
            )
            .mapNotNull { parseListingItem(it) }
            .distinctBy { it.url }
    }

    private fun parseListingItem(
        element: Element
    ): SearchResponse? {

        val rawHref = element.attr("href").trim()

        if (rawHref.isBlank()) {
            return null
        }

        val url = fixUrl(rawHref)

        val title = cleanTitle(
            element.attr("title")
                .trim()
                .ifBlank { element.text().trim() }
        )

        if (title.isBlank()) {
            return null
        }

        return if (
            url.contains("/movie-", ignoreCase = true)
        ) {
            newMovieSearchResponse(
                title,
                url,
                TvType.Movie,
                false
            )
        } else {
            newTvSeriesSearchResponse(
                title,
                url,
                TvType.TvSeries,
                false
            )
        }
    }

    private fun cleanTitle(
        value: String
    ): String {
        return value
            .replace("\n", " ")
            .replace("\r", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document = runCatching {
            app.get(
                fixUrl(url),
                referer = mainUrl
            ).document
        }.getOrNull() ?: return null

        val data = getArabdramaData(document)
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
                    .selectFirst("h1")
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: return null

        val poster = show.coverImage
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it) }

        val description = show.description
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val tags = show.genres
            ?.split(",", "،")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }

        val isMovie = show.dramaType
            ?.equals("Movie", ignoreCase = true) == true

        val episodes = data.epsUrls
            .mapNotNull { episodeData ->

                val episodeUrl = episodeData.watchUrl
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val number = episodeData.episodeNumber
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

        return if (
            isMovie && episodes.size <= 1
        ) {

            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                episodes.firstOrNull()?.data ?: url
            ) {
                posterUrl = poster
                plot = description
                this.tags = tags
            }

        } else {

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {
                posterUrl = poster
                plot = description
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = runCatching {
            app.get(
                fixUrl(data),
                referer = mainUrl
            ).document
        }.getOrNull() ?: return false

        val episodeData = getArabdramaData(document)
            ?: return false

        val episode = episodeData.epInfo.firstOrNull()
            ?: return false

        val servers = episode.streamServers

        if (servers.isEmpty()) {
            return false
        }

        var foundLinks = false

        servers.forEachIndexed { index, encodedServer ->

            val embedUrl = decodeServerUrl(encodedServer)
                ?: return@forEachIndexed

            if (
                !embedUrl.startsWith("http://") &&
                !embedUrl.startsWith("https://")
            ) {
                return@forEachIndexed
            }

            val embedDocument = runCatching {
                app.get(
                    embedUrl,
                    referer = data
                ).document
            }.getOrNull()
                ?: return@forEachIndexed

            val videoSource =
                embedDocument
                    .selectFirst("video#player source[src]")
                    ?.attr("src")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: embedDocument
                        .selectFirst("video source[src]")
                        ?.attr("src")
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                    ?: return@forEachIndexed

            val videoUrl = fixUrl(videoSource)

            callback(
                newExtractorLink(
                    source = name,
                    name = "Server ${index + 1}",
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    referer = embedUrl
                }
            )

            foundLinks = true
        }

        return foundLinks
    }

    /*
     * ArabDrama uses two different JSON schemas.
     *
     * Show page:
     * {
     *   "show": [...],
     *   "EPS": [...]
     * }
     *
     * Watch page:
     * {
     *   "show_info": [...],
     *   "ep_info": [...],
     *   "eps_urls": [...]
     * }
     */

    private val watchDataRegex =
        Regex("""eyJzaG93X2luZm8i[\w+/=]+""")

    private val showDataRegex =
        Regex("""eyJzaG93Ijpb[\w+/=]+""")

    private fun getArabdramaData(
        document: Document
    ): ArabdramaData? {

        val text = document.body()
            ?.text()
            ?: return null

        /*
         * Watch page.
         */
        val watchEncoded = watchDataRegex
            .find(text)
            ?.value

        if (!watchEncoded.isNullOrBlank()) {

            val watchDecoded = runCatching {
                decodeBase64(watchEncoded)
            }.getOrNull()

            if (!watchDecoded.isNullOrBlank()) {

                val watchData = runCatching {
                    parseJson<ArabdramaData>(
                        watchDecoded
                    )
                }.getOrNull()

                if (watchData != null) {
                    return watchData
                }
            }
        }

        /*
         * Show page.
         */
        val showEncoded = showDataRegex
            .find(text)
            ?.value
            ?: return null

        val showDecoded = runCatching {
            decodeBase64(showEncoded)
        }.getOrNull()
            ?: return null

        val showPageData = runCatching {
            parseJson<ArabdramaShowPageData>(
                showDecoded
            )
        }.getOrNull()
            ?: return null

        val convertedShowInfo =
            showPageData.show.map {

                ShowInfo(
                    drama_id = it.drama_id?.toString(),
                    drama_name = it.drama_name,
                    drama_synonyms = it.drama_synonyms,
                    drama_score = it.drama_score,
                    drama_type = it.drama_type,
                    drama_description = it.drama_description,
                    drama_genres = it.drama_genres,
                    drama_cover_image_url =
                        it.drama_cover_image_url,
                    drama_slug = it.drama_slug,
                    info_url = null
                )
            }

        val convertedEpisodes =
            showPageData.EPS.mapNotNull { episode ->

                val number = episode.episode_number
                    ?.toString()
                    ?: return@mapNotNull null

                val watchUrl = episode.infoSrc
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                EpisodeUrl(
                    episode_number = number,
                    episode_name = episode.episode_name,
                    watch_url = watchUrl
                )
            }

        return ArabdramaData(
            show_info = convertedShowInfo,
            ep_info = emptyList(),
            eps_urls = convertedEpisodes
        )
    }

    /*
     * Local Base64 decoder.
     *
     * We do not depend on CloudStream's base64Decode helper,
     * because it is not available in the project's current API.
     */
    private fun decodeBase64(
        value: String
    ): String {

        val normalized = value
            .replace("-", "+")
            .replace("_", "/")
            .let { input ->
                input + "=".repeat(
                    (4 - input.length % 4) % 4
                )
            }

        return String(
            Base64.decode(
                normalized,
                Base64.DEFAULT
            ),
            StandardCharsets.UTF_8
        )
    }

    private fun decodeServerUrl(
        value: String
    ): String? {

        var current = value.trim()

        if (current.isBlank()) {
            return null
        }

        repeat(3) {

            if (
                current.startsWith("http://") ||
                current.startsWith("https://")
            ) {
                return current
            }

            val decoded = runCatching {
                decodeBase64(current)
            }.getOrNull()

            if (
                !decoded.isNullOrBlank() &&
                decoded != current
            ) {
                current = decoded.trim()
                return@repeat
            }

            val urlDecoded = runCatching {
                URLDecoder.decode(
                    current,
                    StandardCharsets.UTF_8.name()
                )
            }.getOrNull()

            if (
                !urlDecoded.isNullOrBlank() &&
                urlDecoded != current
            ) {
                current = urlDecoded.trim()
                return@repeat
            }

            return@repeat
        }

        return current.takeIf {
            it.startsWith("http://") ||
                it.startsWith("https://")
        }
    }

    private fun fixUrl(
        url: String
    ): String {

        val trimmed = url.trim()

        return when {

            trimmed.startsWith("http://") ||
                trimmed.startsWith("https://") ->
                trimmed

            trimmed.startsWith("//") ->
                "https:$trimmed"

            else ->
                "$mainUrl/${trimmed.trimStart('/')}"
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
        val drama_id: String? = null,
        val drama_name: String? = null,
        val drama_synonyms: String? = null,
        val drama_score: String? = null,
        val drama_type: String? = null,
        val drama_description: String? = null,
        val drama_genres: String? = null,
        val drama_cover_image_url: String? = null,
        val drama_slug: String? = null,
        val info_url: String? = null
    ) {
        val dramaName: String?
            get() = drama_name

        val description: String?
            get() = drama_description

        val coverImage: String?
            get() = drama_cover_image_url

        val genres: String?
            get() = drama_genres

        val dramaType: String?
            get() = drama_type

        val infoUrl: String?
            get() = info_url
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

    data class ArabdramaShowPageData(
        val show: List<ShowPageInfo> = emptyList(),

        @JsonProperty("EPS")
        val EPS: List<ShowPageEpisode> = emptyList()
    )

    data class ShowPageInfo(
        val drama_id: Int? = null,
        val drama_name: String? = null,
        val drama_synonyms: String? = null,
        val drama_score: String? = null,
        val drama_country: String? = null,
        val drama_status: String? = null,
        val drama_type: String? = null,
        val drama_release_date: String? = null,
        val drama_description: String? = null,
        val drama_genres: String? = null,
        val drama_cover_image_url: String? = null,
        val wallpapaer: String? = null,
        val drama_slug: String? = null,
        val show_episode_count: Int? = null
    )

    data class ShowPageEpisode(
        val episode_name: String? = null,
        val episode_number: Int? = null,

        @JsonProperty("info-src")
        val infoSrc: String? = null
    )
}
