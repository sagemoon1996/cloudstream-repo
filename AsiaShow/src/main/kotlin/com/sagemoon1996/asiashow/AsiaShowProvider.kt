package com.sagemoon1996.asiashow

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AsiaShowProvider : MainAPI() {

    override var mainUrl = "https://asiashow.net"

    override var name = "AsiaShow"

    override var lang = "ar"

    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    // ملاحظة: شلت رو "أخر الحلقات" من الصفحة الرئيسية لأن روابطها توجه
    // مباشرة لصفحة الحلقة (مش صفحة المسلسل)، وباش نحولها لصفحة المسلسل
    // بطريقة موثوقة لازمنا JS. حطيت بدالها "برامج" وكوريا زادة.
    override val mainPage = mainPageOf(
        "$mainUrl/series/" to "المسلسلات",
        "$mainUrl/movies/" to "الأفلام",
        "$mainUrl/genre/shows/" to "برامج",
        "$mainUrl/country/kr/" to "الكورية",
        "$mainUrl/country/cn/" to "الصينية",
        "$mainUrl/country/jp/" to "اليابانية",
        "$mainUrl/country/th/" to "التايلاندية"
    )

    private fun absoluteUrl(
        element: Element
    ): String {

        val absolute = element
            .attr("abs:href")
            .trim()

        if (absolute.startsWith("http")) {
            return absolute
        }

        val href = element
            .attr("href")
            .trim()

        return when {
            href.startsWith("http") -> href
            href.startsWith("/") -> "$mainUrl$href"
            href.isNotBlank() -> "$mainUrl/${href.trimStart('/')}"
            else -> ""
        }
    }

    private fun posterFromElement(
        element: Element
    ): String? {

        val image = element.selectFirst(
            "img[data-src], img[data-lazy-src], img[src]"
        ) ?: return null

        return image.attr("data-src")
            .ifBlank {
                image.attr("data-lazy-src")
            }
            .ifBlank {
                image.attr("src")
            }
            .trim()
            .takeIf {
                it.startsWith("http")
            }
    }

    /**
     * الموقع الجديد يكرر النص جوه الرابط بشكل "العنوان الدولة العنوان"
     * (مثلا: "العاشق المقنع الصين العاشق المقنع") بدل "الدولة العنوان" القديمة.
     * هاذي الدالة تلقى أطول جزء يتكرر في البداية والنهاية (بلا فرضية أسماء
     * دول معروفة مسبقا) وتاخذو كالعنوان الحقيقي.
     */
    private fun dedupTitle(
        rawText: String
    ): String {

        val words = rawText
            .trim()
            .split(Regex("\\s+"))
            .filter {
                it.isNotBlank()
            }

        if (words.size < 3) {
            return rawText.trim()
        }

        for (k in words.size / 2 downTo 1) {

            if (words.size < 2 * k + 1) {
                continue
            }

            val prefix = words.subList(0, k)
            val suffix = words.subList(words.size - k, words.size)

            if (prefix == suffix) {
                return prefix.joinToString(" ")
            }
        }

        return rawText.trim()
    }

    private fun titleFromLink(
        link: Element
    ): String {

        val attrTitle = link
            .attr("title")
            .trim()

        val rawText = attrTitle.ifBlank {
            link.text().trim()
        }

        if (rawText.isBlank()) {
            return ""
        }

        return dedupTitle(rawText)
    }

    private fun parseContentLink(
        link: Element
    ): SearchResponse? {

        val href = absoluteUrl(link)

        if (!href.startsWith(mainUrl)) {
            return null
        }

        if (
            href.contains("/episodes/") ||
            href.contains("/category/") ||
            href.contains("/country/") ||
            href.contains("/genre/") ||
            href.contains("/person/")
        ) {
            return null
        }

        val isSeries = href.contains(
            "/series/",
            ignoreCase = true
        )

        val isMovie = href.contains(
            "/movies/",
            ignoreCase = true
        )

        if (!isSeries && !isMovie) {
            return null
        }

        val title = titleFromLink(
            link
        )

        if (title.isBlank()) {
            return null
        }

        val container = link.parent()

        val poster =
            posterFromElement(link)
                ?: container?.let {
                    posterFromElement(it)
                }

        return if (isMovie) {

            newMovieSearchResponse(
                name = title,
                url = href,
                type = TvType.Movie
            ) {
                posterUrl = poster
            }

        } else {

            newTvSeriesSearchResponse(
                title,
                href,
                TvType.TvSeries
            ) {
                posterUrl = poster
            }
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        if (page > 1) {
            return newHomePageResponse(
                request.name,
                emptyList(),
                hasNext = false
            )
        }

        val document = app.get(
            request.data,
            referer = mainUrl
        ).document

        val items = document
            .select(
                "a[href*='/series/'], a[href*='/movies/']"
            )
            .mapNotNull {
                parseContentLink(it)
            }
            .distinctBy {
                it.url
            }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = false
        )
    }

    private fun normalizeArabic(
        text: String
    ): String {

        return text
            .lowercase()
            .replace(Regex("[إأآا]"), "ا")
            .replace('ى', 'ي')
            .replace('ة', 'ه')
            .replace(Regex("[\\u064B-\\u0652]"), "")
            .trim()
    }

    /**
     * الموقع رمى الـ ?s= القديمة (ترجع الصفحة الرئيسية بلا فلترة).
     * البحث الحقيقي أصبح مودال JS (⌘+K) ما نجمش نوصلولو بلا تشغيل
     * جافاسكريبت. كبديل: نفلترو محليا في الصفحة الرئيسية + أرشيف
     * المسلسلات والأفلام (الصفحة الأولى فقط، الباقي محمّل بـ"Load more"
     * عبر JS).
     */
    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val normalizedQuery = normalizeArabic(
            query
        )

        if (normalizedQuery.isBlank()) {
            return emptyList()
        }

        val pagesToSearch = listOf(
            mainUrl,
            "$mainUrl/series/",
            "$mainUrl/movies/"
        )

        val results = mutableListOf<SearchResponse>()

        for (pageUrl in pagesToSearch) {

            try {

                val document = app.get(
                    pageUrl,
                    referer = mainUrl
                ).document

                document
                    .select(
                        "a[href*='/series/'], a[href*='/movies/']"
                    )
                    .mapNotNull {
                        parseContentLink(it)
                    }
                    .filterTo(results) {
                        normalizeArabic(it.name).contains(normalizedQuery)
                    }

            } catch (_: Exception) {
            }
        }

        return results.distinctBy {
            it.url
        }
    }

    private fun episodeNumber(
        text: String,
        href: String
    ): Int? {

        val regex = Regex(
            """(?:الحلقة|episode|ep)[^\d]*(\d+)""",
            RegexOption.IGNORE_CASE
        )

        return regex.find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: regex.find(href)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    private fun extractSeason(
        document: Document
    ): Int {

        val text = document
            .select("body")
            .text()

        return Regex("""الموسم\s*(\d+)""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 1
    }

    private fun parseEpisodes(
        document: Document,
        seasonNumber: Int
    ): List<Episode> {

        return document
            .select("a[href*='/episodes/']")
            .mapNotNull { link ->

                val href = absoluteUrl(link)

                if (!href.startsWith(mainUrl)) {
                    return@mapNotNull null
                }

                val rawText = link
                    .text()
                    .trim()

                val number = episodeNumber(
                    rawText,
                    href
                ) ?: return@mapNotNull null

                val displayName = dedupTitle(
                    rawText
                ).ifBlank {
                    "الحلقة $number"
                }

                newEpisode(href) {

                    name = displayName
                    season = seasonNumber
                    episode = number
                }
            }
            .distinctBy {
                it.data
            }
    }

    /**
     * محاولة "best effort" لجلب حلقات إضافية لو المسلسل عندو أكثر من
     * دفعة واحدة محملة عبر "Load more". ما نجمتش نلقى الـ endpoint
     * الحقيقي متاع الموقع (JS)، فنجرب أنماط pagination شائعة في
     * ووردبريس. إذا حتى نمط ما زادش حلقات جديدة، نوقفو بسرعة بلا
     * ضياع وقت.
     */
    private suspend fun loadAdditionalEpisodes(
        seriesUrl: String,
        seasonNumber: Int,
        collected: MutableList<Episode>
    ) {

        val existingUrls = collected
            .map {
                it.data
            }
            .toMutableSet()

        val patterns = listOf(
            "page",
            "paged",
            "ep_page"
        )

        for (pattern in patterns) {

            var page = 2
            var gainedAny = false

            while (page <= 10) {

                val separator = if (seriesUrl.contains("?")) "&" else "?"
                val pageUrl = "$seriesUrl$separator$pattern=$page"

                val newEpisodes = try {

                    val doc = app.get(
                        pageUrl,
                        referer = seriesUrl
                    ).document

                    parseEpisodes(
                        doc,
                        seasonNumber
                    )

                } catch (_: Exception) {
                    emptyList()
                }

                val fresh = newEpisodes.filter {
                    it.data !in existingUrls
                }

                if (fresh.isEmpty()) {
                    break
                }

                fresh.forEach {
                    existingUrls.add(it.data)
                    collected.add(it)
                }

                gainedAny = true
                page++
            }

            if (gainedAny) {
                // هاذا النمط خدم، مانيش محتاج نجرب الباقي
                break
            }
        }
    }

    private fun extractTitle(
        document: Document
    ): String? {

        val ogTitle = document
            .selectFirst(
                "meta[property=og:title]"
            )
            ?.attr("content")
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }

        if (ogTitle != null) {
            return ogTitle
                .replace(
                    Regex("""\s*[–-]\s*اسيا شو.*$"""),
                    ""
                )
                .trim()
        }

        return document
            .selectFirst(
                ".entry-title, h1, h2"
            )
            ?.text()
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
    }

    private fun extractPoster(
        document: Document
    ): String? {

        return document
            .selectFirst(
                "meta[property=og:image]"
            )
            ?.attr("content")
            ?.trim()
            ?.takeIf {
                it.startsWith("http")
            }
            ?: document
                .selectFirst(
                    ".poster img, .cover img, img"
                )
                ?.let {
                    it.attr("data-src")
                        .ifBlank {
                            it.attr("data-lazy-src")
                        }
                        .ifBlank {
                            it.attr("src")
                        }
                }
                ?.trim()
                ?.takeIf {
                    it.startsWith("http")
                }
    }

    private fun extractDescription(
        document: Document
    ): String? {

        return document
            .selectFirst(
                "meta[property=og:description]"
            )
            ?.attr("content")
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?: document
                .selectFirst(
                    ".description, .desc, .entry-content"
                )
                ?.text()
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document = app.get(
            url,
            referer = mainUrl
        ).document

        val title = extractTitle(
            document
        ) ?: return null

        val poster = extractPoster(
            document
        )

        val description = extractDescription(
            document
        )

        val isMovie =
            url.contains(
                "/movies/",
                ignoreCase = true
            )

        if (isMovie) {

            return newMovieLoadResponse(
                name = title,
                url = url,
                type = TvType.Movie,
                dataUrl = url
            ) {
                posterUrl = poster
                plot = description
            }
        }

        val seasonNumber = extractSeason(
            document
        )

        val episodes = parseEpisodes(
            document,
            seasonNumber
        ).toMutableList()

        loadAdditionalEpisodes(
            url,
            seasonNumber,
            episodes
        )

        val sortedEpisodes = episodes
            .distinctBy {
                it.data
            }
            .sortedBy {
                it.episode
            }

        return newTvSeriesLoadResponse(
            name = title,
            url = url,
            type = TvType.TvSeries,
            episodes = sortedEpisodes
        ) {
            posterUrl = poster
            plot = description
        }
    }

    private fun decodeServerUrl(
        encoded: String
    ): String? {

        if (encoded.isBlank()) {
            return null
        }

        return try {

            base64Decode(
                encoded.trim()
            )
                .trim()
                .takeIf {
                    it.startsWith("http")
                }

        } catch (_: Exception) {
            null
        }
    }

    private suspend fun loadDirectVideo(
        serverUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        return try {

            val document = app.get(
                serverUrl,
                referer = mainUrl
            ).document

            val mediaUrl = document
                .selectFirst(
                    "video[data-link]"
                )
                ?.attr("data-link")
                ?.trim()
                ?.takeIf {
                    it.startsWith("http")
                }

            if (mediaUrl == null) {
                false
            } else {

                callback(
                    newExtractorLink(
                        source = "Ult4vid",
                        name = "Ult4vid",
                        url = mediaUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        referer = serverUrl
                    }
                )

                true
            }

        } catch (_: Exception) {
            false
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(
            data,
            referer = mainUrl
        ).document

        var foundLinks = false

        // 1) التصميم الجديد يحط رابط السيرفر مباشرة في iframe[src]
        // (بلا ترميز base64)، فنجربو هاذا أول حاجة لأنه الأكثر احتمالا
        // يخدم مع الموقع الحالي.
        val iframeUrls = document
            .select(
                "iframe[src], iframe[data-src]"
            )
            .mapNotNull { iframe ->

                iframe
                    .attr("src")
                    .ifBlank {
                        iframe.attr("data-src")
                    }
                    .trim()
                    .takeIf {
                        it.startsWith("http")
                    }
            }
            .distinct()

        val ult4vidIframes = iframeUrls.filter {
            it.contains(
                "ult4vid",
                ignoreCase = true
            )
        }

        for (serverUrl in ult4vidIframes) {

            if (
                loadDirectVideo(
                    serverUrl = serverUrl,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            ) {
                foundLinks = true
            }
        }

        for (iframeUrl in iframeUrls.filterNot { ult4vidIframes.contains(it) }) {

            try {

                loadExtractor(
                    url = iframeUrl,
                    referer = iframeUrl,
                    subtitleCallback = subtitleCallback
                ) { link ->

                    foundLinks = true
                    callback(link)
                }

            } catch (_: Exception) {
            }
        }

        // 2) fallback للآلية القديمة: data-etk-src مرمز base64، في حالة
        // كان الموقع يستعملها في بعض الصفحات القديمة أو مستقبلا.
        if (!foundLinks) {

            val serverUrls = document
                .select("[data-etk-src]")
                .mapNotNull { element ->

                    decodeServerUrl(
                        element.attr(
                            "data-etk-src"
                        )
                    )
                }
                .distinct()

            val ult4vidUrls = serverUrls.filter {
                it.contains(
                    "ult4vid",
                    ignoreCase = true
                )
            }

            for (serverUrl in ult4vidUrls) {

                if (
                    loadDirectVideo(
                        serverUrl = serverUrl,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                ) {
                    foundLinks = true
                }
            }

            for (serverUrl in serverUrls.filterNot { ult4vidUrls.contains(it) }) {

                try {

                    loadExtractor(
                        url = serverUrl,
                        referer = serverUrl,
                        subtitleCallback = subtitleCallback
                    ) { link ->

                        foundLinks = true
                        callback(link)
                    }

                } catch (_: Exception) {
                }
            }
        }

        return foundLinks
    }
}
