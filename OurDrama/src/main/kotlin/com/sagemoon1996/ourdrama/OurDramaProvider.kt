package com.sagemoon1996.ourdrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class OurDramaProvider : MainAPI() {

    override var mainUrl = "https://s.ourdrama.pro"
    override var name = "OurDrama"
    override var lang = "ar"

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/serie/cate/مسلسلات-أسيوية" to "مسلسلات أسيوية",
        "$mainUrl/serie/cate/مسلسلات-كورية" to "مسلسلات كورية",
        "$mainUrl/serie/cate/مسلسلات-صينية" to "مسلسلات صينية",
        "$mainUrl/serie/cate/مسلسلات-يابانية" to "مسلسلات يابانية",
        "$mainUrl/serie/cate/مسلسلات-تايوانية" to "مسلسلات تايوانية",
        "$mainUrl/serie/cate/مسلسلات-تايلاندية" to "مسلسلات تايلاندية"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = if (page == 1) {
            request.data
        } else {
            "${request.data}?page=$page"
        }

        val document = app.get(url).document

        val results = document
            .select("a")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            results,
            hasNext = results.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {

        val response = app.post(
            "$mainUrl/searchq",
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest"
            ),
            data = mapOf(
                "searchq" to query
            )
        )

        val document = Jsoup.parse(response.text)

        return document
            .select("a")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResponse(): SearchResponse? {

        val href = attr("href").trim()

        if (
            href.isBlank() ||
            !href.contains("/serie/")
        ) {
            return null
        }

        val url = fixUrl(href)

        val title = text()
            .trim()
            .replace(Regex("\\s+"), " ")

        if (title.isBlank()) {
            return null
        }

        val poster = attr("data-src")
            .ifBlank { attr("src") }
            .takeIf { it.isNotBlank() }
            ?.let { fixUrl(it) }

        return newTvSeriesSearchResponse(
            title = title,
            url = url,
            posterUrl = poster
        )
    }

    override suspend fun load(url: String): LoadResponse {

        val document = app.get(url).document

        val title = document
            .selectFirst("h1")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document
                .selectFirst("title")
                ?.text()
                ?.trim()
                ?: "OurDrama"

        val poster = document
            .selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it) }

        val description = document
            .selectFirst("meta[name=description]")
            ?.attr("content")
            ?.trim()

        val episodes = document
            .select(".episodes a, .episode a, a[href*=\"/watch-\"]")
            .mapNotNull { element ->

                val href = element.attr("href").trim()

                if (href.isBlank()) {
                    return@mapNotNull null
                }

                if (!href.contains("/watch-")) {
                    return@mapNotNull null
                }

                val episodeUrl = fixUrl(href)

                val episodeNumber = Regex(
                    """/(\d+)(?:[/?#]|$)"""
                )
                    .find(href)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: Regex("""/(\d+)$""")
                        .find(href)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()

                val episodeName = element
                    .text()
                    .trim()
                    .ifBlank {
                        episodeNumber?.let { "الحلقة $it" } ?: "Episode"
                    }

                Episode(
                    data = episodeUrl,
                    name = episodeName,
                    episode = episodeNumber
                )
            }
            .distinctBy { it.data }

        return newTvSeriesLoadResponse(
            name = title,
            url = url,
            type = TvType.TvSeries,
            episodes = episodes
        ) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val episodeDocument = app.get(data).document

        /*
         * OurDrama's actual server buttons:
         *
         * .server-list-menu .getplay a[data-code]
         *
         * The data-code is then sent to:
         * /ajax-request
         *
         * with:
         * action=iframe_server
         * code=<data-code>
         */

        val server = episodeDocument
            .selectFirst(".server-list-menu .getplay a[data-code]")
            ?: return false

        val code = server.attr("data-code")
            .trim()
            .takeIf { it.isNotBlank() }
            ?: return false

        /*
         * Dynamic CSRF token.
         */
        val csrf = episodeDocument
            .selectFirst("meta[name=csrf-token]")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return false

        val ajaxUrl = "$mainUrl/ajax-request"

        val ajaxResponse = app.post(
            ajaxUrl,
            headers = mapOf(
                "X-CSRF-TOKEN" to csrf,
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to data
            ),
            data = mapOf(
                "action" to "iframe_server",
                "code" to code
            )
        )

        val json = try {
            JSONObject(ajaxResponse.text)
        } catch (_: Exception) {
            return false
        }

        if (!json.optBoolean("status", false)) {
            return false
        }

        /*
         * Proven response field:
         *
         * codeplay:
         * <iframe ... src="https://ourdrama.cc/v/<id>">
         */
        val codeplay = json
            .optString("codeplay")
            .takeIf { it.isNotBlank() }
            ?: return false

        val iframeUrl = Regex(
            """<iframe[^>]+src=["']([^"']+)["']"""
        )
            .find(codeplay)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { fixUrl(it) }
            ?: return false

        /*
         * The iframe is not a normal extractor page.
         *
         * It contains a P.A.C.K.E.R. script.
         * After unpacking:
         *
         * var links = {
         *     "hls2": "...",
         *     "hls3": "..."
         * };
         *
         * Player source:
         *
         * links.hls4 || links.hls3 || links.hls2
         *
         * hls4 is absent in the proven example.
         * Therefore hls3 is the active source and hls2 is fallback.
         */
        val hls = extractHlsFromEmbed(iframeUrl)
            ?: return false

        /*
         * hls3 is a .txt HLS master playlist but contains
         * #EXTM3U and HLS variants, so CloudStream must treat it
         * as M3U8.
         */
        callback(
            newExtractorLink(
                source = "OurDrama",
                name = "OurDrama",
                url = hls,
                type = ExtractorLinkType.M3U8
            ) {
                referer = iframeUrl
            }
        )

        return true
    }

    private suspend fun extractHlsFromEmbed(
        iframeUrl: String
    ): String? {

        val iframeResponse = app.get(
            iframeUrl,
            headers = mapOf(
                "Referer" to "$mainUrl/"
            )
        )

        val document = iframeResponse.document

        /*
         * The packed script is inline.
         *
         * We cannot search for literal "hls3" before unpacking,
         * because the word itself is obfuscated inside P.A.C.K.E.R.
         *
         * A stable marker proven from the actual iframe is:
         * - riverstonelearninghub
         * - eval(function(p,a,c,k,e,d)
         */
        val packedScript = document
            .select("script")
            .map { it.data() }
            .firstOrNull {
                it.contains("riverstonelearninghub") &&
                    it.contains("eval(function(p,a,c,k,e,d)")
            }
            ?: return null

        val unpacked = unpackPacker(packedScript)
            ?: return null

        /*
         * Proven decoded structure:
         *
         * var links={
         *   "hls2":"...",
         *   "hls3":"..."
         * };
         *
         * jwplayer(...).setup({
         *   sources:[{
         *      file:links.hls4||links.hls3||links.hls2,
         *      type:"hls"
         *   }]
         * });
         */

        val hls3 = Regex(
            """["']hls3["']\s*:\s*["']([^"']+)["']"""
        )
            .find(unpacked)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }

        if (hls3 != null) {
            return hls3
        }

        val hls2 = Regex(
            """["']hls2["']\s*:\s*["']([^"']+)["']"""
        )
            .find(unpacked)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }

        return hls2
    }

    private fun unpackPacker(script: String): String? {

        /*
         * Actual format:
         *
         * eval(function(p,a,c,k,e,d){...}(
         *   'PACKED',
         *   36,
         *   483,
         *   'DICTIONARY'.split('|')
         * ))
         *
         * We capture the packed payload, base and dictionary count
         * instead of hardcoding the episode's URLs.
         */

        val match = Regex(
            """eval\(function\(p,a,c,k,e,d\)\{[\s\S]*?\}\(\s*(['"])([\s\S]*?)\1\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(['"])([\s\S]*?)\5\.split\(\s*['"]\|['"]\s*\)"""
        ).find(script) ?: return null

        val packed = match.groupValues[2]
        val base = match.groupValues[3].toIntOrNull() ?: return null
        val count = match.groupValues[4].toIntOrNull() ?: return null
        val dictionaryRaw = match.groupValues[6]

        if (base <= 1 || count <= 0) {
            return null
        }

        /*
         * The actual browser P.A.C.K.E.R. receives JavaScript
         * string literals. The packed payload normally contains
         * escaped single quotes because the original code contains
         * JavaScript strings.
         */
        val dictionary = decodeJsString(dictionaryRaw)
            .split("|")

        var unpacked = decodeJsString(packed)

        /*
         * Same replacement direction as the proven browser
         * unpacking algorithm:
         *
         * while(c--)
         *     if(k[c])
         *         p=p.replace(...)
         */
        for (index in count - 1 downTo 0) {

            if (index >= dictionary.size) {
                continue
            }

            val word = dictionary[index]

            if (word.isEmpty()) {
                continue
            }

            val token = index.toString(base)

            unpacked = unpacked.replace(
                Regex("""\b${Regex.escape(token)}\b"""),
                word
            )
        }

        return unpacked
    }

    private fun decodeJsString(value: String): String {

        return value
            .replace("\\'", "'")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\/", "/")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
    }
}
