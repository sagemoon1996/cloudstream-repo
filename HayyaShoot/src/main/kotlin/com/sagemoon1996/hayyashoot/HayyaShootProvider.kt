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
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Base64
import org.json.JSONArray
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
    // loadLinks: Server 2 = VidSrc stream API, protocol read from the real player
    // (vsdec.js + player.js):
    //
    //   GET data.vidsrc.sh/api.php?type=..&tmdb=..[&season=..&episode=..]&stream_urls
    //   data.stream_urls = array, or a base64 string (nonce || ChaCha20) together with
    //   vs.wasm_url / vs.wasm
    //   WASM {memory, alloc, decrypt}: alloc(n), write bytes, decrypt(ptr, n) = outLen,
    //   plaintext at ptr + 12 = stream URLs separated by newlines
    //   every URL: {origin}/generate.php -> token -> url + "?token=" (or __TOKEN__)
    //
    // All network calls are made here (Kotlin). The WebView only does the WASM
    // arithmetic (no network, no player page, nothing that detects devtools).
    // ---------------------------------------------------------------

    // true = keep the DIAG card / clipboard trace. false = production.
    private val debugMode = true

    // Shown on the DIAG card: proves which build is installed.
    private val buildTag = "build-35"

    private fun snippet(text: String) =
        text.take(120).replace(Regex("""\s+"""), " ")

    private fun trace(msg: String) {
        val line = "${System.currentTimeMillis() - traceStart}ms $msg"
        traceLines.add(line)
        Log.e("HayyaShoot", line)
    }

    // "https://host/" (with the trailing slash, ready to be used as a Referer)
    private fun originOf(url: String): String {
        val u = Uri.parse(url)
        return "${u.scheme}://${u.authority}/"
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
                    headers = mapOf(
                        "User-Agent" to ua,
                        "Referer" to referer,
                        "Origin" to referer.trimEnd('/')
                    )
                )
                val ok = res.code in 200..299 && res.text.trimStart().startsWith("#EXTM3U")
                ok to "HTTP ${res.code} len=${res.text.length} ${if (ok) "PLAYLIST" else snippet(res.text)}"
            } ?: (false to "timeout 6s")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            false to "exception ${e::class.java.simpleName}"
        }

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

    private fun jstr(o: JSONObject, key: String): String =
        if (o.isNull(key)) "" else o.optString(key)

    private fun streamApiUrl(
        media: HayyaMediaData,
        idKey: String = "tmdb",
        idVal: String = media.id.toString()
    ): String = buildString {
        val isTv = media.type == "tv" && media.season != null && media.episode != null
        append("https://data.vidsrc.sh/api.php?type=").append(if (isTv) "tv" else "movie")
        append("&").append(idKey).append("=").append(idVal)
        if (isTv) append("&season=").append(media.season).append("&episode=").append(media.episode)
        append("&stream_urls")
    }

    // The player calls these APIs from https://cloudorchestranova.com (iframe)
    private fun playerHeaders(): Map<String, String> = mapOf(
        "User-Agent" to MOBILE_CHROME_UA,
        "Accept" to "application/json, text/plain, */*",
        "Referer" to PLAYER_ORIGIN,
        "Origin" to PLAYER_ORIGIN.trimEnd('/')
    )

    private suspend fun downloadBytes(url: String): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 8_000
                c.readTimeout = 8_000
                c.setRequestProperty("User-Agent", MOBILE_CHROME_UA)
                c.setRequestProperty("Referer", PLAYER_ORIGIN)
                c.setRequestProperty("Origin", PLAYER_ORIGIN.trimEnd('/'))
                c.inputStream.use { it.readBytes() }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                trace("S2 download failed ${e::class.java.simpleName}: ${e.message?.take(80)}")
                null
            }
        }

    // WASM arithmetic only. Runs in the first page the WebView loads (about:blank is
    // enough: no fetch is made by the script) and reports back through a request to
    // https://hayya.invalid/r?d=<json> that the resolver catches.
    private suspend fun decryptViaWebView(encB64: String, wasmB64: String): String? {
        val hitUrl: String? = try {
            withTimeoutOrNull(25_000L) {
                val resolver = WebViewResolver(
                    Regex("""hayya\.invalid/r\?d="""),
                    userAgent = MOBILE_CHROME_UA,
                    script = DECRYPT_SCRIPT
                        .replace("__ENC__", encB64)
                        .replace("__WASM__", wasmB64),
                    timeout = 20_000L
                )
                val (hit, _) = resolver.resolveUsingWebView(PLAYER_ORIGIN + "__hayya", "https://vidsrc.sh/")
                hit?.url?.toString()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logError(e)
            trace("S2 webview exception ${e::class.java.simpleName}")
            null
        }

        if (hitUrl == null) {
            trace("S2 decrypt: no answer from the WebView script")
            return null
        }

        val json = try {
            val d = Regex("""[?&]d=([^&]+)""").find(hitUrl)?.groupValues?.getOrNull(1) ?: return null
            JSONObject(java.net.URLDecoder.decode(d, "UTF-8"))
        } catch (e: Exception) {
            trace("S2 decrypt: bad payload ${e::class.java.simpleName}")
            return null
        }

        json.optJSONArray("log")?.let { arr ->
            for (i in 0 until arr.length()) trace("W ${arr.optString(i)}")
        }
        if (!json.optBoolean("ok")) return null
        return jstr(json, "text")
    }

    private fun browserHints(): Map<String, String> = mapOf(
        "Accept-Language" to "en-US,en;q=0.9",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site",
        "sec-ch-ua" to "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\", \"Not-A.Brand\";v=\"99\"",
        "sec-ch-ua-mobile" to "?1",
        "sec-ch-ua-platform" to "\"Android\""
    )

    // What the browser does before the player asks for the streams:
    // vidsrc.sh embed -> data-api (/vs_src.php) -> {"src": player url with a fresh vs=}
    private suspend fun fetchPlayerUrl(
        media: HayyaMediaData,
        idKey: String = "tmdb",
        idVal: String = media.id.toString()
    ): String? {
        val isTv = media.type == "tv" && media.season != null && media.episode != null
        val embed = if (isTv) {
            "https://vidsrc.sh/embed/tv?$idKey=$idVal&season=${media.season}&episode=${media.episode}&sub=ar"
        } else {
            "https://vidsrc.sh/embed/movie?$idKey=$idVal&sub=ar"
        }
        val er = withTimeoutOrNull(10_000L) {
            app.get(embed, headers = browserHeaders + mapOf("Referer" to "$mainUrl/"))
        } ?: return null
        val pageUrl = er.url.ifBlank { embed }
        val api = Regex("""data-api=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(er.text)?.groupValues?.getOrNull(1)?.replace("&amp;", "&")
            ?: return null
        val pu = Uri.parse(pageUrl)
        val origin = "${pu.scheme}://${pu.authority}"
        val vsUrl = when {
            api.startsWith("http") -> api
            api.startsWith("/") -> origin + api
            else -> "$origin/$api"
        }
        val vr = withTimeoutOrNull(10_000L) {
            app.get(
                vsUrl,
                headers = mapOf(
                    "User-Agent" to MOBILE_CHROME_UA,
                    "Accept" to "application/json, text/plain, */*",
                    "Referer" to pageUrl,
                    "X-Requested-With" to "XMLHttpRequest"
                )
            )
        } ?: return null
        val src = Regex(""""src"\s*:\s*"([^"]+)"""").find(vr.text)
            ?.groupValues?.getOrNull(1)?.replace("\\/", "/")
        trace("S0 vs_src HTTP ${vr.code} src=${src?.substringAfter("://")?.take(60)}")
        return src
    }

    private fun compact(t: String) = t.replace(Regex("""\s+"""), " ")

    // Prints the text around each keyword (the player's own code tells how the API is called)
    private fun traceContexts(
        label: String,
        text: String,
        kws: List<String>,
        before: Int = 100,
        after: Int = 260,
        maxHits: Int = 3
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
                trace("$label $kw@$i: ${compact(t.substring(a, b))}")
                hits++
                from = i + kw.length
            }
        }
    }

    // One API attempt. Returns the JSON body on success, null otherwise (always traced).
    private suspend fun apiAttempt(label: String, url: String, headers: Map<String, String>): String? {
        val r = withTimeoutOrNull(12_000L) {
            try {
                app.get(url, headers = headers)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
        }
        if (r == null) {
            trace("S1 [$label] timeout/exception")
            return null
        }
        val t = r.text
        trace("S1 [$label] HTTP ${r.code} len=${t.length} ${snippet(t)}")
        return if (r.code in 200..299 && t.trimStart().startsWith("{") && t.contains("stream_urls")) t else null
    }

    // data.vidsrc.sh/api.php now answers {"error":"invalid api token"} without a token.
    // player.js: "single-use stream_urls API token (CONFIG.apiToken), minted server-side
    // at player-page render and embedded in window.CONFIG". So, like the browser:
    //   vidsrc.sh embed -> vs_src.php -> outer page -> inner player page -> CONFIG.apiToken
    //   -> api.php?...&stream_urls&api_token=<token>   (verified: HTTP 200)
    private suspend fun fetchViaBrowserFlow(
        media: HayyaMediaData,
        apiUrl: String,
        idKey: String = "tmdb",
        idVal: String = media.id.toString()
    ): String? {
        val playerUrl = fetchPlayerUrl(media, idKey, idVal)
        if (playerUrl == null) {
            trace("P0 no player url")
            return null
        }
        val hdr = mapOf(
            "User-Agent" to MOBILE_CHROME_UA,
            "Accept" to "text/html,application/xhtml+xml,*/*",
            "Referer" to "https://vidsrc.sh/"
        )

        val outer = withTimeoutOrNull(10_000L) { app.get(playerUrl, headers = hdr) }
        val oh = outer?.text ?: ""
        trace("P1 outer HTTP ${outer?.code} len=${oh.length}")

        val innerRel = Regex(""""playerUrl"\s*:\s*"([^"]+)"""").find(oh)
            ?.groupValues?.getOrNull(1)
            ?.replace("\\u0026", "&")
            ?.replace("\\/", "/")
        if (innerRel == null) {
            trace("P1 no inner player url: ${snippet(oh)}")
            return null
        }
        val innerUrl = try {
            java.net.URI(playerUrl).resolve(innerRel).toString()
        } catch (e: Exception) {
            null
        } ?: return null

        val inner = withTimeoutOrNull(10_000L) {
            app.get(innerUrl, headers = hdr + mapOf("Referer" to playerUrl))
        }
        val ih = inner?.text ?: ""
        val token = Regex(""""apiToken"\s*:\s*"([^"]+)"""").find(ih)?.groupValues?.getOrNull(1)
        trace("P2 inner HTTP ${inner?.code} len=${ih.length} apiToken=${token?.length}")
        if (token.isNullOrBlank()) {
            traceContexts("P2 html", ih, listOf("token", "CONFIG"), 60, 220, 3)
            return null
        }

        // With another id kind (imdb) the API url is whatever the player page itself says
        var finalApi = apiUrl
        if (idKey != "tmdb") {
            try {
                val cfgJson = Regex("""window\.CONFIG\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
                    .find(ih)?.groupValues?.getOrNull(1)
                if (cfgJson != null) {
                    val c = JSONObject(cfgJson)
                    val isTv = media.type == "tv" && media.season != null && media.episode != null
                    val sb = jstr(c, "streamBase")
                    val ap = jstr(c, "api")
                    if (isTv && sb.isNotBlank()) {
                        finalApi = sb + "&season=" + media.season + "&episode=" + media.episode + "&stream_urls"
                    } else if (!isTv && ap.isNotBlank()) {
                        finalApi = if (ap.contains("stream_urls")) ap else "$ap&stream_urls"
                    }
                }
            } catch (e: Exception) {
                trace("P2 CONFIG parse failed ${e::class.java.simpleName}")
            }
            trace("P2 api=${finalApi.substringAfter("://").take(120)}")
        }

        val body = apiAttempt(
            "api_token",
            "$finalApi&api_token=$token",
            playerHeaders() + mapOf("Referer" to originOf(innerUrl))
        )

        // Once per app session: how the player asks for foreign-language subtitles
        if (debugMode && !subsDumped) {
            subsDumped = true
            try {
                val subSrc = Regex("""src=["']([^"']+subtitles[^"']*\.js[^"']*)["']""", RegexOption.IGNORE_CASE)
                    .find(ih)?.groupValues?.getOrNull(1)
                if (subSrc != null) {
                    val abs = java.net.URI(innerUrl).resolve(subSrc).toString()
                    val js = withTimeoutOrNull(10_000L) {
                        app.get(abs, headers = hdr + mapOf("Referer" to originOf(innerUrl))).text
                    }
                    trace("P5 subtitles.js len=${js?.length}")
                    if (js != null) {
                        traceContexts(
                            "P5 subs", js,
                            listOf("cache.php", "cacheBase", "SubDownloadLink", ".gz", "wyzie", "sublanguageid", "vtt"),
                            120, 420, 3
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                trace("P5 subtitles dump failed ${e::class.java.simpleName}")
            }
        }
        return body
    }

    // GET data.vidsrc.sh/api.php?...&stream_urls (returns the JSON body)
    private suspend fun fetchImdbId(media: HayyaMediaData): String? =
        try {
            val kind = if (media.type == "tv") "tv" else "movie"
            val j = JSONObject(tmdbGet("$kind/${media.id}/external_ids"))
            jstr(j, "imdb_id").takeIf { it.startsWith("tt") }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }

    private suspend fun requestStreamApi(apiUrl: String, media: HayyaMediaData): String? {
        fetchViaBrowserFlow(media, apiUrl)?.let { return it }

        // {"status_code":404}: VidSrc may know this title only by its IMDb id
        val imdb = fetchImdbId(media)
        trace("P6 imdb=$imdb")
        if (imdb != null) {
            delay(1000)
            fetchViaBrowserFlow(media, streamApiUrl(media, "imdb", imdb), "imdb", imdb)
                ?.let { return it }
        }
        return null
    }

    // GET the stream API, decrypt data.stream_urls when needed, return the URLs.
    private suspend fun fetchStreamUrls(
        apiUrl: String,
        media: HayyaMediaData,
        meta: MutableMap<String, String>
    ): List<String>? {
        val body = requestStreamApi(apiUrl, media) ?: return null

        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            trace("S1 not json: ${snippet(body)}")
            return null
        }

        json.optJSONObject("data")?.let { d ->
            meta["imdb"] = jstr(d, "imdb_id")
            meta["title"] = jstr(d, "title")
        }
        val su = json.optJSONObject("data")?.opt("stream_urls")
        val vs = json.optJSONObject("vs")
        trace("S1 stream_urls=${su?.javaClass?.simpleName} vs=${vs?.keys()?.asSequence()?.toList()}")

        if (su is JSONArray) {
            return (0 until su.length()).map { su.optString(it) }.filter { it.startsWith("http") }
        }
        if (su !is String || su.isBlank()) {
            trace("S1 no stream_urls in the answer")
            return null
        }
        if (vs == null) {
            trace("S1 stream_urls is encrypted but the answer has no vs")
            return null
        }

        val wasmInline = jstr(vs, "wasm")
        val wasmUrl = jstr(vs, "wasm_url")
        val wasmB64: String = when {
            wasmInline.isNotBlank() -> wasmInline
            wasmUrl.isNotBlank() -> {
                val bytes = downloadBytes(wasmUrl)
                trace("S2 wasm ${wasmUrl.substringAfter("://").take(80)} bytes=${bytes?.size}")
                if (bytes == null) return null
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
            else -> {
                trace("S2 vs has neither wasm nor wasm_url")
                return null
            }
        }

        val text = decryptViaWebView(su, wasmB64) ?: return null
        return text.split("\n").map { it.trim() }.filter { it.startsWith("http") }
    }

    // player.js parseToken: JSON {token|data|string|result}, or the plain text
    private fun parseToken(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty() || t.length > 600 || t.contains('<')) return ""
        try {
            val j = JSONObject(t)
            for (k in listOf("token", "data", "string", "result")) {
                val v = jstr(j, k)
                if (v.isNotBlank()) return v
            }
            return ""
        } catch (_: Exception) {
        }
        return t.trim('"')
    }

    // player.js applyToken
    private fun applyToken(url: String, token: String): String {
        if (token.isBlank()) return url
        if (url.contains("__TOKEN__")) return url.split("__TOKEN__").joinToString(token)
        return url + (if (url.contains("?")) "&" else "?") + "token=" + token
    }

    // ---------------------------------------------------------------
    // Arabic subtitles: OpenSubtitles legacy REST (the same call the real player makes)
    //   rest.opensubtitles.org/search/episode-E/imdbid-N/season-S/sublanguageid-ara
    // The download link is a gzip of the .srt (maybe not UTF-8), but the player of
    // CloudStream only loads http(s) subtitles as plain text: so the file is
    // downloaded, gunzipped, converted to UTF-8 and served from 127.0.0.1.
    // ---------------------------------------------------------------

    private suspend fun downloadPlain(
        url: String,
        ua: String = "TemporaryUserAgent",
        referer: String? = null,
        forceHttps: Boolean = true
    ): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                var cur = if (forceHttps && url.startsWith("http://")) "https://" + url.removePrefix("http://") else url
                var hops = 0
                while (true) {
                    val c = java.net.URL(cur).openConnection() as java.net.HttpURLConnection
                    c.instanceFollowRedirects = false
                    c.connectTimeout = 8_000
                    c.readTimeout = 10_000
                    c.setRequestProperty("User-Agent", ua)
                    if (referer != null) c.setRequestProperty("Referer", referer)
                    val code = c.responseCode
                    if (code in 300..399 && hops < 5) {
                        val loc = c.getHeaderField("Location")
                        c.disconnect()
                        if (loc.isNullOrBlank()) return@withContext null
                        cur = java.net.URL(java.net.URL(cur), loc).toString()
                        hops++
                        continue
                    }
                    val type = c.contentType
                    val bytes = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() }
                        ?: ByteArray(0)
                    val gz = bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
                    val zip = bytes.size > 3 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
                    trace(
                        "T3 download HTTP $code type=$type bytes=${bytes.size} gzip=$gz zip=$zip " +
                            "host=${java.net.URL(cur).host} " +
                            (if (!gz && !zip) "head=${snippet(String(bytes.copyOf(minOf(bytes.size, 120)), Charsets.ISO_8859_1))}" else "")
                    )
                    return@withContext if (code in 200..299) bytes else null
                }
                @Suppress("UNREACHABLE_CODE")
                null
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                trace("T3 download failed ${e::class.java.simpleName}: ${e.message?.take(80)}")
                null
            }
        }

    // gunzip if needed, drop the BOM, make sure the text is UTF-8
    private fun toUtf8Srt(raw: ByteArray, declaredEncoding: String): ByteArray {
        var b = raw
        if (b.size > 3 && b[0] == 0x50.toByte() && b[1] == 0x4B.toByte() && b[2] == 0x03.toByte() && b[3] == 0x04.toByte()) {
            // zip (Podnapisi, TVSubtitles): take the first subtitle file inside
            java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(b)).use { zin ->
                var entry = zin.nextEntry
                var found: ByteArray? = null
                while (entry != null) {
                    val nm = entry.name.lowercase()
                    if (!entry.isDirectory && (nm.endsWith(".srt") || nm.endsWith(".sub") || nm.endsWith(".txt"))) {
                        found = zin.readBytes()
                        break
                    }
                    entry = zin.nextEntry
                }
                if (found != null) b = found
            }
        }
        if (b.size > 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte()) {
            b = java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(b)).readBytes()
        }
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) {
            b = b.copyOfRange(3, b.size)
        }
        val utf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        val text = try {
            utf8.decode(java.nio.ByteBuffer.wrap(b)).toString()
        } catch (e: Exception) {
            val cs = try {
                java.nio.charset.Charset.forName(
                    if (declaredEncoding.isBlank() || declaredEncoding.equals("utf-8", true)) "windows-1256"
                    else declaredEncoding
                )
            } catch (e2: Exception) {
                java.nio.charset.Charset.forName("windows-1256")
            }
            String(b, cs)
        }
        // BOM + a line of its own: charset detectors recognise UTF-8 at once, and the
        // first line is skipped as an "invalid cue index" so no cue is lost.
        return byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "\n".toByteArray(Charsets.UTF_8) +
            text.toByteArray(Charsets.UTF_8)
    }

    private suspend fun addArabicSubtitles(
        media: HayyaMediaData,
        imdb: String?,
        title: String?,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val digits = imdb.orEmpty().filter { it.isDigit() }
        val isTv = media.type == "tv" && media.season != null && media.episode != null
        val epPart = if (isTv) "episode-${media.episode}/" else ""
        val seasonPart = if (isTv) "season-${media.season}/" else ""
        val cleanTitle = title.orEmpty()
            .replace(Regex("""\s*\(?\d{4}\)?\s*$"""), "")
            .trim()
            .lowercase()

        // 1) by IMDb id (what the real player does), 2) by title when 1) has no Arabic file.
        // The legacy API wants its parameters in alphabetical order.
        val searches = ArrayList<String>()
        if (digits.isNotBlank()) searches.add("${epPart}imdbid-$digits/${seasonPart}sublanguageid-ara")
        if (cleanTitle.isNotBlank()) {
            val q = java.net.URLEncoder.encode(cleanTitle, "UTF-8").replace("+", "%20")
            searches.add("${epPart}query-$q/${seasonPart}sublanguageid-ara")
        }
        if (searches.isEmpty()) {
            trace("T1 no imdb id and no title in the API answer")
            return
        }

        var rows: List<JSONObject> = emptyList()
        for ((i, path) in searches.withIndex()) {
            if (i > 0) delay(700)
            val searchUrl = "https://rest.opensubtitles.org/search/$path"
            val res = withTimeoutOrNull(10_000L) {
                app.get(
                    searchUrl,
                    headers = mapOf(
                        "User-Agent" to "TemporaryUserAgent",
                        "X-User-Agent" to "TemporaryUserAgent",
                        "Accept" to "application/json"
                    )
                )
            }
            if (res == null) {
                trace("T1 opensubtitles timeout $path")
                continue
            }
            val text = res.text
            trace("T1 opensubtitles HTTP ${res.code} len=${text.length} $path")
            val arr = try {
                JSONArray(text)
            } catch (e: Exception) {
                trace("T1 not json: ${snippet(text)}")
                continue
            }
            val found = (0 until arr.length())
                .mapNotNull { arr.optJSONObject(it) }
                .filter {
                    jstr(it, "SubLanguageID") == "ara" &&
                        jstr(it, "SubFormat").equals("srt", true) &&
                        jstr(it, "SubDownloadLink").isNotBlank()
                }
                .sortedByDescending { jstr(it, "SubDownloadsCnt").toIntOrNull() ?: 0 }
                .take(6)
            trace("T2 arabic srt found=${found.size} of ${arr.length()} (${if (path.contains("imdbid-")) "imdb" else "title"} search)")
            if (found.isNotEmpty()) {
                rows = found
                break
            }
        }

        var n = 0
        for (row in rows) {
            if (n >= 2) break

            val link = jstr(row, "SubDownloadLink")
            val raw = downloadPlain(link)
            if (raw == null || raw.isEmpty()) continue

            val srt = try {
                toUtf8Srt(raw, jstr(row, "SubEncoding"))
            } catch (e: Exception) {
                trace("T3 decode failed ${e::class.java.simpleName}")
                continue
            }

            // A real subtitle has many "-->" cues. Anything else (quota/ad/error text) is dropped.
            val srtText = String(srt, Charsets.UTF_8)
            val cues = Regex("-->").findAll(srtText).count()
            if (cues < 5) {
                trace("T3 not a real srt (cues=$cues) head=${snippet(srtText)}")
                continue
            }

            // Some files tagged "ara" on OpenSubtitles are Persian (seen on the TV: "چ", "گ",
            // missing "ی"). Persian uses پ چ ژ گ ک ی; Arabic uses ك ي: drop Persian files.
            var arabicLetters = 0
            var persianOnly = 0
            for (ch in srtText) {
                if (ch in '\u0621'..'\u064A' || ch in '\u0671'..'\u06D3') arabicLetters++
                if (ch == '\u067E' || ch == '\u0686' || ch == '\u0698' ||
                    ch == '\u06AF' || ch == '\u06A9' || ch == '\u06CC'
                ) persianOnly++
            }
            val persianRatio = if (arabicLetters > 0) persianOnly.toDouble() / arabicLetters else 0.0
            if (arabicLetters < 100 || persianRatio > 0.02) {
                trace(
                    "T3 skipped ${jstr(row, "SubFileName").take(40)}: not Arabic " +
                        "(arabic letters=$arabicLetters persian-only=$persianOnly ratio=${"%.3f".format(persianRatio)})"
                )
                continue
            }

            val id = (jstr(row, "IDSubtitleFile").ifBlank { "s$n" } + srt.size)
                .filter { it.isLetterOrDigit() }
            val label = if (n == 0) "Arabic" else "Arabic ${n + 1}"

            // UTF-8: phones. The TV of the user decodes subtitles as Windows-1256 (its
            // "Subtitle encoding" setting), and showed this variant correctly.
            val served = LocalSubServer.publish(id, srt)
            trace("T3 ${jstr(row, "SubFileName").take(48)} enc=${jstr(row, "SubEncoding")} bytes=${srt.size} persian=${"%.3f".format(persianRatio)} -> ${served ?: "local server failed"}")
            if (served != null) subtitleCallback(SubtitleFile(label, served))

            try {
                val cp = java.nio.charset.Charset.forName("windows-1256")
                val served2 = LocalSubServer.publish(id + "w", srtText.toByteArray(cp), "windows-1256")
                if (served2 != null) subtitleCallback(SubtitleFile("$label (CP1256)", served2))
            } catch (e: Exception) {
                trace("T3 cp1256 variant failed ${e::class.java.simpleName}")
            }

            if (served != null) n++
        }

        // Other sources, only when OpenSubtitles gave nothing (or only one file)
        if (n < 2) {
            try {
                n = fromPodnapisi(media, title, n, subtitleCallback)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                trace("T5 podnapisi exception ${e::class.java.simpleName}: ${e.message?.take(80)}")
            }
        }
        if (n < 2 && isTv) {
            try {
                n = fromTvSubtitles(media, title, n, subtitleCallback)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                trace("T7 tvsubtitles exception ${e::class.java.simpleName}: ${e.message?.take(80)}")
            }
        }
        if (n == 0) trace("T3 no real Arabic subtitle found for this title")
    }

    // Same checks as the OpenSubtitles loop: real srt, Arabic (not Persian), UTF-8 copy for
    // phones and a Windows-1256 copy for TVs that decode with that code page.
    private suspend fun serveArabicSrt(
        raw: ByteArray,
        enc: String,
        fileLabel: String,
        seed: String,
        n: Int,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val srt = try {
            toUtf8Srt(raw, enc)
        } catch (e: Exception) {
            trace("T3 decode failed ${e::class.java.simpleName} ($fileLabel)")
            return false
        }
        val srtText = String(srt, Charsets.UTF_8)
        val cues = Regex("-->").findAll(srtText).count()
        if (cues < 5) {
            trace("T3 not a real srt (cues=$cues) head=${snippet(srtText)}")
            return false
        }
        var arabicLetters = 0
        var persianOnly = 0
        for (ch in srtText) {
            if (ch in '\u0621'..'\u064A' || ch in '\u0671'..'\u06D3') arabicLetters++
            if (ch == '\u067E' || ch == '\u0686' || ch == '\u0698' ||
                ch == '\u06AF' || ch == '\u06A9' || ch == '\u06CC'
            ) persianOnly++
        }
        val persianRatio = if (arabicLetters > 0) persianOnly.toDouble() / arabicLetters else 0.0
        if (arabicLetters < 100 || persianRatio > 0.02) {
            trace("T3 skipped $fileLabel: not Arabic (arabic letters=$arabicLetters persian-only=$persianOnly)")
            return false
        }
        val id = (seed + srt.size).filter { it.isLetterOrDigit() }
        val label = if (n == 0) "Arabic" else "Arabic ${n + 1}"
        val served = LocalSubServer.publish(id, srt) ?: return false
        subtitleCallback(SubtitleFile(label, served))
        trace("T3 $fileLabel bytes=${srt.size} -> $served")
        try {
            val cp = java.nio.charset.Charset.forName("windows-1256")
            val served2 = LocalSubServer.publish(id + "w", srtText.toByteArray(cp), "windows-1256")
            if (served2 != null) subtitleCallback(SubtitleFile("$label (CP1256)", served2))
        } catch (e: Exception) {
            trace("T3 cp1256 variant failed ${e::class.java.simpleName}")
        }
        return true
    }

    private fun cleanShowTitle(title: String?): String =
        title.orEmpty().replace(Regex("""\s*\(?\d{4}\)?\s*$"""), "").trim()

    // Podnapisi: JSON search (Accept: application/json), download = zip with the .srt
    private suspend fun fromPodnapisi(
        media: HayyaMediaData,
        title: String?,
        startIndex: Int,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Int {
        val clean = cleanShowTitle(title)
        if (clean.isBlank()) return startIndex
        val year = Regex("""(\d{4})\)?\s*$""").find(title.orEmpty())?.groupValues?.getOrNull(1)
        val isTv = media.type == "tv" && media.season != null && media.episode != null
        val url = buildString {
            append("https://www.podnapisi.net/subtitles/search/advanced?keywords=")
            append(java.net.URLEncoder.encode(clean, "UTF-8"))
            append("&language=ar")
            if (isTv) append("&seasons=").append(media.season).append("&episodes=").append(media.episode)
            else if (year != null) append("&year=").append(year)
        }
        val res = withTimeoutOrNull(10_000L) {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to MOBILE_CHROME_UA,
                    "Accept" to "application/json",
                    "Referer" to "https://www.podnapisi.net/"
                )
            )
        }
        if (res == null) {
            trace("T5 podnapisi timeout")
            return startIndex
        }
        val text = res.text
        trace("T5 podnapisi HTTP ${res.code} len=${text.length} ${snippet(text)}")
        val arr = try {
            JSONObject(text).optJSONArray("data")
        } catch (e: Exception) {
            null
        } ?: return startIndex

        val norm = { x: String -> x.lowercase().filter { ch -> ch.isLetterOrDigit() } }
        val want = norm(clean)
        val items = (0 until arr.length())
            .mapNotNull { arr.optJSONObject(it) }
            .filter { jstr(it, "id").isNotBlank() }
            .filter { jstr(it, "language").let { l -> l.isBlank() || l == "ar" } }
            .filter {
                val t = norm(jstr(it, "title"))
                t.isBlank() || t.contains(want) || (t.length > 3 && want.contains(t))
            }
            .sortedByDescending { it.optJSONObject("stats")?.optInt("downloads") ?: 0 }
            .take(4)
        trace("T6 podnapisi candidates=${items.size} of ${arr.length()}")

        var n = startIndex
        for (item in items) {
            if (n >= 2) break
            val id = jstr(item, "id")
            val raw = downloadPlain(
                "https://www.podnapisi.net/subtitles/$id/download",
                MOBILE_CHROME_UA,
                "https://www.podnapisi.net/"
            )
            if (raw == null || raw.isEmpty()) continue
            if (serveArabicSrt(raw, "", "podnapisi $id", "p$id", n, subtitleCallback)) n++
        }
        return n
    }

    // TVSubtitles (episodes only): search -> season page -> episode page -> subtitle with the
    // Arabic flag -> download-ID.html (zip). Page structure written from memory: every step is traced.
    private suspend fun fromTvSubtitles(
        media: HayyaMediaData,
        title: String?,
        startIndex: Int,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Int {
        val season = media.season ?: return startIndex
        val episode = media.episode ?: return startIndex
        val clean = cleanShowTitle(title)
        if (clean.isBlank()) return startIndex

        val base = "https://www.tvsubtitles.net"
        val h = mapOf("User-Agent" to MOBILE_CHROME_UA, "Referer" to "$base/")

        val sr = withTimeoutOrNull(10_000L) {
            app.post("$base/search.php", headers = h, data = mapOf("q" to clean))
        }
        val sh = sr?.text ?: ""
        trace("T7 tvsubtitles search HTTP ${sr?.code} len=${sh.length}")

        val norm = { x: String -> x.lowercase().filter { ch -> ch.isLetterOrDigit() } }
        val want = norm(clean)
        val shows = Regex("""<a[^>]+href=["']/?tvshow-(\d+)(?:-\d+)?\.html["'][^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(sh)
            .map { it.groupValues[1] to norm(it.groupValues[2].replace(Regex("<[^>]+>"), "")) }
            .toList()
        val showId = (shows.firstOrNull { it.second.isNotBlank() && (it.second.contains(want) || want.contains(it.second)) }
            ?: shows.firstOrNull())?.first
        trace("T7 tvsubtitles show id=$showId (matches=${shows.size})")
        if (showId == null) return startIndex

        val seasonRes = withTimeoutOrNull(10_000L) { app.get("$base/tvshow-$showId-$season.html", headers = h) }
        val seasonHtml = seasonRes?.text ?: ""
        val key = "${season}x${episode.toString().padStart(2, '0')}"
        val row = Regex("""<tr[^>]*>(.*?)</tr>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(seasonHtml)
            .map { it.groupValues[1] }
            .firstOrNull { it.contains(key) && it.contains("episode-") }
        val epId = row?.let { Regex("""episode-(\d+)\.html""").find(it)?.groupValues?.getOrNull(1) }
        trace("T7 tvsubtitles season HTTP ${seasonRes?.code} len=${seasonHtml.length} $key -> episode id=$epId")
        if (epId == null) return startIndex

        val epRes = withTimeoutOrNull(10_000L) { app.get("$base/episode-$epId.html", headers = h) }
        val epHtml = epRes?.text ?: ""
        val subIds = Regex("""<a[^>]+href=["']/?subtitle-(\d+)\.html["'][^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(epHtml)
            .filter { it.groupValues[2].contains("flags/ar.", ignoreCase = true) }
            .map { it.groupValues[1] }
            .distinct()
            .take(3)
            .toList()
        trace("T7 tvsubtitles episode HTTP ${epRes?.code} len=${epHtml.length} arabic subtitles=${subIds.size}")

        var n = startIndex
        for (sid in subIds) {
            if (n >= 2) break
            val raw = downloadPlain("$base/download-$sid.html", MOBILE_CHROME_UA, "$base/subtitle-$sid.html", forceHttps = false)
            if (raw == null || raw.isEmpty()) continue
            if (serveArabicSrt(raw, "", "tvsubtitles $sid", "t$sid", n, subtitleCallback)) n++
        }
        return n
    }

    private suspend fun emitLink(
        callback: (ExtractorLink) -> Unit,
        url: String,
        referer: String,
        label: String,
        isHls: Boolean = true
    ) {
        callback(
            newExtractorLink(
                source = name,
                name = label,
                url = url,
                type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
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

    // Titles VidSrc answers 404 for: the site's own "Server 1" (get_embed.php, fallback
    // pro.vidsrc.sbs). Its host is unknown, so every step is traced: built-in extractors
    // first, then a generic scrape (m3u8/mp4 in the page, nested iframes up to 2 levels).
    private suspend fun tryServer1(
        media: HayyaMediaData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val isTv = media.type == "tv" && media.season != null && media.episode != null
        val q = if (isTv) "type=tv&id=${media.id}&s=${media.season}&e=${media.episode}" else "type=movie&id=${media.id}"
        trace("V1 VidSrc has nothing -> Server 1: get_embed.php?$q")

        val r = withTimeoutOrNull(10_000L) {
            try {
                app.get(
                    "$mainUrl/get_embed.php?$q",
                    headers = browserHeaders + mapOf(
                        "Referer" to "$mainUrl/movies/",
                        "Accept" to "application/json, text/plain, */*"
                    )
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
        }
        val body = r?.text ?: ""
        trace("V1 get_embed HTTP ${r?.code} len=${body.length} ${snippet(body)}")
        val fromSite = try {
            jstr(JSONObject(body), "embed_url")
        } catch (e: Exception) {
            ""
        }
        val fallback =
            if (isTv) "https://pro.vidsrc.sbs/embed/tv/${media.id}/${media.season}/${media.episode}"
            else "https://pro.vidsrc.sbs/embed/movie/${media.id}"

        for (embed in listOf(fromSite, fallback).filter { it.startsWith("http") }.distinct()) {
            trace("V2 embed ${embed.substringAfter("://").take(100)}")
            val viaExtractors = withTimeoutOrNull(15_000L) {
                try {
                    loadExtractor(embed, "$mainUrl/", subtitleCallback, callback)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    false
                }
            } ?: false
            trace("V2 loadExtractor -> $viaExtractors")
            if (viaExtractors) return true
            if (scrapeEmbed(embed, 0, callback)) return true
        }
        return false
    }

    private suspend fun scrapeEmbed(url: String, depth: Int, callback: (ExtractorLink) -> Unit): Boolean {
        val r = withTimeoutOrNull(10_000L) {
            try {
                app.get(url, headers = browserHeaders + mapOf("Referer" to "$mainUrl/"))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
        }
        if (r == null) {
            trace("V3 no answer ${Uri.parse(url).host}")
            return false
        }
        val html = r.text.replace("\\/", "/")
        trace("V3 ${Uri.parse(url).host} HTTP ${r.code} len=${html.length} ${snippet(html)}")

        val direct = Regex("""https?://[^"'\s\\<>]+\.(?:m3u8|mp4)[^"'\s\\<>]*""").find(html)?.value
            ?: Regex("""file:\s*["']([^"']+)["']""").find(html)?.groupValues?.getOrNull(1)?.takeIf { it.startsWith("http") }
        if (direct != null) {
            val ref = originOf(url)
            val isHls = direct.contains(".m3u8") || !direct.contains(".mp4")
            val ok = if (isHls) playlistCheck(direct, ref, MOBILE_CHROME_UA).first else true
            trace("V3 direct ${direct.substringAfter("://").take(90)} hls=$isHls verified=$ok")
            if (ok) {
                emitLink(callback, direct, ref, "Server 1", isHls)
                return true
            }
        }

        if (depth < 2) {
            val frames = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .findAll(html)
                .map { it.groupValues[1] }
                .toList()
            for (f in frames.take(3)) {
                val abs = try {
                    java.net.URI(url).resolve(f).toString()
                } catch (e: Exception) {
                    continue
                }
                if (!abs.startsWith("http")) continue
                if (scrapeEmbed(abs, depth + 1, callback)) return true
            }
        }
        return false
    }

    private suspend fun resolveStreams(
        media: HayyaMediaData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val meta = HashMap<String, String>()
        val urls = fetchStreamUrls(streamApiUrl(media), media, meta)
            ?: return tryServer1(media, subtitleCallback, callback)
        trace("S3 ${urls.size} stream urls: " + urls.joinToString(" | ") { it.substringAfter("://").take(70) })
        if (urls.isEmpty()) return false

        val referers = listOf(PLAYER_ORIGIN, "https://vidsrc.sh/", "$mainUrl/")
        var emitted = 0
        val unverified = ArrayList<Pair<String, String>>()
        // generate.php answers "429 Too Many Requests" when it is called several times
        // in a row, and all the URLs share one host: ask once per host, reuse the token.
        val tokens = HashMap<String, String>()

        for ((i, u0) in urls.take(4).withIndex()) {
            val origin = originOf(u0.replace("__TOKEN__", "x"))

            var token = tokens[origin] ?: ""
            if (token.isNotBlank()) {
                trace("S4 reuse the token of $origin (${token.length})")
            } else {
                val gen = withTimeoutOrNull(8_000L) {
                    try {
                        app.get(origin + "generate.php", headers = playerHeaders())
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        null
                    }
                }
                token = if (gen != null && gen.code in 200..299) parseToken(gen.text) else ""
                trace(
                    "S4 generate ${origin}generate.php HTTP ${gen?.code} token=${token.take(20)}(${token.length})" +
                        if (token.isBlank()) " ${snippet(gen?.text ?: "")}" else ""
                )
                if (token.isNotBlank()) tokens[origin] = token
            }

            val candidates = listOfNotNull(
                if (token.isNotBlank()) applyToken(u0, token) else null,
                u0.takeIf { !it.contains("__TOKEN__") }
            ).distinct()

            var verified = false
            for (cand in candidates) {
                for (ref in referers) {
                    val (good, why) = playlistCheck(cand, ref, MOBILE_CHROME_UA)
                    trace("S5 #${i + 1} ${if (cand == u0) "plain" else "token"} ref=${Uri.parse(ref).host} -> $why")
                    if (good) {
                        emitLink(callback, cand, ref, "VidSrc ${emitted + 1}")
                        emitted++
                        verified = true
                        break
                    }
                }
                if (verified) break
            }
            if (!verified && candidates.isNotEmpty()) unverified.add(candidates.first() to referers.first())
            if (emitted >= 3) break
        }

        // Nothing could be verified: still let the player try the best guesses
        if (emitted == 0) {
            unverified.take(2).forEachIndexed { i, (u, ref) ->
                emitLink(callback, u, ref, "VidSrc unverified ${i + 1}")
                emitted++
            }
        }

        // Arabic subtitles never decide if the video works: any failure is only traced
        if (emitted > 0) {
            try {
                withTimeoutOrNull(35_000L) {
                    addArabicSubtitles(media, meta["imdb"], meta["title"], subtitleCallback)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                trace("T0 subtitles exception ${e::class.java.simpleName}: ${e.message?.take(80)}")
            }
        }
        return emitted > 0
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
            return false
        }

        traceStart = System.currentTimeMillis()
        traceLines.clear()
        trace("$buildTag start ${media.type} id=${media.id} s=${media.season} e=${media.episode}")

        var found = false
        try {
            found = withTimeoutOrNull(100_000L) { resolveStreams(media, subtitleCallback, callback) }
                ?: run {
                    trace("timeout 100s")
                    false
                }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logError(e)
            trace("exception ${e::class.java.simpleName}: ${e.message?.take(120)}")
        }

        trace("end found=$found")
        lastDiagnostic = traceLines.toList().joinToString("\n")
        if (debugMode) copyToClipboard(lastDiagnostic ?: "")
        return found
    }

    // ---------------------------------------------------------------
    // Constants and the WASM script
    // ---------------------------------------------------------------

    private companion object {
        // Debug diagnostics, shown in the app (home card + diagnostics page)
        @Volatile
        var lastDiagnostic: String? = null

        @Volatile
        var traceStart: Long = 0L

        @Volatile
        var subsDumped: Boolean = false

        val traceLines: MutableList<String> =
            java.util.Collections.synchronizedList(ArrayList<String>())

        // The player lives on this origin (iframe); the APIs expect it as Referer/Origin
        const val PLAYER_ORIGIN = "https://cloudorchestranova.com/"

        // A pasted stream URL (search box): /pl/H4sI..., /pI/H4sI... or .m3u8
        val STREAM_URL_REGEX = Regex(
            """\.m3u8|/p[li]/H4s[il]""",
            RegexOption.IGNORE_CASE
        )

        // Chrome on Android WITHOUT the "; wv" WebView marker
        const val MOBILE_CHROME_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        // vsdec.js, as written by the site: compile the WASM, alloc, write, decrypt,
        // read the plaintext at ptr + 12. Pure computation (no fetch).
        const val DECRYPT_SCRIPT = """
(function () {
  if (window.__hayyaDec) return;
  window.__hayyaDec = 1;
  var ENC = "__ENC__";
  var WASM = "__WASM__";
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
    var bin = atob(s), out = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }
  (async function () {
    try {
      L("page=" + location.href.slice(0, 60));
      var mod = await WebAssembly.compile(b64(WASM));
      var inst = await WebAssembly.instantiate(mod, {});
      var ex = inst.exports;
      L("exports=" + Object.keys(ex).join(","));
      var enc = b64(ENC);
      var ptr = ex.alloc(enc.length);
      new Uint8Array(ex.memory.buffer, ptr, enc.length).set(enc);
      var outLen = ex.decrypt(ptr, enc.length);
      var txt = new TextDecoder().decode(new Uint8Array(ex.memory.buffer, ptr + 12, outLen));
      L("enc=" + enc.length + " ptr=" + ptr + " outLen=" + outLen + " text=" + txt.slice(0, 200));
      send({ ok: true, text: txt });
    } catch (e) {
      L("exception " + e);
      send({ ok: false });
    }
  })();
})();
"""
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

// Serves already converted subtitles (plain .srt) on 127.0.0.1 only.
private object LocalSubServer {
    private val store = HashMap<String, Pair<ByteArray, String>>()

    @Volatile
    private var server: java.net.ServerSocket? = null

    @Synchronized
    fun publish(id: String, bytes: ByteArray, charset: String = "utf-8"): String? {
        synchronized(store) {
            if (store.size > 12) store.clear()
            store[id] = bytes to charset
        }
        val ss = server ?: try {
            java.net.ServerSocket(0, 20, java.net.InetAddress.getByName("127.0.0.1")).also {
                server = it
                start(it)
            }
        } catch (e: Exception) {
            return null
        }
        return "http://127.0.0.1:${ss.localPort}/s/$id.srt"
    }

    private fun start(ss: java.net.ServerSocket) {
        val t = Thread {
            while (!ss.isClosed) {
                try {
                    val c = ss.accept()
                    val h = Thread { handle(c) }
                    h.isDaemon = true
                    h.start()
                } catch (e: Exception) {
                    if (ss.isClosed) break
                }
            }
        }
        t.isDaemon = true
        t.name = "hayya-sub"
        t.start()
    }

    private fun handle(c: java.net.Socket) {
        try {
            c.soTimeout = 5_000
            val reader = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val first = reader.readLine() ?: return
            while (true) {
                val l = reader.readLine() ?: break
                if (l.isEmpty()) break
            }
            val id = Regex("""GET /s/([A-Za-z0-9]+)\.srt""").find(first)?.groupValues?.getOrNull(1)
            val entry = id?.let { synchronized(store) { store[it] } }
            val out = c.getOutputStream()
            if (entry == null) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            } else {
                out.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: application/x-subrip; charset=${entry.second}\r\n" +
                        "Content-Length: ${entry.first.size}\r\nAccess-Control-Allow-Origin: *\r\n" +
                        "Connection: close\r\n\r\n").toByteArray()
                )
                out.write(entry.first)
            }
            out.flush()
        } catch (_: Exception) {
        } finally {
            try {
                c.close()
            } catch (_: Exception) {
            }
        }
    }
}
