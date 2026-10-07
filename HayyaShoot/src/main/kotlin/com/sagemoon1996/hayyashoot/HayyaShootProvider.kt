package com.sagemoon1996.hayyashoot

import android.net.Uri
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

class HayyaShootProvider : MainAPI() {

    override var mainUrl = "https://hayyashoot.com"
    override var name = "HayyaShoot2"
    override var lang = "ar"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "Movies",
        "$mainUrl/series/" to "Series"
    )

    private val tokenMutex = Mutex()
    private var tmdbToken: String? = null

    private data class TmdbSearch(
        val results: List<TmdbItem> = emptyList()
    )

    private data class TmdbItem(
        val id: Int,
        val title: String? = null,
        val name: String? = null,
        val poster_path: String? = null,
        val backdrop_path: String? = null,
        val overview: String? = null,
        val release_date: String? = null,
        val first_air_date: String? = null,
        val vote_average: Double? = null
    )

    private data class TmdbMovie(
        val id: Int,
        val title: String? = null,
        val poster_path: String? = null,
        val backdrop_path: String? = null,
        val overview: String? = null,
        val release_date: String? = null,
        val vote_average: Double? = null
    )

    private data class TmdbTv(
        val id: Int,
        val name: String? = null,
        val poster_path: String? = null,
        val backdrop_path: String? = null,
        val overview: String? = null,
        val first_air_date: String? = null,
        val vote_average: Double? = null,
        val seasons: List<TmdbSeason>? = null
    )

    private data class TmdbSeason(
        val season_number: Int,
        val episode_count: Int? = null
    )

    private data class TmdbSeasonDetails(
        val episodes: List<TmdbEpisode>? = null
    )

    private data class TmdbEpisode(
        val id: Int,
        val episode_number: Int,
        val name: String? = null,
        val overview: String? = null,
        val still_path: String? = null,
        val air_date: String? = null
    )

    private data class HayyaData(
        val id: Int,
        val season: Int? = null,
        val episode: Int? = null,
        val type: String
    )

    private data class WasmResult(
        val streams: List<String>,
        val subtitles: List<SubtitleFile>
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val isMovie = request.data.contains("/movies/")
        val type = if (isMovie) "movie" else "tv"

        val data = tmdbRequest<TmdbSearch>(
            "/discover/$type?sort_by=popularity.desc&page=$page"
        ) ?: return newHomePageResponse(
            request.name,
            emptyList()
        )

        val list = data.results.mapNotNull {
            makeSearch(it, isMovie)
        }

        return newHomePageResponse(
            request.name,
            list
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return coroutineScope {
            val movie = async {
                tmdbRequest<TmdbSearch>(
                    "/search/movie?query=${enc(query)}"
                )
            }

            val tv = async {
                tmdbRequest<TmdbSearch>(
                    "/search/tv?query=${enc(query)}"
                )
            }

            val movies = movie.await()?.results.orEmpty()
                .mapNotNull { makeSearch(it, true) }

            val series = tv.await()?.results.orEmpty()
                .mapNotNull { makeSearch(it, false) }

            movies + series
        }
    }

    private fun makeSearch(
        item: TmdbItem,
        movie: Boolean
    ): SearchResponse? {

        val title = (if (movie) item.title else item.name)
            ?: return null

        val poster = item.poster_path?.let {
            "https://image.tmdb.org/t/p/w500$it"
        }

        return if (movie) {
            newMovieSearchResponse(
                title,
                "$mainUrl/movies/?tv=${item.id}",
                TvType.Movie
            ) {
                posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(
                title,
                "$mainUrl/series/?tv=${item.id}",
                TvType.TvSeries
            ) {
                posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val uri = Uri.parse(url)

        val id = uri.getQueryParameter("tv")?.toIntOrNull()
            ?: return null

        return if (url.contains("/movies/")) {
            loadMovie(id)
        } else {
            loadTv(id)
        }
    }

    private suspend fun loadMovie(id: Int): LoadResponse? {
        val movie = tmdbRequest<TmdbMovie>(
            "/movie/$id"
        ) ?: return null

        val title = movie.title ?: return null

        val data = JSONObject()
            .put("id", id)
            .put("type", "movie")
            .toString()

        return newMovieLoadResponse(
            title,
            "$mainUrl/movies/?tv=$id",
            TvType.Movie,
            data
        ) {
            posterUrl = movie.poster_path?.let {
                "https://image.tmdb.org/t/p/w500$it"
            }

            backgroundPosterUrl = movie.backdrop_path?.let {
                "https://image.tmdb.org/t/p/original$it"
            }

            plot = movie.overview

            year = movie.release_date
                ?.takeIf { it.length >= 4 }
                ?.substring(0, 4)
                ?.toIntOrNull()

            score = movie.vote_average
        }
    }

    private suspend fun loadTv(id: Int): LoadResponse? {
        val tv = tmdbRequest<TmdbTv>(
            "/tv/$id"
        ) ?: return null

        val title = tv.name ?: return null

        val seasons = tv.seasons.orEmpty()
            .filter { it.season_number > 0 }

        val episodes = coroutineScope {
            seasons.map { season ->
                async {
                    val details = tmdbRequest<TmdbSeasonDetails>(
                        "/tv/$id/season/${season.season_number}"
                    )

                    details?.episodes.orEmpty().map { episode ->
                        newEpisode(
                            "$mainUrl/series/?tv=$id&s=${season.season_number}&e=${episode.episode_number}"
                        ) {
                            name = episode.name
                            number = episode.episode_number
                            seasonNumber = season.season_number
                            description = episode.overview

                            posterUrl = episode.still_path?.let {
                                "https://image.tmdb.org/t/p/w500$it"
                            }

                            airDate = episode.air_date
                        }
                    }
                }
            }.awaitAll().flatten()
        }

        return newTvSeriesLoadResponse(
            title,
            "$mainUrl/series/?tv=$id",
            TvType.TvSeries,
            episodes
        ) {
            posterUrl = tv.poster_path?.let {
                "https://image.tmdb.org/t/p/w500$it"
            }

            backgroundPosterUrl = tv.backdrop_path?.let {
                "https://image.tmdb.org/t/p/original$it"
            }

            plot = tv.overview

            year = tv.first_air_date
                ?.takeIf { it.length >= 4 }
                ?.substring(0, 4)
                ?.toIntOrNull()

            score = tv.vote_average
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val item = try {
            parseHayyaData(data)
        } catch (_: Exception) {
            return false
        }

        val embed = if (item.type == "movie") {
            "https://vidsrc.sh/embed/movie/${item.id}"
        } else {
            val season = item.season ?: return false
            val episode = item.episode ?: return false

            "https://vidsrc.sh/embed/tv/${item.id}/$season/$episode"
        }

        val result = runWasm(embed)
            ?: return false

        result.subtitles.forEach(subtitleCallback)

        result.streams.forEachIndexed { index, stream ->
            val streamUrl = stream.trim()

            if (streamUrl.isBlank()) {
                return@forEachIndexed
            }

            callback(
                ExtractorLink(
                    source = name,
                    name = "VidSrc ${index + 1}",
                    url = streamUrl,
                    referer = embed,
                    quality = Qualities.Unknown.value,
                    isM3u8 = streamUrl.contains(".m3u8", true)
                )
            )
        }

        return result.streams.isNotEmpty()
    }

    private fun parseHayyaData(data: String): HayyaData {
        val json = JSONObject(data)

        return HayyaData(
            id = json.getInt("id"),
            season = if (json.has("season")) {
                json.optInt("season")
            } else {
                null
            },
            episode = if (json.has("episode")) {
                json.optInt("episode")
            } else {
                null
            },
            type = json.getString("type")
        )
    }

    private suspend fun runWasm(embed: String): WasmResult? {

        val api = if (embed.contains("/tv/")) {

            val parts = embed
                .substringAfter("/embed/tv/")
                .split("/")

            if (parts.size < 3) {
                return null
            }

            val id = parts[0]
            val season = parts[1]
            val episode = parts[2]

            "https://data.vidsrc.sh/api.php" +
                    "?type=tv" +
                    "&tmdb=$id" +
                    "&season=$season" +
                    "&episode=$episode" +
                    "&stream_urls"

        } else {

            val id = embed
                .substringAfter("/embed/movie/")
                .substringBefore("?")

            "https://data.vidsrc.sh/api.php" +
                    "?type=movie" +
                    "&tmdb=$id" +
                    "&stream_urls"
        }

        val script = """
            (async function() {
                try {
                    const api = ${JSONObject.quote(api)};

                    const response = await fetch(api, {
                        credentials: "omit"
                    });

                    const raw = await response.text();
                    let j = JSON.parse(raw);

                    const data = j.data || j;
                    let encoded = data.stream_urls;

                    if (!encoded) {
                        throw new Error("stream_urls missing");
                    }

                    if (typeof encoded !== "string") {
                        encoded = JSON.stringify(encoded);
                    }

                    const bin = atob(encoded);
                    const enc = new Uint8Array(bin.length);

                    for (let i = 0; i < bin.length; i++) {
                        enc[i] = bin.charCodeAt(i);
                    }

                    const vs = j.vs || data.vs;

                    if (!vs) {
                        throw new Error("vs missing");
                    }

                    const wasmUrl = vs.wasm_url || vs.wasm;

                    if (!wasmUrl) {
                        throw new Error("wasm url missing");
                    }

                    const wasmResponse = await fetch(wasmUrl, {
                        credentials: "omit"
                    });

                    const wasmBytes =
                        await wasmResponse.arrayBuffer();

                    const module =
                        await WebAssembly.instantiate(
                            wasmBytes,
                            {}
                        );

                    const ex = module.instance.exports;

                    if (
                        !ex.alloc ||
                        !ex.decrypt ||
                        !ex.memory
                    ) {
                        throw new Error(
                            "invalid wasm exports"
                        );
                    }

                    const ptr = ex.alloc(enc.length);

                    new Uint8Array(
                        ex.memory.buffer,
                        ptr,
                        enc.length
                    ).set(enc);

                    const outLen =
                        ex.decrypt(
                            ptr,
                            enc.length
                        );

                    const output = new Uint8Array(
                        ex.memory.buffer,
                        ptr + 12,
                        outLen
                    );

                    const decoded =
                        new TextDecoder().decode(output);

                    const streams = decoded
                        .split("\\n")
                        .map(x => x.trim())
                        .filter(Boolean);

                    const finalStreams = [];

                    for (const stream of streams) {
                        try {
                            const u = new URL(stream);
                            const origin = u.origin;

                            const gen = await fetch(
                                origin + "/generate.php",
                                {
                                    credentials: "omit"
                                }
                            );

                            const genContent =
                                await gen.text();

                            let token = null;

                            try {
                                const gj =
                                    JSON.parse(genContent);

                                token =
                                    gj.token ||
                                    gj.data?.token ||
                                    gj.result?.token;
                            } catch (_) {}

                            if (!token) {
                                const m =
                                    genContent.match(
                                        /["']?token["']?\s*[:=]\s*["']([^"']+)["']/
                                    );

                                if (m) {
                                    token = m[1];
                                }
                            }

                            if (!token) {
                                const m =
                                    genContent.match(
                                        /[A-Za-z0-9_-]{20,}/
                                    );

                                if (m) {
                                    token = m[0];
                                }
                            }

                            let finalUrl = stream;

                            if (token) {
                                if (
                                    finalUrl.includes(
                                        "__TOKEN__"
                                    )
                                ) {
                                    finalUrl =
                                        finalUrl.replace(
                                            "__TOKEN__",
                                            encodeURIComponent(token)
                                        );
                                } else {
                                    finalUrl +=
                                        (
                                            finalUrl.includes("?")
                                                ? "&"
                                                : "?"
                                        ) +
                                        "token=" +
                                        encodeURIComponent(token);
                                }
                            }

                            finalStreams.push(finalUrl)

                        } catch (_) {}
                    }

                    location.href =
                        "hayya-result://done?data=" +
                        encodeURIComponent(
                            JSON.stringify({
                                streams: finalStreams,
                                subtitles: []
                            })
                        );

                } catch (e) {

                    location.href =
                        "hayya-result://error?msg=" +
                        encodeURIComponent(
                            String(
                                e && e.message
                                    ? e.message
                                    : e
                            )
                        );
                }
            })();
        """.trimIndent()

        val resolver = WebViewResolver(
            Regex("""hayya-result://"""),
            userAgent = USER_AGENT,
            script = script,
            timeout = 35_000L
        )

        val result = withTimeoutOrNull(40_000L) {
            resolver.resolveUsingWebView(
                embed,
                "$mainUrl/"
            )
        } ?: return null

        val hit = result.first?.url
            ?: return null

        val hitUri = Uri.parse(hit.toString())

        val encoded = hitUri.getQueryParameter("data")
            ?: return null

        return parseWasmResult(encoded)
    }

    private fun parseWasmResult(
        encoded: String
    ): WasmResult? {
        return try {
            val json = JSONObject(encoded)

            val streamsArray =
                json.optJSONArray("streams")

            val streams =
                if (streamsArray != null) {
                    (0 until streamsArray.length())
                        .mapNotNull {
                            streamsArray
                                .optString(it)
                                .takeIf { value ->
                                    value.isNotBlank()
                                }
                        }
                } else {
                    emptyList()
                }

            WasmResult(
                streams = streams,
                subtitles = emptyList()
            )
        } catch (_: Exception) {
            null
        }
    }

    private suspend inline fun <reified T> tmdbRequest(
        path: String
    ): T? {

        val token = getTmdbToken()
            ?: return null

        return try {
            app.get(
                "https://api.themoviedb.org/3$path",
                headers = mapOf(
                    "Authorization" to "Bearer $token",
                    "Accept" to "application/json"
                )
            ).parsedSafe<T>()
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun getTmdbToken(): String? {

        tokenMutex.withLock {

            tmdbToken?.let {
                return it
            }

            return try {

                val htmlContent =
                    app.get("$mainUrl/movies/").text()

                val patterns = listOf(
                    Regex(
                        """["']?auth_token["']?\s*[:=]\s*["']([^"']+)["']""",
                        RegexOption.IGNORE_CASE
                    ),
                    Regex(
                        """Bearer\s+([A-Za-z0-9._-]+)""",
                        RegexOption.IGNORE_CASE
                    )
                )

                var token = patterns
                    .asSequence()
                    .mapNotNull {
                        it.find(
                            htmlContent
                        )?.groupValues?.getOrNull(1)
                    }
                    .firstOrNull()

                if (token.isNullOrBlank()) {

                    val scripts = Regex(
                        """<script[^>]+src=["']([^"']+)["']""",
                        RegexOption.IGNORE_CASE
                    )
                        .findAll(htmlContent)
                        .map {
                            it.groupValues[1]
                        }
                        .toList()

                    for (src in scripts) {

                        val full =
                            if (src.startsWith("http")) {
                                src
                            } else {
                                URI(mainUrl)
                                    .resolve(src)
                                    .toString()
                            }

                        val scriptContent =
                            app.get(full).text()

                        token = patterns
                            .asSequence()
                            .mapNotNull {
                                it.find(
                                    scriptContent
                                )?.groupValues?.getOrNull(1)
                            }
                            .firstOrNull()

                        if (!token.isNullOrBlank()) {
                            break
                        }
                    }
                }

                tmdbToken = token
                token

            } catch (_: Exception) {
                null
            }
        }
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 16) " +
                    "AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) " +
                    "Chrome/140.0 Mobile Safari/537.36"
    }
}
