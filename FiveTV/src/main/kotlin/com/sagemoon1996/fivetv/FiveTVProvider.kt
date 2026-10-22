package com.sagemoon1996.fivetv

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class FiveTVProvider : MainAPI() {

    override var mainUrl = "https://new61.5tv.lol"
    override var name = "FiveTV"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val tag = "FiveTV"

    // الأقسام المثبتة (كروت article.ftv-home-poster-card). الأقسام الأخرى (/, /latest-episodes/,
    // /new-rows/, /schedule/) محذوفة مؤقتا لأن شكل كروتها ما تثبتش.
    override val mainPage = mainPageOf(
        "$mainUrl/latest-series/" to "جديد الدراما",
        "$mainUrl/latest-movies/" to "جديد الأفلام",
        "$mainUrl/category/%d8%a7%d9%84%d8%af%d8%b1%d8%a7%d9%85%d8%a7-%d8%a7%d9%84%d8%aa%d8%a7%d9%8a%d9%84%d8%a7%d9%86%d8%af%d9%8a%d8%a9/" to "الدراما التايلاندية"
    )

    // ------------------------------------------------------------------ Helpers

    private fun Element.imageUrl(): String? {
        val img = selectFirst("img") ?: return null
        val raw = img.attr("data-src")
            .ifBlank { img.attr("data-lazy-src") }
            .ifBlank { img.attr("src") }
            .ifBlank {
                img.attr("srcset").split(",").firstOrNull()
                    ?.trim()?.split(" ")?.firstOrNull().orEmpty()
            }
            .trim()
        if (raw.isBlank() || raw.startsWith("data:")) return null
        return fixUrlNull(raw)
    }

    // ------------------------------------------------------------------ Cards

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(
            selectFirst(".ftv-home-poster-card__title[href]")?.attr("href")
                ?: selectFirst(".ftv-home-poster-card__image[href]")?.attr("href")
                ?: selectFirst("a[href]")?.attr("href")
        ) ?: return null

        // العنوان من .ftv-home-poster-card__title فقط (السنة والتصنيف badges منفصلة)
        val title = selectFirst(".ftv-home-poster-card__title")?.text()?.trim().orEmpty()
        if (title.isBlank()) return null

        val poster = selectFirst(".ftv-home-poster-card__image")?.imageUrl() ?: imageUrl()
        val year = selectFirst(".ftv-poster-badge--year")?.text()?.trim()?.toIntOrNull()

        val isMovie = hasClass("ftv-home-poster-card--movie")
        val isSeries = hasClass("ftv-home-poster-card--series") || href.contains("/series/")

        return if (isSeries && !isMovie) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    // كروت صفحات التصنيف (مثبت): article.ftv-tax-card، النوع من الرابط لأن ما فيهاش class series/movie
    private fun Element.toTaxResult(): SearchResponse? {
        val href = fixUrlNull(selectFirst("a.ftv-tax-card__link[href]")?.attr("href"))
            ?: return null

        val title = selectFirst(".ftv-tax-card__info h2")?.text()?.trim().orEmpty()
            .ifBlank {
                selectFirst("a.ftv-tax-card__link")?.attr("aria-label")?.trim().orEmpty()
            }
        if (title.isBlank()) return null

        val poster = selectFirst(".ftv-tax-card__media")?.imageUrl()
        val year = selectFirst(".ftv-tax-card__year")?.text()?.trim()?.toIntOrNull()

        return if (href.contains("/series/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    private fun Element.toCardResult(): SearchResponse? =
        if (hasClass("ftv-tax-card")) toTaxResult() else toSearchResult()

    // ------------------------------------------------------------------ Main page

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val doc = app.get(url).document

        val items = doc.select("article.ftv-home-poster-card, article.ftv-tax-card")
            .mapNotNull { it.toCardResult() }
            .distinctBy { it.url }

        val hasNext = doc.selectFirst("a.next.page-numbers") != null
        Log.d(tag, "mainPage ${request.name} p$page: ${items.size} items, hasNext=$hasNext")

        return newHomePageResponse(request.name, items, hasNext)
    }

    // ------------------------------------------------------------------ Search

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/?q=${URLEncoder.encode(query, "UTF-8")}"
        val doc = app.get(url).document
        return doc.select(".ftv-search-grid .ftv-home-poster-card")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    // ------------------------------------------------------------------ Load

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst(".ftv-content-hero__title")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("main h1, h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null

        // البوستر من الصورة داخل الصفحة؛ og:image فقط كاحتياط (حجمها يختلف)
        val poster = doc.selectFirst(".ftv-content-hero__poster")?.imageUrl()
            ?: fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))

        val plot = doc.selectFirst(".ftv-content-hero__description")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[name=description], meta[property=og:description]")
                ?.attr("content")?.trim()

        val year = doc.selectFirst(".ftv-content-hero__year a")?.text()?.trim()?.toIntOrNull()

        if (!url.contains("/series/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
            }
        }

        val episodes = doc.select("a.ftv-series-episode-card")
            .mapNotNull { card ->
                val epUrl = fixUrlNull(card.attr("href"))?.substringBefore("#")
                    ?: return@mapNotNull null
                val epTitle = card.selectFirst("strong")?.text()?.trim().orEmpty()
                val cardText = card.text().trim()
                val (season, episode) = parseSeasonEpisode(epUrl, cardText)

                newEpisode(epUrl) {
                    this.name = epTitle.ifBlank {
                        if (episode != null) "الحلقة $episode" else null
                    }
                    this.season = season
                    this.episode = episode
                    this.posterUrl = card.imageUrl() ?: poster
                }
            }
            .distinctBy { it.data }
            .sortedWith(compareBy<Episode>({ it.season ?: 1 }, { it.episode ?: 0 }))

        Log.d(tag, "load series '$title': ${episodes.size} episodes")

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
        }
    }

    // مثبت: game-of-thrones-3x3  و  معكرونة-pasta-1
    private fun parseSeasonEpisode(epUrl: String, text: String): Pair<Int?, Int?> {
        val slug = epUrl.substringBefore("?").trimEnd('/').substringAfterLast('/')

        Regex("""(\d+)x(\d+)$""").find(slug)?.let {
            return it.groupValues[1].toIntOrNull() to it.groupValues[2].toIntOrNull()
        }

        val season = Regex("""(?:الموسم|season)\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val episode = Regex("""(?:الحلقة|episode)[^\d]*(\d+)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""-(\d+)$""").find(slug)?.groupValues?.getOrNull(1)?.toIntOrNull()

        return season to episode
    }

    // ------------------------------------------------------------------ Links

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        // مثبت: .ftv-media-servers [data-server-id] مع data-server-url
        val serverLinks = doc.select(".ftv-media-servers [data-server-id]")
            .mapNotNull { el ->
                el.attr("data-server-url").trim().takeIf { it.startsWith("http") }
                    ?.let { it to el.text().trim() }
            }
            .distinctBy { it.first }
            .toMutableList()

        Log.d(tag, "loadLinks: ${serverLinks.size} servers in $data")

        // احتياط فقط إذا القائمة فارغة: iframe + روابط 71Stream داخل الـ HTML (السلوك القديم)
        if (serverLinks.isEmpty()) {
            val fallback = LinkedHashSet<String>()
            doc.select("iframe[src], iframe[data-src]").forEach { iframe ->
                val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }.trim()
                when {
                    src.startsWith("http") -> fallback.add(src)
                    src.startsWith("//") -> fallback.add("https:$src")
                    src.startsWith("/") -> fallback.add("$mainUrl$src")
                }
            }
            Regex("""https?://71stream\.one/embed/[A-Za-z0-9_-]+""")
                .findAll(doc.html().replace("\\/", "/"))
                .forEach { fallback.add(it.value) }
            fallback.forEach { serverLinks.add(it to "fallback") }
            Log.d(tag, "loadLinks fallback: ${fallback.size} links")
        }

        var loaded = false

        for ((link, label) in serverLinks) {
            val ok = try {
                if (link.contains("71stream.one", ignoreCase = true)) {
                    load71Stream(link, callback)
                } else {
                    loadExtractor(link, data, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                Log.d(tag, "error for $link: ${e.message}")
                false
            }
            Log.d(tag, "server '$label' $link -> $ok")
            if (ok) loaded = true
        }

        return loaded
    }

    // منطق 71Stream القديم (يخدم): data-page ثم m3u8/mp4 ثم /download/{id}?quality=Original
    private suspend fun load71Stream(link: String, callback: (ExtractorLink) -> Unit): Boolean {
        var loaded = false

        val appData = try {
            app.get(link).document
                .selectFirst("#app")
                ?.attr("data-page")
                ?.replace("\\/", "/")
        } catch (e: Exception) {
            Log.d(tag, "71Stream embed error: ${e.message}")
            null
        }

        if (appData != null) {
            val m3u8 = Regex("""https://cdnvid\.dramalvr\.com/hls/[^"]+\.m3u8""")
                .find(appData)?.value

            if (m3u8 != null) {
                callback(
                    newExtractorLink(
                        source = "71Stream",
                        name = "71Stream HLS",
                        url = m3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        referer = link
                        quality = Qualities.Unknown.value
                    }
                )
                loaded = true
            }

            val mp4 = Regex("""https://[^"'\\\s<>]+\.mp4(?:\?[^"'\\\s<>]*)?""")
                .find(appData)?.value

            if (mp4 != null) {
                callback(
                    newExtractorLink(
                        source = "71Stream",
                        name = "71Stream MP4",
                        url = mp4,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        referer = link
                        quality = Qualities.Unknown.value
                    }
                )
                loaded = true
            }
        }

        val hashId = Regex("""71stream\.one/embed/([A-Za-z0-9_-]+)""")
            .find(link)?.groupValues?.getOrNull(1)

        if (hashId != null) {
            val downloadUrl = "https://71stream.one/download/$hashId?quality=Original"
            try {
                val finalUrl = app.get(downloadUrl, allowRedirects = true).url
                if (
                    finalUrl.startsWith("http") &&
                    finalUrl != downloadUrl &&
                    (
                        finalUrl.contains(".mp4", true) ||
                        finalUrl.contains("cloudflarestorage.com", true)
                    )
                ) {
                    callback(
                        newExtractorLink(
                            source = "71Stream",
                            name = "71Stream MP4",
                            url = finalUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            referer = link
                            quality = Qualities.Unknown.value
                        }
                    )
                    loaded = true
                }
            } catch (e: Exception) {
                Log.d(tag, "71Stream download error: ${e.message}")
            }
        }

        return loaded
    }
}
