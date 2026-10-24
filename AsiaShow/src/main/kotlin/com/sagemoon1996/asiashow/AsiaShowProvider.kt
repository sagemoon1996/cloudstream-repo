package com.sagemoon1996.asiashow

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
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

    // data format: "vx:<key>|<section html url>" = ajax list with html fallback, otherwise a plain html url
    override val mainPage = mainPageOf(
        "$mainUrl/%D8%A3%D8%AE%D8%B1-%D8%A7%D9%84%D8%AD%D9%84%D9%82%D8%A7%D8%AA/" to "آخر الحلقات",
        "vx:series|$mainUrl/series/" to "المسلسلات",
        "vx:movies|$mainUrl/movies/" to "الأفلام",
        "vx:shows|$mainUrl/genre/shows/" to "البرامج",
        "$mainUrl/country/cn/" to "الصينية",
        "$mainUrl/country/jp/" to "اليابانية",
        "$mainUrl/country/th/" to "التايلاندية"
    )

    private val searchPostTypes =
        """{"movies":{"filter_key":"keywords","taxonomies":[]},"series":{"filter_key":"keywords","taxonomies":[]}}"""

    private val episodeTitleSuffix = Regex(
        """\s*[-–:]?\s*(?:الموسم\s*\d+\s*)?(?:الحلقة|حلقة)\s*\d+.*$""",
        RegexOption.IGNORE_CASE
    )
    private val seasonRegex = Regex("""(?:الموسم|موسم|season)[-\s]*(\d+)""", RegexOption.IGNORE_CASE)
    private val episodeRegex = Regex("""(?:الحلقة|حلقة|episode|ep)[-\s]*(\d+)""", RegexOption.IGNORE_CASE)

    // ───────────────────────── helpers ─────────────────────────

    private fun hrefOf(element: Element): String {
        val abs = element.attr("abs:href").trim()
        if (abs.startsWith("http")) return abs

        val href = element.attr("href").trim()
        return when {
            href.startsWith("http") -> href
            href.startsWith("//") -> "https:$href"
            href.startsWith("/") -> "$mainUrl$href"
            href.isNotBlank() -> "$mainUrl/${href.trimStart('/')}"
            else -> ""
        }
    }

    private fun imageOf(element: Element?): String? {
        val img = element?.selectFirst("img.etk-card-img") ?: element?.selectFirst("img") ?: return null
        val src = img.attr("data-src")
            .ifBlank { img.attr("data-lazy-src") }
            .ifBlank { img.attr("src") }
            .trim()
        return when {
            src.startsWith("http") -> src
            src.startsWith("//") -> "https:$src"
            else -> null
        }
    }

    private fun normalizeUrl(url: String): String {
        if (url.isBlank()) return ""
        val clean = url.substringBefore('?').substringBefore('#')
        val path = clean.substringAfter("://").substringAfter('/', "")
        val decoded = try {
            URLDecoder.decode(path.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            return clean
        }
        val encoded = decoded.split("/").joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        return "$mainUrl/$encoded"
    }

    private fun slugOf(url: String, section: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val raw = path.substringAfter("/$section/", "").trim('/')
        return try {
            URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            raw
        }
    }

    private fun seriesSlugFromEpisodeSlug(episodeSlug: String): String {
        return episodeSlug
            .replace(Regex("""-(?:الحلقة|حلقة)-?\d+$"""), "")
            .replace(Regex("""-(?:الموسم|موسم)-?\d+$"""), "")
    }

    private fun seriesUrlFromEpisodeUrl(episodeUrl: String): String? {
        val episodeSlug = slugOf(episodeUrl, "episodes")
        if (episodeSlug.isBlank()) return null
        val seriesSlug = seriesSlugFromEpisodeSlug(episodeSlug)
        if (seriesSlug.isBlank() || seriesSlug == episodeSlug) return null
        return "$mainUrl/series/" + URLEncoder.encode(seriesSlug, "UTF-8").replace("+", "%20") + "/"
    }

    private fun hostOf(url: String): String {
        return try {
            URI(url).host?.lowercase() ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun originOf(url: String): String {
        return try {
            val u = URI(url)
            "${u.scheme}://${u.host}/"
        } catch (_: Exception) {
            "$mainUrl/"
        }
    }

    private fun collectStrings(node: Any?, out: MutableList<String>) {
        when (node) {
            is String -> out.add(node)
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) collectStrings(node.opt(keys.next()), out)
            }
            is JSONArray -> for (i in 0 until node.length()) collectStrings(node.opt(i), out)
        }
    }

    private fun extractHtml(text: String): String {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("{")) return text
        return try {
            val parts = mutableListOf<String>()
            collectStrings(JSONObject(trimmed), parts)
            parts.filter { it.contains("<") }.joinToString("\n").ifBlank { text }
        } catch (_: Exception) {
            text
        }
    }

    // ───────────────────────── lists ─────────────────────────

    private fun buildCard(link: Element, card: Element): SearchResponse? {
        val rawUrl = hrefOf(link)
        if (rawUrl.isBlank() || !hostOf(rawUrl).endsWith("asiashow.net")) return null

        val rawTitle = card.selectFirst(".etk-card-title")?.text()?.trim()
            .orEmpty()
            .ifBlank { link.attr("title").trim() }
            .ifBlank { link.text().trim() }

        val poster = imageOf(card)

        return when {
            rawUrl.contains("/series/", true) -> {
                if (rawTitle.isBlank()) return null
                newTvSeriesSearchResponse(rawTitle, normalizeUrl(rawUrl), TvType.TvSeries) {
                    this.posterUrl = poster
                }
            }

            rawUrl.contains("/movies/", true) -> {
                if (rawTitle.isBlank()) return null
                newMovieSearchResponse(rawTitle, normalizeUrl(rawUrl), TvType.Movie) {
                    this.posterUrl = poster
                }
            }

            rawUrl.contains("/episodes/", true) -> {
                val seriesUrl = seriesUrlFromEpisodeUrl(rawUrl) ?: return null
                val title = rawTitle.replace(episodeTitleSuffix, "").trim()
                if (title.isBlank()) return null
                newTvSeriesSearchResponse(title, seriesUrl, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            }

            else -> null
        }
    }

    private fun parseCards(text: String): List<SearchResponse> {
        val document = Jsoup.parse(extractHtml(text), mainUrl)

        val fromPreviews = document.select(".ts-preview").mapNotNull { card ->
            val link = card.selectFirst("a.etk-card-link[href]")
                ?: card.selectFirst("a[href*='/series/'], a[href*='/movies/'], a[href*='/episodes/']")
                ?: return@mapNotNull null
            buildCard(link, card)
        }
        if (fromPreviews.isNotEmpty()) return fromPreviews.distinctBy { it.url }

        return document.select("a.etk-card-link[href]")
            .mapNotNull { link -> buildCard(link, link.parent() ?: link) }
            .distinctBy { it.url }
    }

    private fun ajaxUrl(key: String, page: Int): String? {
        val base = "$mainUrl/?vx=1&action=search_posts"
        return when (key) {
            "movies" -> "$base&type=movies&countries=&genres=&year=&sort=&pg=$page&limit=40&__template_id=89530"
            "series" -> "$base&type=series&countries=&genres=&year=&sort=&pg=$page&limit=40&__template_id=89530"
            "shows" -> "$base&type=series&country=&genres=shows&year=&pg=$page&limit=40&__template_id=83304"
            else -> null
        }
    }

    private suspend fun fetchAjaxList(key: String, page: Int, sectionUrl: String): List<SearchResponse> {
        val url = ajaxUrl(key, page) ?: return emptyList()
        return try {
            val response = app.get(
                url,
                referer = sectionUrl,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Accept" to "*/*")
            )
            if (response.code !in 200..299) emptyList() else parseCards(response.text)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchHtmlList(url: String): List<SearchResponse> {
        return try {
            parseCards(app.get(url, referer = mainUrl).text)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data

        if (data.startsWith("vx:")) {
            val spec = data.removePrefix("vx:")
            val key = spec.substringBefore("|")
            val sectionUrl = spec.substringAfter("|")

            var items = fetchAjaxList(key, page, sectionUrl)
            var hasNext = items.isNotEmpty()

            if (items.isEmpty() && page == 1) {
                items = fetchHtmlList(sectionUrl)
                hasNext = false
            }
            return newHomePageResponse(request.name, items, hasNext)
        }

        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
        return newHomePageResponse(request.name, fetchHtmlList(data), false)
    }

    // ───────────────────────── search ─────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.length < 3) return emptyList()

        val url = "$mainUrl/?vx=1&action=quick_search" +
            "&search=${URLEncoder.encode(q, "UTF-8")}" +
            "&post_types=${URLEncoder.encode(searchPostTypes, "UTF-8")}"

        return try {
            val response = app.get(url, referer = mainUrl)
            val data = JSONObject(response.text).optJSONArray("data") ?: return emptyList()

            buildList {
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val title = Jsoup.parse(item.optString("title")).text().trim()
                    val link = item.optString("link").trim()
                    if (title.isBlank() || link.isBlank()) continue

                    val full = when {
                        link.startsWith("http") -> link
                        link.startsWith("/") -> "$mainUrl$link"
                        else -> "$mainUrl/$link"
                    }
                    val poster = item.optString("logo").trim().takeIf { it.startsWith("http") }

                    when {
                        full.contains("/series/", true) -> add(
                            newTvSeriesSearchResponse(title, normalizeUrl(full), TvType.TvSeries) {
                                this.posterUrl = poster
                            }
                        )

                        full.contains("/movies/", true) -> add(
                            newMovieSearchResponse(title, normalizeUrl(full), TvType.Movie) {
                                this.posterUrl = poster
                            }
                        )
                    }
                }
            }.distinctBy { it.url }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ───────────────────────── details ─────────────────────────

    private class EpisodeCandidate(
        val url: String,
        val slug: String,
        val card: Element,
        val index: Int
    )

    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("/episodes/", true)) {
            val seriesUrl = seriesUrlFromEpisodeUrl(url) ?: return null
            return load(seriesUrl)
        }

        val document = app.get(url, referer = mainUrl).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?.replace(Regex("""\s*[–—-]\s*(?:اسيا|آسيا)\s*شو.*$"""), "")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?.takeIf { it.startsWith("http") }
            ?: document.selectFirst("img[src*='image.tmdb.org/t/p/w500']")?.attr("src")?.trim()

        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }

        if (url.contains("/movies/", true)) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        val seriesSlug = slugOf(url, "series")
        val seen = HashSet<String>()
        val candidates = mutableListOf<EpisodeCandidate>()

        document.select("a[href*='/episodes/']").forEachIndexed { index, link ->
            val epUrl = normalizeUrl(hrefOf(link))
            if (epUrl.isBlank() || !seen.add(epUrl)) return@forEachIndexed
            val card = link.closest(".ts-preview") ?: link
            candidates.add(EpisodeCandidate(epUrl, slugOf(epUrl, "episodes"), card, index))
        }

        val related = candidates.filter { seriesSlug.isNotBlank() && it.slug.startsWith(seriesSlug) }
        val chosen = related.ifEmpty { candidates }

        val episodes = chosen.map { c ->
            val cardTitle = c.card.selectFirst(".etk-card-title")?.text()?.trim().orEmpty()
            val seasonNumber = seasonRegex.find(c.slug)?.groupValues?.get(1)?.toIntOrNull()
                ?: seasonRegex.find(cardTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: 1
            val episodeNumber = episodeRegex.find(c.slug)?.groupValues?.get(1)?.toIntOrNull()
                ?: episodeRegex.find(cardTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: (c.index + 1)

            newEpisode(c.url) {
                this.name = cardTitle.ifBlank { "الحلقة $episodeNumber" }
                this.season = seasonNumber
                this.episode = episodeNumber
                this.posterUrl = imageOf(c.card)
            }
        }.sortedWith(
            compareBy<Episode>({ it.season ?: 1 }, { it.episode ?: Int.MAX_VALUE })
        )

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // ───────────────────────── links ─────────────────────────

    private fun decodeServerUrl(encoded: String): String? {
        val value = encoded.trim()
        if (value.startsWith("http")) return value
        return try {
            base64Decode(value).trim().takeIf { it.startsWith("http") }
        } catch (_: Exception) {
            null
        }
    }

    private fun findString(node: Any?, key: String, httpOnly: Boolean): String? {
        when (node) {
            is JSONObject -> {
                val own = node.opt(key)
                if (own is String && (!httpOnly || own.startsWith("http"))) return own
                val keys = node.keys()
                while (keys.hasNext()) {
                    findString(node.opt(keys.next()), key, httpOnly)?.let { return it }
                }
            }

            is JSONArray -> for (i in 0 until node.length()) {
                findString(node.opt(i), key, httpOnly)?.let { return it }
            }
        }
        return null
    }

    private fun isHls(url: String, mime: String = ""): Boolean {
        return url.contains(".m3u8", true) ||
            url.contains("master.txt", true) ||
            mime.contains("mpegurl", true)
    }

    private suspend fun emit(
        source: String,
        mediaUrl: String,
        referer: String,
        mime: String,
        callback: (ExtractorLink) -> Unit
    ) {
        callback(
            newExtractorLink(
                source = source,
                name = source,
                url = mediaUrl,
                type = if (isHls(mediaUrl, mime)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
            }
        )
    }

    private suspend fun load71stream(
        embedUrl: String,
        pageReferer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(embedUrl, referer = pageReferer).document
        val raw = document.selectFirst("#app")?.attr("data-page")?.takeIf { it.isNotBlank() }
            ?: return false

        val props = JSONObject(raw).optJSONObject("props") ?: return false
        val mediaUrl = findString(props, "url", true) ?: return false
        val mime = findString(props, "mime", false).orEmpty()

        emit("71stream", mediaUrl, "https://71stream.one/", mime, callback)
        return true
    }

    private suspend fun loadUlt4vid(
        embedUrl: String,
        pageReferer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(embedUrl, referer = pageReferer)
        val document = response.document

        val mediaUrl = (document.selectFirst("video#playbob-video[data-link]")
            ?: document.selectFirst("video[data-link]"))
            ?.attr("data-link")?.trim()
            ?.takeIf { it.startsWith("http") }
            ?: return false

        emit("Ult4vid", mediaUrl, originOf(response.url), "", callback)
        return true
    }

    private suspend fun scrapeGeneric(
        embedUrl: String,
        pageReferer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(embedUrl, referer = pageReferer)
        var html = response.text

        if (html.contains("eval(function(p,a,c,k,e")) {
            try {
                html += "\n" + getAndUnpack(html)
            } catch (_: Exception) {
            }
        }
        html = html.replace("\\/", "/")

        val finalUrl = response.url
        val origin = originOf(finalUrl)
        val label = hostOf(finalUrl).removePrefix("www.").substringBeforeLast('.').ifBlank { "Server" }
            .replaceFirstChar { it.uppercase() }

        val absolute = Regex(
            """https?://[^\s"'<>\\]+?(?:\.m3u8|\.mp4|/master\.txt)(?:\?[^\s"'<>\\]*)?"""
        ).findAll(html).map { it.value }.toList()

        val relative = Regex("""["'](/[^"'\s<>]+\.m3u8[^"'\s<>]*)["']""")
            .findAll(html).map { origin.trimEnd('/') + it.groupValues[1] }.toList()

        val all = (absolute + relative).distinct()
        if (all.isEmpty()) return false

        all.forEach { emit(label, it, origin, "", callback) }
        return true
    }

    private suspend fun loadOther(
        serverUrl: String,
        pageReferer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var got = false
        val tracking: (ExtractorLink) -> Unit = { link ->
            got = true
            callback(link)
        }

        try {
            loadExtractor(
                url = serverUrl,
                referer = pageReferer,
                subtitleCallback = subtitleCallback,
                callback = tracking
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }

        if (got) return true
        return scrapeGeneric(serverUrl, pageReferer, callback)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = data.trim()
        if (!pageUrl.startsWith("http")) return false

        val document = try {
            app.get(pageUrl, referer = mainUrl).document
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }

        val servers = document
            .select("[data-etk-server-btn][data-etk-src]")
            .mapNotNull { decodeServerUrl(it.attr("data-etk-src")) }
            .distinct()

        if (servers.isEmpty()) return false

        var found = false
        val tracking: (ExtractorLink) -> Unit = { link ->
            found = true
            callback(link)
        }

        for (server in servers) {
            val host = hostOf(server)
            try {
                when {
                    host.contains("71stream") -> load71stream(server, pageUrl, tracking)
                    host.contains("ult4vid") || host.contains("ultra4vid") ->
                        loadUlt4vid(server, pageUrl, tracking)

                    else -> loadOther(server, pageUrl, subtitleCallback, tracking)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // سيرفر فاشل: نكمل للسيرفر اللي بعده
            }
        }

        return found
    }
}
