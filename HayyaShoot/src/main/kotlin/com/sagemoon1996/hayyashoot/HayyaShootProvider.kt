package com.sagemoon1996.hayyashoot

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.Qualities

class HayyaShootProvider : MainAPI() {

    override var mainUrl = "https://hayyashoot.com"
    override var name = "HayyaShoot"
    override val lang = "ar"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val hasMainPage = true

    private val imageBase = "https://image.tmdb.org/t/p/w500"

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0"

    private val tmdbHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "application/json, text/plain, */*"
    )

    private suspend fun getAuthToken(): String? {
        val response = app.get(
            "$mainUrl/movies/",
            headers = mapOf(
                "User-Agent" to userAgent,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
        )

        val tokenRegex = Regex(
            """(?:AUTH_TOKEN|authToken)\s*=\s*["']([^"']+)["']"""
        )

        return tokenRegex.find(response.text)?.groupValues?.getOrNull(1)
    }

    private suspend fun tmdbGet(
        path: String,
        token: String
    ): String {
        return app.get(
            "https://api.themoviedb.org/3/$path",
            headers = tmdbHeaders + mapOf(
                "Authorization" to "Bearer $token"
            )
        ).text
    }

    private fun movieUrl(
        id: Int,
        title: String
    ): String {
        val slug = title
            .replace(Regex("""[\s-]+"""), "-")
            .trim('-')

        return "$mainUrl/movies/?movie=$id&title=$slug"
    }

    private fun tvUrl(
        id: Int,
        title: String
    ): String {
        val slug = title
            .replace(Regex("""[\s-]+"""), "-")
            .trim('-')

        return "$mainUrl/movies/?tv=$id&title=$slug"
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val token = getAuthToken()
            ?: throw ErrorLoadingException("HayyaShoot AUTH_TOKEN not found")

        val sections = arrayListOf<HomePageList>()

        val movieResults = parseJson<TmdbResponse>(
            tmdbGet(
                "movie/popular?language=ar-SA&page=$page",
                token
            )
        ).results.orEmpty()

        val movieItems = movieResults.mapNotNull { item ->
            if (item.posterPath.isNullOrBlank()) return@mapNotNull null

            val title = item.title ?: return@mapNotNull null

            newMovieSearchResponse(
                title,
                movieUrl(item.id, title),
                TvType.Movie
            ) {
                this.posterUrl = imageBase + item.posterPath
            }
        }

        if (movieItems.isNotEmpty()) {
            sections.add(
                HomePageList(
                    "أفلام",
                    movieItems
                )
            )
        }

        val tvResults = parseJson<TmdbResponse>(
            tmdbGet(
                "tv/popular?language=ar-SA&page=$page",
                token
            )
        ).results.orEmpty()

        val tvItems = tvResults.mapNotNull { item ->
            if (item.posterPath.isNullOrBlank()) return@mapNotNull null

            val title = item.name ?: return@mapNotNull null

            newTvSeriesSearchResponse(
                title,
                tvUrl(item.id, title),
                TvType.TvSeries
            ) {
                this.posterUrl = imageBase + item.posterPath
            }
        }

        if (tvItems.isNotEmpty()) {
            sections.add(
                HomePageList(
                    "مسلسلات",
                    tvItems
                )
            )
        }

        return newHomePageResponse(
            sections,
            hasNext = true
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val token = getAuthToken()
            ?: throw ErrorLoadingException("HayyaShoot AUTH_TOKEN not found")

        val encodedQuery = java.net.URLEncoder
            .encode(query, "UTF-8")

        val results = arrayListOf<SearchResponse>()

        val movies = parseJson<TmdbResponse>(
            tmdbGet(
                "search/movie?query=$encodedQuery&language=ar-SA&page=1",
                token
            )
        ).results.orEmpty()

        movies.forEach { item ->
            if (item.posterPath.isNullOrBlank()) return@forEach

            val title = item.title ?: return@forEach

            results.add(
                newMovieSearchResponse(
                    title,
                    movieUrl(item.id, title),
                    TvType.Movie
                ) {
                    this.posterUrl = imageBase + item.posterPath
                }
            )
        }

        val tv = parseJson<TmdbResponse>(
            tmdbGet(
                "search/tv?query=$encodedQuery&language=ar-SA&page=1",
                token
            )
        ).results.orEmpty()

        tv.forEach { item ->
            if (item.posterPath.isNullOrBlank()) return@forEach

            val title = item.name ?: return@forEach

            results.add(
                newTvSeriesSearchResponse(
                    title,
                    tvUrl(item.id, title),
                    TvType.TvSeries
                ) {
                    this.posterUrl = imageBase + item.posterPath
                }
            )
        }

        return results
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val token = getAuthToken()
            ?: throw ErrorLoadingException("HayyaShoot AUTH_TOKEN not found")

        val uri = android.net.Uri.parse(url)

        val movieId = uri.getQueryParameter("movie")?.toIntOrNull()
        val tvId = uri.getQueryParameter("tv")?.toIntOrNull()

        if (movieId != null) {

            val data = parseJson<TmdbMovie>(
                tmdbGet(
                    "movie/$movieId?language=ar-SA",
                    token
                )
            )

            val title = data.title ?: return null

            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                movieId.toString()
            ) {
                this.posterUrl = data.posterPath?.let { imageBase + it }

                this.backgroundPosterUrl =
                    data.backdropPath?.let {
                        "https://image.tmdb.org/t/p/original$it"
                    }

                this.plot = data.overview

                this.year = data.releaseDate
                    ?.split("-")
                    ?.firstOrNull()
                    ?.toIntOrNull()

                this.rating = data.voteAverage
                    ?.times(10)
                    ?.toInt()
            }
        }

        if (tvId != null) {

            val data = parseJson<TmdbTv>(
                tmdbGet(
                    "tv/$tvId?language=ar-SA",
                    token
                )
            )

            val title = data.name ?: return null

            val episodes = arrayListOf<Episode>()

            data.seasons
                .orEmpty()
                .filter { it.seasonNumber > 0 }
                .forEach { season ->

                    val seasonData = parseJson<TmdbSeason>(
                        tmdbGet(
                            "tv/$tvId/season/${season.seasonNumber}?language=ar-SA",
                            token
                        )
                    )

                    seasonData.episodes
                        .orEmpty()
                        .forEach { episode ->

                            episodes.add(
                                newEpisode(
                                    HayyaEpisodeData(
                                        id = tvId,
                                        season = season.seasonNumber,
                                        episode = episode.episodeNumber,
                                        title = title
                                    ).toJson()
                                ) {
                                    this.name = episode.name
                                        ?: "الحلقة ${episode.episodeNumber}"

                                    this.season = season.seasonNumber
                                    this.episode = episode.episodeNumber
                                    this.description = episode.overview
                                }
                            )
                        }
                }

            return newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {
                this.posterUrl = data.posterPath?.let { imageBase + it }

                this.backgroundPosterUrl =
                    data.backdropPath?.let {
                        "https://image.tmdb.org/t/p/original$it"
                    }

                this.plot = data.overview

                this.year = data.firstAirDate
                    ?.split("-")
                    ?.firstOrNull()
                    ?.toIntOrNull()

                this.rating = data.voteAverage
                    ?.times(10)
                    ?.toInt()
            }
        }

        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val episodeData = parseJson<HayyaEpisodeData>(data)

        val embedUrl =
            if (episodeData.season != null && episodeData.episode != null) {
                "https://vidsrc-embed.ru/embed/tv/${episodeData.id}/${episodeData.season}/${episodeData.episode}"
            } else {
                "https://vidsrc-embed.ru/embed/movie/${episodeData.id}"
            }

        val baseHeaders = mapOf(
            "User-Agent" to userAgent,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
            "Accept-Encoding" to "identity"
        )

        try {

            // Step 1:
            // GET vidsrc-embed.ru/embed/...
            val embedResponse = app.get(
                embedUrl,
                headers = baseHeaders
            )

            // Step 2:
            // Extract cloudnestra.com/rcp/...
            val rcpRegex = Regex(
                """src=["']((?:https?:)?//[^"']*cloudnestra\.com/rcp/[^"']+)["']""",
                RegexOption.IGNORE_CASE
            )

            var rcpUrl = rcpRegex
                .find(embedResponse.text)
                ?.groupValues
                ?.getOrNull(1)
                ?: return false

            if (rcpUrl.startsWith("//")) {
                rcpUrl = "https:$rcpUrl"
            }

            // Step 3:
            // GET RCP with Referer = embedUrl
            val rcpResponse = app.get(
                rcpUrl,
                headers = baseHeaders + mapOf(
                    "Referer" to embedUrl
                )
            )

            // Step 4:
            // Extract /prorcp/{hash}
            val prorcpRegex = Regex(
                """/prorcp/([a-zA-Z0-9=+/]+)"""
            )

            val prorcpHash = prorcpRegex
                .find(rcpResponse.text)
                ?.groupValues
                ?.getOrNull(1)
                ?: return false

            val rcpOrigin =
                android.net.Uri.parse(rcpUrl).let {
                    "${it.scheme}://${it.host}"
                }

            // Step 5:
            // GET same host /prorcp/{hash}
            val prorcpUrl =
                "$rcpOrigin/prorcp/$prorcpHash"

            val prorcpResponse = app.get(
                prorcpUrl,
                headers = baseHeaders + mapOf(
                    "Referer" to rcpUrl
                )
            )

            // Step 6:
            // Extract file:"..."
            val fileRegex = Regex(
                """file:\s*["']([^"']+)["']"""
            )

            val rawFileUrl = fileRegex
                .find(prorcpResponse.text)
                ?.groupValues
                ?.getOrNull(1)
                ?: return false

            // Step 7:
            // Exact resolver logic from @definisi/vidsrc-scraper 2.0.2
            val hlsUrl = rawFileUrl
                .split(" or ")[0]
                .trim()
                .replace(
                    Regex("""\{v[1-5]\}"""),
                    "cloudnestra.com"
                )

            if (hlsUrl.isBlank()) {
                return false
            }

            callback(
                ExtractorLink(
                    source = name,
                    name = "VidSrc",
                    url = hlsUrl,
                    referer = "https://cloudnestra.com/",
                    quality = Qualities.Unknown.value,
                    isM3u8 = true
                )
            )

            // The original resolver also searches the prorcp page
            // for VTT/SRT subtitle URLs.
            val subtitleRegex = Regex(
                """["'](https?://[^"']+\.(?:vtt|srt))["']""",
                RegexOption.IGNORE_CASE
            )

            val subtitleUrls = subtitleRegex
                .findAll(prorcpResponse.text)
                .map { it.groupValues[1] }
                .distinct()
                .toList()

            subtitleUrls.forEach { subtitleUrl ->

                val lower = subtitleUrl.lowercase()

                val language = when {
                    Regex("""(^|[^a-z])ar([^a-z]|$)""")
                        .containsMatchIn(lower) -> "Arabic"

                    lower.contains("arab") -> "Arabic"

                    lower.contains("ara") -> "Arabic"

                    else -> "Arabic"
                }

                subtitleCallback(
                    SubtitleFile(
                        lang = language,
                        url = subtitleUrl
                    )
                )
            }

            return true

        } catch (_: Exception) {
            return false
        }
    }

    data class HayyaEpisodeData(
        val id: Int,
        val season: Int? = null,
        val episode: Int? = null,
        val title: String? = null
    )

    data class TmdbResponse(
        @JsonProperty("results")
        val results: List<TmdbItem>? = null
    )

    data class TmdbItem(
        @JsonProperty("id")
        val id: Int,

        @JsonProperty("title")
        val title: String? = null,

        @JsonProperty("name")
        val name: String? = null,

        @JsonProperty("poster_path")
        val posterPath: String? = null
    )

    data class TmdbMovie(
        @JsonProperty("id")
        val id: Int,

        @JsonProperty("title")
        val title: String? = null,

        @JsonProperty("overview")
        val overview: String? = null,

        @JsonProperty("poster_path")
        val posterPath: String? = null,

        @JsonProperty("backdrop_path")
        val backdropPath: String? = null,

        @JsonProperty("release_date")
        val releaseDate: String? = null,

        @JsonProperty("vote_average")
        val voteAverage: Double? = null
    )

    data class TmdbTv(
        @JsonProperty("id")
        val id: Int,

        @JsonProperty("name")
        val name: String? = null,

        @JsonProperty("overview")
        val overview: String? = null,

        @JsonProperty("poster_path")
        val posterPath: String? = null,

        @JsonProperty("backdrop_path")
        val backdropPath: String? = null,

        @JsonProperty("first_air_date")
        val firstAirDate: String? = null,

        @JsonProperty("vote_average")
        val voteAverage: Double? = null,

        @JsonProperty("seasons")
        val seasons: List<TmdbSeasonInfo>? = null
    )

    data class TmdbSeasonInfo(
        @JsonProperty("season_number")
        val seasonNumber: Int,

        @JsonProperty("name")
        val name: String? = null
    )

    data class TmdbSeason(
        @JsonProperty("episodes")
        val episodes: List<TmdbEpisode>? = null
    )

    data class TmdbEpisode(
        @JsonProperty("episode_number")
        val episodeNumber: Int,

        @JsonProperty("name")
        val name: String? = null,

        @JsonProperty("overview")
        val overview: String? = null
    )
}
