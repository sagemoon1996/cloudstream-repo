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
    private val buildTag = "build-24"

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

    private fun streamApiUrl(media: HayyaMediaData): String = buildString {
        val isTv = media.type == "tv" && media.season != null && media.episode != null
        append("https://data.vidsrc.sh/api.php?type=").append(if (isTv) "tv" else "movie")
        append("&tmdb=").append(media.id)
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

    // GET the stream API, decrypt data.stream_urls when needed, return the URLs.
    private suspend fun fetchStreamUrls(apiUrl: String): List<String>? {
        val res = withTimeoutOrNull(12_000L) { app.get(apiUrl, headers = playerHeaders()) }
        if (res == null) {
            trace("S1 api timeout")
            return null
        }
        val body = res.text
        trace("S1 api HTTP ${res.code} len=${body.length}")

        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            trace("S1 not json: ${snippet(body)}")
            return null
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

    private suspend fun emitLink(
        callback: (ExtractorLink) -> Unit,
        url: String,
        referer: String,
        label: String
    ) {
        callback(
            newExtractorLink(
                source = name,
                name = label,
                url = url,
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

    private suspend fun resolveStreams(
        media: HayyaMediaData,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val urls = fetchStreamUrls(streamApiUrl(media)) ?: return false
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
            found = withTimeoutOrNull(60_000L) { resolveStreams(media, callback) }
                ?: run {
                    trace("timeout 60s")
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
