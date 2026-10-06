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

    private suspend fun getAuthToken(
        forceRefresh: Boolean = false
    ): String {
        if (!forceRefresh) cachedToken?.let { return it }

        return tokenMutex.withLock {
            if (!forceRefresh) cachedToken?.let { return@withLock it }

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
    ) =
        app.get(
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

    private fun slug(title: String): String =
        URLEncoder.encode(
            title.replace(
                Regex("""[\s-]+"""),
                "-"
            ).trim('-'),
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
        val itemTitle =
            (if (isMovie) item.title else item.name)
                ?.takeIf { it.isNotBlank() }
                ?: return null

        val poster =
            item.posterPath?.let {
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

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val isMovie =
            request.data.startsWith("movie")

        val response =
            fetchList(
                "${request.data}?language=ar-SA&page=$page"
            )

        val baseItems =
            response?.results
                .orEmpty()
                .mapNotNull {
                    toSearchResponse(
                        it,
                        isMovie
                    )
                }
                .distinctBy { it.url }

        val items =
            if (
                debugMode &&
                page == 1 &&
                isMovie
            ) {
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
            hasNext =
                page <
                    (response?.totalPages ?: 1)
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        val q =
            URLEncoder.encode(
                trimmed,
                "UTF-8"
            )

        return coroutineScope {
            val movies =
                async {
                    fetchList(
                        "search/movie?query=$q&language=ar-SA&page=1"
                    )
                }

            val shows =
                async {
                    fetchList(
                        "search/tv?query=$q&language=ar-SA&page=1"
                    )
                }

            val movieResults =
                movies.await()
                    ?.results
                    .orEmpty()
                    .mapNotNull {
                        toSearchResponse(
                            it,
                            true
                        )
                    }

            val showResults =
                shows.await()
                    ?.results
                    .orEmpty()
                    .mapNotNull {
                        toSearchResponse(
                            it,
                            false
                        )
                    }

            (movieResults + showResults)
                .distinctBy { it.url }
        }
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        if (
            debugMode &&
            url.endsWith("/diag")
        ) {
            return newMovieLoadResponse(
                "Diagnostics $buildTag",
                url,
                TvType.Movie,
                "diag"
            ) {
                this.plot =
                    lastDiagnostic
                        ?: "No trace yet. Play an episode, then refresh the home page."
            }
        }

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

        val movie =
            parseJson<TmdbMovie>(
                tmdbGet(
                    "movie/$id?language=ar-SA"
                )
            )

        val title =
            movie.title
                ?.takeIf { it.isNotBlank() }
                ?: return null

        val data =
            HayyaMediaData(
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
                movie.posterPath
                    ?.let {
                        posterBase + it
                    }

            this.backgroundPosterUrl =
                movie.backdropPath
                    ?.let {
                        backdropBase + it
                    }

            this.plot =
                movie.overview

            this.year =
                yearOf(movie.releaseDate)
        }
    }

    private suspend fun loadTv(
        url: String,
        id: Int
    ): LoadResponse? {

        val tv =
            parseJson<TmdbTv>(
                tmdbGet(
                    "tv/$id?language=ar-SA"
                )
            )

        val title =
            tv.name
                ?.takeIf { it.isNotBlank() }
                ?: return null

        val seasons =
            tv.seasons
                .orEmpty()
                .filter {
                    it.seasonNumber > 0 &&
                        (it.episodeCount ?: 1) > 0
                }

        val seasonData =
            coroutineScope {
                seasons.map { season ->
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
                }.awaitAll()
            }
                .filterNotNull()
                .sortedBy { it.first }

        val episodes =
            seasonData.flatMap {
                (seasonNumber, data) ->

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
                                ep.stillPath
                                    ?.let {
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
                tv.posterPath
                    ?.let {
                        posterBase + it
                    }

            this.backgroundPosterUrl =
                tv.backdropPath
                    ?.let {
                        backdropBase + it
                    }

            this.plot =
                tv.overview

            this.year =
                yearOf(tv.firstAirDate)
        }
    }

    // ---------------------------------------------------------------
    // Server 2 - VidSrc
    // ---------------------------------------------------------------

    private val debugMode = true

    private val buildTag = "build-16-clean"

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
        text.take(120)
            .replace(
                Regex("""\s+"""),
                " "
            )

    private suspend fun reportFailure(
        callback: (ExtractorLink) -> Unit,
        text: String
    ) {
        val label =
            "DEBUG $text"
                .replace(
                    Regex("""\s+"""),
                    " "
                )
                .take(220)

        callback(
            newExtractorLink(
                name,
                label,
                "https://debug.invalid/",
                ExtractorLinkType.VIDEO
            ) {
                this.quality =
                    Qualities.Unknown.value
            }
        )
    }

    private fun embedCandidates(
        media: HayyaMediaData
    ): List<String> {

        val isTv =
            media.type == "tv" &&
                media.season != null &&
                media.episode != null

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

    private fun originOf(
        url: String
    ): String {
        val u = Uri.parse(url)
        return "${u.scheme}://${u.authority}/"
    }

    /*
     * IMPORTANT:
     *
     * The WebView must load the VidSrc embed itself.
     * We do not open the CloudOrchestra URL as a separate
     * normal HTTP request.
     *
     * The network logs already proved that the embedded
     * VidSrc player reaches the real stream requests:
     *
     * /pl/H4s...
     * /pI/H4s...
     * *.m3u8
     */
    private suspend fun sniffM3u8(
        url: String,
        referer: String,
        timeoutMs: Long
    ): String? =
        try {
            withTimeoutOrNull(timeoutMs) {

                val res =
                    app.get(
                        url,
                        referer = referer,
                        interceptor =
                            WebViewResolver(
                                STREAM_URL_REGEX
                            )
                    )

                val captured =
                    res.url

                trace(
                    "webview result " +
                        captured.take(220)
                )

                captured.takeIf {
                    STREAM_URL_REGEX
                        .containsMatchIn(it)
                }
            }
        } catch (e: Exception) {

            if (e is CancellationException) {
                throw e
            }

            logError(e)

            trace(
                "webview exception " +
                    "${e::class.java.simpleName}"
            )

            null
        }

    /*
     * VidSrc:
     *
     * embed
     * -> data-api
     * -> dynamically generated CloudOrchestra player
     *
     * We only use this to discover the player URL.
     * The actual stream is captured from the embedded WebView.
     */
    private suspend fun resolveVsSrc(
        embedUrl: String
    ): VidSrcResult {

        val embedRes =
            app.get(
                embedUrl,
                headers =
                    browserHeaders +
                        mapOf(
                            "Referer" to "$mainUrl/"
                        )
            )

        val embedHtml =
            embedRes.text

        val embedPageUrl =
            embedRes.url
                .ifBlank {
                    embedUrl
                }

        val dataApi =
            DATA_API_REGEX
                .find(embedHtml)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace(
                    "&amp;",
                    "&"
                )

        trace(
            "embed HTTP=${embedRes.code} " +
                "host=${Uri.parse(embedPageUrl).host} " +
                "data-api=${dataApi != null}"
        )

        if (dataApi == null) {
            throw StepFailure(
                "no-data-api",
                "HTTP ${embedRes.code} " +
                    "len=${embedHtml.length}"
            )
        }

        val apiUrl =
            try {
                URI(embedPageUrl)
                    .resolve(dataApi)
                    .toString()
            } catch (_: Exception) {
                dataApi
            }

        val apiRes =
            app.get(
                apiUrl,
                headers =
                    browserHeaders +
                        mapOf(
                            "Referer" to embedPageUrl,
                            "Accept" to
                                "application/json, text/plain, */*",
                            "X-Requested-With" to
                                "XMLHttpRequest"
                        )
            )

        val playerUrl =
            try {
                parseJson<VsSrcResponse>(
                    apiRes.text
                ).src
                    ?.replace(
                        "\\/",
                        "/"
                    )
                    ?.takeIf {
                        it.startsWith("http")
                    }
            } catch (e: Exception) {

                if (e is CancellationException) {
                    throw e
                }

                null
            }

        trace(
            "vs_src HTTP=${apiRes.code} " +
                "player=${playerUrl?.take(160)}"
        )

        if (playerUrl.isNullOrBlank()) {
            throw StepFailure(
                "no-player",
                "HTTP ${apiRes.code} " +
                    snippet(apiRes.text)
            )
        }

        /*
         * FIRST:
         *
         * Load the actual VidSrc embed in WebView.
         *
         * This is the important difference from the previous
         * build: we do not detach CloudOrchestra from its iframe
         * context.
         */
        trace(
            "webview VidSrc embed"
        )

        val embedStream =
            sniffM3u8(
                embedUrl,
                "$mainUrl/",
                45_000L
            )

        if (!embedStream.isNullOrBlank()) {

            trace(
                "embed stream HIT " +
                    embedStream.take(180)
            )

            return VidSrcResult(
                streams =
                    listOf(embedStream),
                subtitles =
                    emptyList(),
                referer =
                    originOf(embedPageUrl)
            )
        }

        /*
         * SECOND:
         *
         * Only if the complete embedded VidSrc flow did not
         * expose the stream, let WebView load the dynamically
         * generated player with the VidSrc origin as referer.
         */
        trace(
            "webview player fallback"
        )

        val playerStream =
            sniffM3u8(
                playerUrl,
                originOf(embedPageUrl),
                30_000L
            )

        if (!playerStream.isNullOrBlank()) {

            trace(
                "player stream HIT " +
                    playerStream.take(180)
            )

            return VidSrcResult(
                streams =
                    listOf(playerStream),
                subtitles =
                    emptyList(),
                referer =
                    originOf(embedPageUrl)
            )
        }

        throw StepFailure(
            "stream-not-captured",
            "VidSrc WebView did not capture HLS"
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

        val media =
            try {
                parseJson<HayyaMediaData>(
                    data
                )
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

        val candidates =
            embedCandidates(media)

        val failures =
            ArrayList<String>()

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

                        trace(
                            "TRY $host index=$index"
                        )

                        val result =
                            resolveVsSrc(
                                embedUrl
                            )

                        result.streams
                            .forEachIndexed {
                                streamIndex,
                                streamUrl ->

                                callback(
                                    newExtractorLink(
                                        source = name,
                                        name =
                                            if (
                                                result.streams.size > 1
                                            ) {
                                                "VidSrc ${streamIndex + 1}"
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
                            subtitleCallback(
                                SubtitleFile(
                                    "Arabic",
                                    it
                                )
                            )
                        }

                        trace(
                            "OK $host " +
                                "streams=${result.streams.size}"
                        )

                        return@withTimeoutOrNull true

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
                    }
                }

                false

            } ?: false

        if (completed) {

            lastDiagnostic =
                traceLines
                    .joinToString("\n")

            return true
        }

        /*
         * Keep the original CloudStream extractor fallback.
         */
        try {

            trace(
                "BUILT-IN extractor"
            )

            val extracted =
                withTimeoutOrNull(
                    20_000L
                ) {
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
                    traceLines
                        .joinToString("\n")

                return true
            }

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

    private fun trace(
        msg: String
    ) {
        val line =
            "${System.currentTimeMillis() - traceStart}ms $msg"

        traceLines.add(line)

        Log.e(
            "HayyaShoot",
            line
        )
    }

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

        val DATA_API_REGEX =
            Regex(
                """data-api=["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            )

        /*
         * The WebView capture must match the actual requests
         * observed in the browser.
         */
        val STREAM_URL_REGEX =
            Regex(
                """(?:https?://[^"'\\\s]+(?:\.m3u8(?:\?[^"'\\\s]*)?|/p[li]/H4s[il][^"'\\\s]*)|(?:/p[li]/H4s[il][^"'\\\s]*))""",
                RegexOption.IGNORE_CASE
            )
    }

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
