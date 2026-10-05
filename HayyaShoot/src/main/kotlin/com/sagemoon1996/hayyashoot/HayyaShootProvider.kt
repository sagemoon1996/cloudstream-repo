package com.sagemoon1996.hayyashoot

import android.net.Uri
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException

class HayyaShootProvider : MainAPI() {
    override var mainUrl = "https://hayyashoot.com"
    override var name = "HayyaShoot"
    override var lang = "ar"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "movie/popular" to "أفلام",
        "tv/popular" to "مسلسلات"
    )

    private val imageBaseUrl = "https://image.tmdb.org/t/p/w500"
    private val backdropBaseUrl = "https://image.tmdb.org/t/p/original"

    private val browserUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0"

    private val browserHeaders = mapOf(
        "User-Agent" to browserUserAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
        "Accept-Encoding" to "identity"
    )

    private val tokenMutex = Mutex()

    @Volatile
    private var cachedToken: String? = null

    private val tokenPatterns = listOf(
        Regex("""auth_?token\s*[=:]\s*["'`]([^"'`]+)["'`]""", RegexOption.IGNORE_CASE),
        Regex("""Bearer\s+(eyJ[\w-]+\.[\w-]+\.[\w-]+)""")
    )

    private fun extractToken(text: String): String? {
        for (pattern in tokenPatterns) {
            val token = pattern.find(text)?.groupValues?.getOrNull(1)
            if (!token.isNullOrBlank()) return token
        }
        return null
    }

    private suspend fun fetchAuthToken(): String? {
        val page = app.get("$mainUrl/movies/", headers = browserHeaders).text

        extractToken(page)?.let { return it }

        val scriptRegex = Regex(
            """<script[^>]+src=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )

        for (match in scriptRegex.findAll(page)) {
            val scriptUrl = fixUrl(match.groupValues[1])
            val host = Uri.parse(scriptUrl).host ?: continue
            if (!host.endsWith("hayyashoot.com")) continue

            try {
                val js = app.get(scriptUrl, headers = browserHeaders).text
                extractToken(js)?.let { return it }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logError(e)
            }
        }

        return null
    }

    private suspend fun getAuthToken(forceRefresh: Boolean = false): String {
        if (!forceRefresh) cachedToken?.let { return it }

        return tokenMutex.withLock {
            if (!forceRefresh) cachedToken?.let { return@withLock it }

            val token = fetchAuthToken()
                ?: throw ErrorLoadingException("HayyaShoot AUTH_TOKEN not found")

            cachedToken = token
            token
        }
    }

    private suspend fun requestTmdb(path: String, token: String) =
        app.get(
            "https://api.themoviedb.org/3/$path",
            headers = mapOf(
                "User-Agent" to browserUserAgent,
                "Accept" to "application/json, text/plain, */*",
                "Authorization" to "Bearer $token"
            )
        )

    private suspend fun tmdbGet(path: String): String {
        var response = requestTmdb(path, getAuthToken())

        if (response.code == 401 || response.code == 403) {
            response = requestTmdb(path, getAuthToken(forceRefresh = true))
        }

        return response.text
    }

    private fun encodeTitle(title: String): String {
        val slug = title
            .replace(Regex("""[\s-]+"""), "-")
            .trim('-')
        return URLEncoder.encode(slug, "UTF-8")
    }

    private fun movieUrl(id: Int, title: String) =
        "$mainUrl/movies/?movie=$id&title=${encodeTitle(title)}"

    private fun tvUrl(id: Int, title: String) =
        "$mainUrl/movies/?tv=$id&title=${encodeTitle(title)}"

    private suspend fun toSearchResponse(
        item: TmdbItem,
        isMovie: Boolean
    ): SearchResponse? {
        val itemTitle = (if (isMovie) item.title else item.name) ?: return null
        val poster = item.posterPath?.let { imageBaseUrl + it }

        return if (isMovie) {
            newMovieSearchResponse(
                itemTitle,
                movieUrl(item.id, itemTitle),
                TvType.Movie
            ) {
                this.posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(
                itemTitle,
                tvUrl(item.id, itemTitle),
                TvType.TvSeries
            ) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val isMovie = request.data.startsWith("movie")

        val response = parseJson<TmdbResponse>(
            tmdbGet("${request.data}?language=ar-SA&page=$page")
        )

        val items = response.results
            .orEmpty()
            .mapNotNull { toSearchResponse(it, isMovie) }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val results = arrayListOf<SearchResponse>()

        val movies = parseJson<TmdbResponse>(
            tmdbGet("search/movie?query=$encodedQuery&language=ar-SA&page=1")
        )
        movies.results.orEmpty().forEach { item ->
            toSearchResponse(item, true)?.let { results.add(it) }
        }

        val shows = parseJson<TmdbResponse>(
            tmdbGet("search/tv?query=$encodedQuery&language=ar-SA&page=1")
        )
        shows.results.orEmpty().forEach { item ->
            toSearchResponse(item, false)?.let { results.add(it) }
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val uri = Uri.parse(url)
        val movieId = uri.getQueryParameter("movie")?.toIntOrNull()
        val tvId = uri.getQueryParameter("tv")?.toIntOrNull()

        if (movieId != null) {
            val movie = parseJson<TmdbMovie>(
                tmdbGet("movie/$movieId?language=ar-SA")
            )

            val title = movie.title ?: return null

            val data = HayyaMediaData(
                type = "movie",
                id = movieId
            ).toJson()

            return newMovieLoadResponse(title, url, TvType.Movie, data) {
                this.posterUrl = movie.posterPath?.let { imageBaseUrl + it }
                this.backgroundPosterUrl = movie.backdropPath?.let { backdropBaseUrl + it }
                this.plot = movie.overview
                this.year = movie.releaseDate?.take(4)?.toIntOrNull()
                this.score = movie.voteAverage?.let { Score.from10(it) }
            }
        }

        if (tvId != null) {
            val tv = parseJson<TmdbTv>(
                tmdbGet("tv/$tvId?language=ar-SA")
            )

            val title = tv.name ?: return null
            val episodes = arrayListOf<Episode>()

            tv.seasons
                .orEmpty()
                .filter { it.seasonNumber > 0 }
                .forEach { season ->
                    val seasonData = try {
                        parseJson<TmdbSeason>(
                            tmdbGet("tv/$tvId/season/${season.seasonNumber}?language=ar-SA")
                        )
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        logError(e)
                        null
                    } ?: return@forEach

                    seasonData.episodes.orEmpty().forEach { ep ->
                        val epData = HayyaMediaData(
                            type = "tv",
                            id = tvId,
                            season = season.seasonNumber,
                            episode = ep.episodeNumber
                        ).toJson()

                        episodes.add(
                            newEpisode(epData) {
                                this.name = ep.name ?: "الحلقة ${ep.episodeNumber}"
                                this.season = season.seasonNumber
                                this.episode = ep.episodeNumber
                                this.description = ep.overview
                            }
                        )
                    }
                }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = tv.posterPath?.let { imageBaseUrl + it }
                this.backgroundPosterUrl = tv.backdropPath?.let { backdropBaseUrl + it }
                this.plot = tv.overview
                this.year = tv.firstAirDate?.take(4)?.toIntOrNull()
                this.score = tv.voteAverage?.let { Score.from10(it) }
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
        val media = try {
            parseJson<HayyaMediaData>(data)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return false
        }

        val embedUrl =
            if (media.type == "tv" && media.season != null && media.episode != null) {
                "https://vidsrc.me/embed/tv?tmdb=${media.id}&season=${media.season}&episode=${media.episode}&sub=ar"
            } else {
                "https://vidsrc.me/embed/movie?tmdb=${media.id}&sub=ar"
            }

        try {
            val embedHtml = app.get(
                embedUrl,
                headers = browserHeaders + mapOf(
                    "Referer" to mainUrl
                )
            ).text

            val iframeRegex = Regex(
                """<iframe[^>]+src=["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            )

            val iframeUrls = iframeRegex
                .findAll(embedHtml)
                .mapNotNull { it.groupValues.getOrNull(1) }
                .map { fixUrl(it) }
                .distinct()
                .toList()

            for (iframeUrl in iframeUrls) {
                try {
                    loadExtractor(
                        iframeUrl,
                        embedUrl,
                        subtitleCallback,
                        callback
                    )
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    logError(e)
                }
            }

            return iframeUrls.isNotEmpty()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logError(e)
            return false
        }
    }

    data class HayyaMediaData(
        val type: String,
        val id: Int,
        val season: Int? = null,
        val episode: Int? = null
    )

    data class TmdbResponse(
        @JsonProperty("results") val results: List<TmdbItem>? = null
    )

    data class TmdbItem(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null
    )

    data class TmdbMovie(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null
    )

    data class TmdbTv(
        @JsonProperty("id") val id: Int,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("seasons") val seasons: List<TmdbSeasonInfo>? = null
    )

    data class TmdbSeasonInfo(
        @JsonProperty("season_number") val seasonNumber: Int,
        @JsonProperty("name") val name: String? = null
    )

    data class TmdbSeason(
        @JsonProperty("episodes") val episodes: List<TmdbEpisode>? = null
    )

    data class TmdbEpisode(
        @JsonProperty("episode_number") val episodeNumber: Int,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null
    )
}
