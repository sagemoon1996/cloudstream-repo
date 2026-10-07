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
import org.json.JSONObject
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

        // MANUAL MODE: paste a playlist URL (copied from the Via sniffer) in the
        // search box and get one card that plays it.
        if (trimmed.startsWith("http", ignoreCase = true) &&
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
        if (url.contains("/manual?u=")) {
            val streamUrl = Uri.parse(url).getQueryParameter("u") ?: return null
            return newMovieLoadResponse(
                "Pasted stream",
                url,
                TvType.Movie,
                "manual:$streamUrl"
            ) {
                this.plot = "Plays the playlist URL you pasted in the search box.\n" +
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
    // loadLinks: Server 2 only = @definisi/vidsrc-scraper 2.0.2 logic
    // ---------------------------------------------------------------

    // true  = if NO server gives a link, "DEBUG ..." entries appear in the
    //         links list (one per server tried) and explain which step failed.
    // false = production (no fake links).
    private val debugMode = true

    // Shown in the first DEBUG entry: if you do not see it, the app is still
    // running an OLD build of the plugin (bump `version` in build.gradle.kts).
    private val buildTag = "build-21"

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
            newExtractorLink(name, label, "https://debug.invalid/", ExtractorLinkType.VIDEO) {
                this.quality = Qualities.Unknown.value
            }
        )
    }

    // Server 2 = VidSrc. Same service on several hosts: the first host that
    // gives a stream wins. The first one is EXACTLY what hayyashoot.com puts in
    // its Server 2 iframe (switchServer(2)); the second is the scraper 2.0.2 host.
    private fun embedCandidates(media: HayyaMediaData): List<String> {
        val isTv = media.type == "tv" && media.season != null && media.episode != null
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

    // The playlist answers only with the right Referer (a download without it was
    // empty). Try the likely ones and keep the first that returns "#EXTM3U".
    private suspend fun pickReferer(streamUrl: String, candidates: List<String>): String {
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

    // Loads the page in CloudStream's hidden WebView and returns the first
    // .m3u8 request the page's own player makes (null if none shows up).
    // Replays the player's own protocol inside the player page and validates the
    // candidate playlist URLs it produces. Returns (playlistUrl, referer) or null.
    private suspend fun extractViaWasm(playerUrl: String, referer: String): Pair<String, String>? {
        trace("W1 wasm-extract in webview (30s)")
        val hitUrl: String? = try {
            withTimeoutOrNull(35_000L) {
                val resolver = WebViewResolver(
                    Regex("""hayya\.invalid/r\?d="""),
                    userAgent = MOBILE_CHROME_UA,
                    script = WASM_SCRIPT,
                    timeout = 30_000L
                )
                val (hit, _) = resolver.resolveUsingWebView(playerUrl, referer)
                hit?.url?.toString()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logError(e)
            trace("W1 exception ${e::class.java.simpleName}")
            null
        }

        if (hitUrl == null) {
            trace("W1 no payload (script did not report back)")
            return null
        }

        val json = try {
            val d = Regex("""[?&]d=([^&]+)""").find(hitUrl)?.groupValues?.getOrNull(1) ?: return null
            JSONObject(java.net.URLDecoder.decode(d, "UTF-8"))
        } catch (e: Exception) {
            trace("W1 bad payload ${e::class.java.simpleName}")
            return null
        }

        val logArr = json.optJSONArray("log")
        if (logArr != null) {
            for (i in 0 until logArr.length()) trace("W ${logArr.optString(i)}")
        }

        val candArr = json.optJSONArray("cands") ?: return null
        val refs = listOf(
            originOf(playerUrl),
            referer,
            "https://vidsrc.sh/",
            "$mainUrl/"
        ).distinct()

        for (i in 0 until candArr.length()) {
            val cand = candArr.optString(i)
            if (!cand.startsWith("http")) continue
            for (ref in refs) {
                val (ok, why) = playlistCheck(cand, ref)
                trace("W cand${i + 1} ref=${Uri.parse(ref).host} -> $why")
                if (ok) return cand to ref
            }
        }
        return null
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
                val (hit, others) = resolver.resolveUsingWebView(url, referer)

                // Everything else the WebView requested, to see how far the player got.
                // API / stream requests are logged in full (the 70-char cut hid the
                // value of "...&stream_url=").
                val seen = others
                    .map { it.url.toString().substringAfter("://") }
                    .distinct()
                trace("$label webview saw ${others.size} requests (${seen.size} distinct)")
                seen.take(40).forEach { r ->
                    val important = listOf("api.php", "stream_url", "generate", "wasm", "comityof", "/pl/", "/pi/")
                        .any { r.contains(it, ignoreCase = true) }
                    trace("$label req ${r.take(if (important) 420 else 70)}")
                }

                // The player itself reports the stream it got in
                // data.vidsrc.sh/api.php?...&stream_url=<url>: read it from the request.
                val fromParam = others.asSequence()
                    .map { it.url.toString() }
                    .mapNotNull { Regex("""[?&]stream_url=([^&]+)""").find(it)?.groupValues?.getOrNull(1) }
                    .map { java.net.URLDecoder.decode(it, "UTF-8") }
                    .firstOrNull { it.startsWith("http") }
                if (fromParam != null) trace("$label stream_url param = ${fromParam.take(160)}")

                hit?.url?.toString() ?: fromParam
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logError(e)
            trace("$label webview exception ${e::class.java.simpleName}: ${e.message}")
            null
        }

    // Current VidSrc chain (confirmed from the page):
    //   embed page -> <iframe id="player_iframe" data-api="/vs_src.php?...">
    //   -> GET data-api -> JSON {"src": "https://cloudorchestranova.com/embed/...?vs=..."}
    //   -> the player page only answers with a vidsrc Referer; opening it directly
    //      returns "Not Found", and the vs= token changes every time (never hardcode).
    // The final .m3u8 is requested by the player's JS, so if it is not visible in
    // the player HTML it is sniffed with a WebView.
    private fun bodySnippet(text: String, n: Int = 300) =
        text.take(n).replace(Regex("""\s+"""), " ")

    // A stream URL inside an API answer: /pl/H4sI..., .m3u8 or a stream_url/file field
    private fun findStreamIn(body: String): String? {
        val t = body.replace("\\/", "/")
        PL_REGEX.find(t)?.value?.let { return it }
        M3U8_REGEX.find(t)?.value?.let { return it }
        return JSON_STREAM_REGEX.find(t)?.groupValues?.getOrNull(1)
    }

    // The WebView request log showed the player calling
    //   https://data.vidsrc.sh/api.php?type=tv&tmdb=ID&season=S&episode=E&stream_url
    // Ask that API ourselves (same Referer/Origin as the player) and show the answer.
    private suspend fun probeStreamApi(vsSrcUrl: String?, playerOrigin: String): String? {
        val u = vsSrcUrl?.let { Uri.parse(it) } ?: return null
        val type = u.getQueryParameter("type") ?: return null
        val id = u.getQueryParameter("id") ?: return null
        val base = buildString {
            append("https://data.vidsrc.sh/api.php?type=").append(type)
            append("&tmdb=").append(id)
            if (type == "tv") {
                append("&season=").append(u.getQueryParameter("season") ?: "1")
                append("&episode=").append(u.getQueryParameter("episode") ?: "1")
            }
        }
        val headers = mapOf(
            "User-Agent" to MOBILE_CHROME_UA,
            "Accept" to "application/json, text/plain, */*",
            "Referer" to playerOrigin,
            "Origin" to playerOrigin.trimEnd('/')
        )

        var found: String? = null
        for (suffix in listOf("&stream_url", "")) {
            try {
                val res = withTimeoutOrNull(8_000L) { app.get(base + suffix, headers = headers) }
                if (res == null) {
                    trace("API$suffix timeout 8s")
                    continue
                }
                val body = res.text
                trace("API$suffix HTTP ${res.code} len=${body.length} ${bodySnippet(body)}")
                if (found == null) found = findStreamIn(body)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                trace("API$suffix exception ${e::class.java.simpleName}")
            }
        }
        return found
    }

    private val defaultKeywords =
        listOf("generate", "stream_url", "api.php", "comityof", "wasm", "m3u8", "/pl/")

    private fun traceKeywords(
        label: String,
        text: String,
        kws: List<String> = defaultKeywords,
        before: Int = 90,
        after: Int = 140,
        maxHits: Int = 2
    ) {
        val t = text.replace("\\/", "/")
        for (kw in kws) {
            var from = 0
            var hits = 0
            while (hits < maxHits) {
                val i = t.indexOf(kw, from, ignoreCase = true)
                if (i < 0) break
                val a = (i - before).coerceAtLeast(0)
                val b = (i + after).coerceAtMost(t.length)
                trace("$label $kw@$i: ${t.substring(a, b).replace(Regex("""\s+"""), " ")}")
                hits++
                from = i + kw.length
            }
        }
    }

    private fun compact(t: String) = t.replace(Regex("""\s+"""), " ")

    // Copies the trace to the clipboard (debug only) so it can be pasted in a chat
    // instead of photographing the screen.
    private fun copyToClipboard(text: String) {
        try {
            val ctx = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? android.content.Context ?: return
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("HayyaShoot DIAG", text))
                    android.widget.Toast.makeText(
                        ctx,
                        "HayyaShoot DIAG copied - paste it in the chat",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    // Diagnostic only: read the player page and its main JS once and show where
    // they talk about generate / stream_url / api.php, so the real protocol is visible.
    private suspend fun probePlayerCode(playerUrl: String, referer: String) {
        try {
            val headers = mapOf(
                "User-Agent" to MOBILE_CHROME_UA,
                "Accept" to "text/html,application/xhtml+xml,*/*",
                "Referer" to referer
            )
            val res = withTimeoutOrNull(8_000L) { app.get(playerUrl, headers = headers) }
            if (res == null) {
                trace("PAGE timeout 8s")
                return
            }
            val html = res.text
            trace("PAGE HTTP ${res.code} len=${html.length}")

            // window.CFG says where the real player (inner page) and the meta API are
            val cfgAt = html.indexOf("window.CFG")
            if (cfgAt >= 0) {
                trace("CFG ${compact(html.substring(cfgAt, (cfgAt + 700).coerceAtMost(html.length)))}")
            }

            // The inner player page is the one that talks to the stream API
            var innerHtml: String? = null
            val cfgPlayer = Regex(""""playerUrl"\s*:\s*"([^"]+)"""").find(html)
                ?.groupValues?.getOrNull(1)
                ?.replace("\\u0026", "&")
                ?.replace("\\/", "/")
            if (cfgPlayer != null) {
                val innerUrl = try {
                    URI(playerUrl).resolve(cfgPlayer).toString()
                } catch (e: Exception) {
                    null
                }
                if (innerUrl != null) {
                    val r2 = withTimeoutOrNull(8_000L) {
                        app.get(innerUrl, headers = headers + mapOf("Referer" to playerUrl))
                    }
                    innerHtml = r2?.text
                    trace("INNER HTTP ${r2?.code} len=${innerHtml?.length} ${innerUrl.substringAfter("://").take(110)}")
                    if (innerHtml != null) {
                        Regex("""<script(?![^>]*\bsrc=)[^>]*>(.*?)</script>""",
                            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                            .findAll(innerHtml)
                            .forEach { m ->
                                val body = compact(m.groupValues[1])
                                if (body.isNotBlank()) trace("INNER inline-script len=${body.length}: ${body.take(2500)}")
                            }
                    }
                }
            }

            // vsdec.js (decrypts the stream list) is dumped whole; player.js by keywords
            val scripts = Regex("""src=["']([^"']+\.js[^"']*)["']""", RegexOption.IGNORE_CASE)
                .findAll(html + (innerHtml ?: ""))
                .map { it.groupValues[1] }
                .filter {
                    it.contains("vsdec", true) ||
                        it.substringBefore('?').endsWith("/player.js", true)
                }
                .distinct()
                .take(3)
                .toList()

            for (src in scripts) {
                val abs = try {
                    URI(playerUrl).resolve(src).toString()
                } catch (e: Exception) {
                    continue
                }
                val js = withTimeoutOrNull(8_000L) {
                    app.get(abs, headers = headers + mapOf("Referer" to originOf(playerUrl))).text
                }
                val name = abs.substringBefore('?').substringAfterLast('/').take(40)
                trace("JS $name len=${js?.length}")
                if (js == null) continue
                if (name.contains("vsdec", true)) {
                    trace("JS $name FULL: ${compact(js).take(4500)}")
                } else {
                    traceKeywords(
                        "JS $name", js,
                        listOf("fetch(", "XMLHttpRequest", "stream_url", "Authorization", "wasm", "loadSource", "api.php", "CFG"),
                        before = 140, after = 380, maxHits = 3
                    )
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            trace("PAGE probe exception ${e::class.java.simpleName}")
        }
    }

    private suspend fun resolveVsSrc(embedUrl: String, allowWebView: Boolean): VidSrcResult {
        val embedRes = app.get(
            embedUrl,
            headers = browserHeaders + mapOf("Referer" to "$mainUrl/")
        )
        val embedHtml = embedRes.text
        val embedPageUrl = embedRes.url.ifBlank { embedUrl }

        val dataApi = DATA_API_REGEX.find(embedHtml)
            ?.groupValues?.getOrNull(1)
            ?.replace("&amp;", "&")

        trace("embed HTTP ${embedRes.code} final=${Uri.parse(embedPageUrl).host} data-api=${dataApi != null}")

        var playerUrl: String? = null
        var vsSrcUrl: String? = null
        var reason = ""

        if (dataApi == null) {
            reason = "2-no-data-api HTTP ${embedRes.code} len=${embedHtml.length} ${snippet(embedHtml)}"
        } else {
            val apiUrl = try {
                URI(embedPageUrl).resolve(dataApi).toString()
            } catch (e: Exception) {
                dataApi
            }

            vsSrcUrl = apiUrl

            val apiRes = app.get(
                apiUrl,
                headers = browserHeaders + mapOf(
                    "Referer" to embedPageUrl,
                    "Accept" to "application/json, text/plain, */*",
                    "X-Requested-With" to "XMLHttpRequest"
                )
            )

            playerUrl = try {
                parseJson<VsSrcResponse>(apiRes.text).src
                    ?.replace("\\/", "/")
                    ?.takeIf { it.startsWith("http") }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }

            if (playerUrl == null) {
                reason = "3-vs_src HTTP ${apiRes.code} ${snippet(apiRes.text)}"
            }
        }

        trace("vs_src playerUrl=${playerUrl?.let { Uri.parse(it).host }} ${reason.take(120)}")

        // P1) the stream API the player itself calls (seen in the WebView log)
        if (allowWebView && playerUrl != null) {
            val playerOrigin = originOf(playerUrl)
            val fromApi = probeStreamApi(vsSrcUrl, playerOrigin)
            trace("API stream=${fromApi?.take(90)}")
            if (fromApi != null) {
                val ref = pickReferer(
                    fromApi,
                    listOf(playerOrigin, originOf(embedPageUrl), "https://vidsrc.me/", "$mainUrl/")
                )
                return VidSrcResult(listOf(fromApi), emptyList(), ref)
            }
        }

        // The stream is generated by the player's own JS
        // (cloudorchestra -> generate.php -> https://<host>/pl/H4sI...), so it is
        // not in any HTML. A hidden WebView runs the player and we catch that URL.
        if (allowWebView) {
            var sniffed: String? = null

            // W1) replay the player's own protocol (api -> wasm decrypt -> generate.php)
            if (playerUrl != null) {
                val w = extractViaWasm(playerUrl, originOf(embedPageUrl))
                if (w != null) {
                    return VidSrcResult(listOf(w.first), emptyList(), w.second)
                }
                reason += " wasm-extract-failed"
            }

            // B1) the player page alone (fresh vs= token) with the vidsrc Referer
            if (playerUrl != null) {
                trace("B1 webview on player (15s, mobile Chrome UA)")
                sniffed = sniffM3u8("B1", playerUrl, originOf(embedPageUrl), 15_000L, MOBILE_CHROME_UA)
                trace("B1 result=${sniffed?.take(90)}")
                if (sniffed == null) reason += " webview-player-no-stream"
            }

            // B2) the real nested-iframe flow, starting from the embed page
            if (sniffed == null) {
                trace("B2 webview on embed (15s, mobile Chrome UA)")
                sniffed = sniffM3u8("B2", embedUrl, "$mainUrl/", 15_000L, MOBILE_CHROME_UA)
                trace("B2 result=${sniffed?.take(90)}")
                if (sniffed == null) reason += " webview-embed-no-stream"
            }

            if (sniffed != null) {
                // The playlist is hotlink-protected: keep the Referer that works
                val referer = pickReferer(
                    sniffed,
                    listOfNotNull(
                        playerUrl?.let { originOf(it) },
                        originOf(sniffed),
                        originOf(embedPageUrl),
                        "https://vidsrc.me/",
                        "$mainUrl/"
                    )
                )
                return VidSrcResult(listOf(sniffed), emptyList(), referer)
            }
        }

        if (allowWebView && playerUrl != null) {
            probePlayerCode(playerUrl, originOf(embedPageUrl))
        }

        throw StepFailure("vs_src-chain", reason)
    }

    // True only if the URL answers 2xx and really is an HLS playlist.
    private fun trace(msg: String) {
        val line = "${System.currentTimeMillis() - traceStart}ms $msg"
        traceLines.add(line)
        Log.e("HayyaShoot", line)
    }

    // (is it a real playlist?, short explanation)
    private suspend fun playlistCheck(
        url: String,
        referer: String,
        ua: String = userAgent
    ): Pair<Boolean, String> =
        try {
            withTimeoutOrNull(6_000L) {
                val res = app.get(
                    url,
                    headers = mapOf("User-Agent" to ua, "Referer" to referer)
                )
                val ok = res.code in 200..299 && res.text.trimStart().startsWith("#EXTM3U")
                ok to "HTTP ${res.code} len=${res.text.length} ${if (ok) "PLAYLIST" else snippet(res.text)}"
            } ?: (false to "timeout 6s")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            false to "exception ${e::class.java.simpleName}"
        }

    private suspend fun isPlaylist(url: String, referer: String): Boolean =
        playlistCheck(url, referer).first

    // embed page -> cloudnestra RCP -> /prorcp/hash -> file: -> HLS (+ subtitles)
    // Throws StepFailure with the failing step so the caller can try the next host.
    private suspend fun resolveVidSrc(embedUrl: String): VidSrcResult {
        // Step 1-2: embed page, RCP iframe (fallback: first absolute iframe)
        // The site's iframe uses referrerpolicy="origin" -> Referer = https://hayyashoot.com/
        val embedRes = app.get(
            embedUrl,
            headers = browserHeaders + mapOf("Referer" to "$mainUrl/")
        )
        val embedHtml = embedRes.text
        // vidsrc.me redirects to another host: the real page URL is the final one
        val embedPageUrl = embedRes.url.ifBlank { embedUrl }

        var rcpUrl = RCP_REGEX.find(embedHtml)?.groupValues?.getOrNull(1)
            ?: IFRAME_REGEX.find(embedHtml)?.groupValues?.getOrNull(1)
            ?: throw StepFailure(
                "2-no-iframe",
                "HTTP ${embedRes.code} len=${embedHtml.length} ${snippet(embedHtml)}"
            )
        if (rcpUrl.startsWith("//")) {
            rcpUrl = "https:$rcpUrl"
        } else if (rcpUrl.startsWith("/")) {
            val pageUri = Uri.parse(embedPageUrl)
            rcpUrl = "${pageUri.scheme}://${pageUri.authority}$rcpUrl"
        }

        // Step 3: RCP page (Referer = embed page URL)
        val rcpRes = app.get(
            rcpUrl,
            headers = browserHeaders + mapOf("Referer" to embedPageUrl)
        )
        val rcpHtml = rcpRes.text

        // Step 4: prorcp path (fallback: srcrcp)
        val prorcpPath = PRORCP_PATH_REGEX.find(rcpHtml)?.value
            ?: SRCRCP_PATH_REGEX.find(rcpHtml)?.value
            ?: throw StepFailure(
                "4-no-prorcp",
                "HTTP ${rcpRes.code} rcp=${Uri.parse(rcpUrl).host} len=${rcpHtml.length} ${snippet(rcpHtml)}"
            )

        val rcpUri = Uri.parse(rcpUrl)
        val rcpOrigin = "${rcpUri.scheme}://${rcpUri.authority}"

        // Step 5: prorcp page (Referer = RCP URL)
        val prorcpRes = app.get(
            "$rcpOrigin$prorcpPath",
            headers = browserHeaders + mapOf("Referer" to rcpUrl)
        )
        val prorcpHtml = prorcpRes.text

        // Step 6: file: "..." (fallback: any .m3u8 URL in the page)
        val rawFile = FILE_REGEX.find(prorcpHtml)?.groupValues?.getOrNull(1)
            ?: M3U8_REGEX.find(prorcpHtml.replace("\\/", "/"))?.value
            ?: PL_REGEX.find(prorcpHtml.replace("\\/", "/"))?.value
            ?: throw StepFailure(
                "6-no-file",
                "HTTP ${prorcpRes.code} len=${prorcpHtml.length} ${snippet(prorcpHtml)}"
            )

        // Step 7: every " or " alternative, {v1}..{v5} -> domain.
        // The scraper used cloudnestra.com; the RCP host can rotate to another
        // domain, so the RCP root domain is tried as well.
        val rcpRoot = (rcpUri.host ?: "").split(".").takeLast(2).joinToString(".")
        val placeholderDomains = listOf(rcpRoot, "cloudnestra.com")
            .filter { it.isNotBlank() }
            .distinct()

        val streams = rawFile
            .split(" or ")
            .flatMap { alt ->
                placeholderDomains.map { domain ->
                    alt.trim().replace(PLACEHOLDER_REGEX, domain)
                }
            }
            .filter { it.startsWith("http") }
            .distinct()

        if (streams.isEmpty()) {
            throw StepFailure("7-bad-url", rawFile.take(150))
        }

        // Subtitles (.vtt / .srt) found in the prorcp page, deduplicated.
        val subtitles = SUBTITLE_REGEX
            .findAll(prorcpHtml.replace("\\/", "/"))
            .map { it.groupValues[1] }
            .distinct()
            .toList()

        return VidSrcResult(streams, subtitles, "$rcpOrigin/")
    }

    // Plays a playlist URL given by hand. The playlist is hotlink-protected, so every
    // likely Referer is tested; each one that really answers "#EXTM3U" becomes a link.
    // If none answers, all are still offered so the player itself can try them.
    private suspend fun loadManual(
        streamUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        traceStart = System.currentTimeMillis()
        traceLines.clear()
        trace("$buildTag manual host=${Uri.parse(streamUrl).host}")

        val referers = listOf(
            "https://cloudorchestranova.com/",
            "https://vidsrc.sh/",
            originOf(streamUrl),
            "https://vidsrc.me/",
            "$mainUrl/"
        ).distinct()

        suspend fun emit(referer: String, label: String) {
            callback(
                newExtractorLink(
                    source = name,
                    name = label,
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to MOBILE_CHROME_UA,
                        "Origin" to referer.trimEnd('/')
                    )
                }
            )
        }

        var emitted = 0
        for (referer in referers) {
            val (ok, why) = playlistCheck(streamUrl, referer, MOBILE_CHROME_UA)
            trace("referer ${Uri.parse(referer).host} -> $why")
            if (ok) {
                emit(referer, "Manual (${Uri.parse(referer).host})")
                emitted++
            }
        }

        if (emitted == 0) {
            referers.forEach { referer ->
                emit(referer, "Manual try ${Uri.parse(referer).host}")
            }
            emitted = referers.size
        }

        lastDiagnostic = traceLines.toList().joinToString("\n")
        return emitted > 0
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.startsWith("manual:")) {
            return loadManual(data.removePrefix("manual:"), callback)
        }

        val media = try {
            parseJson<HayyaMediaData>(data)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (debugMode) reportFailure(callback, "0-bad-data ${data.take(80)}")
            return false
        }

        traceStart = System.currentTimeMillis()
        traceLines.clear()
        trace("$buildTag start ${media.type} id=${media.id} s=${media.season} e=${media.episode}")

        val candidates = embedCandidates(media)
        val failures = ArrayList<String>()
        var found = false

        Log.e(
            "HayyaShoot",
            "loadLinks start type=${media.type} id=${media.id} s=${media.season} e=${media.episode}"
        )

        // Try each VidSrc host until one gives a stream
        // Hard time budget: loadLinks must never spin forever
        val completed = withTimeoutOrNull(90_000L) {
            for ((index, embedUrl) in candidates.withIndex()) {
                val host = Uri.parse(embedUrl).host ?: embedUrl

                try {
                    val result = try {
                        resolveVsSrc(embedUrl, allowWebView = index == 0)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        val why = if (e is StepFailure) "${e.step} ${e.detail}"
                        else "${e::class.java.simpleName}: ${e.message}"
                        failures.add("$host $why")
                        Log.e("HayyaShoot", "vs_src chain failed: $why")
                        resolveVidSrc(embedUrl)   // legacy cloudnestra chain
                    }

                    // Keep only playlists that really answer; if none does, still
                    // offer them all (and explain in a DEBUG entry).
                    val playable = result.streams.filter { isPlaylist(it, result.referer) }
                    val toEmit = playable.ifEmpty { result.streams }

                    Log.e("HayyaShoot", "streams=${result.streams.size} playable=${playable.size}")
                    if (playable.isEmpty() && debugMode) {
                        val hosts = result.streams
                            .mapNotNull { Uri.parse(it).host }
                            .distinct()
                            .take(4)
                            .joinToString(",")
                        reportFailure(callback, "8-unreachable hosts=$hosts")
                    }

                    toEmit.forEachIndexed { index, streamUrl ->
                        callback(
                            newExtractorLink(
                                source = name,
                                name = if (toEmit.size > 1) "VidSrc ${index + 1}" else "VidSrc",
                                url = streamUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = result.referer
                                this.quality = Qualities.Unknown.value
                                this.headers = mapOf("User-Agent" to userAgent)
                            }
                        )
                    }

                    result.subtitles.forEach { subtitleUrl ->
                        subtitleCallback(SubtitleFile("Arabic", subtitleUrl))
                    }

                    found = true
                    Log.e("HayyaShoot", "OK host=$host streams=${result.streams.size} subs=${result.subtitles.size}")
                    break
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (e is StepFailure) {
                        failures.add("$host ${e.step} ${e.detail}")
                    } else {
                        logError(e)
                        failures.add("$host exception ${e::class.java.simpleName}: ${e.message}")
                    }
                    Log.e("HayyaShoot", "FAIL ${failures.last()}")
                }
            }
            true
        }
        if (completed == null) failures.add("timeout-90s (still running, stopped)")

        // Last resort: CloudStream's built-in extractors on the first embed URL
        if (!found) {
            try {
                found = withTimeoutOrNull(15_000L) {
                    loadExtractor(
                        candidates.first(),
                        "$mainUrl/",
                        subtitleCallback,
                        callback
                    )
                } ?: false
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logError(e)
            }
        }

        if (!found && debugMode) {
            if (failures.isEmpty()) {
                reportFailure(callback, "$buildTag no-failure-info ${media.type} id=${media.id}")
            } else {
                failures.take(4).forEach { reportFailure(callback, "$buildTag $it") }
            }
        }

        trace("end found=$found")
        lastDiagnostic = buildString {
            traceLines.toList().forEach { append(it).append('\n') }
            if (failures.isNotEmpty()) append("--- failures ---\n")
            failures.forEach { append("- ").append(it).append('\n') }
        }
        if (debugMode) copyToClipboard(lastDiagnostic ?: "")

        return found
    }

    // ---------------------------------------------------------------
    // Regexes copied from scraper.js 2.0.2
    // ---------------------------------------------------------------

    private companion object {
        // Debug diagnostics, shown in the app (home card + diagnostics page)
        @Volatile
        var lastDiagnostic: String? = null

        @Volatile
        var traceStart: Long = 0L

        val traceLines: MutableList<String> =
            java.util.Collections.synchronizedList(ArrayList<String>())

        val RCP_REGEX = Regex(
            """src=["']((?:https?:)?//[^"']*cloudnestra\.com/rcp/[^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        val PRORCP_REGEX = Regex("""/prorcp/([a-zA-Z0-9=+/]+)""")
        val PRORCP_PATH_REGEX = Regex("""/prorcp/[a-zA-Z0-9=+/]+""")
        val SRCRCP_PATH_REGEX = Regex("""/srcrcp/[a-zA-Z0-9=+/_-]+""")
        val IFRAME_REGEX = Regex(
            """<iframe[^>]+src=["']((?:https?:)?//[^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        val M3U8_REGEX = Regex("""https?://[^"'\s\\]+\.m3u8[^"'\s\\]*""")

        // Seen in the real player (no .m3u8 extension): https://<host>/pl/H4sIAAAA...
        val PL_REGEX = Regex(
            """https?://[^"'\s\\]+/p[li]/H4s[il][^"'\s\\]*""",
            RegexOption.IGNORE_CASE
        )

        // Tries to start the player: click the usual play buttons, play every <video>,
        // and click the centre of the page. Repeats for ~15 s. Synthetic clicks are not
        // user gestures, but most players start fetching the playlist on click anyway.
        // Chrome on Android WITHOUT the "; wv" WebView marker some players block
        const val MOBILE_CHROME_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        // Runs inside the cloudorchestra player page (same origin as the real player).
        // Replays the player's own protocol, found in vsdec.js:
        //   api.php?...&stream_urls=1 -> data.stream_urls (array, or base64 ChaCha20 string)
        //   encrypted -> vs.wasm_url / vs.wasm -> WebAssembly {alloc, decrypt, memory}
        //   alloc(n) -> write bytes -> decrypt(ptr, n) = outLen -> read at ptr+12 -> UTF-8 urls
        //   then {host}/generate.php -> token -> final playlist URL
        // The result leaves the WebView as a request to https://hayya.invalid/r?d=<json>.
        const val WASM_SCRIPT = """
(function () {
  if (window.__hayyaWasm) return;
  window.__hayyaWasm = 1;
  var log = [];
  function L(s) { try { log.push(String(s).slice(0, 320)); } catch (e) {} }
  function send(o) {
    o.log = log;
    var u = "https://hayya.invalid/r?d=" + encodeURIComponent(JSON.stringify(o));
    try { new Image().src = u; } catch (e) {}
    try { fetch(u, { mode: "no-cors" }).catch(function () {}); } catch (e) {}
    setTimeout(function () { try { location.href = u; } catch (e) {} }, 400);
  }
  function b64(s) {
    var bin = atob(s.replace(/-/g, "+").replace(/_/g, "/"));
    var out = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }
  (async function () {
    try {
      var api = (window.CFG && window.CFG.metaApi) ? window.CFG.metaApi : null;
      L("CFG.metaApi=" + api);
      if (!api) return send({ ok: false });
      var variants = ["&stream_urls=1", "&stream_urls=true", "&stream_urls"];
      var j = null;
      for (var i = 0; i < variants.length; i++) {
        var r = await fetch(api + variants[i]);
        var raw = await r.text();
        L("api" + variants[i] + " HTTP " + r.status + " len=" + raw.length + " " + raw.slice(0, 200));
        try { j = JSON.parse(raw); } catch (e) { j = null; }
        if (j && j.data && j.data.stream_urls) break;
      }
      if (!j || !j.data || !j.data.stream_urls) return send({ ok: false });
      var su = j.data.stream_urls;
      var vs = j.vs || {};
      L("stream_urls=" + (Array.isArray(su) ? "array" : typeof su) + " vs keys=" + Object.keys(vs).join(","));
      var urls = [];
      if (Array.isArray(su)) {
        urls = su.map(function (x) { return typeof x === "string" ? x : (x.url || x.file || JSON.stringify(x)); });
      } else {
        var enc = b64(su);
        var wasmBytes;
        if (vs.wasm_url) {
          var wr = await fetch(vs.wasm_url);
          wasmBytes = await wr.arrayBuffer();
          L("wasm HTTP " + wr.status + " bytes=" + wasmBytes.byteLength);
        } else if (vs.wasm) {
          wasmBytes = b64(vs.wasm).buffer;
        } else {
          L("no wasm in vs");
          return send({ ok: false });
        }
        var inst = await WebAssembly.instantiate(wasmBytes, {});
        var ex = inst.instance.exports;
        L("wasm exports=" + Object.keys(ex).join(","));
        var ptr = ex.alloc(enc.length);
        new Uint8Array(ex.memory.buffer, ptr, enc.length).set(enc);
        var outLen = ex.decrypt(ptr, enc.length);
        L("ptr=" + ptr + " outLen=" + outLen);
        var text = new TextDecoder().decode(new Uint8Array(ex.memory.buffer, ptr + 12, outLen));
        L("decoded=" + text.slice(0, 260));
        urls = text.match(/https?:\/\/[^"'\s\\,\]\[]+/g) || [];
      }
      urls = urls.filter(function (u, i2) { return urls.indexOf(u) === i2; }).slice(0, 4);
      L("urls=" + urls.length);
      var cands = [];
      for (var k = 0; k < urls.length; k++) {
        var u0 = urls[k];
        var origin = "";
        try { origin = new URL(u0).origin; } catch (e) {}
        if (origin) {
          try {
            var g = await fetch(origin + "/generate.php");
            var gt = await g.text();
            L("generate " + origin + " HTTP " + g.status + " " + gt.slice(0, 220));
            var token = null;
            try {
              var gj = JSON.parse(gt);
              token = (typeof gj === "string") ? gj : (gj.token || gj.t || (gj.data && gj.data.token) || null);
            } catch (e) {
              if (gt.length < 400 && gt.indexOf("<") < 0) token = gt.trim();
            }
            if (token) cands.push(u0 + (u0.indexOf("?") >= 0 ? "&" : "?") + "token=" + encodeURIComponent(token));
          } catch (e) { L("generate error " + e); }
        }
        cands.push(u0);
      }
      send({ ok: true, urls: urls, cands: cands });
    } catch (e) {
      L("exception " + e);
      send({ ok: false });
    }
  })();
})();
"""

        const val CLICK_SCRIPT = """
            (function () {
              var n = 0;
              var t = setInterval(function () {
                n++;
                try {
                  var sels = ['.jw-icon-display', '.vjs-big-play-button',
                              '.plyr__control--overlaid', '.play-button', '.play',
                              '#play', '[aria-label="Play"]', 'button[title*="Play"]'];
                  sels.forEach(function (s) {
                    document.querySelectorAll(s).forEach(function (e) {
                      try { e.click(); } catch (x) {}
                    });
                  });
                  document.querySelectorAll('video').forEach(function (v) {
                    try { v.muted = true; v.play(); } catch (x) {}
                  });
                  var el = document.elementFromPoint(window.innerWidth / 2, window.innerHeight / 2);
                  if (el) {
                    ['mousedown', 'mouseup', 'click'].forEach(function (ev) {
                      try {
                        el.dispatchEvent(new MouseEvent(ev, {bubbles: true, cancelable: true, view: window}));
                      } catch (x) {}
                    });
                  }
                } catch (e) {}
                if (n > 20) clearInterval(t);
              }, 700);
            })();
        """

        // What the player really requests (network log): /pl/H4sI... and /pI/H4sI...
        val STREAM_URL_REGEX = Regex(
            """\.m3u8|/p[li]/H4s[il]""",
            RegexOption.IGNORE_CASE
        )
        val FILE_REGEX = Regex("""file:\s*["']([^"']+)["']""")
        val DATA_API_REGEX = Regex("""data-api=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val PLACEHOLDER_REGEX = Regex("""\{v[1-5]\}""")
        val JSON_STREAM_REGEX = Regex(""""(?:stream_url|streamUrl|file|hls)"\s*:\s*"(https?://[^"]+)"""")
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
        @JsonProperty("src") val src: String? = null
    )

    data class TmdbResponse(
        @JsonProperty("results") val results: List<TmdbItem>? = null,
        @JsonProperty("total_pages") val totalPages: Int? = null
    )

    data class TmdbItem(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null
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
        @JsonProperty("episode_count") val episodeCount: Int? = null
    )

    data class TmdbSeason(
        @JsonProperty("episodes") val episodes: List<TmdbEpisode>? = null
    )

    data class TmdbEpisode(
        @JsonProperty("episode_number") val episodeNumber: Int,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("still_path") val stillPath: String? = null
    )
}
