package com.sagemoon1996.fivetv

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
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

    // مثبت بالـ HTML: /latest-series/ و /latest-movies/ و /category/... (الترقيم /page/N/).
    // غير مثبت بعد: /latest-episodes/ و /new-rows/ -> يستعملان parser عام (parseGenericCards)
    // وسطور Logcat "mainPage ..." تبين كم عنصر تلقط.
    override val mainPage = mainPageOf(
        "$mainUrl/latest-episodes/" to "آخر الحلقات",
        "$mainUrl/new-rows/" to "أحدث الإضافات",
        "$mainUrl/latest-series/" to "جديد الدراما",
        "$mainUrl/latest-movies/" to "آخر الأفلام",
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

    // ------------------------------------------------------------------ Cards (مثبتة)

    // كروت /latest-series/ و /latest-movies/ و البحث
    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(
            selectFirst(".ftv-home-poster-card__title[href]")?.attr("href")
                ?: selectFirst(".ftv-home-poster-card__image[href]")?.attr("href")
                ?: selectFirst("a[href]")?.attr("href")
        ) ?: return null

        val title = selectFirst(".ftv-home-poster-card__title")?.text()?.trim().orEmpty()
        if (title.isBlank()) return null

        val poster = selectFirst(".ftv-home-poster-card__image")?.imageUrl() ?: imageUrl()
        val year = selectFirst(".ftv-poster-badge--year")?.text()?.trim()?.toIntOrNull()

        val isMovie = hasClass("ftv-home-poster-card--movie")
        val isSeries = hasClass("ftv-home-poster-card--series") || href.contains("/series/")

        return if ((isSeries || href.contains("/episode/")) && !isMovie) {
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

    // كروت صفحات التصنيف: article.ftv-tax-card (النوع من الرابط)
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

    // Parser عام (غير مثبت): لأقسام /latest-episodes/ و /new-rows/ إلى أن يتثبت HTML متاعهم.
    // يجمع كل رابط /episode/ أو /series/ أو /movie/ داخل <main> بدون تكرار.
    private fun parseGenericCards(doc: Document): List<SearchResponse> {
        val result = LinkedHashMap<String, SearchResponse>()

        for (a in doc.select("main a[href*=/episode/], main a[href*=/series/], main a[href*=/movie/]")) {
            val href = fixUrlNull(a.attr("href"))?.substringBefore("#") ?: continue
            if (result.containsKey(href)) continue

            val box: Element = a.closest("article") ?: a.parent() ?: a

            var title = box.selectFirst(
                ".ftv-home-poster-card__title, .ftv-tax-card__info h2, h2, h3, h4, strong"
            )?.text()?.takeIf { it.isNotBlank() }
                ?: a.attr("aria-label").ifBlank { a.text() }
            title = title.replace(Regex("\\s+"), " ").trim()
            if (title.isBlank()) continue

            if (href.contains("/episode/") && !title.contains("الحلقة")) {
                val (s, e) = parseSeasonEpisode(href, "")
                title += when {
                    s != null && e != null -> " - الموسم $s الحلقة $e"
                    e != null -> " - الحلقة $e"
                    else -> ""
                }
            }

            val poster = box.imageUrl() ?: a.imageUrl()
            val year = box.selectFirst(".ftv-poster-badge--year, .ftv-tax-card__year")
                ?.text()?.trim()?.toIntOrNull()

            result[href] = if (href.contains("/movie/")) {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                }
            } else {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                }
            }
        }
        return result.values.toList()
    }

    // ------------------------------------------------------------------ Main page

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val doc = app.get(url).document

        val known = doc.select("article.ftv-home-poster-card, article.ftv-tax-card")
            .mapNotNull { it.toCardResult() }

        val isEpisodes = request.data.contains("/latest-episodes/")
        val items = (
            if (isEpisodes) parseGenericCards(doc).ifEmpty { known }
            else known.ifEmpty { parseGenericCards(doc) }
        ).distinctBy { it.url }

        val hasNext = doc.selectFirst("a.next.page-numbers") != null
        Log.d(tag, "mainPage '${request.name}' p$page: known=${known.size} total=${items.size} hasNext=$hasNext")

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
        // بطاقات آخر الحلقات تشير لرابط حلقة: نحاول نفتح صفحة المسلسل (مستنتج من نمطين مثبتين:
        // game-of-thrones-3x3 -> /series/game-of-thrones/ ، معكرونة-pasta-1 -> /series/معكرونة-pasta/)
        if (url.contains("/episode/")) {
            val seriesUrl = seriesUrlFromEpisode(url)
            if (seriesUrl != null) {
                val series = try {
                    loadPage(seriesUrl)
                } catch (e: Exception) {
                    Log.d(tag, "series from episode failed: ${e.message}")
                    null
                }
                if (series is TvSeriesLoadResponse && series.episodes.isNotEmpty()) return series
            }
        }
        return loadPage(url)
    }

    private fun seriesUrlFromEpisode(url: String): String? {
        val slug = url.substringBefore("?").trimEnd('/').substringAfterLast('/')
        val base = slug.replace(Regex("""-\d+x\d+$"""), "").replace(Regex("""-\d+$"""), "")
        return if (base.isBlank() || base == slug) null else "$mainUrl/series/$base/"
    }

    private suspend fun loadPage(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst(".ftv-content-hero__title")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("main h1, h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null

        // البوستر من الصورة داخل الصفحة؛ og:image احتياط فقط (حجمها يختلف)
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
                val (season, episode) = parseSeasonEpisode(epUrl, card.text().trim())

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

    // ------------------------------------------------------------------ Links (كل السيرفرات)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        // 1) سيرفرات المشاهدة (مثبت): .ftv-media-servers [data-server-id] + data-server-url
        val watch = LinkedHashMap<String, String>()
        for (el in doc.select(".ftv-media-servers [data-server-id]")) {
            val u = el.attr("data-server-url").trim()
            if (u.startsWith("http") && !watch.containsKey(u)) watch[u] = el.text().trim()
        }

        // 2) روابط التحميل (مثبت): .ftv-download-server a[href]
        val download = LinkedHashMap<String, String>()
        for (a in doc.select(".ftv-download-server a[href]")) {
            val u = fixUrlNull(a.attr("href"))?.trim() ?: continue
            if (u.startsWith("http") && !watch.containsKey(u) && !download.containsKey(u)) {
                download[u] = a.text().trim().ifBlank { "download" }
            }
        }

        Log.d(tag, "loadLinks: watch=${watch.size} download=${download.size} in $data")

        // احتياط فقط إذا ما لقينا سيرفرات: iframe + روابط 71Stream داخل الـ HTML (السلوك القديم)
        if (watch.isEmpty()) {
            for (iframe in doc.select("iframe[src], iframe[data-src]")) {
                val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }.trim()
                val full = when {
                    src.startsWith("http") -> src
                    src.startsWith("//") -> "https:$src"
                    src.startsWith("/") -> "$mainUrl$src"
                    else -> null
                }
                if (full != null) watch[full] = "iframe"
            }
            Regex("""https?://71stream\.one/embed/[A-Za-z0-9_-]+""")
                .findAll(doc.html().replace("\\/", "/"))
                .forEach { watch.putIfAbsent(it.value, "fallback") }
            Log.d(tag, "loadLinks fallback: watch=${watch.size}")
        }

        var loaded = false

        // سيرفرات المشاهدة: 71Stream بمنطقه الخاص، الباقي loadExtractor ثم مسح عام كحل أخير
        for ((link, label) in watch) {
            var ok = false
            try {
                ok = if (link.contains("71stream.one", ignoreCase = true)) {
                    load71Stream(link, callback)
                } else {
                    loadExtractor(link, data, subtitleCallback, callback) ||
                        genericScan(link, data, callback)
                }
            } catch (e: Exception) {
                Log.d(tag, "watch error $link: ${e.message}")
            }
            Log.d(tag, "watch '$label' $link -> $ok")
            if (ok) loaded = true
        }

        // روابط التحميل: loadExtractor فقط (71Stream مغطى بصفحة الـ embed)
        for ((link, label) in download) {
            if (link.contains("71stream.one", ignoreCase = true)) continue
            var ok = false
            try {
                ok = loadExtractor(link, data, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.d(tag, "download error $link: ${e.message}")
            }
            Log.d(tag, "download '$label' $link -> $ok")
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

    // حل أخير لسيرفر ما له extractor: نقرأ صفحة الـ embed ونلقط m3u8/mp4 الظاهرة في النص.
    // لا ينجح مع الصفحات المشفرة (packed JS)، وقتها يرجع false والـ Logcat يبين السيرفر.
    private suspend fun genericScan(
        link: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val text = try {
            app.get(link, referer = referer).text.replace("\\/", "/")
        } catch (e: Exception) {
            Log.d(tag, "genericScan error $link: ${e.message}")
            return false
        }

        val host = link.substringAfter("://").substringBefore("/")
        var found = false

        val m3u8s = Regex("""https?://[^\s"'\\<>]+\.m3u8[^\s"'\\<>]*""")
            .findAll(text).map { it.value }.distinct().toList()
        for (m in m3u8s) {
            callback(
                newExtractorLink(
                    source = host,
                    name = host,
                    url = m,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = link
                    quality = Qualities.Unknown.value
                }
            )
            found = true
        }

        val mp4s = Regex("""https?://[^\s"'\\<>]+\.mp4[^\s"'\\<>]*""")
            .findAll(text).map { it.value }.distinct().toList()
        for (m in mp4s) {
            callback(
                newExtractorLink(
                    source = host,
                    name = host,
                    url = m,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = link
                    quality = Qualities.Unknown.value
                }
            )
            found = true
        }

        Log.d(tag, "genericScan $host: m3u8=${m3u8s.size} mp4=${mp4s.size}")
        return found
    }
}
