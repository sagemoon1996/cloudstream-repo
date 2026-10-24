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

    // تشخيص: يظهر في قائمة المصادر كل سيرفر فشل استخراجه باسم "✗ ..." (الرابط لا يشتغل، للمعاينة فقط).
    // بدّلها false بعد ما نكمّل السيرفرات.
    private val debugShowFailed = true

    // صفحات مثبتة بالـ HTML: /latest-series/ و /latest-movies/ (الترقيم /page/N/).
    // /latest-episodes/ غير مثبت (parser عام).
    // الأقسام HOMEROW:: تُقرا من الصفحة الرئيسية "/" بالبحث عن عنوان القسم (غير مثبت، طريقة تقريبية)؛
    // سطر Logcat "homeRow" يبين هل لقى القسم وكم عنصر.
    override val mainPage = mainPageOf(
        "$mainUrl/latest-episodes/" to "آخر الحلقات",
        "$mainUrl/latest-series/" to "جديد الدراما",
        "$mainUrl/latest-movies/" to "جديد الأفلام",
        "HOMEROW::قريبا" to "تعرض قريبا",
        "HOMEROW::البرامج" to "جديد البرامج الآسيوية",
        "HOMEROW::انمي" to "جديد الأنمي",
        "HOMEROW::الدراما الآسيوية" to "دراما آسيوية"
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

    // "Game of Thrones — الموسم 3 — الحلقة 3" -> "Game of Thrones"
    private fun cleanSeriesName(raw: String): String =
        raw.split("—", "–", " - ")
            .first()
            .substringBefore("الموسم")
            .substringBefore("الحلقة")
            .replace(Regex("\\s+"), " ")
            .trim()

    // كلمات مثل "جديدة" (badge) أو أرقام فقط ما هيش اسم مسلسل
    private fun isNoiseTitle(t: String): Boolean =
        t.isBlank() || t.matches(Regex("""\d+""")) || (t.contains("جديد") && t.length < 12)

    // احتياط: اسم المسلسل من slug الحلقة (game-of-thrones-3x3 -> Game Of Thrones)
    private fun seriesNameFromSlug(epUrl: String): String {
        val slug = epUrl.substringBefore("?").trimEnd('/').substringAfterLast('/')
        val decoded = try {
            java.net.URLDecoder.decode(slug, "UTF-8")
        } catch (e: Exception) {
            slug
        }
        return decoded
            .replace(Regex("""-\d+x\d+$"""), "")
            .replace(Regex("""-\d+$"""), "")
            .split("-")
            .filter { it.isNotBlank() }
            .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
            .trim()
    }

    // Parser عام (غير مثبت): لأقسام /latest-episodes/ و /new-rows/ إلى أن يتثبت HTML متاعهم.
    // يجمع كل رابط /episode/ أو /series/ أو /movie/ داخل <main> بدون تكرار.
    private fun parseGenericCards(root: Element): List<SearchResponse> {
        val result = LinkedHashMap<String, SearchResponse>()

        for (a in root.select("a[href*=/episode/], a[href*=/series/], a[href*=/movie/]")) {
            val href = fixUrlNull(a.attr("href"))?.substringBefore("#") ?: continue
            if (result.containsKey(href)) continue

            val box: Element = a.closest("article") ?: a.parent() ?: a

            val title: String
            if (href.contains("/episode/")) {
                // اسم المسلسل (alt الصورة، ثم عناوين الكارت، ثم الاسم من الرابط) + رقم الحلقة
                val candidates = listOfNotNull(
                    box.selectFirst("img[alt]")?.attr("alt"),
                    box.selectFirst(
                        ".ftv-home-poster-card__title, .ftv-tax-card__info h2, h2, h3, h4"
                    )?.text(),
                    a.attr("aria-label")
                )
                val seriesName = candidates
                    .map { cleanSeriesName(it) }
                    .firstOrNull { it.isNotBlank() && !isNoiseTitle(it) }
                    ?: seriesNameFromSlug(href)
                if (seriesName.isBlank()) continue

                val (s, e) = parseSeasonEpisode(href, "")
                title = when {
                    s != null && e != null -> "$seriesName - الموسم $s الحلقة $e"
                    e != null -> "$seriesName - الحلقة $e"
                    else -> seriesName
                }
            } else {
                val raw = box.selectFirst(
                    ".ftv-home-poster-card__title, .ftv-tax-card__info h2, h2, h3, h4, strong"
                )?.text()?.takeIf { it.isNotBlank() }
                    ?: a.attr("aria-label").ifBlank { a.text() }
                title = raw.replace(Regex("\\s+"), " ").trim()
                if (title.isBlank()) continue
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

    private fun normAr(t: String): String =
        t.replace(Regex("[أإآ]"), "ا").replace("ة", "ه").replace("ى", "ي")
            .replace(Regex("[^\\p{L}\\p{N}]"), "")

    private var homeCache: Pair<Long, Document>? = null

    private suspend fun homeDoc(): Document {
        val c = homeCache
        if (c != null && System.currentTimeMillis() - c.first < 60_000) return c.second
        val d = app.get("$mainUrl/").document
        homeCache = System.currentTimeMillis() to d
        return d
    }

    // نبحث في الصفحة الرئيسية عن عنوان يحتوي الكلمة المفتاحية، ثم نصعد لأقرب حاوية فيها >= 3 روابط محتوى.
    private suspend fun homeRow(keyword: String, label: String): List<SearchResponse> {
        val doc = try {
            homeDoc()
        } catch (e: Exception) {
            Log.d(tag, "homeRow '$label' fetch error: ${e.message}")
            return emptyList()
        }
        val root = doc.selectFirst("main") ?: doc
        val key = normAr(keyword)

        fun matches(el: Element): Boolean {
            val t = el.text().trim()
            return t.isNotBlank() && t.length < 60 && normAr(t).contains(key)
        }

        // أولا العناوين الحقيقية، ثم عناصر class فيها title/heading (بدون عناوين الكروت)
        val heading = root.select("h1, h2, h3, h4, h5").firstOrNull { matches(it) }
            ?: root.select("[class*=title]:not([class*=card]), [class*=heading]:not([class*=card])")
                .firstOrNull { matches(it) }

        if (heading == null) {
            Log.d(tag, "homeRow '$label': heading with '$keyword' NOT found")
            return emptyList()
        }

        var container: Element = heading
        var depth = 0
        while (
            depth < 6 &&
            container.select("a[href*=/series/], a[href*=/episode/], a[href*=/movie/]").size < 3
        ) {
            container = container.parent() ?: break
            depth++
        }

        val known = container.select("article.ftv-home-poster-card, article.ftv-tax-card")
            .mapNotNull { it.toCardResult() }
        val items = known.ifEmpty { parseGenericCards(container) }.distinctBy { it.url }

        Log.d(tag, "homeRow '$label': heading='${heading.text().trim()}' depth=$depth known=${known.size} total=${items.size}")
        return items
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data.startsWith("HOMEROW::")) {
            val items = homeRow(request.data.removePrefix("HOMEROW::"), request.name)
            return newHomePageResponse(request.name, items, false)
        }

        val isEpisodes = request.data.contains("/latest-episodes/")

        // request.data يمكن يحتوي أكثر من رابط مفصولين بـ | (نجرب التالي إذا الأول فارغ)
        for (base in request.data.split("|")) {
            val url = if (page <= 1) base else "${base.trimEnd('/')}/page/$page/"
            val doc = try {
                app.get(url).document
            } catch (e: Exception) {
                Log.d(tag, "mainPage fetch error $url: ${e.message}")
                continue
            }

            val known = doc.select("article.ftv-home-poster-card, article.ftv-tax-card")
                .mapNotNull { it.toCardResult() }

            val items = (
                if (isEpisodes) parseGenericCards(doc.selectFirst("main") ?: doc).ifEmpty { known }
                else known.ifEmpty { parseGenericCards(doc.selectFirst("main") ?: doc) }
            ).distinctBy { it.url }

            val hasNext = doc.selectFirst("a.next.page-numbers") != null
            Log.d(tag, "mainPage '${request.name}' $url: known=${known.size} total=${items.size} hasNext=$hasNext")

            if (items.isNotEmpty()) return newHomePageResponse(request.name, items, hasNext)
        }

        return newHomePageResponse(request.name, emptyList(), false)
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
            Log.d(tag, "server attrs: " + el.attributes().joinToString(" ") { "${it.key}=${it.value.take(60)}" })
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
                if (link.contains("71stream.one", ignoreCase = true)) {
                    ok = load71Stream(link, callback)
                } else {
                    // VidMoly: الدومين يتحول من .net إلى .biz (مثبت من المتصفح) فنجرب البدائل
                    val variants = if (link.contains("vidmoly", ignoreCase = true)) {
                        listOf(
                            link,
                            link.replace("vidmoly.net", "vidmoly.biz"),
                            link.replace("vidmoly.net", "vidmoly.to")
                        ).distinct()
                    } else {
                        listOf(link)
                    }
                    for (v in variants) {
                        ok = loadExtractor(v, data, subtitleCallback, callback) ||
                            genericScan(v, label, data, callback)
                        Log.d(tag, "  try $v -> $ok")
                        if (ok) break
                    }
                }
            } catch (e: Exception) {
                Log.d(tag, "watch error $link: ${e.message}")
            }
            Log.d(tag, "watch '$label' $link -> $ok")
            if (ok) {
                loaded = true
            } else if (debugShowFailed) {
                callback(
                    newExtractorLink(
                        source = "FiveTV ✗",
                        name = "✗ ${label.ifBlank { link.substringAfter("://").substringBefore("/") }} (فشل الاستخراج)",
                        url = link,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        referer = data
                        quality = Qualities.Unknown.value
                    }
                )
            }
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
                emitHls("71Stream", "71Stream HLS", m3u8, link, callback)
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

    // HLS: نقرا الـ master playlist لنطلع كل جودة حقيقية (الجودة المكتوبة في الموقع ما هيش موثوقة)،
    // وإذا فشل نرجع رابط واحد بجودة غير محددة.
    private suspend fun emitHls(
        source: String,
        name: String,
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val links = try {
            M3u8Helper.generateM3u8(source = name, streamUrl = url, referer = referer)
        } catch (e: Exception) {
            Log.d(tag, "generateM3u8 error: ${e.message}")
            emptyList()
        }
        Log.d(tag, "emitHls $source: ${links.size} qualities")
        if (links.isNotEmpty()) {
            for (l in links) callback(l)
            return
        }
        callback(
            newExtractorLink(
                source = source,
                name = name,
                url = url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer
                quality = Qualities.Unknown.value
            }
        )
    }

    // حل أخير لسيرفر ما له extractor: نقرأ صفحة الـ embed (ونفك eval packed إذا موجود) ونلقط روابط m3u8/mp4.
    // يغطي الأنماط الشائعة: رابط مباشر، file:"..."، hls2:"..."، مع مسارات نسبية.
    // ما ينجح مع الصفحات المحمية (Cloudflare) أو المشفرة بشكل آخر، وقتها يرجع false والـ Logcat يبين السبب.
    private suspend fun genericScan(
        link: String,
        label: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val raw = try {
            app.get(link, referer = referer).text
        } catch (e: Exception) {
            Log.d(tag, "genericScan error $link: ${e.message}")
            return false
        }

        val html = raw.replace("\\/", "/")
        val packed = html.contains("eval(function(p,a,c,k,e,d)")
        val unpacked = try {
            if (packed) getAndUnpack(html).replace("\\/", "/") else ""
        } catch (e: Exception) {
            Log.d(tag, "unpack error $link: ${e.message}")
            ""
        }
        val text = html + "\n" + unpacked

        val origin = Regex("""^https?://[^/]+""").find(link)?.value.orEmpty()
        val host = link.substringAfter("://").substringBefore("/")
        val found = LinkedHashSet<String>()

        Regex("""https?://[^\s"'\\<>]+\.(?:m3u8|mp4)[^\s"'\\<>]*""")
            .findAll(text).forEach { found.add(it.value) }

        Regex("""(?:file|hls\d*|src|source)["']?\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""")
            .findAll(text).forEach { m ->
                val u = m.groupValues[1]
                found.add(
                    when {
                        u.startsWith("http") -> u
                        u.startsWith("//") -> "https:$u"
                        u.startsWith("/") -> origin + u
                        else -> return@forEach
                    }
                )
            }

        Log.d(
            tag,
            "genericScan $host: len=${raw.length} packed=$packed found=${found.size} " +
                "cf=${raw.contains("Just a moment", true)}"
        )

        val name = label.ifBlank { host }
        for (u in found) {
            if (u.contains(".m3u8", true)) {
                emitHls(name, name, u, link, callback)
            } else {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = u,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = link
                        quality = Qualities.Unknown.value
                    }
                )
            }
        }
        return found.isNotEmpty()
    }
}
