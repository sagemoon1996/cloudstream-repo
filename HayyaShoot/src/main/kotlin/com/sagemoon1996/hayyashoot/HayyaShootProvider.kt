package com.sagemoon1996.hayyashoot

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.URLEncoder

class HayyaShootProvider : MainAPI() {

    override var mainUrl = "https://hayyashoot.com"
    override var name = "HayyaShoot"
    override var lang = "ar"

    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "movie" to "الأفلام",
        "tv" to "المسلسلات"
    )

    private val tmdbBaseUrl = "https://api.themoviedb.org/3"

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    // ---------------------------------------------------------------
    // TMDB TOKEN
    // ---------------------------------------------------------------

    private suspend fun getTmdbToken(): String {
        val html = app.get(
            "$mainUrl/movies/",
            headers = mapOf("User-Agent" to userAgent)
        ).text

        val patterns = listOf(
            Regex("""Bearer\s+([A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+)"""),
            Regex("""['"]?(eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+)['"]?""")
        )

        for (pattern in patterns) {
            val token = pattern.find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()

            if (!token.isNullOrBlank()) {
                return token
            }
        }

        throw ErrorLoadingException("TMDB token not found")
    }

    private suspend fun tmdbGet(
        path: String,
        token: String
    ): JSONObject {

        val response = app.get(
            "$tmdbBaseUrl$path",
            headers = mapOf(
                "Authorization" to "Bearer $token",
                "Accept" to "application/json",
                "User-Agent" to userAgent
            )
        )

        return JSONObject(response.text)
    }

    // ---------------------------------------------------------------
    // HELPERS
    // ---------------------------------------------------------------

    private fun posterUrl(path: String?): String? {
        return path
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?.let {
                "https://image.tmdb.org/t/p/w500$it"
            }
    }

    private fun backdropUrl(path: String?): String? {
        return path
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?.let {
                "https://image.tmdb.org/t/p/original$it"
            }
    }

    private fun titleSlug(title: String): String {
        return URLEncoder.encode(
            title.trim()
                .replace(Regex("""[\s-]+"""), "-"),
            "UTF-8"
        )
    }

    private fun hayyaUrl(
        type: String,
        id: Int,
        title: String,
        season: Int? = null,
        episode: Int? = null
    ): String {

        val params = StringBuilder()

        params.append("?")
        params.append(type)
        params.append("=")
        params.append(id)

        if (season != null) {
            params.append("&s=")
            params.append(season)
        }

        if (episode != null) {
            params.append("&e=")
            params.append(episode)
        }

        params.append("&title=")
        params.append(titleSlug(title))

        return "$mainUrl/movies/$params"
    }

    private fun getQueryValue(
        url: String,
        key: String
    ): String? {
        val match = Regex(
            """(?:\?|&)${Regex.escape(key)}=([^&]+)"""
        ).find(url)

        return match
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun getIntQuery(
        url: String,
        key: String
    ): Int? {
        return getQueryValue(url, key)?.toIntOrNull()
    }

    // ---------------------------------------------------------------
    // SEARCH RESPONSE
    // ---------------------------------------------------------------

    private fun movieSearch(
        item: JSONObject
    ): SearchResponse? {

        val id = item.optInt("id", 0)
        if (id <= 0) return null

        val title = item.optString("title")
            .takeIf { it.isNotBlank() }
            ?: return null

        val url = hayyaUrl(
            "movie",
            id,
            title
        )

        return newMovieSearchResponse(
            title,
            url,
            TvType.Movie
        ) {
            posterUrl = posterUrl(
                item.optString("poster_path")
            )
        }
    }

    private fun tvSearch(
        item: JSONObject
    ): SearchResponse? {

        val id = item.optInt("id", 0)
        if (id <= 0) return null

        val title = item.optString("name")
            .takeIf { it.isNotBlank() }
            ?: return null

        val url = hayyaUrl(
            "tv",
            id,
            title
        )

        return newTvSeriesSearchResponse(
            title,
            url,
            TvType.TvSeries
        ) {
            posterUrl = posterUrl(
                item.optString("poster_path")
            )
        }
    }

    // ---------------------------------------------------------------
    // MAIN PAGE
    // ---------------------------------------------------------------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val token = getTmdbToken()

        val type = if (
            request.data.equals("tv", ignoreCase = true)
        ) {
            "tv"
        } else {
            "movie"
        }

        val response = tmdbGet(
            "/$type/popular" +
                "?language=ar-SA" +
                "&page=$page",
            token
        )

        val results = response.optJSONArray("results")
            ?: return newHomePageResponse(
                request.name,
                emptyList(),
                hasNext = false
            )

        val items = (0 until results.length())
            .mapNotNull { index ->
                val item = results.optJSONObject(index)
                    ?: return@mapNotNull null

                if (type == "movie") {
                    movieSearch(item)
                } else {
                    tvSearch(item)
                }
            }

        val totalPages = response.optInt(
            "total_pages",
            page
        )

        return newHomePageResponse(
            request.name,
            items,
            hasNext = page < totalPages
        )
    }

    // ---------------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------------

    override suspend fun search(
        query: String
    ): List<SearchResponse> = coroutineScope {

        val token = getTmdbToken()

        val encoded = URLEncoder.encode(
            query.trim(),
            "UTF-8"
        )

        val movieDeferred = async {
            try {
                tmdbGet(
                    "/search/movie" +
                        "?query=$encoded" +
                        "&language=ar-SA" +
                        "&page=1",
                    token
                )
            } catch (_: Exception) {
                null
            }
        }

        val tvDeferred = async {
            try {
                tmdbGet(
                    "/search/tv" +
                        "?query=$encoded" +
                        "&language=ar-SA" +
                        "&page=1",
                    token
                )
            } catch (_: Exception) {
                null
            }
        }

        val movieResponse = movieDeferred.await()
        val tvResponse = tvDeferred.await()

        val movies = movieResponse
            ?.optJSONArray("results")
            ?.let { array ->
                (0 until array.length())
                    .mapNotNull { index ->
                        movieSearch(
                            array.optJSONObject(index)
                                ?: return@mapNotNull null
                        )
                    }
            }
            ?: emptyList()

        val tvShows = tvResponse
            ?.optJSONArray("results")
            ?.let { array ->
                (0 until array.length())
                    .mapNotNull { index ->
                        tvSearch(
                            array.optJSONObject(index)
                                ?: return@mapNotNull null
                        )
                    }
            }
            ?: emptyList()

        movies + tvShows
    }

    // ---------------------------------------------------------------
    // LOAD MOVIE / TV
    // ---------------------------------------------------------------

    override suspend fun load(
        url: String
    ): LoadResponse {

        val movieId = getIntQuery(url, "movie")
        val tvId = getIntQuery(url, "tv")

        val token = getTmdbToken()

        if (movieId != null) {

            val data = tmdbGet(
                "/movie/$movieId?language=ar-SA",
                token
            )

            val title = data.optString("title")
                .takeIf { it.isNotBlank() }
                ?: throw ErrorLoadingException(
                    "Movie title not found"
                )

            val description = data.optString(
                "overview"
            ).takeIf { it.isNotBlank() }

            val year = data.optString(
                "release_date"
            )
                .takeIf { it.isNotBlank() }
                ?.take(4)
                ?.toIntOrNull()

            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                posterUrl = posterUrl(
                    data.optString("poster_path")
                )

                backgroundPosterUrl = backdropUrl(
                    data.optString("backdrop_path")
                )

                plot = description
                this.year = year

                rating = (data.optDouble(
                    "vote_average",
                    0.0
                ) * 10).toInt()
            }
        }

        if (tvId != null) {

            val data = tmdbGet(
                "/tv/$tvId?language=ar-SA",
                token
            )

            val title = data.optString("name")
                .takeIf { it.isNotBlank() }
                ?: throw ErrorLoadingException(
                    "TV title not found"
                )

            val description = data.optString(
                "overview"
            ).takeIf { it.isNotBlank() }

            val year = data.optString(
                "first_air_date"
            )
                .takeIf { it.isNotBlank() }
                ?.take(4)
                ?.toIntOrNull()

            val seasons = data.optJSONArray("seasons")

            val episodes = mutableListOf<Episode>()

            if (seasons != null) {

                for (i in 0 until seasons.length()) {

                    val season = seasons.optJSONObject(i)
                        ?: continue

                    val seasonNumber =
                        season.optInt(
                            "season_number",
                            -1
                        )

                    if (seasonNumber <= 0) continue

                    val seasonData = try {
                        tmdbGet(
                            "/tv/$tvId/season/$seasonNumber" +
                                "?language=ar-SA",
                            token
                        )
                    } catch (_: Exception) {
                        continue
                    }

                    val episodeArray =
                        seasonData.optJSONArray(
                            "episodes"
                        ) ?: continue

                    for (j in 0 until episodeArray.length()) {

                        val ep =
                            episodeArray.optJSONObject(j)
                                ?: continue

                        val episodeNumber =
                            ep.optInt(
                                "episode_number",
                                0
                            )

                        if (episodeNumber <= 0) continue

                        val episodeName =
                            ep.optString("name")
                                .takeIf { it.isNotBlank() }
                                ?: "الحلقة $episodeNumber"

                        val episodeUrl = hayyaUrl(
                            "tv",
                            tvId,
                            title,
                            seasonNumber,
                            episodeNumber
                        )

                        newEpisode(
                            episodeUrl
                        ) {
                            name = episodeName
                            season = seasonNumber
                            episode = episodeNumber

                            posterUrl = posterUrl(
                                ep.optString(
                                    "still_path"
                                )
                            ) ?: posterUrl(
                                data.optString(
                                    "poster_path"
                                )
                            )

                            description = ep.optString(
                                "overview"
                            ).takeIf { it.isNotBlank() }
                        }.also {
                            episodes.add(it)
                        }
                    }
                }
            }

            return newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {
                posterUrl = posterUrl(
                    data.optString("poster_path")
                )

                backgroundPosterUrl = backdropUrl(
                    data.optString("backdrop_path")
                )

                plot = description
                this.year = year

                rating = (data.optDouble(
                    "vote_average",
                    0.0
                ) * 10).toInt()
            }
        }

        throw ErrorLoadingException(
            "Unsupported HayyaShoot URL"
        )
    }

    // ---------------------------------------------------------------
    // VIDSRC
    // ---------------------------------------------------------------

    private fun buildVidSrcEmbedUrl(
        tmdbId: Int,
        type: String,
        season: Int?,
        episode: Int?
    ): String {

        return if (type == "tv") {
            "https://vidsrc-embed.ru/embed/tv/$tmdbId/$season/$episode"
        } else {
            "https://vidsrc-embed.ru/embed/movie/$tmdbId"
        }
    }

    private fun extractRcpUrl(
        document: String
    ): String? {

        val regex = Regex(
            """src=["']((?:https?:)?//[^"']*cloudnestra\.com/rcp/[^"']+)["']""",
            RegexOption.IGNORE_CASE
        )

        val value = regex.find(document)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?: return null

        return if (value.startsWith("//")) {
            "https:$value"
        } else {
            value
        }
    }

    private fun extractProrcpHash(
        document: String
    ): String? {

        return Regex(
            """/prorcp/([a-zA-Z0-9=+/]+)"""
        )
            .find(document)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun extractFileUrl(
        document: String
    ): String? {

        val raw = Regex(
            """file:\s*["']([^"']+)["']"""
        )
            .find(document)
            ?.groupValues
            ?.getOrNull(1)
            ?: return null

        return raw
            .split(" or ")
            .first()
            .trim()
            .replace(
                Regex("""\{v[1-5]\}"""),
                "cloudnestra.com"
            )
    }

    private fun extractSubtitles(
        document: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {

        val regex = Regex(
            """["'](https?://[^"']+\.(?:vtt|srt))["']""",
            RegexOption.IGNORE_CASE
        )

        val urls = regex
            .findAll(document)
            .map {
                it.groupValues[1]
            }
            .distinct()

        for (subtitleUrl in urls) {

            subtitleCallback(
                SubtitleFile(
                    "Arabic",
                    subtitleUrl
                )
            )
        }
    }

    // ---------------------------------------------------------------
    // LOAD LINKS
    // ---------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val movieId = getIntQuery(
            data,
            "movie"
        )

        val tvId = getIntQuery(
            data,
            "tv"
        )

        val season = getIntQuery(
            data,
            "s"
        )

        val episode = getIntQuery(
            data,
            "e"
        )

        val type: String
        val tmdbId: Int

        if (movieId != null) {
            type = "movie"
            tmdbId = movieId
        } else if (
            tvId != null &&
            season != null &&
            episode != null
        ) {
            type = "tv"
            tmdbId = tvId
        } else {
            return false
        }

        val embedUrl = buildVidSrcEmbedUrl(
            tmdbId,
            type,
            season,
            episode
        )

        val defaultHeaders = mapOf(
            "User-Agent" to
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) " +
                "Gecko/20100101 Firefox/121.0",
            "Accept" to
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
            "Accept-Encoding" to "identity"
        )

        // Step 1:
        // VidSrc embed
        val embedDocument = try {
            app.get(
                embedUrl,
                headers = defaultHeaders
            ).text
        } catch (_: Exception) {
            return false
        }

        // Step 2:
        // cloudnestra /rcp/
        val rcpUrl = extractRcpUrl(
            embedDocument
        ) ?: return false

        val rcpDocument = try {
            app.get(
                rcpUrl,
                headers = defaultHeaders,
                referer = embedUrl
            ).text
        } catch (_: Exception) {
            return false
        }

        // Step 3:
        // /prorcp/{hash}
        val prorcpHash = extractProrcpHash(
            rcpDocument
        ) ?: return false

        val rcpOrigin = try {
            rcpUrl.substringBefore(
                "/rcp/"
            )
        } catch (_: Exception) {
            return false
        }

        val prorcpUrl =
            "$rcpOrigin/prorcp/$prorcpHash"

        // Step 4:
        // file: "...m3u8"
        val prorcpDocument = try {
            app.get(
                prorcpUrl,
                headers = defaultHeaders,
                referer = rcpUrl
            ).text
        } catch (_: Exception) {
            return false
        }

        val hlsUrl = extractFileUrl(
            prorcpDocument
        ) ?: return false

        // Subtitles from the same prorcp page.
        extractSubtitles(
            prorcpDocument,
            subtitleCallback
        )

        callback(
            newExtractorLink(
                "VidSrc",
                "VidSrc",
                hlsUrl,
                type = ExtractorLinkType.M3U8
            ) {
                referer = "https://cloudnestra.com/"
                quality = Qualities.Unknown.value
            }
        )

        return true
    }
}
