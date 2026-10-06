package com.sagemoon1996.hayyashoot

import android.net.Uri
import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import io.github.charlietap.chasm.embedding.instance
import io.github.charlietap.chasm.embedding.invoke
import io.github.charlietap.chasm.embedding.module
import io.github.charlietap.chasm.embedding.readByte
import io.github.charlietap.chasm.embedding.store
import io.github.charlietap.chasm.embedding.writeByte
import io.github.charlietap.chasm.embedding.shapes.Memory
import io.github.charlietap.chasm.type.NumberValue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
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
    // TMDB token
    // ---------------------------------------------------------------

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
                extractToken(
                    app.get(scriptUrl, headers = browserHeaders).text
                )?.let { return it }
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
                "User-Agent" to userAgent,
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

    // ---------------------------------------------------------------
    // URL + card helpers
    // ---------------------------------------------------------------

    private fun slug(title: String): String =
        URLEncoder.encode(
            title.replace(Regex("""[\s-]+"""), "-").trim('-'),
            "UTF-8"
        )

    private fun movieUrl(id: Int, title: String) =
        "$mainUrl/movies/?movie=$id&title=${slug(title)}"

    private fun tvUrl(id: Int, title: String) =
        "$mainUrl/movies/?tv=$id&title=${slug(title)}"

    private fun yearOf(date: String?): Int? =
        date?.take(4)?.toIntOrNull()

    private fun toSearchResponse(item: TmdbItem, isMovie: Boolean): SearchResponse? {
        val itemTitle = (if (isMovie) item.title else item.name)
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = item.posterPath?.let { posterBase + it }

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

    private suspend fun fetchList(path: String): TmdbResponse? =
        try {
            parseJson<TmdbResponse>(tmdbGet(path))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logError(e)
            null
        }

    // ---------------------------------------------------------------
    // Main page
    // ---------------------------------------------------------------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val isMovie = request.data.startsWith("movie")

        val response = fetchList("${request.data}?language=ar-SA&page=$page")

        val baseItems = response?.results
            .orEmpty()
            .mapNotNull { toSearchResponse(it, isMovie) }
            .distinctBy { it.url }

        val items =
            if (debugMode && page == 1 && isMovie) {
                listOf(
                    newMovieSearchResponse(
                        "DIAG $buildTag - tap to read the last trace",
                        "$mainUrl/diag",
                        TvType.Movie
                    )
                ) + baseItems
            } else {
                baseItems
            }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = page < (response?.totalPages ?: 1)
        )
    }

    // ---------------------------------------------------------------
    // Search
    // ---------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        if (
            trimmed.startsWith("http", ignoreCase = true) &&
            STREAM_URL_REGEX.containsMatchIn(trimmed)
        ) {
            return listOf(
                newMovieSearchResponse(
                    "Play pasted stream",
                    "$mainUrl/manual?u=${URLEncoder.encode(trimmed, "UTF-8")}",
                    TvType.Movie
                )
            )
        }

        val q = URLEncoder.encode(trimmed, "UTF-8")

        return coroutineScope {
            val movies = async {
                fetchList("search/movie?query=$q&language=ar-SA&page=1")
            }

            val shows = async {
                fetchList("search/tv?query=$q&language=ar-SA&page=1")
            }

            val movieResults = movies.await()?.results.orEmpty()
                .mapNotNull { toSearchResponse(it, true) }

            val showResults = shows.await()?.results.orEmpty()
                .mapNotNull { toSearchResponse(it, false) }

            (movieResults + showResults).distinctBy { it.url }
        }
    }

    // ---------------------------------------------------------------
    // Load
    // ---------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("/manual?u=")) {
            val streamUrl = Uri.parse(url).getQueryParameter("u") ?: return null

            return newMovieLoadResponse(
                "Pasted stream",
                url,
                TvType.Movie,
                "manual:$streamUrl"
            ) {
                this.plot =
                    "Plays the playlist URL you pasted in the search box.\n" +
                    "Host: ${Uri.parse(streamUrl).host}"
            }
        }

        if (debugMode && url.endsWith("/diag")) {
            return newMovieLoadResponse(
                "Diagnostics $buildTag",
                url,
                TvType.Movie,
                "diag"
            ) {
                this.plot = lastDiagnostic
                    ?: "No trace yet. Play an episode, then refresh the home page."
            }
        }

        val uri = Uri.parse(url)
        val movieId = uri.getQueryParameter("movie")?.toIntOrNull()
        val tvId = uri.getQueryParameter("tv")?.toIntOrNull()

        if (movieId != null) return loadMovie(url, movieId)
        if (tvId != null) return loadTv(url, tvId)

        return null
    }

    private suspend fun loadMovie(url: String, id: Int): LoadResponse? {
        val movie = parseJson<TmdbMovie>(
            tmdbGet("movie/$id?language=ar-SA")
        )

        val title = movie.title?.takeIf { it.isNotBlank() } ?: return null

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
            this.posterUrl = movie.posterPath?.let { posterBase + it }
            this.backgroundPosterUrl =
                movie.backdropPath?.let { backdropBase + it }
            this.plot = movie.overview
            this.year = yearOf(movie.releaseDate)
        }
    }

    private suspend fun loadTv(url: String, id: Int): LoadResponse? {
        val tv = parseJson<TmdbTv>(
            tmdbGet("tv/$id?language=ar-SA")
        )

        val title = tv.name?.takeIf { it.isNotBlank() } ?: return null

        val seasons = tv.seasons
            .orEmpty()
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
        }
            .filterNotNull()
            .sortedBy { it.first }

        val episodes = seasonData.flatMap { (seasonNumber, data) ->
            data.episodes.orEmpty().map { ep ->
                val epData = HayyaMediaData(
                    type = "tv",
                    id = id,
                    season = seasonNumber,
                    episode = ep.episodeNumber
                ).toJson()

                newEpisode(epData) {
                    this.name = ep.name?.takeIf { it.isNotBlank() }
                        ?: "الحلقة ${ep.episodeNumber}"

                    this.season = seasonNumber
                    this.episode = ep.episodeNumber
                    this.description = ep.overview
                    this.posterUrl =
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
            this.posterUrl = tv.posterPath?.let { posterBase + it }
            this.backgroundPosterUrl =
                tv.backdropPath?.let { backdropBase + it }
            this.plot = tv.overview
            this.year = yearOf(tv.firstAirDate)
        }
    }

    // ---------------------------------------------------------------
    // loadLinks
    // ---------------------------------------------------------------

    private val debugMode = true

    private val buildTag = "build-18"

    private class StepFailure(
        val step: String,
        val detail: String
    ) : Exception("$step $detail")

    private data class VidSrcResult(
        val streams: List<String>,
        val subtitles: List<String>,
        val referer: String
    )

    private fun snippet(text: String) =
        text.take(120).replace(Regex("""\s+"""), " ")

    private suspend fun reportFailure(
        callback: (ExtractorLink) -> Unit,
        text: String
    ) {
        val label = "DEBUG $text"
            .replace(Regex("""\s+"""), " ")
            .take(220)

        callback(
            newExtractorLink(
                name,
                label,
                "https://debug.invalid/",
                ExtractorLinkType.VIDEO
            ) {
                this.quality = Qualities.Unknown.value
            }
        )
    }

    private fun embedCandidates(media: HayyaMediaData): List<String> {
        val isTv =
            media.type == "tv" &&
                media.season != null &&
                media.episode != null

        return if (isTv) {
            val s = media.season
            val e = media.episode

            listOf(
                "https://vidsrc.sh/embed/tv?tmdb=${media.id}&season=$s&episode=$e&sub=ar&autoplay=1",
                "https://vidsrc.sh/embed/tv/${media.id}/$s/$e",
                "https://vidsrc.me/embed/tv?tmdb=${media.id}&season=$s&episode=$e&sub=ar",
                "https://vidsrc-embed.ru/embed/tv/${media.id}/$s/$e",
                "https://vidsrc.xyz/embed/tv?tmdb=${media.id}&season=$s&episode=$e&sub=ar",
                "https://vidsrc.net/embed/tv?tmdb=${media.id}&season=$s&episode=$e&sub=ar"
            )
        } else {
            listOf(
                "https://vidsrc.sh/embed/movie?tmdb=${media.id}&sub=ar&autoplay=1",
                "https://vidsrc.sh/embed/movie/${media.id}",
                "https://vidsrc.me/embed/movie?tmdb=${media.id}&sub=ar",
                "https://vidsrc-embed.ru/embed/movie/${media.id}",
                "https://vidsrc.xyz/embed/movie?tmdb=${media.id}&sub=ar",
                "https://vidsrc.net/embed/movie?tmdb=${media.id}&sub=ar"
            )
        }
    }

    private suspend fun pickReferer(
        streamUrl: String,
        candidates: List<String>
    ): String {
        val list = candidates
            .filter { it.isNotBlank() }
            .distinct()

        for (candidate in list) {
            val (ok, why) = playlistCheck(streamUrl, candidate)

            trace(
                "referer ${Uri.parse(candidate).host} -> $why"
            )

            if (ok) return candidate
        }

        return list.firstOrNull() ?: "$mainUrl/"
    }

    private fun originOf(url: String): String {
        val u = Uri.parse(url)
        return "${u.scheme}://${u.authority}/"
    }

    private suspend fun sniffM3u8(
        label: String,
        url: String,
        referer: String,
        timeoutMs: Long,
        ua: String? = null
    ): String? =
        try {
            withTimeoutOrNull(timeoutMs + 5_000L) {
                val resolver =
                    if (ua != null) {
                        WebViewResolver(
                            STREAM_URL_REGEX,
                            additionalUrls = listOf(Regex(""".*""")),
                            userAgent = ua,
                            script = CLICK_SCRIPT,
                            timeout = timeoutMs
                        )
                    } else {
                        WebViewResolver(
                            STREAM_URL_REGEX,
                            additionalUrls = listOf(Regex(""".*""")),
                            script = CLICK_SCRIPT,
                            timeout = timeoutMs
                        )
                    }

                val (hit, others) =
                    resolver.resolveUsingWebView(url, referer)

                val seen = others
                    .map {
                        it.url.toString()
                            .substringAfter("://")
                            .take(70)
                    }
                    .distinct()

                trace(
                    "$label webview saw ${others.size} requests (${seen.size} distinct)"
                )

                seen.take(30).forEach {
                    trace("$label req $it")
                }

                hit?.url?.toString()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logError(e)
            trace(
                "$label webview exception " +
                    "${e::class.java.simpleName}: ${e.message}"
            )
            null
        }

    // ---------------------------------------------------------------
    // CURRENT VIDSRС API + WASM
    // ---------------------------------------------------------------

    private suspend fun resolveCurrentVidSrc(
        media: HayyaMediaData
    ): VidSrcResult {
        val type =
            if (media.type == "tv") "tv" else "movie"

        var apiUrl =
            "https://data.vidsrc.sh/api.php" +
                "?type=$type" +
                "&tmdb=${media.id}"

        if (type == "tv") {
            val season = media.season
                ?: throw StepFailure(
                    "vidsrc-api",
                    "missing season"
                )

            val episode = media.episode
                ?: throw StepFailure(
                    "vidsrc-api",
                    "missing episode"
                )

            apiUrl +=
                "&season=$season" +
                    "&episode=$episode"
        }

        apiUrl += "&stream_urls"

        trace(
            "CURRENT API request type=$type id=${media.id}"
        )

        val response = app.get(
            apiUrl,
            headers = browserHeaders + mapOf(
                "Referer" to "https://vidsrc.sh/",
                "Accept" to "application/json, text/plain, */*"
            )
        )

        val body = response.text

        if (response.code !in 200..299 || body.isBlank()) {
            throw StepFailure(
                "vidsrc-api",
                "HTTP ${response.code} len=${body.length} ${snippet(body)}"
            )
        }

        trace(
            "CURRENT API HTTP ${response.code} len=${body.length}"
        )

        val json =
            try {
                parseJson<VidSrcApiResponse>(body)
            } catch (e: Exception) {
                throw StepFailure(
                    "vidsrc-api-json",
                    "${e::class.java.simpleName}: ${e.message}"
                )
            }

        val rawStreamUrls = json.data?.streamUrls

        // Plain response:
        // data.stream_urls = [...]
        val plainStreams =
            when (rawStreamUrls) {
                is List<*> ->
                    rawStreamUrls
                        .filterIsInstance<String>()
                        .filter { it.isNotBlank() }

                else -> emptyList()
            }

        if (plainStreams.isNotEmpty()) {
            trace(
                "CURRENT API plain streams=${plainStreams.size}"
            )

            val stamped =
                plainStreams.map {
                    stampVidSrcStream(it)
                }

            return VidSrcResult(
                streams = stamped,
                subtitles = emptyList(),
                referer = "https://vidsrc.sh/"
            )
        }

        // Encrypted response:
        // data.stream_urls = base64
        val encrypted =
            rawStreamUrls as? String
                ?: throw StepFailure(
                    "vidsrc-api",
                    "no stream_urls"
                )

        val vs = json.vs
            ?: throw StepFailure(
                "vidsrc-api",
                "encrypted stream_urls but missing vs"
            )

        val wasmBytes =
            when {
                !vs.wasmUrl.isNullOrBlank() -> {
                    trace(
                        "WASM download " +
                            "${Uri.parse(vs.wasmUrl).host}"
                    )

                    val wasmResponse = app.get(
                        vs.wasmUrl,
                        headers = browserHeaders + mapOf(
                            "Referer" to "https://vidsrc.sh/"
                        )
                    )

                    if (wasmResponse.code !in 200..299) {
                        throw StepFailure(
                            "wasm-download",
                            "HTTP ${wasmResponse.code}"
                        )
                    }

                    wasmResponse.body.bytes()
                }

                !vs.wasm.isNullOrBlank() -> {
                    Base64.decode(
                        vs.wasm,
                        Base64.DEFAULT
                    )
                }

                else -> {
                    throw StepFailure(
                        "wasm",
                        "missing wasm_url and wasm"
                    )
                }
            }

        val decoded = decryptVidSrcWasm(
            wasmBytes = wasmBytes,
            encryptedBase64 = encrypted
        )

        val streams = decoded
            .lineSequence()
            .map { it.trim() }
            .filter {
                it.startsWith("http://") ||
                    it.startsWith("https://")
            }
            .distinct()
            .toList()

        if (streams.isEmpty()) {
            throw StepFailure(
                "wasm-decrypt",
                "decrypt produced no URLs"
            )
        }

        trace(
            "CURRENT WASM decrypted streams=${streams.size}"
        )

        val stamped =
            streams.map {
                stampVidSrcStream(it)
            }

        return VidSrcResult(
            streams = stamped,
            subtitles = emptyList(),
            referer = "https://vidsrc.sh/"
        )
    }

    private fun decryptVidSrcWasm(
        wasmBytes: ByteArray,
        encryptedBase64: String
    ): String {
        val encrypted =
            Base64.decode(
                encryptedBase64,
                Base64.DEFAULT
            )

        val wasmModule =
            module(wasmBytes)
                .expect("Failed to decode VidSrc WASM")

        val wasmStore = store()

        val instance =
            instance(
                wasmStore,
                wasmModule
            ).expect(
                "Failed to instantiate VidSrc WASM"
            )

        val memory =
            instance.exports
                .firstNotNullOfOrNull {
                    it.value as? Memory
                }
                ?: throw IllegalStateException(
                    "VidSrc WASM memory export not found"
                )

        val allocResult =
            invoke(
                wasmStore,
                instance,
                "alloc",
                listOf(
                    NumberValue.I32(
                        encrypted.size
                    )
                )
            ).expect(
                "VidSrc WASM alloc failed"
            )

        val ptr =
            (allocResult.firstOrNull()
                as? NumberValue.I32)
                ?.value
                ?: throw IllegalStateException(
                    "VidSrc WASM alloc returned no i32"
                )

        encrypted.forEachIndexed { index, byte ->
            writeByte(
                wasmStore,
                memory,
                ptr + index,
                byte
            )
        }

        val decryptResult =
            invoke(
                wasmStore,
                instance,
                "decrypt",
                listOf(
                    NumberValue.I32(ptr),
                    NumberValue.I32(encrypted.size)
                )
            ).expect(
                "VidSrc WASM decrypt failed"
            )

        val outLen =
            (decryptResult.firstOrNull()
                as? NumberValue.I32)
                ?.value
                ?: throw IllegalStateException(
                    "VidSrc WASM decrypt returned no length"
                )

        if (outLen <= 0) {
            throw IllegalStateException(
                "VidSrc WASM decrypt returned invalid length=$outLen"
            )
        }

        val output =
            ByteArray(outLen)

        for (index in 0 until outLen) {
            output[index] =
                readByte(
                    wasmStore,
                    memory,
                    ptr + 12 + index
                ).toByte()
        }

        return output.toString(Charsets.UTF_8)
    }

    private suspend fun stampVidSrcStream(
        streamUrl: String
    ): String {
        val uri = Uri.parse(streamUrl)

        val host =
            "${uri.scheme}://${uri.authority}"

        val tokenResponse = app.get(
            "$host/generate.php",
            headers = browserHeaders + mapOf(
                "Referer" to "https://vidsrc.sh/"
            )
        )

        val token =
            tokenResponse.text.trim()

        if (token.isBlank()) {
            throw StepFailure(
                "generate-token",
                "empty token host=${uri.host}"
            )
        }

        return if (streamUrl.contains("__TOKEN__")) {
            streamUrl.replace(
                "__TOKEN__",
                token
            )
        } else {
            "$streamUrl?token=${
                URLEncoder.encode(
                    token,
                    "UTF-8"
                )
            }"
        }
    }

    // ---------------------------------------------------------------
    // OLD CURRENT VidSrc chain
    // ---------------------------------------------------------------

    private suspend fun resolveVsSrc(
        embedUrl: String,
        allowWebView: Boolean
    ): VidSrcResult {
        val embedRes = app.get(
            embedUrl,
            headers = browserHeaders + mapOf(
                "Referer" to "$mainUrl/"
            )
        )

        val embedHtml = embedRes.text
        val embedPageUrl =
            embedRes.url.ifBlank { embedUrl }

        val dataApi =
            DATA_API_REGEX.find(embedHtml)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace("&amp;", "&")

        trace(
            "embed HTTP ${embedRes.code} " +
                "final=${Uri.parse(embedPageUrl).host} " +
                "data-api=${dataApi != null}"
        )

        var playerUrl: String? = null
        var reason = ""

        if (dataApi == null) {
            reason =
                "2-no-data-api HTTP ${embedRes.code} " +
                    "len=${embedHtml.length} " +
                    snippet(embedHtml)
        } else {
            val apiUrl =
                try {
                    URI(embedPageUrl)
                        .resolve(dataApi)
                        .toString()
                } catch (e: Exception) {
                    dataApi
                }

            val apiRes = app.get(
                apiUrl,
                headers = browserHeaders + mapOf(
                    "Referer" to embedPageUrl,
                    "Accept" to
                        "application/json, text/plain, */*",
                    "X-Requested-With" to
                        "XMLHttpRequest"
                )
            )

            playerUrl =
                try {
                    parseJson<VsSrcResponse>(
                        apiRes.text
                    ).src
                        ?.replace("\\/", "/")
                        ?.takeIf {
                            it.startsWith("http")
                        }
                } catch (e: Exception) {
                    if (e is CancellationException) {
                        throw e
                    }
                    null
                }

            if (playerUrl == null) {
                reason =
                    "3-vs_src HTTP ${apiRes.code} " +
                        snippet(apiRes.text)
            }
        }

        trace(
            "vs_src playerUrl=" +
                "${playerUrl?.let { Uri.parse(it).host }} " +
                reason.take(120)
        )

        if (allowWebView) {
            var sniffed: String? = null

            if (playerUrl != null) {
                trace(
                    "B1 webview on player " +
                        "(20s, mobile Chrome UA)"
                )

                sniffed =
                    sniffM3u8(
                        "B1",
                        playerUrl,
                        originOf(embedPageUrl),
                        20_000L,
                        MOBILE_CHROME_UA
                    )

                trace(
                    "B1 result=${sniffed?.take(90)}"
                )

                if (sniffed == null) {
                    reason +=
                        " webview-player-no-stream"
                }
            }

            if (sniffed == null) {
                trace(
                    "B2 webview on embed " +
                        "(25s, mobile Chrome UA)"
                )

                sniffed =
                    sniffM3u8(
                        "B2",
                        embedUrl,
                        "$mainUrl/",
                        25_000L,
                        MOBILE_CHROME_UA
                    )

                trace(
                    "B2 result=${sniffed?.take(90)}"
                )

                if (sniffed == null) {
                    reason +=
                        " webview-embed-no-stream"
                }
            }

            if (sniffed == null) {
                trace(
                    "B3 webview on embed " +
                        "(20s, desktop UA)"
                )

                sniffed =
                    sniffM3u8(
                        "B3",
                        embedUrl,
                        "$mainUrl/",
                        20_000L,
                        userAgent
                    )

                trace(
                    "B3 result=${sniffed?.take(90)}"
                )

                if (sniffed == null) {
                    reason +=
                        " webview-desktop-no-stream"
                }
            }

            if (sniffed != null) {
                val referer =
                    pickReferer(
                        sniffed,
                        listOfNotNull(
                            playerUrl?.let {
                                originOf(it)
                            },
                            originOf(sniffed),
                            originOf(embedPageUrl),
                            "https://vidsrc.me/",
                            "$mainUrl/"
                        )
                    )

                return VidSrcResult(
                    listOf(sniffed),
                    emptyList(),
                    referer
                )
            }
        }

        throw StepFailure(
            "vs_src-chain",
            reason
        )
    }

    // ---------------------------------------------------------------
    // Trace + playlist checking
    // ---------------------------------------------------------------

    private fun trace(msg: String) {
        val line =
            "${System.currentTimeMillis() - traceStart}ms $msg"

        traceLines.add(line)

        Log.e(
            "HayyaShoot",
            line
        )
    }

    private suspend fun playlistCheck(
        url: String,
        referer: String,
        ua: String = userAgent
    ): Pair<Boolean, String> =
        try {
            withTimeoutOrNull(6_000L) {
                val res = app.get(
                    url,
                    headers = mapOf(
                        "User-Agent" to ua,
                        "Referer" to referer
                    )
                )

                val ok =
                    res.code in 200..299 &&
                        res.text
                            .trimStart()
                            .startsWith("#EXTM3U")

                ok to
                    "HTTP ${res.code} " +
                    "len=${res.text.length} " +
                    if (ok) {
                        "PLAYLIST"
                    } else {
                        snippet(res.text)
                    }
            } ?: (
                false to
                    "timeout 6s"
                )
        } catch (e: Exception) {
            if (e is CancellationException) {
                throw e
            }

            false to
                "exception " +
                e::class.java.simpleName
        }

    private suspend fun isPlaylist(
        url: String,
        referer: String
    ): Boolean =
        playlistCheck(
            url,
            referer
        ).first

    // ---------------------------------------------------------------
    // Legacy cloudnestra chain
    // ---------------------------------------------------------------

    private suspend fun resolveVidSrc(
        embedUrl: String
    ): VidSrcResult {
        val embedRes = app.get(
            embedUrl,
            headers = browserHeaders + mapOf(
                "Referer" to "$mainUrl/"
            )
        )

        val embedHtml = embedRes.text

        val embedPageUrl =
            embedRes.url.ifBlank { embedUrl }

        var rcpUrl =
            RCP_REGEX.find(embedHtml)
                ?.groupValues
                ?.getOrNull(1)
                ?: IFRAME_REGEX.find(embedHtml)
                    ?.groupValues
                    ?.getOrNull(1)
                ?: throw StepFailure(
                    "2-no-iframe",
                    "HTTP ${embedRes.code} " +
                        "len=${embedHtml.length} " +
                        snippet(embedHtml)
                )

        if (rcpUrl.startsWith("//")) {
            rcpUrl = "https:$rcpUrl"
        } else if (rcpUrl.startsWith("/")) {
            val pageUri =
                Uri.parse(embedPageUrl)

            rcpUrl =
                "${pageUri.scheme}://" +
                    "${pageUri.authority}$rcpUrl"
        }

        val rcpRes = app.get(
            rcpUrl,
            headers = browserHeaders + mapOf(
                "Referer" to embedPageUrl
            )
        )

        val rcpHtml = rcpRes.text

        val prorcpPath =
            PRORCP_PATH_REGEX.find(rcpHtml)?.value
                ?: SRCRCP_PATH_REGEX
                    .find(rcpHtml)
                    ?.value
                ?: throw StepFailure(
                    "4-no-prorcp",
                    "HTTP ${rcpRes.code} " +
                        "rcp=${Uri.parse(rcpUrl).host} " +
                        "len=${rcpHtml.length} " +
                        snippet(rcpHtml)
                )

        val rcpUri =
            Uri.parse(rcpUrl)

        val rcpOrigin =
            "${rcpUri.scheme}://${rcpUri.authority}"

        val prorcpRes = app.get(
            "$rcpOrigin$prorcpPath",
            headers = browserHeaders + mapOf(
                "Referer" to rcpUrl
            )
        )

        val prorcpHtml =
            prorcpRes.text

        val rawFile =
            FILE_REGEX.find(prorcpHtml)
                ?.groupValues
                ?.getOrNull(1)
                ?: M3U8_REGEX.find(
                    prorcpHtml.replace("\\/", "/")
                )?.value
                ?: PL_REGEX.find(
                    prorcpHtml.replace("\\/", "/")
                )?.value
                ?: throw StepFailure(
                    "6-no-file",
                    "HTTP ${prorcpRes.code} " +
                        "len=${prorcpHtml.length} " +
                        snippet(prorcpHtml)
                )

        val rcpRoot =
            (rcpUri.host ?: "")
                .split(".")
                .takeLast(2)
                .joinToString(".")

        val placeholderDomains =
            listOf(
                rcpRoot,
                "cloudnestra.com"
            )
                .filter { it.isNotBlank() }
                .distinct()

        val streams =
            rawFile
                .split(" or ")
                .flatMap { alt ->
                    placeholderDomains.map { domain ->
                        alt.trim()
                            .replace(
                                PLACEHOLDER_REGEX,
                                domain
                            )
                    }
                }
                .filter {
                    it.startsWith("http")
                }
                .distinct()

        if (streams.isEmpty()) {
            throw StepFailure(
                "7-bad-url",
                rawFile.take(150)
            )
        }

        val subtitles =
            SUBTITLE_REGEX
                .findAll(
                    prorcpHtml.replace("\\/", "/")
                )
                .map {
                    it.groupValues[1]
                }
                .distinct()
                .toList()

        return VidSrcResult(
            streams,
            subtitles,
            "$rcpOrigin/"
        )
    }

    // ---------------------------------------------------------------
    // Manual stream
    // ---------------------------------------------------------------

    private suspend fun loadManual(
        streamUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        traceStart =
            System.currentTimeMillis()

        traceLines.clear()

        trace(
            "$buildTag manual " +
                "host=${Uri.parse(streamUrl).host}"
        )

        val referers =
            listOf(
                "https://cloudorchestranova.com/",
                "https://vidsrc.sh/",
                originOf(streamUrl),
                "https://vidsrc.me/",
                "$mainUrl/"
            ).distinct()

        suspend fun emit(
            referer: String,
            label: String
        ) {
            callback(
                newExtractorLink(
                    source = name,
                    name = label,
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = referer
                    this.quality =
                        Qualities.Unknown.value

                    this.headers =
                        mapOf(
                            "User-Agent" to
                                MOBILE_CHROME_UA,
                            "Origin" to
                                referer.trimEnd('/')
                        )
                }
            )
        }

        var emitted = 0

        for (referer in referers) {
            val (ok, why) =
                playlistCheck(
                    streamUrl,
                    referer,
                    MOBILE_CHROME_UA
                )

            trace(
                "referer " +
                    "${Uri.parse(referer).host} -> $why"
            )

            if (ok) {
                emit(
                    referer,
                    "Manual (${Uri.parse(referer).host})"
                )

                emitted++
            }
        }

        if (emitted == 0) {
            referers.forEach { referer ->
                emit(
                    referer,
                    "Manual try ${Uri.parse(referer).host}"
                )
            }

            emitted =
                referers.size
        }

        lastDiagnostic =
            traceLines
                .toList()
                .joinToString("\n")

        return emitted > 0
    }

    // ---------------------------------------------------------------
    // loadLinks
    // ---------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.startsWith("manual:")) {
            return loadManual(
                data.removePrefix("manual:"),
                callback
            )
        }

        val media =
            try {
                parseJson<HayyaMediaData>(data)
            } catch (e: Exception) {
                if (e is CancellationException) {
                    throw e
                }

                if (debugMode) {
                    reportFailure(
                        callback,
                        "0-bad-data ${data.take(80)}"
                    )
                }

                return false
            }

        traceStart =
            System.currentTimeMillis()

        traceLines.clear()

        trace(
            "$buildTag start " +
                "${media.type} " +
                "id=${media.id} " +
                "s=${media.season} " +
                "e=${media.episode}"
        )

        val failures =
            ArrayList<String>()

        var found = false

        Log.e(
            "HayyaShoot",
            "loadLinks start " +
                "type=${media.type} " +
                "id=${media.id} " +
                "s=${media.season} " +
                "e=${media.episode}"
        )

        // -----------------------------------------------------------
        // NEW CURRENT VIDSRС API
        // -----------------------------------------------------------

        try {
            val currentResult =
                resolveCurrentVidSrc(media)

            val playable =
                currentResult.streams.filter {
                    isPlaylist(
                        it,
                        currentResult.referer
                    )
                }

            val toEmit =
                playable.ifEmpty {
                    currentResult.streams
                }

            if (toEmit.isNotEmpty()) {
                toEmit.forEachIndexed {
                    index,
                    streamUrl ->

                    callback(
                        newExtractorLink(
                            source = name,
                            name =
                                if (toEmit.size > 1) {
                                    "VidSrc ${index + 1}"
                                } else {
                                    "VidSrc"
                                },
                            url = streamUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer =
                                currentResult.referer

                            this.quality =
                                Qualities.Unknown.value

                            this.headers =
                                mapOf(
                                    "User-Agent" to
                                        userAgent
                                )
                        }
                    )
                }

                currentResult.subtitles.forEach {
                    subtitleUrl ->

                    subtitleCallback(
                        SubtitleFile(
                            "Arabic",
                            subtitleUrl
                        )
                    )
                }

                found = true

                trace(
                    "CURRENT VIDSRС OK " +
                        "streams=${toEmit.size}"
                )

                Log.e(
                    "HayyaShoot",
                    "CURRENT VIDSRС OK " +
                        "streams=${toEmit.size}"
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) {
                throw e
            }

            val why =
                if (e is StepFailure) {
                    "${e.step} ${e.detail}"
                } else {
                    "${e::class.java.simpleName}: " +
                        e.message
                }

            failures.add(
                "current-vidsrc $why"
            )

            trace(
                "CURRENT VIDSRС FAIL $why"
            )

            Log.e(
                "HayyaShoot",
                "CURRENT VIDSRС FAIL $why"
            )
        }

        // -----------------------------------------------------------
        // Existing fallback chain — unchanged
        // -----------------------------------------------------------

        if (!found) {
            val candidates =
                embedCandidates(media)

            val completed =
                withTimeoutOrNull(90_000L) {
                    for (
                        (index, embedUrl)
                        in candidates.withIndex()
                    ) {
                        val host =
                            Uri.parse(embedUrl).host
                                ?: embedUrl

                        try {
                            val result =
                                try {
                                    resolveVsSrc(
                                        embedUrl,
                                        allowWebView =
                                            index == 0
                                    )
                                } catch (e: Exception) {
                                    if (
                                        e is CancellationException
                                    ) {
                                        throw e
                                    }

                                    val why =
                                        if (
                                            e is StepFailure
                                        ) {
                                            "${e.step} " +
                                                e.detail
                                        } else {
                                            "${e::class.java.simpleName}: " +
                                                e.message
                                        }

                                    failures.add(
                                        "$host $why"
                                    )

                                    Log.e(
                                        "HayyaShoot",
                                        "vs_src chain failed: $why"
                                    )

                                    resolveVidSrc(
                                        embedUrl
                                    )
                                }

                            val playable =
                                result.streams.filter {
                                    isPlaylist(
                                        it,
                                        result.referer
                                    )
                                }

                            val toEmit =
                                playable.ifEmpty {
                                    result.streams
                                }

                            Log.e(
                                "HayyaShoot",
                                "streams=${result.streams.size} " +
                                    "playable=${playable.size}"
                            )

                            if (
                                playable.isEmpty() &&
                                debugMode
                            ) {
                                val hosts =
                                    result.streams
                                        .mapNotNull {
                                            Uri.parse(it).host
                                        }
                                        .distinct()
                                        .take(4)
                                        .joinToString(",")

                                reportFailure(
                                    callback,
                                    "8-unreachable hosts=$hosts"
                                )
                            }

                            toEmit.forEachIndexed {
                                linkIndex,
                                streamUrl ->

                                callback(
                                    newExtractorLink(
                                        source = name,
                                        name =
                                            if (
                                                toEmit.size > 1
                                            ) {
                                                "VidSrc ${linkIndex + 1}"
                                            } else {
                                                "VidSrc"
                                            },
                                        url = streamUrl,
                                        type =
                                            ExtractorLinkType.M3U8
                                    ) {
                                        this.referer =
                                            result.referer

                                        this.quality =
                                            Qualities.Unknown.value

                                        this.headers =
                                            mapOf(
                                                "User-Agent" to
                                                    userAgent
                                            )
                                    }
                                )
                            }

                            result.subtitles.forEach {
                                subtitleUrl ->

                                subtitleCallback(
                                    SubtitleFile(
                                        "Arabic",
                                        subtitleUrl
                                    )
                                )
                            }

                            found = true

                            Log.e(
                                "HayyaShoot",
                                "OK host=$host " +
                                    "streams=${result.streams.size} " +
                                    "subs=${result.subtitles.size}"
                            )

                            break
                        } catch (e: Exception) {
                            if (
                                e is CancellationException
                            ) {
                                throw e
                            }

                            if (
                                e is StepFailure
                            ) {
                                failures.add(
                                    "$host " +
                                        "${e.step} " +
                                        e.detail
                                )
                            } else {
                                logError(e)

                                failures.add(
                                    "$host exception " +
                                        "${e::class.java.simpleName}: " +
                                        e.message
                                )
                            }

                            Log.e(
                                "HayyaShoot",
                                "FAIL ${failures.last()}"
                            )
                        }
                    }

                    true
                }

            if (completed == null) {
                failures.add(
                    "timeout-90s " +
                        "(still running, stopped)"
                )
            }
        }

        // -----------------------------------------------------------
        // Last resort
        // -----------------------------------------------------------

        if (!found) {
            try {
                found =
                    withTimeoutOrNull(15_000L) {
                        loadExtractor(
                            embedCandidates(media).first(),
                            "$mainUrl/",
                            subtitleCallback,
                            callback
                        )
                    } ?: false
            } catch (e: Exception) {
                if (e is CancellationException) {
                    throw e
                }

                logError(e)
            }
        }

        if (!found && debugMode) {
            if (failures.isEmpty()) {
                reportFailure(
                    callback,
                    "$buildTag no-failure-info " +
                        "${media.type} id=${media.id}"
                )
            } else {
                failures
                    .take(4)
                    .forEach {
                        reportFailure(
                            callback,
                            "$buildTag $it"
                        )
                    }
            }
        }

        trace(
            "end found=$found"
        )

        lastDiagnostic =
            buildString {
                traceLines
                    .toList()
                    .forEach {
                        append(it)
                            .append('\n')
                    }

                if (failures.isNotEmpty()) {
                    append(
                        "--- failures ---\n"
                    )
                }

                failures.forEach {
                    append("- ")
                        .append(it)
                        .append('\n')
                }
            }

        return found
    }

    // ---------------------------------------------------------------
    // Regexes
    // ---------------------------------------------------------------

    private companion object {

        @Volatile
        var lastDiagnostic: String? = null

        @Volatile
        var traceStart: Long = 0L

        val traceLines: MutableList<String> =
            java.util.Collections.synchronizedList(
                ArrayList<String>()
            )

        val RCP_REGEX = Regex(
            """src=["']((?:https?:)?//[^"']*cloudnestra\.com/rcp/[^"']+)["']""",
            RegexOption.IGNORE_CASE
        )

        val PRORCP_REGEX =
            Regex("""/prorcp/([a-zA-Z0-9=+/]+)""")

        val PRORCP_PATH_REGEX =
            Regex("""/prorcp/[a-zA-Z0-9=+/]+""")

        val SRCRCP_PATH_REGEX =
            Regex("""/srcrcp/[a-zA-Z0-9=+/_-]+""")

        val IFRAME_REGEX = Regex(
            """<iframe[^>]+src=["']((?:https?:)?//[^"']+)["']""",
            RegexOption.IGNORE_CASE
        )

        val M3U8_REGEX =
            Regex("""https?://[^"'\s\\]+\.m3u8[^"'\s\\]*""")

        val PL_REGEX = Regex(
            """https?://[^"'\s\\]+/p[li]/H4s[il][^"'\s\\]*""",
            RegexOption.IGNORE_CASE
        )

        const val MOBILE_CHROME_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                "AppleWebKit/537.36 " +
                "(KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"

        const val CLICK_SCRIPT = """
            (function () {
              var n = 0;
              var t = setInterval(function () {
                n++;
                try {
                  var sels = ['.jw-icon-display',
                              '.vjs-big-play-button',
                              '.plyr__control--overlaid',
                              '.play-button',
                              '.play',
                              '#play',
                              '[aria-label="Play"]',
                              'button[title*="Play"]'];

                  sels.forEach(function (s) {
                    document.querySelectorAll(s).forEach(function (e) {
                      try { e.click(); } catch (x) {}
                    });
                  });

                  document.querySelectorAll('video').forEach(function (v) {
                    try {
                      v.muted = true;
                      v.play();
                    } catch (x) {}
                  });

                  var el = document.elementFromPoint(
                    window.innerWidth / 2,
                    window.innerHeight / 2
                  );

                  if (el) {
                    ['mousedown', 'mouseup', 'click']
                      .forEach(function (ev) {
                        try {
                          el.dispatchEvent(
                            new MouseEvent(ev, {
                              bubbles: true,
                              cancelable: true,
                              view: window
                            })
                          );
                        } catch (x) {}
                      });
                  }
                } catch (e) {}

                if (n > 20) {
                  clearInterval(t);
                }
              }, 700);
            })();
        """

        val STREAM_URL_REGEX =
            Regex(
                """\.m3u8|/p[li]/H4s[il]""",
                RegexOption.IGNORE_CASE
            )

        val FILE_REGEX =
            Regex("""file:\s*["']([^"']+)["']""")

        val DATA_API_REGEX =
            Regex(
                """data-api=["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            )

        val PLACEHOLDER_REGEX =
            Regex("""\{v[1-5]\}""")

        val SUBTITLE_REGEX =
            Regex(
                """["'](https?://[^"']+\.(?:vtt|srt))["']""",
                RegexOption.IGNORE_CASE
            )
    }

    // ---------------------------------------------------------------
    // Data classes
    // ---------------------------------------------------------------

    data class HayyaMediaData(
        val type: String,
        val id: Int,
        val season: Int? = null,
        val episode: Int? = null
    )

    data class VidSrcApiResponse(
        @JsonProperty("data")
        val data: VidSrcApiData? = null,

        @JsonProperty("vs")
        val vs: VidSrcWasm? = null
    )

    data class VidSrcApiData(
        @JsonProperty("stream_urls")
        val streamUrls: Any? = null
    )

    data class VidSrcWasm(
        @JsonProperty("wasm_url")
        val wasmUrl: String? = null,

        @JsonProperty("wasm")
        val wasm: String? = null
    )

    data class VsSrcResponse(
        @JsonProperty("src")
        val src: String? = null
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
