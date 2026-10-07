package com.sagemoon1996.hayyashoot

import android.net.Uri
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException

class HayyaShootProvider : MainAPI() {

    override var mainUrl = "https://hayyashoot.com"
    override var name = "HayyaShoot2"
    override var lang = "ar"

    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "movie/popular" to "أفلام",
        "tv/popular" to "مسلسلات"
    )

    private val ua =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar,en;q=0.8"
    )

    private val posterBase = "https://image.tmdb.org/t/p/w500"
    private val backdropBase = "https://image.tmdb.org/t/p/original"

    private val tokenMutex = Mutex()

    @Volatile
    private var token: String? = null

    private fun getYear(s: String?) =
        s?.take(4)?.toIntOrNull()

    private fun slug(s: String) =
        URLEncoder.encode(
            s.replace(Regex("""[\s-]+"""), "-").trim('-'),
            "UTF-8"
        )

    private fun movieUrl(id: Int, title: String) =
        "$mainUrl/movies/?movie=$id&title=${slug(title)}"

    private fun tvUrl(id: Int, title: String) =
        "$mainUrl/movies/?tv=$id&title=${slug(title)}"

    // ---------------------------------------------------------
    // TMDB AUTH
    // ---------------------------------------------------------

    private fun findToken(text: String): String? {
        val patterns = listOf(
            Regex(
                """auth_?token\s*[=:]\s*["'`]([^"'`]+)["'`]""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """Bearer\s+(eyJ[\w-]+\.[\w-]+\.[\w-]+)"""
            )
        )

        return patterns.firstNotNullOfOrNull {
            it.find(text)?.groupValues?.getOrNull(1)
        }
    }

    private suspend fun authToken(force: Boolean = false): String {
        if (!force) token?.let { return it }

        return tokenMutex.withLock {
            if (!force) token?.let { return@withLock it }

            val html = app.get(
                "$mainUrl/movies/",
                headers = headers
            ).text

            findToken(html)?.let {
                token = it
                return@withLock it
            }

            val scripts = Regex(
                """<script[^>]+src=["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ).findAll(html)

            for (m in scripts) {
                val url = fixUrl(m.groupValues[1])

                if (
                    Uri.parse(url).host
                        ?.endsWith("hayyashoot.com") != true
                ) continue

                try {
                    findToken(
                        app.get(url, headers = headers).text
                    )?.let {
                        token = it
                        return@withLock it
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }

            throw ErrorLoadingException(
                "HayyaShoot AUTH token not found"
            )
        }
    }

    private suspend fun tmdb(path: String): String {
        var r = app.get(
            "https://api.themoviedb.org/3/$path",
            headers = mapOf(
                "User-Agent" to ua,
                "Authorization" to "Bearer ${authToken()}",
                "Accept" to "application/json"
            )
        )

        if (r.code == 401 || r.code == 403) {
            r = app.get(
                "https://api.themoviedb.org/3/$path",
                headers = mapOf(
                    "User-Agent" to ua,
                    "Authorization" to
                        "Bearer ${authToken(true)}",
                    "Accept" to "application/json"
                )
            )
        }

        return r.text
    }

    // ---------------------------------------------------------
    // MAIN / SEARCH
    // ---------------------------------------------------------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val movie = request.data.startsWith("movie")

        val data = parseJson<TmdbList>(
            tmdb(
                "${request.data}?language=ar-SA&page=$page"
            )
        )

        val items = data.results.orEmpty().mapNotNull {
            makeSearch(it, movie)
        }

        return newHomePageResponse(
            request.name,
            items,
            page < (data.totalPages ?: 1)
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        if (query.isBlank()) return emptyList()

        val q = URLEncoder.encode(query.trim(), "UTF-8")

        return coroutineScope {

            val movies = async {
                runCatching {
                    parseJson<TmdbList>(
                        tmdb(
                            "search/movie?query=$q&language=ar-SA&page=1"
                        )
                    )
                }.getOrNull()
            }

            val shows = async {
                runCatching {
                    parseJson<TmdbList>(
                        tmdb(
                            "search/tv?query=$q&language=ar-SA&page=1"
                        )
                    )
                }.getOrNull()
            }

            (
                movies.await()?.results.orEmpty()
                    .mapNotNull { makeSearch(it, true) } +
                shows.await()?.results.orEmpty()
                    .mapNotNull { makeSearch(it, false) }
            ).distinctBy { it.url }
        }
    }

    private fun makeSearch(
        item: TmdbItem,
        movie: Boolean
    ): SearchResponse? {

        val title =
            if (movie) item.title else item.name
                ?: return null

        val url =
            if (movie) movieUrl(item.id, title)
            else tvUrl(item.id, title)

        return if (movie) {
            newMovieSearchResponse(
                title,
                url,
                TvType.Movie
            ) {
                posterUrl =
                    item.posterPath?.let { posterBase + it }
                year = getYear(item.releaseDate)
            }
        } else {
            newTvSeriesSearchResponse(
                title,
                url,
                TvType.TvSeries
            ) {
                posterUrl =
                    item.posterPath?.let { posterBase + it }
                year = getYear(item.firstAirDate)
            }
        }
    }

    // ---------------------------------------------------------
    // LOAD
    // ---------------------------------------------------------

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val u = Uri.parse(url)

        u.getQueryParameter("movie")
            ?.toIntOrNull()
            ?.let {
                return loadMovie(url, it)
            }

        u.getQueryParameter("tv")
            ?.toIntOrNull()
            ?.let {
                return loadTv(url, it)
            }

        return null
    }

    private suspend fun loadMovie(
        url: String,
        id: Int
    ): LoadResponse {

        val m = parseJson<TmdbMovie>(
            tmdb("movie/$id?language=ar-SA")
        )

        val title = m.title ?: "Movie"

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            HayyaData("movie", id).toJson()
        ) {
            posterUrl =
                m.posterPath?.let { posterBase + it }

            backgroundPosterUrl =
                m.backdropPath?.let { backdropBase + it }

            plot = m.overview
            year = getYear(m.releaseDate)
        }
    }

    private suspend fun loadTv(
        url: String,
        id: Int
    ): LoadResponse {

        val tv = parseJson<TmdbTv>(
            tmdb("tv/$id?language=ar-SA")
        )

        val episodes = coroutineScope {

            tv.seasons.orEmpty()
                .filter {
                    it.seasonNumber > 0 &&
                        (it.episodeCount ?: 0) > 0
                }
                .flatMap { season ->

                    val data = async {
                        runCatching {
                            parseJson<TmdbSeason>(
                                tmdb(
                                    "tv/$id/season/" +
                                        "${season.seasonNumber}" +
                                        "?language=ar-SA"
                                )
                            )
                        }.getOrNull()
                    }

                    listOf(season to data)
                }
                .mapNotNull { (season, deferred) ->
                    deferred.await()
                        ?.episodes
                        ?.map { ep ->

                            newEpisode(
                                HayyaData(
                                    "tv",
                                    id,
                                    season.seasonNumber,
                                    ep.episodeNumber
                                ).toJson()
                            ) {
                                name =
                                    ep.name
                                        ?.takeIf { it.isNotBlank() }
                                        ?: "الحلقة ${ep.episodeNumber}"

                                this.season =
                                    season.seasonNumber

                                episode =
                                    ep.episodeNumber

                                description =
                                    ep.overview

                                posterUrl =
                                    ep.stillPath?.let {
                                        posterBase + it
                                    }
                            }
                        }
                }
                .flatten()
        }

        return newTvSeriesLoadResponse(
            tv.name ?: "TV",
            url,
            TvType.TvSeries,
            episodes
        ) {
            posterUrl =
                tv.posterPath?.let { posterBase + it }

            backgroundPosterUrl =
                tv.backdropPath?.let { backdropBase + it }

            plot = tv.overview
            year = getYear(tv.firstAirDate)
        }
    }

    // ---------------------------------------------------------
    // LINKS
    // ---------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val media = runCatching {
            parseJson<HayyaData>(data)
        }.getOrNull() ?: return false

        val embed =
            if (media.type == "tv") {
                "https://vidsrc.sh/embed/tv/" +
                    "${media.id}/${media.season}/${media.episode}"
            } else {
                "https://vidsrc.sh/embed/movie/${media.id}"
            }

        val api =
            if (media.type == "tv") {
                "https://data.vidsrc.sh/api.php" +
                    "?type=tv" +
                    "&tmdb=${media.id}" +
                    "&season=${media.season}" +
                    "&episode=${media.episode}" +
                    "&stream_urls"
            } else {
                "https://data.vidsrc.sh/api.php" +
                    "?type=movie" +
                    "&tmdb=${media.id}" +
                    "&stream_urls"
            }

        val result = runWasm(
            embed,
            api
        ) ?: return false

        result.streams.forEachIndexed { i, stream ->

            callback(
                newExtractorLink(
                    "VidSrc",
                    if (result.streams.size > 1)
                        "VidSrc ${i + 1}"
                    else
                        "VidSrc",
                    stream,
                    ExtractorLinkType.M3U8
                ) {
                    referer = result.referer
                    quality = Qualities.Unknown.value
                    headers = mapOf(
                        "User-Agent" to ua
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

        return result.streams.isNotEmpty()
    }

    // ---------------------------------------------------------
    // VIDSRС + WASM
    // ---------------------------------------------------------

    private data class WasmResult(
        val streams: List<String>,
        val subtitles: List<String>,
        val referer: String
    )

    private suspend fun runWasm(
        embed: String,
        api: String
    ): WasmResult? {

        val script =
            WASM_SCRIPT.replace(
                "__API_URL__",
                api
            )

        val resolver = WebViewResolver(
            Regex("""hayya-result://"""),
            userAgent = ua,
            script = script,
            timeout = 35_000L
        )

        val (hit, _) =
            try {
                resolver.resolveUsingWebView(
                    embed,
                    "$mainUrl/"
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logError(e)
                return null
            }

        val url =
            hit?.url?.toString()
                ?: return null

        val encoded =
            Regex("""[?&]data=([^&]+)""")
                .find(url)
                ?.groupValues
                ?.getOrNull(1)
                ?: return null

        val json = runCatching {
            JSONObject(
                java.net.URLDecoder.decode(
                    encoded,
                    "UTF-8"
                )
            )
        }.getOrNull() ?: return null

        if (!json.optBoolean("ok", false))
            return null

        val streams = mutableListOf<String>()

        json.optJSONArray("streams")
            ?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optString(i)
                        .takeIf {
                            it.startsWith("http")
                        }
                        ?.let(streams::add)
                }
            }

        if (streams.isEmpty())
            return null

        val subtitles = mutableListOf<String>()

        json.optJSONArray("subtitles")
            ?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optString(i)
                        .takeIf {
                            it.startsWith("http")
                        }
                        ?.let(subtitles::add)
                }
            }

        return WasmResult(
            streams.distinct(),
            subtitles.distinct(),
            embed.substringBefore("/embed") + "/"
        )
    }

    // ---------------------------------------------------------
    // JAVASCRIPT
    // ---------------------------------------------------------

    private companion object {

        val WASM_SCRIPT = """
(async()=>{

try{

const api="__API_URL__";

const r=await fetch(api,{
    credentials:"omit",
    headers:{
        "Accept":
        "application/json, text/plain, */*"
    }
});

const j=await r.json();

const data=j.data||j;

let enc=data.stream_urls;

let vs=j.vs||data.vs||{};

let urls=[];

/* Already decoded */

if(Array.isArray(enc)){

    urls=enc;

/* Encrypted Base64 */

}else if(typeof enc==="string"){

    const wasmUrl=
        vs.wasm_url||vs.wasm;

    if(!wasmUrl)
        throw new Error("WASM URL missing");

    const wb=
        await fetch(wasmUrl,{
            credentials:"omit"
        });

    const wasm=
        await WebAssembly.instantiate(
            await wb.arrayBuffer(),
            {}
        );

    const ex=
        wasm.instance.exports;

    if(
        !ex.memory||
        !ex.alloc||
        !ex.decrypt
    )
        throw new Error("WASM exports missing");

    const bin=
        atob(enc);

    const bytes=
        new Uint8Array(bin.length);

    for(
        let i=0;
        i<bin.length;
        i++
    )
        bytes[i]=bin.charCodeAt(i);

    /*
     * Exact vsdec.js flow:
     *
     * alloc
     * copy encrypted bytes
     * decrypt
     * read ptr + 12
     */

    const ptr=
        ex.alloc(bytes.length);

    new Uint8Array(
        ex.memory.buffer,
        ptr,
        bytes.length
    ).set(bytes);

    const outLen=
        ex.decrypt(
            ptr,
            bytes.length
        );

    const out=
        new Uint8Array(
            ex.memory.buffer,
            ptr+12,
            outLen
        );

    const decoded=
        new TextDecoder().decode(out);

    urls=
        decoded
        .split("\n")
        .map(x=>x.trim())
        .filter(Boolean);
}

const result=[];

for(const stream of urls){

    try{

        const u=new URL(stream);

        const generate=
            await fetch(
                u.origin+
                "/generate.php",
                {
                    credentials:"omit"
                }
            );

        const text=
            await generate.text();

        let token="";

        try{

            const x=
                JSON.parse(text);

            token=
                x.token||
                x.data||
                x.result||
                "";

        }catch(e){

            token=
                text.trim();

            const m=
                text.match(
                    /(?:token|t)=([^"'&\s]+)/i
                );

            if(m)
                token=m[1];
        }

        if(!token)
            continue;

        let finalUrl=stream;

        if(
            finalUrl.includes(
                "__TOKEN__"
            )
        ){

            finalUrl=
                finalUrl.replace(
                    "__TOKEN__",
                    token
                );

        }else{

            finalUrl +=
                (
                    finalUrl.includes("?")
                    ? "&"
                    : "?"
                )+
                "token="+
                encodeURIComponent(token);
        }

        result.push(finalUrl);

    }catch(e){}

}

if(!result.length)
    throw new Error(
        "No stream after generate.php"
    );

location.href=
    "hayya-result://done?data="+
    encodeURIComponent(
        JSON.stringify({
            ok:true,
            streams:result,
            subtitles:[]
        })
    );

}catch(e){

location.href=
    "hayya-result://error?data="+
    encodeURIComponent(
        JSON.stringify({
            ok:false,
            error:String(e)
        })
    );
}

})();
""".trimIndent()
    }

    // ---------------------------------------------------------
    // DATA
    // ---------------------------------------------------------

    data class HayyaData(
        val type: String,
        val id: Int,
        val season: Int? = null,
        val episode: Int? = null
    )

    data class TmdbList(
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
        @JsonProperty("title")
        val title: String? = null,

        @JsonProperty("overview")
        val overview: String? = null,

        @JsonProperty("poster_path")
        val posterPath: String? = null,

        @JsonProperty("backdrop_path")
        val backdropPath: String? = null,

        @JsonProperty("release_date")
        val releaseDate: String? = null
    )

    data class TmdbTv(
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
