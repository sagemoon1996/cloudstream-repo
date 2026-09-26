package com.sagemoon1996.arabdrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class ArabdramaProvider : MainAPI() {

    override var mainUrl = "https://www.arab-drama.me"
    override var name = "Arabdrama"
    override var lang = "ar"

    // The homepage lists recently added episodes, so we can drive
    // getMainPage() directly from it.
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

        val items = parseListingDocument(document)

        return newHomePageResponse(request.name, items)
    }

    /*
     * IMPORTANT: I could not confirm the site's real search request
     * (method / field name) because the fetch tool used to inspect
     * this site strips <form> tags and JavaScript, so the actual
     * request the search box sends is invisible to me.
     *
     * This tries several common patterns in turn and keeps the first
     * one that returns results. If none of them work on your device,
     * open the site in a browser, use dev tools -> Network tab, type
     * a query in the search box, and send me the exact request URL
     * (and method/body if it's a POST) so this can be wired precisely.
     */
    override suspend fun search(query: String): List<SearchResponse> {

        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        val encoded = trimmed.urlEncode()

        val getCandidates = listOf(
            "$mainUrl/search?keyword=$encoded",
            "$mainUrl/search?q=$encoded",
            "$mainUrl/search/$encoded",
            "$mainUrl/searchq?searchq=$encoded"
        )

        for (candidate in getCandidates) {

            val document = runCatching {
                app.get(candidate, referer = mainUrl).document
            }.getOrNull() ?: continue

            val results = parseListingDocument(document)

            if (results.isNotEmpty()) {
                return results
            }
        }

        // Fallback: POST request, in case the search box submits a form.
        val postDocument = runCatching {
            app.post(
                "$mainUrl/searchq",
                data = mapOf("searchq" to trimmed),
                referer = mainUrl
            ).document
        }.getOrNull() ?: return emptyList()

        return parseListingDocument(postDocument)
    }

    /*
     * Generic listing parser, reused for the homepage and for
     * whatever markup a search-results page returns. It doesn't
     * depend on specific wrapper div/class names (which I couldn't
     * verify), only on the href pattern and title attribute that I
     * confirmed are present on every show/episode link on this site.
     */
    private fun parseListingDocument(document: Document): List<SearchResponse> {
        return document
            .select("a[href*='/show-'][title], a[href*='/watch-'][title]")
            .mapNotNull { parseListingItem(it) }
            .distinctBy { it.url }
    }

    private fun parseListingItem(element: Element): SearchResponse? {

        val href = element
            .attr("href")
            .trim()
            .takeIf { it.isNotBlank() }
            ?: return null

        val fullUrl = fixUrl(href)

        val rawTitle = element
            .attr("title")
            .trim()
            .takeIf { it.isNotBlank() }
            ?: element.selectFirst("img")
                ?.attr("alt")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: return null

        // Strip a trailing "الحلقة N" so episode links collapse to a
        // clean series title.
        val title = rawTitle
            .replace(Regex("""\s*الحلقة\s*\d+.*$"""), "")
            .trim()
            .ifBlank { rawTitle }

        val poster = element
            .selectFirst("img")
            ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it) }

        val isMovie =
            fullUrl.contains("/movie-", ignoreCase = true) ||
                fullUrl.contains("/film-", ignoreCase = true) ||
                title.contains("فيلم", ignoreCase = true)

        return if (isMovie) {
            newMovieSearchResponse(
                name = title,
                url = fullUrl,
                type = TvType.Movie,
                fix = false
            ) {
                posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(
                title,
                fullUrl,
                TvType.TvSeries,
                false
            ) {
                posterUrl = poster
            }
        }
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document = app.get(
            url,
            referer = mainUrl
        ).document

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

        return if (isMovie && episodes.size <= 1) {

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

        val document = try {
            app.get(
                data,
                referer = mainUrl
            ).document
        } catch (_: Exception) {
            return false
        }

        val episodeData = getArabdramaData(document)
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

    /*
     * Pulls the site's embedded Base64 JSON blob out of the page.
     *
     * I confirmed this blob's real prefix by decoding a live page:
     * it always starts with the Base64 encoding of
     * {"show_info":[...  i.e. "eyJzaG93X2luZm8i".
     *
     * This scans the whole page text for that pattern instead of
     * relying on a specific element id like #datawatch, which I was
     * not able to verify from the outside.
     */
    private val dataRegex = Regex("""eyJzaG93X2luZm8i[\w+/=]+""")

    private fun getArabdramaData(
        document: Document
    ): ArabdramaData? {

        val text = document.body()?.text()
            ?: return null

        val encoded = dataRegex.find(text)?.value
            ?: return null

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

    // Field names below match what I decoded from a live page's
    // embedded JSON blob (show-2075 / doctor-cha).
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

    // Confirmed: stream_servers is a flat list of Base64-encoded
    // strings, not a list of objects.
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
