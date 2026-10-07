package com.sagemoon1996.hayyashoot

import android.net.Uri
import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.newEpisode
import io.github.charlietap.chasm.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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

    // ---------------------------------------------------------------
    // TMDB AUTH TOKEN
    // ---------------------------------------------------------------

    private val tokenMutex = Mutex()

    @Volatile
    private var cachedToken: String? = null

    private val tokenPatterns = listOf(
        Regex(
            """auth_?token\s*[=:]\s*["'`]([^"'`]+)["'`]""",
            RegexOption.IGNORE_CASE
        ),
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
        val page = app.get(
            "$mainUrl/movies/",
            headers = browserHeaders
        ).text

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
                extractToken(
                    app.get(
                        scriptUrl,
                        headers = browserHeaders
                    ).text
                )?.let { return it }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logError(e)
            }
        }

        return null
    }

    private suspend fun getAuthToken(forceRefresh: Boolean = false): String {
        if (!forceRefresh) {
            cachedToken?.let { return it }
        }

        return tokenMutex.withLock {
            if (!forceRefresh) {
                cachedToken?.let { return@withLock it }
            }

            val token = fetchAuthToken()
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
    ) = app.get(
        "https://api.themoviedb.org/3/$path",
        headers = mapOf(
            "User-Agent" to userAgent,
            "Accept" to "application/json, text/plain, */*",
            "Authorization" to "Bearer $token"
        )
    )

    private suspend fun tmdbGet(path: String): String {
        var response = requestTmdb(
            path,
            getAuthToken()
        )

        if (response.code == 401 || response.code == 403) {
            response = requestTmdb(
                path,
                getAuthToken(forceRefresh = true)
            )
        }

        return response.text
    }

    // ---------------------------------------------------------------
    // URL + SEARCH HELPERS
    // ---------------------------------------------------------------

    private fun slug(title: String): String =
        URLEncoder.encode(
            title
                .replace(Regex("""[\s-]+"""), "-")
                .trim('-'),
            "UTF-8"
        )

    private fun movieUrl(id: Int, title: String) =
        "$mainUrl/movies/?movie=$id&title=${slug(title)}"

    private fun tvUrl(id: Int, title: String) =
        "$mainUrl/movies/?tv=$id&title=${slug(title)}"

    private fun yearOf(date: String?): Int? =
        date?.take(4)?.toIntOrNull()

    private fun toSearchResponse(
        item: TmdbItem,
        isMovie: Boolean
    ): SearchResponse? {

        val itemTitle = (
            if (isMovie) item.title else item.name
        )?.takeIf { it.isNotBlank() } ?: return null

        val poster = item.posterPath?.let {
            posterBase + it
        }

        return if (isMovie) {

            newMovieSearchResponse(
                itemTitle,
                movieUrl(item.id, itemTitle),
                TvType.Movie
            ) {
                this.posterUrl = poster
                this.year = yearOf(item.releaseDate)
            }

        } else {

            newTvSeriesSearchResponse(
                itemTitle,
                tvUrl(item.id, itemTitle),
                TvType.TvSeries
            ) {
                this.posterUrl = poster
                this.year = yearOf(item.firstAirDate)
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
            if (e is CancellationException) throw e
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

        val isMovie = request.data.startsWith("movie")

        val response = fetchList(
            "${request.data}?language=ar-SA&page=$page"
        )

        val items = response?.results
            .orEmpty()
            .mapNotNull {
                toSearchResponse(it, isMovie)
            }
            .distinctBy {
                it.url
            }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = page < (response?.totalPages ?: 1)
        )
    }

    // ---------------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------------

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val trimmed = query.trim()

        if (trimmed.isBlank()) {
            return emptyList()
        }

        val q = URLEncoder.encode(
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
                        toSearchResponse(it, true)
                    }

            val showResults =
                shows.await()
                    ?.results
                    .orEmpty()
                    .mapNotNull {
                        toSearchResponse(it, false)
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

        val uri = Uri.parse(url)

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

        val movie = parseJson<TmdbMovie>(
            tmdbGet(
                "movie/$id?language=ar-SA"
            )
        )

        val title =
            movie.title
                ?.takeIf { it.isNotBlank() }
                ?: return null

        val data = HayyaMediaData(
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

            this.plot = movie.overview
            this.year = yearOf(
                movie.releaseDate
            )
        }
    }

    private suspend fun loadTv(
        url: String,
        id: Int
    ): LoadResponse? {

        val tv = parseJson<TmdbTv>(
            tmdbGet(
                "tv/$id?language=ar-SA"
            )
        )

        val title =
            tv.name
                ?.takeIf { it.isNotBlank() }
                ?: return null

        val seasons = tv.seasons
            .orEmpty()
            .filter {
                it.seasonNumber > 0 &&
                    (it.episodeCount ?: 1) > 0
            }

        val seasonData = coroutineScope {

            seasons
                .map { season ->

                    async {

                        try {

                            season.seasonNumber to
                                parseJson<TmdbSeason>(
                                    tmdbGet(
                                        "tv/$id/season/${season.seasonNumber}?language=ar-SA"
                                    )
                                )

                        } catch (e: Exception) {

                            if (e is CancellationException) {
                                throw e
                            }

                            logError(e)
                            null
                        }
                    }
                }
                .awaitAll()
        }
            .filterNotNull()
            .sortedBy {
                it.first
            }

        val episodes =
            seasonData.flatMap { (seasonNumber, data) ->

                data.episodes
                    .orEmpty()
                    .map { ep ->

                        val epData =
                            HayyaMediaData(
                                type = "tv",
                                id = id,
                                season = seasonNumber,
                                episode = ep.episodeNumber
                            ).toJson()

                        newEpisode(epData) {

                            this.name =
                                ep.name
                                    ?.takeIf {
                                        it.isNotBlank()
                                    }
                                    ?: "الحلقة ${ep.episodeNumber}"

                            this.season =
                                seasonNumber

                            this.episode =
                                ep.episodeNumber

                            this.description =
                                ep.overview

                            this.posterUrl =
                                ep.stillPath?.let {
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

            this.plot = tv.overview

            this.year =
                yearOf(tv.firstAirDate)
        }
    }

    // ---------------------------------------------------------------
    // VID SRC
    //
    // Confirmed chain:
    //
    // HayyaShoot
    //      ↓
    // VidSrc embed
    //      ↓
    // data.vidsrc.sh/api.php
    //      ↓
    // stream_urls
    //      ↓
    // Base64 encrypted data
    //      ↓
    // vs.wasm / vs.wasm_url
    //      ↓
    // alloc()
    //      ↓
    // decrypt()
    //      ↓
    // ptr + 12
    //      ↓
    // stream URLs
    //      ↓
    // <stream-origin>/generate.php
    //      ↓
    // token
    //      ↓
    // final HLS
    // ---------------------------------------------------------------

    private data class VidSrcResult(
        val streams: List<String>,
        val referer: String
    )

    private fun trace(
        message: String
    ) {
        val line =
            "${System.currentTimeMillis() - traceStart}ms $message"

        traceLines.add(line)

        Log.e(
            "HayyaShoot",
            line
        )
    }

    private fun snippet(
        text: String,
        length: Int = 180
    ): String =
        text
            .take(length)
            .replace(
                Regex("""\s+"""),
                " "
            )

    private fun originOf(
        url: String
    ): String {

        val uri = Uri.parse(url)

        return "${uri.scheme}://${uri.authority}/"
    }

    private fun vidSrcApiUrl(
        media: HayyaMediaData
    ): String {

        return buildString {

            append(
                "https://data.vidsrc.sh/api.php?"
            )

            append("type=")
            append(
                if (media.type == "tv") {
                    "tv"
                } else {
                    "movie"
                }
            )

            append("&tmdb=")
            append(media.id)

            if (media.type == "tv") {

                append("&season=")
                append(media.season)

                append("&episode=")
                append(media.episode)
            }

            append("&stream_urls")
        }
    }

    private fun vidSrcHeaders(): Map<String, String> =
        mapOf(
            "User-Agent" to MOBILE_CHROME_UA,
            "Accept" to "application/json, text/plain, */*",
            "Referer" to "https://vidsrc.sh/",
            "Origin" to "https://vidsrc.sh"
        )

    // ---------------------------------------------------------------
    // JSON HELPERS
    // ---------------------------------------------------------------

    private fun jsonString(
        node: JsonNode?,
        name: String
    ): String? {

        val value = node?.get(name)

        return if (
            value != null &&
            value.isTextual
        ) {
            value.asText()
        } else {
            null
        }
    }

    private fun jsonStringList(
        node: JsonNode?,
        name: String
    ): List<String> {

        val value = node?.get(name)
            ?: return emptyList()

        if (!value.isArray) {
            return emptyList()
        }

        return value
            .mapNotNull {
                if (it.isTextual) {
                    it.asText()
                } else {
                    null
                }
            }
            .filter {
                it.startsWith("http")
            }
    }

    private fun decodeBase64(
        value: String
    ): ByteArray {

        return Base64.decode(
            value
                .replace("\\/", "/")
                .trim(),
            Base64.DEFAULT
        )
    }

    // ---------------------------------------------------------------
    // WASM DECRYPT
    //
    // Chasm 0.9.70 API:
    // module()
    // store()
    // instance()
    // invoke()
    // NumberValue.I32
    // readBytes()
    // writeBytes()
    // ---------------------------------------------------------------

    private fun decryptVidSrc(
        encrypted: ByteArray,
        wasmBytes: ByteArray
    ): List<String> {

        trace(
            "WASM start encrypted=${encrypted.size} wasm=${wasmBytes.size}"
        )

        val wasmModule =
            module(wasmBytes)
                .expect("VidSrc WASM decode failed")

        val wasmStore =
            store()

        val wasmInstance =
            instance(
                wasmStore,
                wasmModule,
                emptyList()
            ).expect(
                "VidSrc WASM instance failed"
            )

        val memoryExport =
            wasmInstance.exports.firstOrNull {
                it.name == "memory" &&
                    it.value is Memory
            }
                ?: throw IllegalStateException(
                    "VidSrc WASM memory export missing"
                )

        val memory =
            memoryExport.value as Memory

        val allocResult =
            invoke(
                wasmStore,
                wasmInstance,
                "alloc",
                listOf(
                    NumberValue.I32(
                        encrypted.size
                    )
                )
            ).expect(
                "VidSrc WASM alloc failed"
            )

        val pointer =
            (allocResult.firstOrNull()
                as? NumberValue.I32)
                ?.value
                ?: throw IllegalStateException(
                    "VidSrc WASM alloc returned no i32"
                )

        trace(
            "WASM alloc ptr=$pointer"
        )

        writeBytes(
            wasmStore,
            memory,
            encrypted,
            0,
            encrypted.size,
            pointer
        )

        val decryptResult =
            invoke(
                wasmStore,
                wasmInstance,
                "decrypt",
                listOf(
                    NumberValue.I32(pointer),
                    NumberValue.I32(
                        encrypted.size
                    )
                )
            ).expect(
                "VidSrc WASM decrypt failed"
            )

        val outputLength =
            (decryptResult.firstOrNull()
                as? NumberValue.I32)
                ?.value
                ?: throw IllegalStateException(
                    "VidSrc WASM decrypt returned no i32"
                )

        if (outputLength <= 0) {
            throw IllegalStateException(
                "VidSrc WASM returned empty output"
            )
        }

        trace(
            "WASM decrypt outLen=$outputLength"
        )

        val decoded =
            ByteArray(outputLength)

        // Confirmed vsdec.js:
        // new Uint8Array(ex.memory.buffer, ptr + 12, outLen)
        readBytes(
            wasmStore,
            memory,
            decoded,
            pointer + 12,
            outputLength,
            0
        )

        val text =
            decoded.decodeToString()

        trace(
            "WASM decoded len=${text.length}"
        )

        return text
            .split('\n')
            .map {
                it.trim()
            }
            .filter {
                it.startsWith("http")
            }
            .distinct()
    }

    // ---------------------------------------------------------------
    // GET WASM + DECRYPT STREAM_URLS
    // ---------------------------------------------------------------

    private suspend fun decryptStreamUrls(
        apiJson: JsonNode
    ): List<String> {

        // New/direct response:
        // {
        //   "stream_urls": [...]
        // }
        val direct =
            jsonStringList(
                apiJson,
                "stream_urls"
            )

        if (direct.isNotEmpty()) {
            trace(
                "API stream_urls array=${direct.size}"
            )

            return direct
        }

        // Encrypted stream_urls:
        // "stream_urls": "BASE64..."
        val encryptedString =
            jsonString(
                apiJson,
                "stream_urls"
            )

        if (encryptedString.isNullOrBlank()) {
            throw IllegalStateException(
                "VidSrc stream_urls missing"
            )
        }

        trace(
            "API encrypted stream_urls len=${encryptedString.length}"
        )

        val encrypted =
            decodeBase64(
                encryptedString
            )

        val vs =
            apiJson.get("vs")

        val wasmUrl =
            jsonString(
                vs,
                "wasm_url"
            )

        val wasmBase64 =
            jsonString(
                vs,
                "wasm"
            )

        val wasmBytes =
            when {

                !wasmUrl.isNullOrBlank() -> {

                    trace(
                        "WASM downloading ${wasmUrl.take(160)}"
                    )

                    val response =
                        app.get(
                            wasmUrl,
                            headers = vidSrcHeaders()
                        )

                    trace(
                        "WASM HTTP ${response.code} bytes=${response.body.bytes().size}"
                    )

                    // Fetch again because the response body is consumed above.
                    app.get(
                        wasmUrl,
                        headers = vidSrcHeaders()
                    ).body.bytes()
                }

                !wasmBase64.isNullOrBlank() -> {

                    trace(
                        "WASM using inline base64"
                    )

                    decodeBase64(
                        wasmBase64
                    )
                }

                else -> {
                    throw IllegalStateException(
                        "VidSrc WASM source missing"
                    )
                }
            }

        return decryptVidSrc(
            encrypted,
            wasmBytes
        )
    }

    // ---------------------------------------------------------------
    // GENERATE TOKEN
    // ---------------------------------------------------------------

    private suspend fun generateToken(
        streamUrl: String
    ): String {

        val origin =
            originOf(streamUrl)

        val generateUrl =
            "${origin}generate.php"

        trace(
            "generate.php host=${Uri.parse(origin).host}"
        )

        val response =
            app.get(
                generateUrl,
                headers = mapOf(
                    "User-Agent" to MOBILE_CHROME_UA,
                    "Accept" to "*/*",
                    "Referer" to "https://vidsrc.sh/",
                    "Origin" to "https://vidsrc.sh"
                )
            )

        val body =
            response.text.trim()

        trace(
            "generate HTTP ${response.code} len=${body.length} ${snippet(body)}"
        )

        if (response.code !in 200..299) {
            throw IllegalStateException(
                "generate.php HTTP ${response.code}"
            )
        }

        if (body.isBlank()) {
            throw IllegalStateException(
                "generate.php returned empty token"
            )
        }

        // Usually generate.php returns the token directly.
        // Also accept JSON {"token":"..."} if the server changes format.
        val jsonToken =
            Regex(
                """"token"\s*:\s*"([^"]+)"""",
                RegexOption.IGNORE_CASE
            )
                .find(body)
                ?.groupValues
                ?.getOrNull(1)

        return (
            jsonToken
                ?: body
                    .removePrefix("\"")
                    .removeSuffix("\"")
                    .trim()
            )
                .takeIf {
                    it.isNotBlank()
                }
                ?: throw IllegalStateException(
                    "generate.php token invalid"
                )
    }

    // ---------------------------------------------------------------
    // BUILD FINAL STREAM URL
    // ---------------------------------------------------------------

    private fun applyToken(
        streamUrl: String,
        token: String
    ): String {

        if (
            streamUrl.contains(
                "__TOKEN__"
            )
        ) {
            return streamUrl.replace(
                "__TOKEN__",
                token
            )
        }

        return if (
            streamUrl.contains("?")
        ) {
            "$streamUrl&token=$token"
        } else {
            "$streamUrl?token=$token"
        }
    }

    // ---------------------------------------------------------------
    // RESOLVE VID SRC API
    // ---------------------------------------------------------------

    private suspend fun resolveVidSrc(
        media: HayyaMediaData
    ): VidSrcResult {

        val apiUrl =
            vidSrcApiUrl(media)

        trace(
            "API GET ${apiUrl}"
        )

        val response =
            app.get(
                apiUrl,
                headers = vidSrcHeaders()
            )

        trace(
            "API HTTP ${response.code} len=${response.text.length}"
        )

        if (response.code !in 200..299) {
            throw IllegalStateException(
                "VidSrc API HTTP ${response.code}"
            )
        }

        val json =
            try {
                parseJson<JsonNode>(
                    response.text
                )
            } catch (e: Exception) {
                throw IllegalStateException(
                    "VidSrc API invalid JSON: ${snippet(response.text)}",
                    e
                )
            }

        val streamUrls =
            decryptStreamUrls(
                json
            )

        if (streamUrls.isEmpty()) {
            throw IllegalStateException(
                "VidSrc returned no stream URLs"
            )
        }

        trace(
            "stream URLs=${streamUrls.size}"
        )

        val finalStreams =
            ArrayList<String>()

        for ((index, streamUrl) in streamUrls.withIndex()) {

            try {

                trace(
                    "stream[$index] host=${Uri.parse(streamUrl).host}"
                )

                val token =
                    generateToken(
                        streamUrl
                    )

                val finalUrl =
                    applyToken(
                        streamUrl,
                        token
                    )

                trace(
                    "final[$index] ${finalUrl.take(180)}"
                )

                finalStreams.add(
                    finalUrl
                )

            } catch (e: Exception) {

                if (e is CancellationException) {
                    throw e
                }

                trace(
                    "stream[$index] generate failed ${e.message}"
                )
            }
        }

        if (finalStreams.isEmpty()) {
            throw IllegalStateException(
                "No stream survived generate.php"
            )
        }

        return VidSrcResult(
            streams = finalStreams.distinct(),
            referer = "https://vidsrc.sh/"
        )
    }

    // ---------------------------------------------------------------
    // PLAYLIST CHECK
    // ---------------------------------------------------------------

    private suspend fun playlistCheck(
        url: String,
        referer: String
    ): Pair<Boolean, String> {

        return try {

            withTimeoutOrNull(8_000L) {

                val response =
                    app.get(
                        url,
                        headers = mapOf(
                            "User-Agent" to MOBILE_CHROME_UA,
                            "Referer" to referer
                        )
                    )

                val body =
                    response.text

                val ok =
                    response.code in 200..299 &&
                        body
                            .trimStart()
                            .startsWith(
                                "#EXTM3U"
                            )

                ok to
                    "HTTP ${response.code} len=${body.length} " +
                    if (ok) {
                        "PLAYLIST"
                    } else {
                        snippet(body)
                    }

            } ?: (
                false to
                    "timeout 8s"
                )

        } catch (e: Exception) {

            if (e is CancellationException) {
                throw e
            }

            false to
                "exception ${e::class.java.simpleName}: ${e.message}"
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

        val media =
            try {

                parseJson<HayyaMediaData>(
                    data
                )

            } catch (e: Exception) {

                if (e is CancellationException) {
                    throw e
                }

                Log.e(
                    "HayyaShoot",
                    "Bad media data",
                    e
                )

                return false
            }

        traceStart =
            System.currentTimeMillis()

        traceLines.clear()

        trace(
            "build-20 start " +
                "${media.type} id=${media.id} " +
                "s=${media.season} e=${media.episode}"
        )

        return try {

            val result =
                withTimeoutOrNull(60_000L) {

                    resolveVidSrc(
                        media
                    )
                }
                    ?: throw IllegalStateException(
                        "VidSrc timeout 60s"
                    )

            var emitted = false

            for (
                (index, streamUrl)
                in result.streams.withIndex()
            ) {

                val (playable, reason) =
                    playlistCheck(
                        streamUrl,
                        result.referer
                    )

                trace(
                    "playlist[$index] $reason"
                )

                if (!playable) {
                    continue
                }

                callback(
                    newExtractorLink(
                        source = name,
                        name =
                            if (result.streams.size > 1) {
                                "VidSrc ${index + 1}"
                            } else {
                                "VidSrc"
                            },
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {

                        this.referer =
                            result.referer

                        this.quality =
                            Qualities.Unknown.value

                        this.headers =
                            mapOf(
                                "User-Agent" to MOBILE_CHROME_UA
                            )
                    }
                )

                emitted = true
            }

            // If the playlist probe is blocked by the server but we have a
            // dynamically generated URL, still give CloudStream the URL.
            if (!emitted) {

                trace(
                    "playlist probe failed; emitting generated streams"
                )

                result.streams.forEachIndexed {
                    index,
                    streamUrl ->

                    callback(
                        newExtractorLink(
                            source = name,
                            name =
                                if (result.streams.size > 1) {
                                    "VidSrc ${index + 1}"
                                } else {
                                    "VidSrc"
                                },
                            url = streamUrl,
                            type = ExtractorLinkType.M3U8
                        ) {

                            this.referer =
                                result.referer

                            this.quality =
                                Qualities.Unknown.value

                            this.headers =
                                mapOf(
                                    "User-Agent" to MOBILE_CHROME_UA
                                )
                        }
                    )
                }

                emitted =
                    result.streams.isNotEmpty()
            }

            trace(
                "end found=$emitted streams=${result.streams.size}"
            )

            lastDiagnostic =
                traceLines.joinToString("\n")

            emitted

        } catch (e: Exception) {

            if (e is CancellationException) {
                throw e
            }

            logError(e)

            trace(
                "FAIL ${e::class.java.simpleName}: ${e.message}"
            )

            lastDiagnostic =
                traceLines.joinToString("\n")

            false
        }
    }

    // ---------------------------------------------------------------
    // DIAGNOSTICS
    // ---------------------------------------------------------------

    private companion object {

        @Volatile
        var lastDiagnostic: String? = null

        @Volatile
        var traceStart: Long = 0L

        val traceLines:
            MutableList<String> =
            java.util.Collections.synchronizedList(
                ArrayList<String>()
            )

        const val MOBILE_CHROME_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                "AppleWebKit/537.36 " +
                "(KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    // ---------------------------------------------------------------
    // DATA CLASSES
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
