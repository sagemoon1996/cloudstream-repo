package com.sagemoon1996.hayyashoot

import android.net.Uri
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
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

    private val imageBaseUrl =
        "https://image.tmdb.org/t/p/w500"

    private val backdropBaseUrl =
        "https://image.tmdb.org/t/p/original"

    private val browserUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0"

    private val browserHeaders = mapOf(
        "User-Agent" to browserUserAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
        "Accept-Encoding" to "identity"
    )

    private suspend fun getAuthToken(): String? {
        val response = app.get(
            "$mainUrl/movies/",
            headers = browserHeaders
        )

        val patterns = listOf(
            Regex(
                """AUTH_TOKEN\s*=\s*["']([^"']+)["']"""
            ),
            Regex(
                """AUTH_TOKEN\s*:\s*["']([^"']+)["']"""
            ),
            Regex(
                """authToken\s*=\s*["']([^"']+)["']"""
            )
        )

        for (pattern in patterns) {
            pattern.find(response.text)?.groupValues?.getOrNull(1)?.let {
                return it
            }
        }

        return null
    }

    private suspend fun tmdbGet(
        path: String,
        token: String
    ): String {
        return app.get(
            "https://api.themoviedb.org/3/$path",
            headers = mapOf(
                "User-Agent" to browserUserAgent,
                "Accept" to "application/json, text/plain, */*",
                "Authorization" to "Bearer $token"
            )
        ).text
    }

    private fun encodeTitle(title: String): String {
        return title
            .replace(Regex("""[\s-]+"""), "-")
            .trim('-')
    }

    private fun movieUrl(
        id: Int,
        title: String
    ): String {
        return "$mainUrl/movies/?movie=$id&title=${encodeTitle(title)}"
    }

    private fun tvUrl(
        id: Int,
        title: String
    ): String {
        return "$mainUrl/movies/?tv=$id&title=${encodeTitle(title)}"
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val token = getAuthToken()
            ?: throw ErrorLoadingException(
                "HayyaShoot AUTH_TOKEN not found"
            )

        val sections = arrayListOf<HomePageList>()

        /*
         * HayyaShoot original site:
         *
         * movie mode:
         * /3/movie/popular?language=ar-SA&page=N
         *
         * tv mode:
         * /3/tv/popular?language=ar-SA&page=N
         *
         * The CloudStream home page exposes both modes as
         * separate sections.
         */

        val movieResponse = parseJson<TmdbResponse>(
            tmdbGet(
                "movie/popular?language=ar-SA&page=$page",
                token
            )
        )

        val movieItems = movieResponse.results
            .orEmpty()
            .mapNotNull { item ->

                val title = item.title
                    ?: return@mapNotNull null

                val poster = item.posterPath
                    ?: return@mapNotNull null

                newMovieSearchResponse(
                    title,
                    movieUrl(item.id, title),
                    TvType.Movie
                ) {
                    this.posterUrl =
                        imageBaseUrl + poster
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

        val tvResponse = parseJson<TmdbResponse>(
            tmdbGet(
                "tv/popular?language=ar-SA&page=$page",
                token
            )
        )

        val tvItems = tvResponse.results
            .orEmpty()
            .mapNotNull { item ->

                val title = item.name
                    ?: return@mapNotNull null

                val poster = item.posterPath
                    ?: return@mapNotNull null

                newTvSeriesSearchResponse(
                    title,
                    tvUrl(item.id, title),
                    TvType.TvSeries
                ) {
                    this.posterUrl =
                        imageBaseUrl + poster
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
            ?: throw ErrorLoadingException(
                "HayyaShoot AUTH_TOKEN not found"
            )

        val encodedQuery =
            java.net.URLEncoder.encode(
                query,
                "UTF-8"
            )

        val results = arrayListOf<SearchResponse>()

        /*
         * Original HayyaShoot search:
         *
         * /3/search/movie?query=...&language=ar-SA&page=1
         * /3/search/tv?query=...&language=ar-SA&page=1
         */

        val movieResponse = parseJson<TmdbResponse>(
            tmdbGet(
                "search/movie?query=$encodedQuery&language=ar-SA&page=1",
                token
            )
        )

        movieResponse.results
            .orEmpty()
            .forEach { item ->

                val title = item.title
                    ?: return@forEach

                val poster = item.posterPath
                    ?: return@forEach

                results.add(
                    newMovieSearchResponse(
                        title,
                        movieUrl(item.id, title),
                        TvType.Movie
                    ) {
                        this.posterUrl =
                            imageBaseUrl + poster
                    }
                )
            }

        val tvResponse = parseJson<TmdbResponse>(
            tmdbGet(
                "search/tv?query=$encodedQuery&language=ar-SA&page=1",
                token
            )
        )

        tvResponse.results
            .orEmpty()
            .forEach { item ->

                val title = item.name
                    ?: return@forEach

                val poster = item.posterPath
                    ?: return@forEach

                results.add(
                    newTvSeriesSearchResponse(
                        title,
                        tvUrl(item.id, title),
                        TvType.TvSeries
                    ) {
                        this.posterUrl =
                            imageBaseUrl + poster
                    }
                )
            }

        return results
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val token = getAuthToken()
            ?: throw ErrorLoadingException(
                "HayyaShoot AUTH_TOKEN not found"
            )

        val uri = Uri.parse(url)

        val movieId =
            uri.getQueryParameter("movie")
                ?.toIntOrNull()

        val tvId =
            uri.getQueryParameter("tv")
                ?.toIntOrNull()

        /*
         * =========================
         * MOVIE
         * =========================
         */

        if (movieId != null) {

            val movie = parseJson<TmdbMovie>(
                tmdbGet(
                    "movie/$movieId?language=ar-SA",
                    token
                )
            )

            val title =
                movie.title
                    ?: return null

            val data = HayyaMediaData(
                type = "movie",
                id = movieId
            ).toJson()

            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                data
            ) {

                this.posterUrl =
                    movie.posterPath?.let {
                        imageBaseUrl + it
                    }

                this.backgroundPosterUrl =
                    movie.backdropPath?.let {
                        backdropBaseUrl + it
                    }

                this.plot =
                    movie.overview

                this.year =
                    movie.releaseDate
                        ?.split("-")
                        ?.firstOrNull()
                        ?.toIntOrNull()

                this.rating =
                    movie.voteAverage
                        ?.times(10)
                        ?.toInt()
            }
        }

        /*
         * =========================
         * TV SERIES
         * =========================
         */

        if (tvId != null) {

            val tv = parseJson<TmdbTv>(
                tmdbGet(
                    "tv/$tvId?language=ar-SA",
                    token
                )
            )

            val title =
                tv.name
                    ?: return null

            val episodes =
                arrayListOf<Episode>()

            /*
             * Original HayyaShoot:
             *
             * currentItem.seasons
             * filter season_number > 0
             * then /tv/{id}/season/{season}
             */

            tv.seasons
                .orEmpty()
                .filter {
                    it.seasonNumber > 0
                }
                .forEach { season ->

                    val seasonData =
                        parseJson<TmdbSeason>(
                            tmdbGet(
                                "tv/$tvId/season/${season.seasonNumber}?language=ar-SA",
                                token
                            )
                        )

                    seasonData.episodes
                        .orEmpty()
                        .forEach { episode ->

                            val episodeData =
                                HayyaMediaData(
                                    type = "tv",
                                    id = tvId,
                                    season = season.seasonNumber,
                                    episode = episode.episodeNumber
                                ).toJson()

                            episodes.add(
                                newEpisode(
                                    episodeData
                                ) {

                                    this.name =
                                        episode.name
                                            ?: "الحلقة ${episode.episodeNumber}"

                                    this.season =
                                        season.seasonNumber

                                    this.episode =
                                        episode.episodeNumber

                                    this.description =
                                        episode.overview
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

                this.posterUrl =
                    tv.posterPath?.let {
                        imageBaseUrl + it
                    }

                this.backgroundPosterUrl =
                    tv.backdropPath?.let {
                        backdropBaseUrl + it
                    }

                this.plot =
                    tv.overview

                this.year =
                    tv.firstAirDate
                        ?.split("-")
                        ?.firstOrNull()
                        ?.toIntOrNull()

                this.rating =
                    tv.voteAverage
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

        val media =
            try {
                parseJson<HayyaMediaData>(data)
            } catch (_: Exception) {
                return false
            }

        /*
         * @definisi/vidsrc-scraper 2.0.2
         *
         * Movie:
         * https://vidsrc-embed.ru/embed/movie/{tmdbId}
         *
         * TV:
         * https://vidsrc-embed.ru/embed/tv/{tmdbId}/{season}/{episode}
         */

        val embedUrl =
            if (
                media.type == "tv" &&
                media.season != null &&
                media.episode != null
            ) {

                "https://vidsrc-embed.ru/embed/tv/" +
                    "${media.id}/${media.season}/${media.episode}"

            } else {

                "https://vidsrc-embed.ru/embed/movie/${media.id}"
            }

        try {

            /*
             * STEP 1
             *
             * GET embed page
             */

            val embedResponse =
                app.get(
                    embedUrl,
                    headers = browserHeaders
                )

            /*
             * STEP 2
             *
             * Exact regex from scraper.js 2.0.2:
             *
             * src=["']((?:https?:)?\/\/[^"']*
             * cloudnestra\.com\/rcp\/[^"']+)["']
             */

            val rcpRegex = Regex(
                """src=["']((?:https?:)?//[^"']*cloudnestra\.com/rcp/[^"']+)["']""",
                RegexOption.IGNORE_CASE
            )

            var rcpUrl =
                rcpRegex
                    .find(embedResponse.text)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?: return false

            if (rcpUrl.startsWith("//")) {
                rcpUrl = "https:$rcpUrl"
            }

            /*
             * STEP 3
             *
             * GET RCP with:
             * Referer = embedUrl
             */

            val rcpResponse =
                app.get(
                    rcpUrl,
                    headers = browserHeaders + mapOf(
                        "Referer" to embedUrl
                    )
                )

            /*
             * STEP 4
             *
             * Exact scraper.js logic:
             *
             * /prorcp/([a-zA-Z0-9=+/]+)
             */

            val prorcpRegex =
                Regex(
                    """/prorcp/([a-zA-Z0-9=+/]+)"""
                )

            val prorcpHash =
                prorcpRegex
                    .find(rcpResponse.text)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?: return false

            /*
             * Same origin as RCP.
             */

            val rcpUri =
                Uri.parse(rcpUrl)

            val rcpOrigin =
                "${rcpUri.scheme}://${rcpUri.host}"

            /*
             * STEP 5
             *
             * GET:
             * {same-origin}/prorcp/{hash}
             *
             * Referer = rcpUrl
             */

            val prorcpUrl =
                "$rcpOrigin/prorcp/$prorcpHash"

            val prorcpResponse =
                app.get(
                    prorcpUrl,
                    headers = browserHeaders + mapOf(
                        "Referer" to rcpUrl
                    )
                )

            /*
             * STEP 6
             *
             * Exact scraper.js:
             *
             * file:\s*["']([^"']+)["']
             */

            val fileRegex =
                Regex(
                    """file:\s*["']([^"']+)["']"""
                )

            val rawFileUrl =
                fileRegex
                    .find(prorcpResponse.text)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?: return false

            /*
             * STEP 7
             *
             * Exact resolver:
             *
             * url.split(' or ')[0]
             * .trim()
             * .replace({v1}-{v5}, cloudnestra.com)
             */

            val hlsUrl =
                rawFileUrl
                    .split(" or ")[0]
                    .trim()
                    .replace(
                        Regex("""\{v[1-5]\}"""),
                        "cloudnestra.com"
                    )

            if (hlsUrl.isBlank()) {
                return false
            }

            /*
             * HLS link
             */

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

            /*
             * SUBTITLES
             *
             * Same regex used by scraper.js 2.0.2:
             *
             * https://... .vtt
             * https://... .srt
             */

            val subtitleRegex =
                Regex(
                    """["'](https?://[^"']+\.(?:vtt|srt))["']""",
                    RegexOption.IGNORE_CASE
                )

            subtitleRegex
                .findAll(prorcpResponse.text)
                .map {
                    it.groupValues[1]
                }
                .distinct()
                .forEach { subtitleUrl ->

                    subtitleCallback(
                        SubtitleFile(
                            lang = "Arabic",
                            url = subtitleUrl
                        )
                    )
                }

            return true

        } catch (_: Exception) {
            return false
        }
    }

    /*
     * =========================
     * DATA CLASSES
     * =========================
     */

    data class HayyaMediaData(
        val type: String,
        val id: Int,
        val season: Int? = null,
        val episode: Int? = null
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
