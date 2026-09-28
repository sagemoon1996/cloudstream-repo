package com.sagemoon1996.ourdrama

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLDecoder

@JsonIgnoreProperties(ignoreUnknown = true)
data class AjaxServerResponse(
    val status: Boolean? = null,
    val codeplay: String? = null
)

class OurDramaProvider : MainAPI() {

    override var mainUrl = "https://s.ourdrama.pro"
    override var name = "OurDrama"
    override var lang = "ar"

    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.AsianDrama)

    private val asianPath =
        "$mainUrl/serie/cate/مسلسلات-أسيوية"

    override val mainPage = mainPageOf(
        asianPath to "المسلسلات الآسيوية"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = if (page <= 1) {
            request.data
        } else {
            "${request.data}?page=$page"
        }

        val doc = app.get(
            url,
            referer = mainUrl
        ).document

        val items = doc
            .select("article.post-movie")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = items.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("h4 a[href]") ?: return null

        val title = link.text().trim()
        val url = fixUrl(link.attr("href"))

        if (title.isBlank() || url.isBlank()) {
            return null
        }

        val poster = selectFirst("img")
            ?.attr("data-src")
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it) }

        return newTvSeriesSearchResponse(
            name = title,
            url = url,
            type = TvType.AsianDrama
        ) {
            posterUrl = poster
        }
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val response = app.post(
            "$mainUrl/searchq",
            data = mapOf(
                "searchq" to query
            ),
            referer = mainUrl
        )

        return response.document
            .select("article.post-movie")
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(
        url: String
    ): LoadResponse {

        val doc = app.get(
            url,
            referer = mainUrl
        ).document

        val title =
            doc.selectFirst("h1")
                ?.text()
                ?.trim()
                ?: doc.selectFirst("meta[property=og:title]")
                    ?.attr("content")
                    ?.trim()
                ?: "OurDrama"

        val poster =
            doc.selectFirst("meta[property=og:image]")
                ?.attr("content")
                ?.takeIf { it.isNotBlank() }

        val plot =
            doc.selectFirst("meta[property=og:description]")
                ?.attr("content")
                ?.takeIf { it.isNotBlank() }

        val episodes = doc
            .select("a[href*='/episode/']")
            .mapNotNull { element ->

                val episodeUrl = fixUrl(
                    element.attr("href")
                )

                if (episodeUrl.isBlank()) {
                    return@mapNotNull null
                }

                val decoded = runCatching {
                    URLDecoder.decode(
                        episodeUrl,
                        "UTF-8"
                    )
                }.getOrDefault(episodeUrl)

                val number = Regex(
                    """الحلقة-(\d+)"""
                )
                    .find(decoded)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()

                newEpisode(episodeUrl) {
                    name = if (number != null) {
                        "الحلقة $number"
                    } else {
                        "حلقة"
                    }

                    episode = number
                }
            }
            .distinctBy { it.data }
            .sortedBy { it.episode ?: Int.MAX_VALUE }

        return newTvSeriesLoadResponse(
            name = title,
            url = url,
            type = TvType.AsianDrama,
            episodes = episodes
        ) {
            posterUrl = poster
            plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val response = app.get(
            data,
            referer = mainUrl
        )

        val html = response.text

        val csrf = Regex(
            """X-CSRF-TOKEN["']?\s*:\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?: return false

        val codes = response.document
            .select("[data-code]")
            .map { it.attr("data-code").trim() }
            .filter { it.isNotBlank() }
            .distinct()

        if (codes.isEmpty()) {
            return false
        }

        var found = false

        for (code in codes) {

            val serverResponse = runCatching {
                app.post(
                    "$mainUrl/ajax-request",
                    headers = mapOf(
                        "X-CSRF-TOKEN" to csrf,
                        "X-Requested-With" to "XMLHttpRequest"
                    ),
                    referer = data,
                    cookies = response.cookies,
                    data = mapOf(
                        "action" to "iframe_server",
                        "code" to code
                    )
                ).text
            }.getOrNull() ?: continue

            val parsed =
                tryParseJson<AjaxServerResponse>(
                    serverResponse
                ) ?: continue

            if (parsed.status != true) {
                continue
            }

            var iframeUrl = Jsoup
                .parse(parsed.codeplay ?: "")
                .selectFirst("iframe")
                ?.attr("src")
                ?.trim()
                ?: continue

            if (iframeUrl.startsWith("//")) {
                iframeUrl = "https:$iframeUrl"
            }

            if (iframeUrl.isBlank()) {
                continue
            }

            val loaded = runCatching {
                loadExtractor(
                    iframeUrl,
                    data,
                    subtitleCallback,
                    callback
                )
            }.getOrDefault(false)

            if (loaded) {
                found = true
            }
        }

        return found
    }
}
