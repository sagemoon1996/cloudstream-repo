package com.sagemoon1996.hayyashoot

import android.net.Uri
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

        val items = response?.results
            .orEmpty()
            .mapNotNull { toSearchResponse(it, isMovie) }
            .distinctBy { it.url }

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
            this.score = movie.voteAverage?.let { Score.from10(it) }
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
            this.score = tv.voteAverage?.let { Score.from10(it) }
        }
    }

    // ---------------------------------------------------------------
    // loadLinks: Server 2 only = @definisi/vidsrc-scraper 2.0.2 logic
    // ---------------------------------------------------------------

    // true  = if NO server gives a link, "DEBUG ..." entries appear in the
    //         links list (one per server tried) and explain which step failed.
    // false = production (no fake links).
    private val debugMode = true

    private class StepFailure(val step: String, val detail: String) :
        Exception("$step $detail")

    private data class VidSrcResult(
        val streams: List<String>,
        val subtitles: List<String>
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
    // gives a stream wins. The first one is the one from the scraper 2.0.2,
    // the second is the original HayyaShoot embed (with sub=ar).
    private fun embedCandidates(media: HayyaMediaData): List<String> {
        val isTv = media.type == "tv" && media.season != null && media.episode != null
        return if (isTv) {
            val s = media.season
            val e = media.episode
            listOf(
                "https://vidsrc-embed.ru/embed/tv/${media.id}/$s/$e",
                "https://vidsrc.me/embed/tv?tmdb=${media.id}&season=$s&episode=$e&sub=ar",
                "https://vidsrc.xyz/embed/tv?tmdb=${media.id}&season=$s&episode=$e&sub=ar",
                "https://vidsrc.net/embed/tv?tmdb=${media.id}&season=$s&episode=$e&sub=ar"
            )
        } else {
            listOf(
                "https://vidsrc-embed.ru/embed/movie/${media.id}",
                "https://vidsrc.me/embed/movie?tmdb=${media.id}&sub=ar",
                "https://vidsrc.xyz/embed/movie?tmdb=${media.id}&sub=ar",
                "https://vidsrc.net/embed/movie?tmdb=${media.id}&sub=ar"
            )
        }
    }

    // embed page -> cloudnestra RCP -> /prorcp/hash -> file: -> HLS (+ subtitles)
    // Throws StepFailure with the failing step so the caller can try the next host.
    private suspend fun resolveVidSrc(embedUrl: String): VidSrcResult {
        // Step 1-2: embed page, RCP iframe (fallback: first absolute iframe)
        val embedRes = app.get(embedUrl, headers = browserHeaders)
        val embedHtml = embedRes.text

        var rcpUrl = RCP_REGEX.find(embedHtml)?.groupValues?.getOrNull(1)
            ?: IFRAME_REGEX.find(embedHtml)?.groupValues?.getOrNull(1)
            ?: throw StepFailure(
                "2-no-iframe",
                "HTTP ${embedRes.code} len=${embedHtml.length} ${snippet(embedHtml)}"
            )
        if (rcpUrl.startsWith("//")) rcpUrl = "https:$rcpUrl"

        // Step 3: RCP page (Referer = embed URL)
        val rcpRes = app.get(
            rcpUrl,
            headers = browserHeaders + mapOf("Referer" to embedUrl)
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
            ?: throw StepFailure(
                "6-no-file",
                "HTTP ${prorcpRes.code} len=${prorcpHtml.length} ${snippet(prorcpHtml)}"
            )

        // Step 7: every " or " alternative, {v1}..{v5} -> cloudnestra.com
        val streams = rawFile
            .split(" or ")
            .map { it.trim().replace(PLACEHOLDER_REGEX, "cloudnestra.com") }
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

        return VidSrcResult(streams, subtitles)
    }

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
            if (debugMode) reportFailure(callback, "0-bad-data ${data.take(80)}")
            return false
        }

        val candidates = embedCandidates(media)
        val failures = ArrayList<String>()
        var found = false

        // Try each VidSrc host until one gives a stream
        for (embedUrl in candidates) {
            val host = Uri.parse(embedUrl).host ?: embedUrl

            try {
                val result = resolveVidSrc(embedUrl)

                result.streams.forEachIndexed { index, streamUrl ->
                    callback(
                        newExtractorLink(
                            source = name,
                            name = if (result.streams.size > 1) "VidSrc ${index + 1}" else "VidSrc",
                            url = streamUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "https://cloudnestra.com/"
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf("User-Agent" to userAgent)
                        }
                    )
                }

                result.subtitles.forEach { subtitleUrl ->
                    subtitleCallback(SubtitleFile("Arabic", subtitleUrl))
                }

                found = true
                break
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is StepFailure) {
                    failures.add("$host ${e.step} ${e.detail}")
                } else {
                    logError(e)
                    failures.add("$host exception ${e::class.java.simpleName}: ${e.message}")
                }
            }
        }

        // Last resort: CloudStream's built-in extractors on the first embed URL
        if (!found) {
            try {
                found = loadExtractor(
                    candidates.first(),
                    "$mainUrl/",
                    subtitleCallback,
                    callback
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logError(e)
            }
        }

        if (!found && debugMode) {
            failures.take(4).forEach { reportFailure(callback, it) }
        }

        return found
    }

    // ---------------------------------------------------------------
    // Regexes copied from scraper.js 2.0.2
    // ---------------------------------------------------------------

    private companion object {
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
        val FILE_REGEX = Regex("""file:\s*["']([^"']+)["']""")
        val PLACEHOLDER_REGEX = Regex("""\{v[1-5]\}""")
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
