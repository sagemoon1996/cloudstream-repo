package com.sagemoon1996.hayyashoot

import android.net.Uri
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
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
    override var name = "HayyaShoot"
    override var lang = "ar"

    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // Same sources as the original site: /movie/popular and /tv/popular
    override val mainPage = mainPageOf(
        "movie/popular" to "أفلام",
        "tv/popular" to "مسلسلات"
    )

    private val posterBase = "https://image.tmdb.org/t/p/w500"
    private val backdropBase = "https://image.tmdb.org/t/p/original"

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0"

    // Same headers as @definisi/vidsrc-scraper 2.0.2
    private val browserHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
        "Accept-Encoding" to "identity"
    )

    // ---------------------------------------------------------------
    // TMDB token: read from the site at runtime, cached, never hardcoded
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

        // 1) inline <script> in the page
        extractToken(page)?.let { return it }

        // 2) external JS files hosted on the site itself
        val scriptRegex = Regex(
            """<script[^>]+src=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )

        for (match in scriptRegex.findAll(page)) {
            val scriptUrl = fixUrl(match.groupValues[1])
            val host = Uri.parse(scriptUrl).host ?: continue
            if (!host.endsWith("hayyashoot.com")) continue

            try {
                extractToken(app.get(scriptUrl, headers = browserHeaders).text)
                    ?.let { return it }
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

        // Token rotated on the site -> refresh once and retry
        if (response.code == 401 || response.code == 403) {
            response = requestTmdb(path, getAuthToken(forceRefresh = true))
        }

        return response.text
    }

    // ---------------------------------------------------------------
    // URL + card helpers (same URL format as the site)
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

        // Debug only: a card that opens the last loadLinks trace as text
        // Always shown in debug mode: seeing "DIAG build-N" proves which build runs.
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
    // Search (movies + TV in parallel)
    // ---------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        val q = URLEncoder.encode(trimmed, "UTF-8")

        return coroutineScope {
            val movies = async { fetchList("search/movie?query=$q&language=ar-SA&page=1") }
            val shows = async { fetchList("search/tv?query=$q&language=ar-SA&page=1") }

            val movieResults = movies.await()?.results.orEmpty()
                .mapNotNull { toSearchResponse(it, true) }
            val showResults = shows.await()?.results.orEmpty()
                .mapNotNull { toSearchResponse(it, false) }

            (movieResults + showResults).distinctBy { it.url }
        }
    }

    // ---------------------------------------------------------------
    // Load: details, seasons, episodes
    // ---------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
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
        val movie = parseJson<TmdbMovie>(tmdbGet("movie/$id?language=ar-SA"))
        val title = movie.title?.takeIf { it.isNotBlank() } ?: return null

        val data = HayyaMediaData(type = "movie", id = id).toJson()

        return newMovieLoadResponse(title, url, TvType.Movie, data) {
            this.posterUrl = movie.posterPath?.let { posterBase + it }
            this.backgroundPosterUrl = movie.backdropPath?.let { backdropBase + it }
            this.plot = movie.overview
            this.year = yearOf(movie.releaseDate)
        }
    }

    private suspend fun loadTv(url: String, id: Int): LoadResponse? {
        val tv = parseJson<TmdbTv>(tmdbGet("tv/$id?language=ar-SA"))
        val title = tv.name?.takeIf { it.isNotBlank() } ?: return null

        // Site logic: seasons.filter(season_number > 0); also skip empty seasons
        val seasons = tv.seasons
            .orEmpty()
            .filter { it.seasonNumber > 0 && (it.episodeCount ?: 1) > 0 }

        // All seasons fetched in parallel
        val seasonData = coroutineScope {
            seasons.map { season ->
                async {
                    try {
                        season.seasonNumber to parseJson<TmdbSeason>(
                            tmdbGet("tv/$id/season/${season.seasonNumber}?language=ar-SA")
                        )
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        logError(e)
                        null
                    }
                }
            }.awaitAll()
        }.filterNotNull().sortedBy { it.first }

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
                    this.posterUrl = ep.stillPath?.let { posterBase + it }
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = tv.posterPath?.let { posterBase + it }
            this.backgroundPosterUrl = tv.backdropPath?.let { backdropBase + it }
            this.plot = tv.overview
            this.year = yearOf(tv.firstAirDate)
        }
    }

    // ---------------------------------------------------------------
    // loadLinks: Server 2 only = VidSrc
    // ---------------------------------------------------------------

    private val debugMode = true

    // New build marker
    private val buildTag = "build-15"

    private class StepFailure(val step: String, val detail: String) :
        Exception("$step $detail")

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
        val label = "DEBUG $text".replace(Regex("""\s+"""), " ").take(220)
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

    // Server 2 = VidSrc.
    private fun embedCandidates(media: HayyaMediaData): List<String> {
        val isTv = media.type == "tv" && media.season != null && media.episode != null

        return if (isTv) {
            val s = media.season
            val e = media.episode

            listOf(
                "https://vidsrc.me/embed/tv?tmdb=${media.id}&season=$s&episode=$e&sub=ar",
                "https://vidsrc-embed.ru/embed/tv/${media.id}/$s/$e",
                "https://vidsrc.xyz/embed/tv?tmdb=${media.id}&season=$s&episode=$e&sub=ar",
                "https://vidsrc.net/embed/tv?tmdb=${media.id}&season=$s&episode=$e&sub=ar"
            )
        } else {
            listOf(
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
        val list = candidates.filter { it.isNotBlank() }.distinct()

        for (candidate in list) {
            val (ok, why) = playlistCheck(streamUrl, candidate)
            trace("referer ${Uri.parse(candidate).host} -> $why")

            if (ok) return candidate
        }

        return list.firstOrNull() ?: "$mainUrl/"
    }

    private fun originOf(url: String): String {
        val u = Uri.parse(url)
        return "${u.scheme}://${u.authority}/"
    }

    // Loads the page in CloudStream's hidden WebView and captures
    // the first real HLS/CloudOrchestra stream request.
    private suspend fun sniffM3u8(
        url: String,
        referer: String,
        timeoutMs: Long = 20_000L
    ): String? =
        try {
            withTimeoutOrNull(timeoutMs) {
                val res = app.get(
                    url,
                    referer = referer,
                    interceptor = WebViewResolver(STREAM_URL_REGEX)
                )

                val captured = res.url

                trace(
                    "interceptor result host=${Uri.parse(captured).host} " +
                        "match=${STREAM_URL_REGEX.containsMatchIn(captured)}"
                )

                captured.takeIf {
                    STREAM_URL_REGEX.containsMatchIn(it)
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logError(e)
            trace("sniff exception ${e::class.java.simpleName}: ${e.message}")
            null
        }

    // Current VidSrc chain:
    // embed -> data-api -> CloudOrchestra iframe -> generated stream.
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
        val embedPageUrl = embedRes.url.ifBlank { embedUrl }

        val dataApi = DATA_API_REGEX.find(embedHtml)
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
                    "len=${embedHtml.length} ${snippet(embedHtml)}"
        } else {
            val apiUrl = try {
                URI(embedPageUrl).resolve(dataApi).toString()
            } catch (e: Exception) {
                dataApi
            }

            val apiRes = app.get(
                apiUrl,
                headers = browserHeaders + mapOf(
                    "Referer" to embedPageUrl,
                    "Accept" to "application/json, text/plain, */*",
                    "X-Requested-With" to "XMLHttpRequest"
                )
            )

            playerUrl = try {
                parseJson<VsSrcResponse>(apiRes.text)
                    .src
                    ?.replace("\\/", "/")
                    ?.takeIf { it.startsWith("http") }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
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

            /*
             * B1:
             * Open the dynamically generated CloudOrchestra player.
             *
             * We DO NOT open its URL manually.
             * The WebView receives it with the VidSrc referer.
             */
            if (playerUrl != null) {
                trace("B1 webview on player (35s)")

                sniffed = sniffM3u8(
                    playerUrl,
                    originOf(embedPageUrl),
                    35_000L
                )

                trace("B1 result=${sniffed?.take(180)}")

                if (sniffed == null) {
                    reason += " webview-player-no-stream"
                }
            }

            /*
             * B2:
             * Full VidSrc embedded flow.
             */
            if (sniffed == null) {
                trace("B2 webview on embed (35s)")

                sniffed = sniffM3u8(
                    embedUrl,
                    "$mainUrl/",
                    35_000L
                )

                trace("B2 result=${sniffed?.take(180)}")

                if (sniffed == null) {
                    reason += " webview-embed-no-stream"
                }
            }

            /*
             * IMPORTANT:
             *
             * Do NOT call playlistCheck() here.
             *
             * The URL was captured from the browser/player session.
             * A separate HTTP request can lose the session/referer/cookies.
             */
            if (!sniffed.isNullOrBlank()) {
                val referer = when {
                    playerUrl != null ->
                        originOf(playerUrl)

                    else ->
                        originOf(embedPageUrl)
                }

                return VidSrcResult(
                    streams = listOf(sniffed),
                    subtitles = emptyList(),
                    referer = referer
                )
            }
        }

        throw StepFailure(
            "vs_src-chain",
            reason
        )
    }

    private fun trace(msg: String) {
        val line = "${System.currentTimeMillis() - traceStart}ms $msg"
        traceLines.add(line)
        Log.e("HayyaShoot", line)
    }

    private suspend fun playlistCheck(
        url: String,
        referer: String
    ): Pair<Boolean, String> =
        try {
            withTimeoutOrNull(6_000L) {
                val res = app.get(
                    url,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to referer
                    )
                )

                val ok =
                    res.code in 200..299 &&
                        res.text.trimStart().startsWith("#EXTM3U")

                ok to
                    "HTTP ${res.code} len=${res.text.length} " +
                        if (ok) {
                            "PLAYLIST"
                        } else {
                            snippet(res.text)
                        }
            } ?: (false to "timeout 6s")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            false to "exception ${e::class.java.simpleName}"
        }

    private suspend fun isPlaylist(
        url: String,
        referer: String
    ): Boolean =
        playlistCheck(url, referer).first

    // Legacy CloudOrchestra/RCP resolver kept intact as a fallback helper.
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
            val pageUri = Uri.parse(embedPageUrl)
            rcpUrl =
                "${pageUri.scheme}://${pageUri.authority}$rcpUrl"
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
                ?: SRCRCP_PATH_REGEX.find(rcpHtml)?.value
                ?: throw StepFailure(
                    "4-no-prorcp",
                    "HTTP ${rcpRes.code} " +
                        "rcp=${Uri.parse(rcpUrl).host} " +
                        "len=${rcpHtml.length} " +
                        snippet(rcpHtml)
                )

        val rcpUri = Uri.parse(rcpUrl)
        val rcpOrigin =
            "${rcpUri.scheme}://${rcpUri.authority}"

        val prorcpRes = app.get(
            "$rcpOrigin$prorcpPath",
            headers = browserHeaders + mapOf(
                "Referer" to rcpUrl
            )
        )

        val prorcpHtml = prorcpRes.text

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
    // loadLinks
    // ---------------------------------------------------------------

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

            if (debugMode) {
                reportFailure(
                    callback,
                    "0-bad-data ${data.take(80)}"
                )
            }

            return false
        }

        traceStart = System.currentTimeMillis()
        traceLines.clear()

        trace(
            "$buildTag start " +
                "${media.type} " +
                "id=${media.id} " +
                "s=${media.season} " +
                "e=${media.episode}"
        )

        val candidates = embedCandidates(media)
        val failures = ArrayList<String>()

        Log.e(
            "HayyaShoot",
            "loadLinks start " +
                "type=${media.type} " +
                "id=${media.id} " +
                "s=${media.season} " +
                "e=${media.episode}"
        )

        /*
         * Main path:
         *
         * VidSrc embed
         * -> CloudOrchestra
         * -> WebView
         * -> captured /pl/... or /pI/... or .m3u8
         *
         * IMPORTANT:
         * The captured URL is emitted immediately.
         * We do NOT perform another HTTP playlistCheck().
         */
        val completed =
            withTimeoutOrNull(75_000L) {

                for ((index, embedUrl) in candidates.withIndex()) {
                    val host =
                        Uri.parse(embedUrl).host
                            ?: embedUrl

                    try {
                        trace(
                            "TRY $host index=$index"
                        )

                        val result =
                            resolveVsSrc(
                                embedUrl,
                                allowWebView = true
                            )

                        if (result.streams.isEmpty()) {
                            throw StepFailure(
                                "empty-streams",
                                host
                            )
                        }

                        /*
                         * Emit exactly what the WebView captured.
                         *
                         * No playlistCheck().
                         * No second HTTP request.
                         */
                        result.streams.forEachIndexed {
                            streamIndex,
                            streamUrl ->

                            trace(
                                "EMIT stream " +
                                    streamUrl.take(220)
                            )

                            callback(
                                newExtractorLink(
                                    source = name,
                                    name =
                                        if (result.streams.size > 1) {
                                            "VidSrc ${streamIndex + 1}"
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
                                            "User-Agent" to userAgent
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

                        trace(
                            "OK host=$host " +
                                "streams=${result.streams.size} " +
                                "subs=${result.subtitles.size}"
                        )

                        true
                    } catch (e: Exception) {
                        if (e is CancellationException) {
                            throw e
                        }

                        val why =
                            if (e is StepFailure) {
                                "${e.step} ${e.detail}"
                            } else {
                                logError(e)
                                "exception " +
                                    "${e::class.java.simpleName}: " +
                                    e.message
                            }

                        failures.add(
                            "$host $why"
                        )

                        trace(
                            "FAIL $host $why"
                        )

                        false
                    }.let { success ->
                        if (success) {
                            return@withTimeoutOrNull true
                        }
                    }
                }

                false
            } ?: false

        /*
         * Built-in CloudStream extractor fallback.
         */
        if (!completed) {
            try {
                trace(
                    "BUILT-IN extractor"
                )

                val extracted =
                    withTimeoutOrNull(20_000L) {
                        loadExtractor(
                            candidates.first(),
                            "$mainUrl/",
                            subtitleCallback,
                            callback
                        )
                    } ?: false

                if (extracted) {
                    trace(
                        "BUILT-IN HIT"
                    )

                    lastDiagnostic =
                        traceLines.joinToString("\n")

                    return true
                }

                trace(
                    "BUILT-IN MISS"
                )
            } catch (e: Exception) {
                if (e is CancellationException) {
                    throw e
                }

                logError(e)

                failures.add(
                    "builtin " +
                        "${e::class.java.simpleName}: " +
                        e.message
                )

                trace(
                    "BUILT-IN FAIL " +
                        failures.last()
                )
            }
        }

        if (debugMode) {
            if (failures.isEmpty()) {
                reportFailure(
                    callback,
                    "$buildTag no-stream " +
                        "${media.type} " +
                        "id=${media.id}"
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
            "end found=false"
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

                    failures.forEach {
                        append("- ")
                            .append(it)
                            .append('\n')
                    }
                }
            }

        return false
    }

    // ---------------------------------------------------------------
    // Regexes copied from scraper.js 2.0.2
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

        val RCP_REGEX = Regex(
            """src=["']((?:https?:)?//[^"']*cloudnestra\.com/rcp/[^"']+)["']""",
            RegexOption.IGNORE_CASE
        )

        val PRORCP_REGEX = Regex(
            """/prorcp/([a-zA-Z0-9=+/]+)"""
        )

        val PRORCP_PATH_REGEX = Regex(
            """/prorcp/[a-zA-Z0-9=+/]+"""
        )

        val SRCRCP_PATH_REGEX = Regex(
            """/srcrcp/[a-zA-Z0-9=+/_-]+"""
        )

        val IFRAME_REGEX = Regex(
            """<iframe[^>]+src=["']((?:https?:)?//[^"']+)["']""",
            RegexOption.IGNORE_CASE
        )

        val M3U8_REGEX = Regex(
            """https?://[^"'\s\\]+\.m3u8[^"'\s\\]*"""
        )

        val PL_REGEX = Regex(
            """https?://[^"'\s\\]+/p[li]/H4s[il][^"'\s\\]*""",
            RegexOption.IGNORE_CASE
        )

        /*
         * Real network requests observed from the player:
         *
         *   /pl/H4s...
         *   /pI/H4s...
         *
         * Some requests may also end in .m3u8 with query parameters.
         */
        val STREAM_URL_REGEX = Regex(
            """(?:\.m3u8(?:\?[^"'\\\s]*)?|/p[li]/H4s[il][^"'\\\s]*)""",
            RegexOption.IGNORE_CASE
        )

        val FILE_REGEX = Regex(
            """file:\s*["']([^"']+)["']"""
        )

        val DATA_API_REGEX = Regex(
            """data-api=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )

        val PLACEHOLDER_REGEX = Regex(
            """\{v[1-5]\}"""
        )

        val SUBTITLE_REGEX = Regex(
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
