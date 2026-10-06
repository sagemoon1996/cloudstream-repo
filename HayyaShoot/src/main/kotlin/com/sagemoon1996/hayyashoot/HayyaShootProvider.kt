package com.sagemoon1996.hayyashoot

import android.net.Uri
import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import io.github.charlietap.chasm.embedding.*
import io.github.charlietap.chasm.embedding.shapes.expect
import io.github.charlietap.chasm.type.Value
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException

class HayyaShootProvider : MainAPI() {

    override var mainUrl = "https://hayyashoot.com"
    override var name = "HayyaShoot2"
    override var lang = "ar"

    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "movie/popular" to "أفلام",
        "tv/popular" to "مسلسلات"
    )

    private val posterBase =
        "https://image.tmdb.org/t/p/w500"

    private val backdropBase =
        "https://image.tmdb.org/t/p/original"

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0"

    private val browserHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
        "Accept-Encoding" to "identity"
    )

    // ---------------------------------------------------------------
    // TMDB AUTH
    // ---------------------------------------------------------------

    private val tokenMutex = Mutex()

    @Volatile
    private var cachedToken: String? = null

    private val tokenPatterns = listOf(
        Regex(
            """auth_?token\s*[=:]\s*["'`]([^"'`]+)["'`]""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """Bearer\s+(eyJ[\w-]+\.[\w-]+\.[\w-]+)"""
        )
    )

    private fun extractToken(
        text: String
    ): String? {
        for (pattern in tokenPatterns) {
            pattern.find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return null
    }

    private suspend fun fetchAuthToken(): String? {
        val html =
            app.get(
                "$mainUrl/movies/",
                headers = browserHeaders
            ).text

        extractToken(html)?.let {
            return it
        }

        val scriptRegex = Regex(
            """<script[^>]+src=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )

        for (match in scriptRegex.findAll(html)) {

            val scriptUrl =
                fixUrl(match.groupValues[1])

            val host =
                Uri.parse(scriptUrl).host
                    ?: continue

            if (!host.endsWith("hayyashoot.com")) {
                continue
            }

            try {
                extractToken(
                    app.get(
                        scriptUrl,
                        headers = browserHeaders
                    ).text
                )?.let {
                    return it
                }
            } catch (e: Exception) {
                if (e is CancellationException) {
                    throw e
                }
                logError(e)
            }
        }

        return null
    }

    private suspend fun getAuthToken(
        forceRefresh: Boolean = false
    ): String {

        if (!forceRefresh) {
            cachedToken?.let {
                return it
            }
        }

        return tokenMutex.withLock {

            if (!forceRefresh) {
                cachedToken?.let {
                    return@withLock it
                }
            }

            val token =
                fetchAuthToken()
                    ?: throw ErrorLoadingException(
                        "HayyaShoot AUTH_TOKEN not found"
                    )

            cachedToken = token
            token
        }
    }

    private suspend fun requestTmdb(
        path: String,
        token: String
    ) =
        app.get(
            "https://api.themoviedb.org/3/$path",
            headers = mapOf(
                "User-Agent" to userAgent,
                "Accept" to "application/json, text/plain, */*",
                "Authorization" to "Bearer $token"
            )
        )

    private suspend fun tmdbGet(
        path: String
    ): String {

        var response =
            requestTmdb(
                path,
                getAuthToken()
            )

        if (
            response.code == 401 ||
            response.code == 403
        ) {
            response =
                requestTmdb(
                    path,
                    getAuthToken(
                        forceRefresh = true
                    )
                )
        }

        return response.text
    }

    // ---------------------------------------------------------------
    // HELPERS
    // ---------------------------------------------------------------

    private fun slug(
        title: String
    ): String =
        URLEncoder.encode(
            title
                .replace(
                    Regex("""[\s-]+"""),
                    "-"
                )
                .trim('-'),
            "UTF-8"
        )

    private fun movieUrl(
        id: Int,
        title: String
    ) =
        "$mainUrl/movies/?movie=$id&title=${slug(title)}"

    private fun tvUrl(
        id: Int,
        title: String
    ) =
        "$mainUrl/movies/?tv=$id&title=${slug(title)}"

    private fun yearOf(
        date: String?
    ): Int? =
        date
            ?.take(4)
            ?.toIntOrNull()

    private fun toSearchResponse(
        item: TmdbItem,
        isMovie: Boolean
    ): SearchResponse? {

        val title =
            if (isMovie) {
                item.title
            } else {
                item.name
            }?.takeIf {
                it.isNotBlank()
            } ?: return null

        val poster =
            item.posterPath?.let {
                posterBase + it
            }

        return if (isMovie) {

            newMovieSearchResponse(
                title,
                movieUrl(
                    item.id,
                    title
                ),
                TvType.Movie
            ) {
                this.posterUrl = poster
                this.year =
                    yearOf(item.releaseDate)
            }

        } else {

            newTvSeriesSearchResponse(
                title,
                tvUrl(
                    item.id,
                    title
                ),
                TvType.TvSeries
            ) {
                this.posterUrl = poster
                this.year =
                    yearOf(item.firstAirDate)
            }
        }
    }

    private suspend fun fetchList(
        path: String
    ): TmdbResponse? =
        try {
            parseJson<TmdbResponse>(
                tmdbGet(path)
            )
        } catch (e: Exception) {
            if (e is CancellationException) {
                throw e
            }
            logError(e)
            null
        }

    // ---------------------------------------------------------------
    // MAIN PAGE
    // ---------------------------------------------------------------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val isMovie =
            request.data.startsWith("movie")

        val response =
            fetchList(
                "${request.data}?language=ar-SA&page=$page"
            )

        val items =
            response?.results
                .orEmpty()
                .mapNotNull {
                    toSearchResponse(
                        it,
                        isMovie
                    )
                }
                .distinctBy {
                    it.url
                }

        return newHomePageResponse(
            request.name,
            items,
            hasNext =
                page < (response?.totalPages ?: 1)
        )
    }

    // ---------------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------------

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val trimmed =
            query.trim()

        if (trimmed.isBlank()) {
            return emptyList()
        }

        val q =
            URLEncoder.encode(
                trimmed,
                "UTF-8"
            )

        return coroutineScope {

            val movies = async {
                fetchList(
                    "search/movie?query=$q&language=ar-SA&page=1"
                )
            }

            val shows = async {
                fetchList(
                    "search/tv?query=$q&language=ar-SA&page=1"
                )
            }

            val movieResults =
                movies.await()
                    ?.results
                    .orEmpty()
                    .mapNotNull {
                        toSearchResponse(
                            it,
                            true
                        )
                    }

            val showResults =
                shows.await()
                    ?.results
                    .orEmpty()
                    .mapNotNull {
                        toSearchResponse(
                            it,
                            false
                        )
                    }

            (movieResults + showResults)
                .distinctBy {
                    it.url
                }
        }
    }

    // ---------------------------------------------------------------
    // LOAD
    // ---------------------------------------------------------------

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val uri =
            Uri.parse(url)

        val movieId =
            uri.getQueryParameter("movie")
                ?.toIntOrNull()

        val tvId =
            uri.getQueryParameter("tv")
                ?.toIntOrNull()

        if (movieId != null) {
            return loadMovie(
                url,
                movieId
            )
        }

        if (tvId != null) {
            return loadTv(
                url,
                tvId
            )
        }

        return null
    }

    private suspend fun loadMovie(
        url: String,
        id: Int
    ): LoadResponse? {

        val movie =
            parseJson<TmdbMovie>(
                tmdbGet(
                    "movie/$id?language=ar-SA"
                )
            )

        val title =
            movie.title
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: return null

        val data =
            HayyaMediaData(
                type = "movie",
                id = id
            ).toJson()

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            data
        ) {

            this.posterUrl =
                movie.posterPath?.let {
                    posterBase + it
                }

            this.backgroundPosterUrl =
                movie.backdropPath?.let {
                    backdropBase + it
                }

            this.plot =
                movie.overview

            this.year =
                yearOf(
                    movie.releaseDate
                )
        }
    }

    private suspend fun loadTv(
        url: String,
        id: Int
    ): LoadResponse? {

        val tv =
            parseJson<TmdbTv>(
                tmdbGet(
                    "tv/$id?language=ar-SA"
                )
            )

        val title =
            tv.name
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: return null

        val seasons =
            tv.seasons
                .orEmpty()
                .filter {
                    it.seasonNumber > 0 &&
                    (it.episodeCount ?: 1) > 0
                }

        val seasonData =
            coroutineScope {

                seasons.map { season ->

                    async {

                        try {

                            season.seasonNumber to
                                parseJson<TmdbSeason>(
                                    tmdbGet(
                                        "tv/$id/season/${season.seasonNumber}?language=ar-SA"
                                    )
                                )

                        } catch (e: Exception) {

                            if (
                                e is CancellationException
                            ) {
                                throw e
                            }

                            logError(e)
                            null
                        }
                    }
                }.awaitAll()
            }
                .filterNotNull()
                .sortedBy {
                    it.first
                }

        val episodes =
            seasonData.flatMap {
                (seasonNumber, season) ->

                season.episodes
                    .orEmpty()
                    .map { episode ->

                        val data =
                            HayyaMediaData(
                                type = "tv",
                                id = id,
                                season = seasonNumber,
                                episode =
                                    episode.episodeNumber
                            ).toJson()

                        newEpisode(data) {

                            this.name =
                                episode.name
                                    ?.takeIf {
                                        it.isNotBlank()
                                    }
                                    ?: "الحلقة ${episode.episodeNumber}"

                            this.season =
                                seasonNumber

                            this.episode =
                                episode.episodeNumber

                            this.description =
                                episode.overview

                            this.posterUrl =
                                episode.stillPath?.let {
                                    posterBase + it
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

            this.posterUrl =
                tv.posterPath?.let {
                    posterBase + it
                }

            this.backgroundPosterUrl =
                tv.backdropPath?.let {
                    backdropBase + it
                }

            this.plot =
                tv.overview

            this.year =
                yearOf(
                    tv.firstAirDate
                )
        }
    }

    // ---------------------------------------------------------------
    // VIDSRC CURRENT API + WASM
    // ---------------------------------------------------------------

    private suspend fun resolveVidSrc(
        media: HayyaMediaData
    ): String {

        val apiUrl =
            buildString {

                append(
                    "https://data.vidsrc.sh/api.php?type="
                )

                append(
                    if (media.type == "tv") {
                        "tv"
                    } else {
                        "movie"
                    }
                )

                append(
                    "&tmdb="
                )
                append(media.id)

                if (media.type == "tv") {

                    append(
                        "&season="
                    )
                    append(media.season)

                    append(
                        "&episode="
                    )
                    append(media.episode)
                }

                append(
                    "&stream_urls"
                )
            }

        val response =
            app.get(
                apiUrl,
                headers =
                    browserHeaders +
                        mapOf(
                            "Referer" to
                                "https://vidsrc.sh/"
                        )
            )

        if (response.code !in 200..299) {

            throw ErrorLoadingException(
                "VidSrc API HTTP ${response.code}"
            )
        }

        val root =
            JSONObject(
                response.text
            )

        val data =
            root.optJSONObject("data")
                ?: throw ErrorLoadingException(
                    "VidSrc: no data"
                )

        val streamValue =
            data.opt("stream_urls")

        val streams =
            when (streamValue) {

                is JSONArray -> {

                    (0 until streamValue.length())
                        .mapNotNull {
                            streamValue
                                .optString(it)
                                .takeIf {
                                    it.isNotBlank()
                                }
                        }
                }

                is String -> {

                    val encrypted =
                        try {
                            Base64.decode(
                                streamValue,
                                Base64.DEFAULT
                            )
                        } catch (e: Exception) {
                            throw ErrorLoadingException(
                                "VidSrc: invalid stream_urls"
                            )
                        }

                    val vs =
                        root.optJSONObject("vs")
                            ?: throw ErrorLoadingException(
                                "VidSrc: missing vs"
                            )

                    val wasmBytes =
                        if (vs.has("wasm_url")) {

                            val wasmUrl =
                                vs.optString(
                                    "wasm_url"
                                )

                            if (wasmUrl.isBlank()) {
                                throw ErrorLoadingException(
                                    "VidSrc: empty wasm_url"
                                )
                            }

                            app.get(
                                wasmUrl,
                                headers = mapOf(
                                    "User-Agent" to userAgent
                                )
                            ).body?.bytes()
                                ?: throw ErrorLoadingException(
                                    "VidSrc: empty WASM"
                                )

                        } else {

                            val wasm =
                                vs.optString("wasm")

                            if (wasm.isBlank()) {
                                throw ErrorLoadingException(
                                    "VidSrc: missing wasm"
                                )
                            }

                            Base64.decode(
                                wasm,
                                Base64.DEFAULT
                            )
                        }

                    val wasmModule =
                        module(wasmBytes).expect(
                            "VidSrc: failed to decode WASM"
                        )

                    val wasmStore =
                        store()

                    val wasmInstance =
                        instance(
                            wasmStore,
                            wasmModule,
                            emptyList()
                        ).expect(
                            "VidSrc: failed to instantiate WASM"
                        )

                    val memory =
                        wasmInstance.exports
                            .firstOrNull {
                                it.name == "memory"
                            }
                            ?.value
                            ?: throw ErrorLoadingException(
                                "VidSrc: WASM memory export not found"
                            )

                    val allocResult =
                        invoke(
                            wasmStore,
                            wasmInstance,
                            "alloc",
                            listOf(
                                Value.Number.I32(
                                    encrypted.size
                                )
                            )
                        ).expect(
                            "VidSrc: alloc failed"
                        )

                    val ptr =
                        (
                            allocResult.firstOrNull()
                                as? Value.Number.I32
                            )?.value
                            ?: throw ErrorLoadingException(
                                "VidSrc: alloc returned no pointer"
                            )

                    writeBytes(
                        wasmStore,
                        memory,
                        encrypted,
                        0,
                        encrypted.size,
                        ptr
                    )

                    val decryptResult =
                        invoke(
                            wasmStore,
                            wasmInstance,
                            "decrypt",
                            listOf(
                                Value.Number.I32(ptr),
                                Value.Number.I32(
                                    encrypted.size
                                )
                            )
                        ).expect(
                            "VidSrc: decrypt failed"
                        )

                    val outLen =
                        (
                            decryptResult.firstOrNull()
                                as? Value.Number.I32
                            )?.value
                            ?: throw ErrorLoadingException(
                                "VidSrc: decrypt returned no length"
                            )

                    if (outLen <= 0) {
                        throw ErrorLoadingException(
                            "VidSrc: invalid decrypt length $outLen"
                        )
                    }

                    val decoded =
                        ByteArray(outLen)

                    readBytes(
                        wasmStore,
                        memory,
                        decoded,
                        ptr + 12,
                        outLen,
                        0
                    )

                    String(
                        decoded,
                        Charsets.UTF_8
                    )
                        .split("\n")
                        .map {
                            it.trim()
                        }
                        .filter {
                            it.startsWith("http")
                        }
                }

                else -> emptyList()
            }

        if (streams.isEmpty()) {

            throw ErrorLoadingException(
                "VidSrc: no streams"
            )
        }

        val raw =
            streams.first()

        val uri =
            Uri.parse(raw)

        val origin =
            "${uri.scheme}://${uri.authority}"

        val token =
            app.get(
                "$origin/generate.php",
                headers =
                    mapOf(
                        "User-Agent" to
                            userAgent
                    )
            )
                .text
                .trim()

        if (token.isBlank()) {

            throw ErrorLoadingException(
                "VidSrc: empty generate token"
            )
        }

        return if (
            raw.contains("__TOKEN__")
        ) {

            raw.replace(
                "__TOKEN__",
                token
            )

        } else {

            "$raw?token=$token"
        }
    }

    // ---------------------------------------------------------------
    // LINKS
    // ---------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val media =
            try {

                parseJson<HayyaMediaData>(
                    data
                )

            } catch (e: Exception) {

                if (
                    e is CancellationException
                ) {
                    throw e
                }

                logError(e)
                return false
            }

        return try {

            val streamUrl =
                resolveVidSrc(
                    media
                )

            callback(
                newExtractorLink(
                    source = name,
                    name = "VidSrc",
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {

                    this.referer =
                        "https://vidsrc.sh/"

                    this.quality =
                        Qualities.Unknown.value

                    this.headers =
                        mapOf(
                            "User-Agent" to
                                userAgent
                        )
                }
            )

            true

        } catch (e: Exception) {

            if (
                e is CancellationException
            ) {
                throw e
            }

            logError(e)
            false
        }
    }

    // ---------------------------------------------------------------
    // DATA
    // ---------------------------------------------------------------

    data class HayyaMediaData(
        val type: String,
        val id: Int,
        val season: Int? = null,
        val episode: Int? = null
    )

    data class TmdbResponse(
        @JsonProperty("results")
        val results: List<TmdbItem>? = null,

        @JsonProperty("total_pages")
        val totalPages: Int? = null
    )

    data class TmdbItem(
        @JsonProperty("id")
        val id: Int,

        @JsonProperty("title")
        val title: String? = null,

        @JsonProperty("name")
        val name: String? = null,

        @JsonProperty("poster_path")
        val posterPath: String? = null,

        @JsonProperty("release_date")
        val releaseDate: String? = null,

        @JsonProperty("first_air_date")
        val firstAirDate: String? = null
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
        val releaseDate: String? = null
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

        @JsonProperty("seasons")
        val seasons: List<TmdbSeasonInfo>? = null
    )

    data class TmdbSeasonInfo(
        @JsonProperty("season_number")
        val seasonNumber: Int,

        @JsonProperty("episode_count")
        val episodeCount: Int? = null
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
        val overview: String? = null,

        @JsonProperty("still_path")
        val stillPath: String? = null
    )
}
