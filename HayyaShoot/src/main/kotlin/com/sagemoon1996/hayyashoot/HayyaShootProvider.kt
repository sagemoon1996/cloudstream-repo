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

    private val tokenMutex = Mutex()

    @Volatile
    private var cachedToken: String? = null

    private val tokenPatterns = listOf(
        Regex("""auth_?tokens*[=:]s*["'`]([^"'`]+)["'`]""", RegexOption.IGNORE_CASE),
        Regex("""Bearers+(eyJ[w-]+.[w-]+.[w-]+)""")
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
        if (response.code == 401 || response.code == 403) {
            response = requestTmdb(path, getAuthToken(forceRefresh = true))
        }
        return response.text
    }

    private fun slug(title: String): String =
        URLEncoder.encode(
            title.replace(Regex("""[s-]+"""), "-").trim('-'),
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

    override suspend fun search(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()
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

    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("/manual?u=")) {
            val streamUrl = Uri.parse(url).getQueryParameter("u") ?: return null
            return newMovieLoadResponse(
                "Pasted stream",
                url,
                TvType.Movie,
                "manual:$streamUrl"
            ) {
                this.plot = "Plays the playlist URL you pasted in the search box.
" +
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
        val seasons = tv.seasons
            .orEmpty()
            .filter { it.seasonNumber > 0 && (it.episodeCount ?: 1) > 0 }
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

    private val debugMode = true
    private val buildTag = "build-29"

    private fun snippet(text: String) =
        text.take(120).replace(Regex("""s+"""), " ")

    private fun trace(msg: String) {
        val line = "${System.currentTimeMillis() - traceStart}ms $msg"
        traceLines.add(line)
        Log.e("HayyaShoot", line)
    }

    private fun originOf(url: String): String {
        val u = Uri.parse(url)
        return "${u.scheme}://${u.authority}/"
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

    private suspend fun decryptViaWebView(encB64: String, wasmB64: String): String? {
        val hitUrl: String? = try {
            withTimeoutOrNull(25_000L) {
                val resolver = WebViewResolver(
                    Regex("""hayya.invalid/r?d="""),
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
        "sec-ch-ua" to ""Chromium";v="124", "Google Chrome";v="124", "Not-A.Brand";v="99"",
        "sec-ch-ua-mobile" to "?1",
        "sec-ch-ua-platform" to ""Android""
    )

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
        val src = Regex(""""src"s*:s*"([^"]+)"""").find(vr.text)
            ?.groupValues?.getOrNull(1)?.replace("\\/", "/")
        trace("S0 vs_src HTTP ${vr.code} src=${src?.substringAfter("://")?.take(60)}")
        return src
    }

    private fun compact(t: String) = t.replace(Regex("""s+"""), " ")

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
        val innerRel = Regex(""""playerUrl"s*:s*"([^"]+)"""").find(oh)
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
        val token = Regex(""""apiToken"s*:s*"([^"]+)"""").find(ih)?.groupValues?.getOrNull(1)
        trace("P2 inner HTTP ${inner?.code} len=${ih.length} apiToken=${token?.length}")
        if (token.isNullOrBlank()) {
            traceContexts("P2 html", ih, listOf("token", "CONFIG"), 60, 220, 3)
            return null
        }
        var finalApi = apiUrl
        if (idKey != "tmdb") {
            try {
                val cfgJson = Regex("""window.CONFIGs*=s*({.*?});""", RegexOption.DOT_MATCHES_ALL)
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
        if (debugMode && !subsDumped) {
            subsDumped = true
            try {
                val subSrc = Regex("""src=["']([^"']+subtitles[^"']*.js[^"']*)["']""", RegexOption.IGNORE_CASE)
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
        val imdb = fetchImdbId(media)
        trace("P6 imdb=$imdb")
        if (imdb != null) {
            delay(1000)
            fetchViaBrowserFlow(media, streamApiUrl(media, "imdb", imdb), "imdb", imdb)
                ?.let { return it }
        }
        return null
    }

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
        json.optJSONObject("data")?.let { d -> meta["imdb"] = jstr(d, "imdb_id") }
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
        return text.split("
").map { it.trim() }.filter { it.startsWith("http") }
    }

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

    private fun applyToken(url: String, token: String): String {
        if (token.isBlank()) return url
        if (url.contains("__TOKEN__")) return url.split("__TOKEN__").joinToString(token)
        return url + (if (url.contains("?")) "&" else "?") + "token=" + token
    }

    private suspend fun downloadPlain(url: String): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                val safe = if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url
                val c = java.net.URL(safe).openConnection() as java.net.HttpURLConnection
                c.instanceFollowRedirects = true
                c.connectTimeout = 8_000
                c.readTimeout = 10_000
                c.setRequestProperty("User-Agent", "TemporaryUserAgent")
                val code = c.responseCode
                val type = c.contentType
                val bytes = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() }
                    ?: ByteArray(0)
                val gz = bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
                trace(
                    "T3 download HTTP $code type=$type bytes=${bytes.size} gzip=$gz " +
                        "host=${java.net.URL(safe).host} " +
                        (if (!gz) "head=${snippet(String(bytes.copyOf(minOf(bytes.size, 120)), Charsets.ISO_8859_1))}" else "")
                )
                if (code in 200..299) bytes else null
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                trace("T3 download failed ${e::class.java.simpleName}: ${e.message?.take(80)}")
                null
            }
        }

    // === الدالة المعدّلة: UTF-8 مع BOM ===
    private fun toUtf8Srt(raw: ByteArray, declaredEncoding: String): ByteArray {
        var b = raw
        if (b.size > 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte()) {
            b = java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(b)).readBytes()
            trace("T3 gunzipped: ${raw.size} -> ${b.size} bytes")
        }
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) {
            b = b.copyOfRange(3, b.size)
            trace("T3 removed UTF-8 BOM")
        }
        val charset = when {
            declaredEncoding.equals("utf-8", true) || declaredEncoding.equals("utf8", true) -> Charsets.UTF_8
            declaredEncoding.equals("windows-1256", true) || declaredEncoding.equals("cp1256", true) -> Charsets.forName("windows-1256")
            declaredEncoding.equals("iso-8859-6", true) -> Charsets.forName("iso-8859-6")
            else -> {
                try {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(b))
                    Charsets.UTF_8
                } catch (e: Exception) {
                    Charsets.forName("windows-1256")
                }
            }
        }
        trace("T3 using charset: $charset (declared: $declaredEncoding)")
        val text = String(b, charset)
        return byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)
    }

    private suspend fun addArabicSubtitles(
        media: HayyaMediaData,
        imdb: String?,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val digits = imdb.orEmpty().filter { it.isDigit() }
        if (digits.isBlank()) {
            trace("T1 no imdb id in the API answer")
            return
        }
        val isTv = media.type == "tv" && media.season != null && media.episode != null
        val searchUrl = "https://rest.opensubtitles.org/search/" +
            (if (isTv) "episode-${media.episode}/" else "") +
            "imdbid-$digits/" +
            (if (isTv) "season-${media.season}/" else "") +
            "sublanguageid-ara"
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
            trace("T1 opensubtitles timeout")
            return
        }
        val text = res.text
        trace("T1 opensubtitles HTTP ${res.code} len=${text.length} ${searchUrl.substringAfter("search/")}")
        val arr = try {
            JSONArray(text)
        } catch (e: Exception) {
            trace("T1 not json: ${snippet(text)}")
            return
        }
        val rows = (0 until arr.length())
            .mapNotNull { arr.optJSONObject(it) }
            .filter {
                jstr(it, "SubLanguageID") == "ara" &&
                    jstr(it, "SubFormat").equals("srt", true) &&
                    jstr(it, "SubDownloadLink").isNotBlank()
            }
            .sortedByDescending { jstr(it, "SubDownloadsCnt").toIntOrNull() ?: 0 }
            .take(3)
        trace("T2 arabic srt found=${rows.size} of ${arr.length()}")
        var n = 0
        for (row in rows) {
            val link = jstr(row, "SubDownloadLink")
            val raw = downloadPlain(link)
            if (raw == null || raw.isEmpty()) continue
            val sampleText = try {
                String(raw.take(minOf(raw.size, 200)).toByteArray(), Charsets.UTF_8)
            } catch (e: Exception) {
                String(raw.take(minOf(raw.size, 200)).toByteArray(), Charsets.forName("windows-1256"))
            }
            trace("T3 sample: ${sampleText.take(80)}")
            val srt = try {
                toUtf8Srt(raw, jstr(row, "SubEncoding"))
            } catch (e: Exception) {
                trace("T3 decode failed ${e::class.java.simpleName}: ${e.message}")
                continue
            }
            val srtText = String(srt, Charsets.UTF_8)
            val cues = Regex("-->").findAll(srtText).count()
            if (cues < 5) {
                trace("T3 not a real srt (cues=$cues) head=${snippet(srtText)}")
                continue
            }
            val id = (jstr(row, "IDSubtitleFile").ifBlank { "s$n" } + srt.size)
                .filter { it.isLetterOrDigit() }
            val served = LocalSubServer.publish(id, srt)
            trace("T3 ${jstr(row, "SubFileName").take(48)} enc=${jstr(row, "SubEncoding")} bytes=${srt.size} -> ${served ?: "local server failed"}")
            if (served != null) {
                subtitleCallback(SubtitleFile(n++, served))
            }
            n++
        }
    }

    override suspend fun loadLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        traceStart = System.currentTimeMillis()
        traceLines.clear()
        lastDiagnostic = null
        trace("$buildTag start ${data.take(80)}")
        if (data == "diag") {
            lastDiagnostic = traceLines.joinToString("
")
            return true
        }
        if (data.startsWith("manual:")) {
            val url = data.removePrefix("manual:")
            callback(
                newExtractorLink(
                    "Pasted stream",
                    "manual",
                    url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.headers = mapOf("Referer" to originOf(url))
                }
            )
            return true
        }
        val media = parseJson<HayyaMediaData>(data)
        val meta = mutableMapOf<String, String>()
        val streamUrls = fetchStreamUrls(streamApiUrl(media), media, meta)
        if (streamUrls.isNullOrEmpty()) {
            trace("end found=false")
            lastDiagnostic = traceLines.joinToString("
")
            if (debugMode) copyToClipboard(traceLines.joinToString("
"))
            return false
        }
        val referer = originOf(streamUrls.first())
        for ((i, url) in streamUrls.withIndex()) {
            val (ok, msg) = playlistCheck(url, referer)
            trace("S5 #${i + 1} ${if (ok) "OK" else "FAIL"} $msg")
            if (ok) {
                callback(
                    newExtractorLink(
                        "VidSrc #${i + 1}",
                        "vidsrc-$i",
                        url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.headers = mapOf(
                            "Referer" to referer,
                            "Origin" to referer.trimEnd('/')
                        ) + browserHints()
                    }
                )
            }
        }
        meta["imdb"]?.let { imdb ->
            addArabicSubtitles(media, imdb, subtitleCallback)
        }
        trace("end found=true")
        lastDiagnostic = traceLines.joinToString("
")
        if (debugMode) copyToClipboard(traceLines.joinToString("
"))
        return true
    }

    data class HayyaMediaData(
        val type: String,
        val id: Int,
        val season: Int? = null,
        val episode: Int? = null
    )

    data class TmdbResponse(
        val results: List<TmdbItem>,
        @JsonProperty("total_pages") val totalPages: Int
    )

    data class TmdbItem(
        val id: Int,
        val title: String?,
        val name: String?,
        @JsonProperty("poster_path") val posterPath: String?,
        @JsonProperty("backdrop_path") val backdropPath: String?,
        @JsonProperty("release_date") val releaseDate: String?,
        @JsonProperty("first_air_date") val firstAirDate: String?,
        val overview: String?
    )

    data class TmdbMovie(
        override val id: Int,
        override val title: String?,
        override val posterPath: String?,
        override val backdropPath: String?,
        override val releaseDate: String?,
        override val overview: String?
    ) : TmdbItem(id, title, null, posterPath, backdropPath, releaseDate, null, overview)

    data class TmdbTv(
        override val id: Int,
        override val name: String?,
        override val posterPath: String?,
        override val backdropPath: String?,
        @JsonProperty("first_air_date") override val firstAirDate: String?,
        override val overview: String?,
        val seasons: List<TmdbSeason>?
    ) : TmdbItem(id, null, name, posterPath, backdropPath, null, firstAirDate, overview)

    data class TmdbSeason(
        @JsonProperty("season_number") val seasonNumber: Int,
        val episodes: List<TmdbEpisode>?
    )

    data class TmdbEpisode(
        @JsonProperty("episode_number") val episodeNumber: Int,
        val name: String?,
        @JsonProperty("still_path") val stillPath: String?,
        val overview: String?
    )

    companion object {
        private var traceStart = 0L
        private val traceLines = mutableListOf<String>()
        private var lastDiagnostic: String? = null
        private var subsDumped = false
    }
                                                               }
