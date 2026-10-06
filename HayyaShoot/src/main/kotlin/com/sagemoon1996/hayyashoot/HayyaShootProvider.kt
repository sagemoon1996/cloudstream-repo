package com.sagemoon1996.hayyashoot

import android.net.Uri
import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import io.github.charlietap.chasm.embedding.invoke
import io.github.charlietap.chasm.embedding.instance
import io.github.charlietap.chasm.embedding.module
import io.github.charlietap.chasm.embedding.store
import io.github.charlietap.chasm.type.NumberValue
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
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "movie/popular" to "أفلام",
        "tv/popular" to "مسلسلات"
    )

    private val posterBase = "https://image.tmdb.org/t/p/w500"
    private val backdropBase = "https://image.tmdb.org/t/p/original"

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0"

    private val browserHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
        "Accept-Encoding" to "identity"
    )

    // ---------------------------------------------------------
    // TMDB
    // ---------------------------------------------------------

    private val tokenMutex = Mutex()

    @Volatile
    private var cachedToken: String? = null

    private val tokenPatterns = listOf(
        Regex("""auth_?token\s*[=:]\s*["'`]([^"'`]+)["'`]""", RegexOption.IGNORE_CASE),
        Regex("""Bearer\s+(eyJ[\w-]+\.[\w-]+\.[\w-]+)""")
    )

    private fun extractToken(text: String): String? =
        tokenPatterns.firstNotNullOfOrNull {
            it.find(text)?.groupValues?.getOrNull(1)
                ?.takeIf(String::isNotBlank)
        }

    private suspend fun fetchAuthToken(): String? {
        val page = app.get("$mainUrl/movies/", headers = browserHeaders).text

        extractToken(page)?.let { return it }

        val scripts = Regex(
            """<script[^>]+src=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )

        for (match in scripts.findAll(page)) {
            val url = fixUrl(match.groupValues[1])
            if (!Uri.parse(url).host.orEmpty().endsWith("hayyashoot.com")) continue

            try {
                extractToken(app.get(url, headers = browserHeaders).text)
                    ?.let { return it }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logError(e)
            }
        }

        return null
    }

    private suspend fun getAuthToken(force: Boolean = false): String {
        if (!force) cachedToken?.let { return it }

        return tokenMutex.withLock {
            if (!force) cachedToken?.let { return@withLock it }

            val token = fetchAuthToken()
                ?: throw ErrorLoadingException("HayyaShoot AUTH_TOKEN not found")

            cachedToken = token
            token
        }
    }

    private suspend fun tmdbGet(path: String): String {
        suspend fun request(token: String) =
            app.get(
                "https://api.themoviedb.org/3/$path",
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "application/json, text/plain, */*",
                    "Authorization" to "Bearer $token"
                )
            )

        var response = request(getAuthToken())

        if (response.code == 401 || response.code == 403) {
            response = request(getAuthToken(true))
        }

        return response.text
    }

    // ---------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------

    private fun slug(title: String) =
        URLEncoder.encode(
            title.replace(Regex("""[\s-]+"""), "-").trim('-'),
            "UTF-8"
        )

    private fun movieUrl(id: Int, title: String) =
        "$mainUrl/movies/?movie=$id&title=${slug(title)}"

    private fun tvUrl(id: Int, title: String) =
        "$mainUrl/movies/?tv=$id&title=${slug(title)}"

    private fun yearOf(date: String?) =
        date?.take(4)?.toIntOrNull()

    private fun toSearchResponse(
        item: TmdbItem,
        movie: Boolean
    ): SearchResponse? {
        val title = (if (movie) item.title else item.name)
            ?.takeIf(String::isNotBlank)
            ?: return null

        val poster = item.posterPath?.let { posterBase + it }

        return if (movie) {
            newMovieSearchResponse(
                title,
                movieUrl(item.id, title),
                TvType.Movie
            ) {
                posterUrl = poster
                year = yearOf(item.releaseDate)
            }
        } else {
            newTvSeriesSearchResponse(
                title,
                tvUrl(item.id, title),
                TvType.TvSeries
            ) {
                posterUrl = poster
                year = yearOf(item.firstAirDate)
            }
        }
    }

    private suspend fun fetchList(path: String): TmdbResponse? =
        try {
            parseJson<TmdbResponse>(tmdbGet(path))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logError(e)
            null
        }

    // ---------------------------------------------------------
    // Main page
    // ---------------------------------------------------------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val movie = request.data.startsWith("movie")
        val response = fetchList(
            "${request.data}?language=ar-SA&page=$page"
        )

        val items = response?.results.orEmpty()
            .mapNotNull { toSearchResponse(it, movie) }
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = page < (response?.totalPages ?: 1)
        )
    }

    // ---------------------------------------------------------
    // Search
    // ---------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(q, "UTF-8")

        return coroutineScope {
            val movies = async {
                fetchList(
                    "search/movie?query=$encoded&language=ar-SA&page=1"
                )
            }

            val shows = async {
                fetchList(
                    "search/tv?query=$encoded&language=ar-SA&page=1"
                )
            }

            val movieResults = movies.await()
                ?.results.orEmpty()
                .mapNotNull { toSearchResponse(it, true) }

            val tvResults = shows.await()
                ?.results.orEmpty()
                .mapNotNull { toSearchResponse(it, false) }

            (movieResults + tvResults).distinctBy { it.url }
        }
    }

    // ---------------------------------------------------------
    // Load
    // ---------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        val uri = Uri.parse(url)
        val movieId = uri.getQueryParameter("movie")?.toIntOrNull()
        val tvId = uri.getQueryParameter("tv")?.toIntOrNull()

        if (movieId != null) return loadMovie(url, movieId)
        if (tvId != null) return loadTv(url, tvId)

        return null
    }

    private suspend fun loadMovie(
        url: String,
        id: Int
    ): LoadResponse? {
        val movie = parseJson<TmdbMovie>(
            tmdbGet("movie/$id?language=ar-SA")
        )

        val title = movie.title
            ?.takeIf(String::isNotBlank)
            ?: return null

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            HayyaMediaData("movie", id).toJson()
        ) {
            posterUrl = movie.posterPath?.let { posterBase + it }
            backgroundPosterUrl =
                movie.backdropPath?.let { backdropBase + it }
            plot = movie.overview
            year = yearOf(movie.releaseDate)
        }
    }

    private suspend fun loadTv(
        url: String,
        id: Int
    ): LoadResponse? {
        val tv = parseJson<TmdbTv>(
            tmdbGet("tv/$id?language=ar-SA")
        )

        val title = tv.name
            ?.takeIf(String::isNotBlank)
            ?: return null

        val seasons = tv.seasons.orEmpty()
            .filter {
                it.seasonNumber > 0 &&
                    (it.episodeCount ?: 1) > 0
            }

        val seasonData = coroutineScope {
            seasons.map { season ->
                async {
                    try {
                        season.seasonNumber to parseJson<TmdbSeason>(
                            tmdbGet(
                                "tv/$id/season/${season.seasonNumber}?language=ar-SA"
                            )
                        )
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        logError(e)
                        null
                    }
                }
            }.awaitAll()
        }.filterNotNull().sortedBy { it.first }

        val episodes = seasonData.flatMap { (season, data) ->
            data.episodes.orEmpty().map { ep ->
                newEpisode(
                    HayyaMediaData(
                        type = "tv",
                        id = id,
                        season = season,
                        episode = ep.episodeNumber
                    ).toJson()
                ) {
                    name = ep.name
                        ?.takeIf(String::isNotBlank)
                        ?: "الحلقة ${ep.episodeNumber}"

                    this.season = season
                    episode = ep.episodeNumber
                    description = ep.overview
                    posterUrl =
                        ep.stillPath?.let { posterBase + it }
                }
            }
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            posterUrl = tv.posterPath?.let { posterBase + it }
            backgroundPosterUrl =
                tv.backdropPath?.let { backdropBase + it }
            plot = tv.overview
            year = yearOf(tv.firstAirDate)
        }
    }

    // ---------------------------------------------------------
    // Current VidSrc
    // ---------------------------------------------------------

    private data class VidSrcResult(
        val url: String,
        val referer: String
    )

    private suspend fun decryptStreamUrls(
        root: JSONObject,
        encrypted: ByteArray
    ): List<String> {

        val vs = root.optJSONObject("vs")
            ?: throw ErrorLoadingException("VidSrc: missing vs")

        val wasmBytes = if (vs.optString("wasm_url").isNotBlank()) {
            app.get(
                vs.getString("wasm_url"),
                headers = mapOf("User-Agent" to userAgent)
            ).body?.bytes()
                ?: throw ErrorLoadingException("VidSrc: empty WASM")
        } else {
            val wasm = vs.optString("wasm")
                .takeIf(String::isNotBlank)
                ?: throw ErrorLoadingException("VidSrc: missing wasm")

            Base64.decode(wasm, Base64.DEFAULT)
        }

        val wasmModule = module(wasmBytes)
        val wasmStore = store()
        val wasmInstance = instance(wasmStore, wasmModule)

        val memory = wasmInstance.exports
            .first { it.name == "memory" }
            .value

        val alloc = invoke(
            wasmStore,
            wasmInstance,
            "alloc",
            listOf(NumberValue.I32(encrypted.size))
        )

        val ptr =
            (alloc.firstOrNull() as? NumberValue.I32)?.value
                ?: throw ErrorLoadingException("VidSrc: alloc failed")

        encrypted.forEachIndexed { i, byte ->
            memory.writeByte(
                wasmStore,
                ptr + i,
                byte
            )
        }

        val result = invoke(
            wasmStore,
            wasmInstance,
            "decrypt",
            listOf(
                NumberValue.I32(ptr),
                NumberValue.I32(encrypted.size)
            )
        )

        val length =
            (result.firstOrNull() as? NumberValue.I32)?.value
                ?: throw ErrorLoadingException("VidSrc: decrypt failed")

        if (length <= 0) {
            throw ErrorLoadingException(
                "VidSrc: decrypt returned $length"
            )
        }

        val output = ByteArray(length)

        for (i in 0 until length) {
            output[i] = memory.readByte(
                wasmStore,
                ptr + 12 + i
            )
        }

        return String(output, Charsets.UTF_8)
            .split('\n')
            .map(String::trim)
            .filter { it.startsWith("http") }
    }

    private suspend fun resolveVidSrc(
        media: HayyaMediaData
    ): VidSrcResult {

        val api = buildString {
            append("https://data.vidsrc.sh/api.php?type=")
            append(if (media.type == "tv") "tv" else "movie")
            append("&tmdb=").append(media.id)

            if (media.type == "tv") {
                append("&season=").append(media.season)
                append("&episode=").append(media.episode)
            }

            append("&stream_urls")
        }

        val response = app.get(
            api,
            headers = browserHeaders + mapOf(
                "Referer" to "https://vidsrc.sh/"
            )
        )

        if (response.code !in 200..299) {
            throw ErrorLoadingException(
                "VidSrc API HTTP ${response.code}"
            )
        }

        val root = JSONObject(response.text)
        val value = root
            .optJSONObject("data")
            ?.opt("stream_urls")
            ?: throw ErrorLoadingException(
                "VidSrc: missing stream_urls"
            )

        val streams = when (value) {
            is JSONArray -> {
                (0 until value.length())
                    .mapNotNull {
                        value.optString(it)
                            .takeIf(String::isNotBlank)
                    }
            }

            is String -> {
                val encrypted = try {
                    Base64.decode(value, Base64.DEFAULT)
                } catch (e: Exception) {
                    throw ErrorLoadingException(
                        "VidSrc: invalid stream_urls"
                    )
                }

                decryptStreamUrls(root, encrypted)
            }

            else -> emptyList()
        }

        val raw = streams.firstOrNull()
            ?: throw ErrorLoadingException(
                "VidSrc: no stream"
            )

        val uri = Uri.parse(raw)
        val origin = "${uri.scheme}://${uri.authority}"

        val token = app.get(
            "$origin/generate.php",
            headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to "https://vidsrc.sh/"
            )
        ).text.trim()

        if (token.isBlank()) {
            throw ErrorLoadingException(
                "VidSrc: empty generate token"
            )
        }

        val finalUrl =
            if (raw.contains("__TOKEN__")) {
                raw.replace("__TOKEN__", token)
            } else {
                "$raw?token=$token"
            }

        return VidSrcResult(
            finalUrl,
            "https://vidsrc.sh/"
        )
    }

    // ---------------------------------------------------------
    // Links
    // ---------------------------------------------------------

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
            logError(e)
            return false
        }

        return try {
            val result = resolveVidSrc(media)

            callback(
                newExtractorLink(
                    source = name,
                    name = "VidSrc",
                    url = result.url,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = result.referer
                    quality = Qualities.Unknown.value
                    headers = mapOf(
                        "User-Agent" to userAgent
                    )
                }
            )

            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logError(e)
            false
        }
    }

    // ---------------------------------------------------------
    // Data
    // ---------------------------------------------------------

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
