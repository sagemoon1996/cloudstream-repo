package com.sagemoon1996.ourdrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class OurDramaProvider : MainAPI() {

    override var mainUrl = "https://s.ourdrama.pro"

    override var name = "OurDrama"

    override var lang = "ar"

    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "$mainUrl/serie/cate/مسلسلات-أسيوية" to "مسلسلات أسيوية",
        "$mainUrl/serie/cate/مسلسلات-كورية" to "مسلسلات كورية",
        "$mainUrl/serie/cate/مسلسلات-صينية" to "مسلسلات صينية",
        "$mainUrl/serie/cate/مسلسلات-يابانية" to "مسلسلات يابانية",
        "$mainUrl/serie/cate/مسلسلات-تايوانية" to "مسلسلات تايوانية",
        "$mainUrl/serie/cate/مسلسلات-تايلاندية" to "مسلسلات تايلاندية"
    )

    private fun makeSearchResponses(
        document: org.jsoup.nodes.Document
    ): List<SearchResponse> {

        return document
            .select("a")
            .mapNotNull { element ->

                val href = element
                    .attr("href")
                    .trim()

                if (
                    href.isBlank() ||
                    !href.contains("/serie/")
                ) {
                    return@mapNotNull null
                }

                val title = element
                    .text()
                    .trim()
                    .replace(Regex("\\s+"), " ")
                    .ifBlank {
                        element
                            .selectFirst("img[alt]")
                            ?.attr("alt")
                            ?.trim()
                            ?: return@mapNotNull null
                    }

                val poster = element
                    .selectFirst(
                        "img[src], img[data-src], img[data-lazy-src], img[data-original]"
                    )
                    ?.let { image ->

                        image
                            .attr("src")
                            .ifBlank {
                                image.attr("data-src")
                            }
                            .ifBlank {
                                image.attr("data-lazy-src")
                            }
                            .ifBlank {
                                image.attr("data-original")
                            }
                            .takeIf {
                                it.isNotBlank()
                            }
                    }

                newTvSeriesSearchResponse(
                    title,
                    fixUrl(href),
                    TvType.TvSeries
                ) {
                    posterUrl = poster?.let { fixUrl(it) }
                }
            }
            .distinctBy {
                it.url
            }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = if (page == 1) {
            request.data
        } else {
            "${request.data.trimEnd('/')}/page/$page"
        }

        val document = app
            .get(url)
            .document

        return newHomePageResponse(
            request.name,
            makeSearchResponses(document)
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val response = app.post(
            "$mainUrl/searchq",
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest"
            ),
            data = mapOf(
                "searchq" to query
            )
        )

        return makeSearchResponses(
            Jsoup.parse(response.text)
        )
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document = app
            .get(url)
            .document

        val title = document
            .selectFirst(
                "h1, h2, meta[property='og:title']"
            )
            ?.let { element ->

                if (element.tagName() == "meta") {
                    element.attr("content").trim()
                } else {
                    element.text().trim()
                }
            }
            ?.takeIf {
                it.isNotBlank()
            }
            ?: return null

        val poster = document
            .selectFirst(
                "meta[property='og:image']"
            )
            ?.attr("content")
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let {
                fixUrl(it)
            }

        val plot = document
            .selectFirst(
                "meta[name='description'], meta[property='og:description']"
            )
            ?.attr("content")
            ?.trim()

        val episodes = document
            .select(
                "a[href*='/watch-']"
            )
            .mapNotNull { link ->

                val episodeUrl = link
                    .attr("href")
                    .trim()

                if (episodeUrl.isBlank()) {
                    return@mapNotNull null
                }

                val episodeText = link
                    .text()
                    .trim()

                val episode = Regex(
                    """(?:الحلقة|episode)[^\d]*(\d+)""",
                    RegexOption.IGNORE_CASE
                )
                    .find(episodeText)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: Regex(
                        """/(\d+)$"""
                    )
                        .find(episodeUrl)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                    ?: return@mapNotNull null

                newEpisode(
                    fixUrl(episodeUrl)
                ) {

                    name = episodeText.ifBlank {
                        "Episode $episode"
                    }

                    season = 1
                    this.episode = episode
                }
            }
            .distinctBy {
                it.data
            }
            .sortedBy {
                it.episode ?: 0
            }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val episodeDocument = app
            .get(data)
            .document

        val server = episodeDocument
            .selectFirst(
                ".server-list-menu .getplay a[data-code]"
            )
            ?: return false

        val code = server
            .attr("data-code")
            .trim()
            .takeIf {
                it.isNotBlank()
            }
            ?: return false

        val csrf = episodeDocument
            .selectFirst(
                "meta[name='csrf-token']"
            )
            ?.attr("content")
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?: return false

        val ajaxResponse = app.post(
            "$mainUrl/ajax-request",
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
            org.json.JSONObject(
                ajaxResponse.text
            )
        } catch (_: Exception) {
            return false
        }

        if (!json.optBoolean("status", false)) {
            return false
        }

        val codeplay = json
            .optString("codeplay")
            .takeIf {
                it.isNotBlank()
            }
            ?: return false

        val iframeUrl = Regex(
            """<iframe[^>]+src=["']([^"']+)["']"""
        )
            .find(codeplay)
            ?.groupValues
            ?.getOrNull(1)
            ?.let {
                fixUrl(it)
            }
            ?: return false

        val hlsUrl = extractHlsFromEmbed(
            iframeUrl
        )
            ?: return false

        callback(
            newExtractorLink(
                source = "OurDrama",
                name = "OurDrama",
                url = hlsUrl,
                type = ExtractorLinkType.M3U8
            ) {
                referer = iframeUrl
                quality = Qualities.Unknown.value
            }
        )

        return true
    }

    private suspend fun extractHlsFromEmbed(
        iframeUrl: String
    ): String? {

        val iframeDocument = app
            .get(
                iframeUrl,
                headers = mapOf(
                    "Referer" to "$mainUrl/"
                )
            )
            .document

        val packedScript = iframeDocument
            .select("script")
            .map {
                it.data()
            }
            .firstOrNull {
                it.contains("riverstonelearninghub") &&
                    it.contains("eval(function(p,a,c,k,e,d)")
            }
            ?: return null

        val unpacked = unpackPacker(
            packedScript
        )
            ?: return null

        val hls3 = Regex(
            """["']hls3["']\s*:\s*["']([^"']+)["']"""
        )
            .find(unpacked)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf {
                it.isNotBlank()
            }

        if (hls3 != null) {
            return hls3
        }

        return Regex(
            """["']hls2["']\s*:\s*["']([^"']+)["']"""
        )
            .find(unpacked)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf {
                it.isNotBlank()
            }
    }

    private fun unpackPacker(
        script: String
    ): String? {

        val match = Regex(
            """eval\(function\(p,a,c,k,e,d\)\{[\s\S]*?\}\(\s*(['"])([\s\S]*?)\1\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(['"])([\s\S]*?)\5\.split\(\s*['"]\|['"]\s*\)"""
        )
            .find(script)
            ?: return null

        val packed = match
            .groupValues
            .getOrNull(2)
            ?: return null

        val base = match
            .groupValues
            .getOrNull(3)
            ?.toIntOrNull()
            ?: return null

        val count = match
            .groupValues
            .getOrNull(4)
            ?.toIntOrNull()
            ?: return null

        val dictionaryRaw = match
            .groupValues
            .getOrNull(6)
            ?: return null

        if (base <= 1 || count <= 0) {
            return null
        }

        val dictionary = decodeJsString(
            dictionaryRaw
        ).split("|")

        var unpacked = decodeJsString(
            packed
        )

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
                Regex(
                    """\b${Regex.escape(token)}\b"""
                ),
                word
            )
        }

        return unpacked
    }

    private fun decodeJsString(
        value: String
    ): String {

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
