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

    override val hasMainPage = true

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

        val isMovie =
            request.data.contains("/movies/")

        val type =
            if (isMovie) "movie" else "tv"

        val json = tmdbGet(
            "/discover/$type?sort_by=popularity.desc&page=$page"
        ) ?: return newHomePageResponse(
            request.name,
            emptyList(),
            hasNext = false
        )

        val results =
            json.optJSONArray("results")
                ?: return newHomePageResponse(
                    request.name,
                    emptyList(),
                    hasNext = false
                )

        val items = buildList {

            for (i in 0 until results.length()) {

                val item =
                    results.optJSONObject(i)
                        ?: continue

                val id =
                    item.optInt("id", 0)

                if (id <= 0) {
                    continue
                }

                val title =
                    if (isMovie) {
                        item.optString("title")
                    } else {
                        item.optString("name")
                    }.trim()

                if (title.isBlank()) {
                    continue
                }

                val poster =
                    item.optString("poster_path")
                        .takeIf { it.isNotBlank() }
                        ?.let {
                            "https://image.tmdb.org/t/p/w500$it"
                        }

                if (isMovie) {

                    add(
                        newMovieSearchResponse(
                            name = title,
                            url = "$mainUrl/movies/?tv=$id",
                            type = TvType.Movie
                        ) {
                            posterUrl = poster
                        }
                    )

                } else {

                    add(
                        newTvSeriesSearchResponse(
                            name = title,
                            url = "$mainUrl/series/?tv=$id",
                            type = TvType.TvSeries
                        ) {
                            posterUrl = poster
                        }
                    )
                }
            }
        }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = results.length() > 0
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        if (query.trim().isEmpty()) {
            return emptyList()
        }

        return coroutineScope {

            val movies = async {
                tmdbGet(
                    "/search/movie?query=${enc(query)}"
                )
            }

            val series = async {
                tmdbGet(
                    "/search/tv?query=${enc(query)}"
                )
            }

            val movieResults =
                parseSearchResults(
                    movies.await(),
                    true
                )

            val seriesResults =
                parseSearchResults(
                    series.await(),
                    false
                )

            (movieResults + seriesResults)
                .distinctBy { it.url }
        }
    }

    private fun parseSearchResults(
        json: JSONObject?,
        movie: Boolean
    ): List<SearchResponse> {

        if (json == null) {
            return emptyList()
        }

        val results =
            json.optJSONArray("results")
                ?: return emptyList()

        return buildList {

            for (i in 0 until results.length()) {

                val item =
                    results.optJSONObject(i)
                        ?: continue

                val id =
                    item.optInt("id", 0)

                if (id <= 0) {
                    continue
                }

                val title =
                    if (movie) {
                        item.optString("title")
                    } else {
                        item.optString("name")
                    }.trim()

                if (title.isBlank()) {
                    continue
                }

                val poster =
                    item.optString("poster_path")
                        .takeIf { it.isNotBlank() }
                        ?.let {
                            "https://image.tmdb.org/t/p/w500$it"
                        }

                if (movie) {

                    add(
                        newMovieSearchResponse(
                            name = title,
                            url = "$mainUrl/movies/?tv=$id",
                            type = TvType.Movie
                        ) {
                            posterUrl = poster
                        }
                    )

                } else {

                    add(
                        newTvSeriesSearchResponse(
                            name = title,
                            url = "$mainUrl/series/?tv=$id",
                            type = TvType.TvSeries
                        ) {
                            posterUrl = poster
                        }
                    )
                }
            }
        }
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val uri = Uri.parse(url)

        val id =
            uri.getQueryParameter("tv")
                ?.toIntOrNull()
                ?: return null

        return if (
            url.contains(
                "/movies/",
                ignoreCase = true
            )
        ) {
            loadMovie(id)
        } else {
            loadSeries(id)
        }
    }

    private suspend fun loadMovie(
        id: Int
    ): LoadResponse? {

        val json =
            tmdbGet("/movie/$id")
                ?: return null

        val title =
            json.optString("title")
                .trim()
                .takeIf { it.isNotBlank() }
                ?: return null

        val poster =
            json.optString("poster_path")
                .takeIf { it.isNotBlank() }
                ?.let {
                    "https://image.tmdb.org/t/p/w500$it"
                }

        val background =
            json.optString("backdrop_path")
                .takeIf { it.isNotBlank() }
                ?.let {
                    "https://image.tmdb.org/t/p/original$it"
                }

        val overview =
            json.optString("overview")
                .trim()
                .takeIf { it.isNotBlank() }

        val data =
            JSONObject()
                .put("id", id)
                .put("type", "movie")
                .toString()

        return newMovieLoadResponse(
            name = title,
            url = "$mainUrl/movies/?tv=$id",
            type = TvType.Movie,
            dataUrl = data
        ) {
            posterUrl = poster
            backgroundPosterUrl = background
            plot = overview
        }
    }

    private suspend fun loadSeries(
        id: Int
    ): LoadResponse? {

        val json =
            tmdbGet("/tv/$id")
                ?: return null

        val title =
            json.optString("name")
                .trim()
                .takeIf { it.isNotBlank() }
                ?: return null

        val poster =
            json.optString("poster_path")
                .takeIf { it.isNotBlank() }
                ?.let {
                    "https://image.tmdb.org/t/p/w500$it"
                }

        val background =
            json.optString("backdrop_path")
                .takeIf { it.isNotBlank() }
                ?.let {
                    "https://image.tmdb.org/t/p/original$it"
                }

        val overview =
            json.optString("overview")
                .trim()
                .takeIf { it.isNotBlank() }

        val seasons =
            json.optJSONArray("seasons")
                ?: return newTvSeriesLoadResponse(
                    name = title,
                    url = "$mainUrl/series/?tv=$id",
                    type = TvType.TvSeries,
                    episodes = emptyList()
                ) {
                    posterUrl = poster
                    backgroundPosterUrl = background
                    plot = overview
                }

        val validSeasons = buildList {

            for (i in 0 until seasons.length()) {

                val season =
                    seasons.optJSONObject(i)
                        ?: continue

                val seasonNumber =
                    season.optInt(
                        "season_number",
                        -1
                    )

                if (seasonNumber > 0) {
                    add(seasonNumber)
                }
            }
        }

        val episodes = coroutineScope {

            validSeasons.map { seasonNumber ->

                async {

                    val seasonJson =
                        tmdbGet(
                            "/tv/$id/season/$seasonNumber"
                        )

                    val episodeArray =
                        seasonJson?.optJSONArray(
                            "episodes"
                        )

                    if (episodeArray == null) {
                        emptyList()
                    } else {

                        buildList {

                            for (
                                i in 0 until episodeArray.length()
                            ) {

                                val episode =
                                    episodeArray
                                        .optJSONObject(i)
                                        ?: continue

                                val episodeNumber =
                                    episode.optInt(
                                        "episode_number",
                                        -1
                                    )

                                if (episodeNumber <= 0) {
                                    continue
                                }

                                val episodeName =
                                    episode
                                        .optString("name")
                                        .trim()

                                val still =
                                    episode
                                        .optString("still_path")
                                        .takeIf {
                                            it.isNotBlank()
                                        }
                                        ?.let {
                                            "https://image.tmdb.org/t/p/w500$it"
                                        }

                                val description =
                                    episode
                                        .optString("overview")
                                        .trim()
                                        .takeIf {
                                            it.isNotBlank()
                                        }

                                add(
                                    newEpisode(
                                        "$mainUrl/series/?tv=$id" +
                                            "&s=$seasonNumber" +
                                            "&e=$episodeNumber"
                                    ) {
                                        name =
                                            episodeName.ifBlank {
                                                "الحلقة $episodeNumber"
                                            }

                                        season =
                                            seasonNumber

                                        episode =
                                            episodeNumber

                                        posterUrl =
                                            still

                                        this.description =
                                            description
                                    }
                                )
                            }
                        }
                    }
                }
            }.awaitAll()
                .flatten()
        }

        return newTvSeriesLoadResponse(
            name = title,
            url = "$mainUrl/series/?tv=$id",
            type = TvType.TvSeries,
            episodes = episodes
        ) {
            posterUrl = poster
            backgroundPosterUrl = background
            plot = overview
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

        val embed =
            if (item.type == "movie") {

                "https://vidsrc.sh/embed/movie/${item.id}"

            } else {

                val season =
                    item.season ?: return false

                val episode =
                    item.episode ?: return false

                "https://vidsrc.sh/embed/tv/" +
                    "${item.id}/$season/$episode"
            }

        val result =
            runWasm(embed)
                ?: return false

        result.subtitles.forEach(
            subtitleCallback
        )

        var found = false

        result.streams
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .forEachIndexed { index, streamUrl ->

                found = true

                callback(
                    newExtractorLink(
                        source = name,
                        name = "VidSrc ${index + 1}",
                        url = streamUrl,
                        type =
                            if (
                                streamUrl.contains(
                                    ".m3u8",
                                    ignoreCase = true
                                )
                            ) {
                                ExtractorLinkType.M3U8
                            } else {
                                ExtractorLinkType.VIDEO
                            }
                    ) {
                        referer = embed
                        quality = Qualities.Unknown.value
                    }
                )
            }

        return found
    }

    private fun parseHayyaData(
        data: String
    ): HayyaData {

        val json =
            JSONObject(data)

        return HayyaData(
            id = json.getInt("id"),
            season =
                if (json.has("season")) {
                    json.optInt("season")
                } else {
                    null
                },
            episode =
                if (json.has("episode")) {
                    json.optInt("episode")
                } else {
                    null
                },
            type = json.getString("type")
        )
    }

    private suspend fun runWasm(
        embed: String
    ): WasmResult? {

        val api =
            if (embed.contains("/tv/")) {

                val parts =
                    embed
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

                val id =
                    embed
                        .substringAfter(
                            "/embed/movie/"
                        )
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
                    const j = JSON.parse(raw);

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

                    const wasmUrl =
                        vs.wasm_url || vs.wasm;

                    if (!wasmUrl) {
                        throw new Error(
                            "wasm url missing"
                        );
                    }

                    const wasmResponse =
                        await fetch(wasmUrl, {
                            credentials: "omit"
                        });

                    const wasmBytes =
                        await wasmResponse.arrayBuffer();

                    const module =
                        await WebAssembly.instantiate(
                            wasmBytes,
                            {}
                        );

                    const ex =
                        module.instance.exports;

                    if (
                        !ex.alloc ||
                        !ex.decrypt ||
                        !ex.memory
                    ) {
                        throw new Error(
                            "invalid wasm exports"
                        );
                    }

                    const ptr =
                        ex.alloc(enc.length);

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

                    const output =
                        new Uint8Array(
                            ex.memory.buffer,
                            ptr + 12,
                            outLen
                        );

                    const decoded =
                        new TextDecoder()
                            .decode(output);

                    const streams =
                        decoded
                            .split("\\n")
                            .map(x => x.trim())
                            .filter(Boolean);

                    const finalStreams = [];

                    for (const stream of streams) {
                        try {
                            const u =
                                new URL(stream);

                            const origin =
                                u.origin;

                            const gen =
                                await fetch(
                                    origin +
                                    "/generate.php",
                                    {
                                        credentials: "omit"
                                    }
                                );

                            const genContent =
                                await gen.text();

                            let token = null;

                            try {
                                const gj =
                                    JSON.parse(
                                        genContent
                                    );

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

                            let finalUrl =
                                stream;

                            if (token) {

                                if (
                                    finalUrl.includes(
                                        "__TOKEN__"
                                    )
                                ) {

                                    finalUrl =
                                        finalUrl.replace(
                                            "__TOKEN__",
                                            encodeURIComponent(
                                                token
                                            )
                                        );

                                } else {

                                    finalUrl +=
                                        (
                                            finalUrl
                                                .includes("?")
                                                ? "&"
                                                : "?"
                                        ) +
                                        "token=" +
                                        encodeURIComponent(
                                            token
                                        );
                                }
                            }

                            finalStreams.push(
                                finalUrl
                            );

                        } catch (_) {}
                    }

                    location.href =
                        "hayya-result://done?data=" +
                        encodeURIComponent(
                            JSON.stringify({
                                streams:
                                    finalStreams,
                                subtitles: []
                            })
                        );

                } catch (e) {

                    location.href =
                        "hayya-result://error?msg=" +
                        encodeURIComponent(
                            String(
                                e &&
                                e.message
                                    ? e.message
                                    : e
                            )
                        );
                }
            })();
        """.trimIndent()

        val resolver =
            WebViewResolver(
                Regex("""hayya-result://"""),
                userAgent = USER_AGENT,
                script = script,
                timeout = 35_000L
            )

        val result =
            withTimeoutOrNull(40_000L) {
                resolver.resolveUsingWebView(
                    embed,
                    "$mainUrl/"
                )
            } ?: return null

        val hit =
            result.first?.url
                ?: return null

        val hitUri =
            Uri.parse(hit.toString())

        if (
            hitUri.scheme != "hayya-result"
        ) {
            return null
        }

        if (
            hitUri.host != "done"
        ) {
            return null
        }

        val encoded =
            hitUri.getQueryParameter("data")
                ?: return null

        return parseWasmResult(encoded)
    }

    private fun parseWasmResult(
        encoded: String
    ): WasmResult? {

        return try {

            val json =
                JSONObject(encoded)

            val array =
                json.optJSONArray("streams")

            val streams =
                if (array != null) {

                    buildList {

                        for (
                            i in 0 until array.length()
                        ) {

                            val value =
                                array
                                    .optString(i)
                                    .trim()

                            if (
                                value.isNotBlank()
                            ) {
                                add(value)
                            }
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

    private suspend fun tmdbGet(
        path: String
    ): JSONObject? {

        val token =
            getTmdbToken()
                ?: return null

        return try {

            val response =
                app.get(
                    "https://api.themoviedb.org/3$path",
                    headers = mapOf(
                        "Authorization" to
                            "Bearer $token",
                        "Accept" to
                            "application/json"
                    )
                )

            JSONObject(response.text)

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
                    app.get(
                        "$mainUrl/movies/"
                    ).text

                val patterns =
                    listOf(
                        Regex(
                            """["']?auth_token["']?\s*[:=]\s*["']([^"']+)["']""",
                            RegexOption.IGNORE_CASE
                        ),
                        Regex(
                            """Bearer\s+([A-Za-z0-9._-]+)""",
                            RegexOption.IGNORE_CASE
                        )
                    )

                var token =
                    patterns
                        .asSequence()
                        .mapNotNull { regex ->
                            regex
                                .find(htmlContent)
                                ?.groupValues
                                ?.getOrNull(1)
                        }
                        .firstOrNull()

                if (
                    token.isNullOrBlank()
                ) {

                    val scripts =
                        Regex(
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
                            if (
                                src.startsWith("http")
                            ) {
                                src
                            } else {
                                URI(mainUrl)
                                    .resolve(src)
                                    .toString()
                            }

                        val scriptContent =
                            app.get(full).text

                        token =
                            patterns
                                .asSequence()
                                .mapNotNull { regex ->
                                    regex
                                        .find(
                                            scriptContent
                                        )
                                        ?.groupValues
                                        ?.getOrNull(1)
                                }
                                .firstOrNull()

                        if (
                            !token.isNullOrBlank()
                        ) {
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

    private fun enc(
        value: String
    ): String =
        URLEncoder.encode(
            value,
            "UTF-8"
        )

    companion object {

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 16) " +
            "AppleWebKit/537.36 " +
            "(KHTML, like Gecko) " +
            "Chrome/140.0 Mobile Safari/537.36"
    }
}
