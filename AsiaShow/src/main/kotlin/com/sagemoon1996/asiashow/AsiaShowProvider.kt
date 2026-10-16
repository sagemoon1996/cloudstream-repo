package com.sagemoon1996.asiashow

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AsiaShowProvider : MainAPI() {

    override var mainUrl = "https://asiashow.net"
    override var name = "AsiaShow"
    override var lang = "ar"

    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    private val cfKiller = CloudflareKiller()

    override val mainPage = mainPageOf(
        "$mainUrl/%D8%A3%D8%AE%D8%B1-%D8%A7%D9%84%D8%AD%D9%84%D9%82%D8%A7%D8%AA/" to "آخر الحلقات",
        "$mainUrl/series/" to "المسلسلات",
        "$mainUrl/movies/" to "الأفلام",
        "$mainUrl/country/cn/" to "الصينية",
        "$mainUrl/country/kr/" to "الكورية",
        "$mainUrl/country/jp/" to "اليابانية",
        "$mainUrl/country/th/" to "التايلاندية"
    )

    private val searchPostTypes =
        """{"movies":{"filter_key":"keywords","taxonomies":[]},"series":{"filter_key":"keywords","taxonomies":[]}}"""

    private fun absoluteUrl(element: Element): String {
        val absolute = element.attr("abs:href").trim()

        if (absolute.startsWith("http")) {
            return absolute
        }

        val href = element.attr("href").trim()

        return when {
            href.startsWith("http") -> href
            href.startsWith("//") -> "https:$href"
            href.startsWith("/") -> "$mainUrl$href"
            href.isNotBlank() -> "$mainUrl/${href.trimStart('/')}"
            else -> ""
        }
    }

    private fun isSameSite(url: String): Boolean {
        return try {
            val host = java.net.URI(url).host ?: return false
            host.equals("asiashow.net", ignoreCase = true) ||
                host.equals("www.asiashow.net", ignoreCase = true)
        } catch (_: Exception) {
            url.startsWith(mainUrl, ignoreCase = true)
        }
    }

    private fun elementImage(element: Element): String? {
        val image = element.selectFirst(
            "img[data-src], img[data-lazy-src], img[data-original], img[src]"
        ) ?: return null

        val imageUrl = image.attr("data-src")
            .ifBlank { image.attr("data-lazy-src") }
            .ifBlank { image.attr("data-original") }
            .ifBlank { image.attr("src") }
            .trim()

        return when {
            imageUrl.startsWith("https://") ||
                imageUrl.startsWith("http://") -> imageUrl
            imageUrl.startsWith("//") -> "https:$imageUrl"
            imageUrl.startsWith("/") -> "$mainUrl$imageUrl"
            else -> null
        }
    }

    private fun cardTitle(element: Element): String {
        val selectors = listOf(
            ".etk-card-title",
            ".entry-title",
            ".post-title",
            ".title",
            ".name",
            "[itemprop=name]",
            "h1",
            "h2",
            "h3",
            "h4"
        )

        for (selector in selectors) {
            val title = element.selectFirst(selector)
                ?.text()
                ?.trim()

            if (!title.isNullOrBlank()) {
                return title
            }
        }

        return element.attr("aria-label").trim()
            .ifBlank { element.attr("title").trim() }
            .ifBlank { element.text().trim() }
    }

    private fun parseContentCard(link: Element): SearchResponse? {
        val url = absoluteUrl(link)

        if (url.isBlank() || !isSameSite(url)) {
            return null
        }

        if (url.contains("/episodes/", ignoreCase = true)) {
            return null
        }

        val isSeries = url.contains("/series/", ignoreCase = true)
        val isMovie = url.contains("/movies/", ignoreCase = true)

        if (!isSeries && !isMovie) {
            return null
        }

        val card = link.closest(
            ".etk-card, article, .item, .post, .movie-item, .series-item"
        ) ?: link.parent() ?: link

        val title = cardTitle(link)
            .ifBlank { cardTitle(card) }

        if (title.isBlank()) {
            return null
        }

        val poster = elementImage(link)
            ?: elementImage(card)

        return if (isSeries) {
            newTvSeriesSearchResponse(
                name = title,
                url = url,
                type = TvType.TvSeries
            ) {
                posterUrl = poster
            }
        } else {
            newMovieSearchResponse(
                name = title,
                url = url,
                type = TvType.Movie
            ) {
                posterUrl = poster
            }
        }
    }

    private fun parseContentCards(document: Document): List<SearchResponse> {
        return document
            .select("a[href*='/series/'], a[href*='/movies/']")
            .mapNotNull { link ->
                parseContentCard(link)
            }
            .distinctBy { it.url }
    }

    private fun episodeNumber(title: String, url: String): Int? {
        val source = "$title $url"

        return Regex(
            """(?:الحلقة|حلقة|episode|ep)[^\d]*(\d+)""",
            RegexOption.IGNORE_CASE
        )
            .find(source)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun parseEpisode(link: Element): Episode? {
        val url = absoluteUrl(link)

        if (!url.contains("/episodes/", ignoreCase = true)) {
            return null
        }

        val card = link.closest(
            ".etk-card, article, .item, .post"
        ) ?: link.parent() ?: link

        val title = cardTitle(link)
            .ifBlank { cardTitle(card) }

        val number = episodeNumber(title, url) ?: return null

        return newEpisode(url) {
            name = title.ifBlank { "الحلقة $number" }
            season = 1
            episode = number
        }
    }

    private fun parseEpisodes(document: Document): List<Episode> {
        return document
            .select("a[href*='/episodes/']")
            .mapNotNull { link ->
                parseEpisode(link)
            }
            .distinctBy { it.data }
            .sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    private fun episodeToSeriesUrl(episodeUrl: String): String? {
        val path = episodeUrl
            .substringAfter("$mainUrl/")
            .trimEnd('/')

        if (!path.startsWith("episodes/")) {
            return null
        }

        val episodeSlug = path.removePrefix("episodes/")

        val seriesSlug = episodeSlug.replace(
            Regex(
                """-(?:الحلقة|حلقة|episode|ep)-?\d+$""",
                RegexOption.IGNORE_CASE
            ),
            ""
        )

        if (seriesSlug.isBlank() || seriesSlug == episodeSlug) {
            return null
        }

        return "$mainUrl/series/$seriesSlug/"
    }

    private fun parseLatestEpisodes(document: Document): List<SearchResponse> {
        return document
            .select("a[href*='/episodes/']")
            .mapNotNull { link ->
                val episodeUrl = absoluteUrl(link)

                if (episodeUrl.isBlank() || !isSameSite(episodeUrl)) {
                    return@mapNotNull null
                }

                val card = link.closest(
                    ".etk-card, article, .item, .post, .episode-item"
                ) ?: link.parent() ?: link

                val seriesLink = card
                    .select("a[href*='/series/']")
                    .firstOrNull()

                val seriesUrl = seriesLink?.let {
                    absoluteUrl(it)
                }?.takeIf {
                    it.contains("/series/") && isSameSite(it)
                } ?: episodeToSeriesUrl(episodeUrl)
                    ?: return@mapNotNull null

                val rawTitle = seriesLink?.let {
                    cardTitle(it)
                }.orEmpty()
                    .ifBlank { cardTitle(card) }
                    .ifBlank { link.text().trim() }

                val title = rawTitle
                    .replace(
                        Regex(
                            """\s*(?:الحلقة|حلقة|episode|ep)\s*\d+.*$""",
                            RegexOption.IGNORE_CASE
                        ),
                        ""
                    )
                    .trim()

                if (title.isBlank()) {
                    return@mapNotNull null
                }

                newTvSeriesSearchResponse(
                    name = title,
                    url = seriesUrl,
                    type = TvType.TvSeries
                ) {
                    posterUrl = elementImage(link)
                        ?: elementImage(card)
                }
            }
            .distinctBy { it.url }
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

        return try {
            val response = app.get(
                request.data,
                referer = mainUrl,
                interceptor = cfKiller
            )

            val document = response.document
            val html = response.text

            val seriesLinks = document.select(
                "a[href*='/series/']"
            ).size

            val movieLinks = document.select(
                "a[href*='/movies/']"
            ).size

            val episodeLinks = document.select(
                "a[href*='/episodes/']"
            ).size

            val etkCards = document.select(
                ".etk-card-link"
            ).size

            val normalizedRequestData = try {
                URLDecoder.decode(request.data, "UTF-8")
            } catch (_: Exception) {
                request.data
            }

            val isLatest =
                request.name.contains("الحلقات") ||
                    normalizedRequestData.contains("الحلقات") ||
                    request.data.contains(
                        "%D8%A3%D8%AE%D8%B1-%D8%A7%D9%84%D8%AD%D9%84%D9%82%D8%A7%D8%AA",
                        ignoreCase = true
                    )

            val items = if (isLatest) {
                parseLatestEpisodes(document)
            } else {
                parseContentCards(document)
            }

            if (items.isNotEmpty()) {
                newHomePageResponse(
                    request.name,
                    items,
                    hasNext = false
                )
            } else {
                val diagnostic =
                    "DIAG HTTP=${response.code} " +
                    "HTML=${html.length} " +
                    "Series=$seriesLinks " +
                    "Movies=$movieLinks " +
                    "Episodes=$episodeLinks " +
                    "ETK=$etkCards " +
                    "Latest=$isLatest"

                newHomePageResponse(
                    request.name,
                    listOf(
                        newMovieSearchResponse(
                            name = diagnostic,
                            url = mainUrl,
                            type = TvType.Movie
                        )
                    ),
                    hasNext = false
                )
            }
        } catch (e: Exception) {
            newHomePageResponse(
                request.name,
                listOf(
                    newMovieSearchResponse(
                        name = "DIAG ERROR: ${e.javaClass.simpleName}",
                        url = mainUrl,
                        type = TvType.Movie
                    )
                ),
                hasNext = false
            )
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.trim().length < 3) {
            return emptyList()
        }

        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        val encodedPostTypes = URLEncoder.encode(searchPostTypes, "UTF-8")

        val url = "$mainUrl/?vx=1" +
            "&action=quick_search" +
            "&search=$encodedQuery" +
            "&post_types=$encodedPostTypes"

        return try {
            val response = app.get(
                url,
                referer = mainUrl,
                interceptor = cfKiller
            )

            val json = JSONObject(response.text)
            val data = json.optJSONArray("data") ?: return emptyList()

            buildList {
                for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index) ?: continue

                    val title = item.optString("title").trim()
                    val rawUrl = item.optString("link").trim()

                    if (title.isBlank() || rawUrl.isBlank()) {
                        continue
                    }

                    val resultUrl = when {
                        rawUrl.startsWith("http") -> rawUrl
                        rawUrl.startsWith("/") -> "$mainUrl$rawUrl"
                        else -> "$mainUrl/${rawUrl.trimStart('/')}"
                    }

                    when {
                        resultUrl.contains("/series/", ignoreCase = true) -> {
                            add(
                                newTvSeriesSearchResponse(
                                    name = title,
                                    url = resultUrl,
                                    type = TvType.TvSeries
                                )
                            )
                        }

                        resultUrl.contains("/movies/", ignoreCase = true) -> {
                            add(
                                newMovieSearchResponse(
                                    name = title,
                                    url = resultUrl,
                                    type = TvType.Movie
                                )
                            )
                        }
                    }
                }
            }.distinctBy { it.url }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun extractTitle(document: Document): String? {
        return document
            .selectFirst("meta[property=og:title]")
            ?.attr("content")
            ?.trim()
            ?.replace(Regex("""\s*[–-]\s*اسيا شو.*$"""), "")
            ?.takeIf { it.isNotBlank() }
            ?: document
                .selectFirst(".entry-title, h1")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
    }

    private fun extractPoster(document: Document): String? {
        return document
            .selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.startsWith("http") }
            ?: document
                .selectFirst(".poster img, .cover img, img")
                ?.let {
                    it.attr("data-src")
                        .ifBlank { it.attr("data-lazy-src") }
                        .ifBlank { it.attr("data-original") }
                        .ifBlank { it.attr("src") }
                        .trim()
                }
                ?.takeIf { it.startsWith("http") }
    }

    private fun extractDescription(document: Document): String? {
        return document
            .selectFirst("meta[property=og:description]")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document
                .selectFirst(".description, .desc, .entry-content")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(
            url,
            referer = mainUrl,
            interceptor = cfKiller
        ).document

        val title = extractTitle(document) ?: return null
        val poster = extractPoster(document)
        val description = extractDescription(document)

        if (url.contains("/movies/", ignoreCase = true)) {
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

        val episodes = parseEpisodes(document)

        return newTvSeriesLoadResponse(
            name = title,
            url = url,
            type = TvType.TvSeries,
            episodes = episodes
        ) {
            posterUrl = poster
            plot = description
        }
    }

    private fun decodeServerUrl(encoded: String): String? {
        return try {
            base64Decode(encoded.trim())
                .trim()
                .takeIf { it.startsWith("http") }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun loadUlt4vid(
        serverUrl: String,
        episodeUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val document = app.get(
                serverUrl,
                referer = episodeUrl,
                interceptor = cfKiller
            ).document

            val mediaUrl = document
                .selectFirst("video[data-link]")
                ?.attr("data-link")
                ?.trim()
                ?.takeIf { it.startsWith("http") }
                ?: return false

            callback(
                newExtractorLink(
                    source = "Ult4vid",
                    name = "Ult4vid",
                    url = mediaUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    referer = serverUrl
                    quality = Qualities.Unknown.value
                }
            )

            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun loadOkRu(
        serverUrl: String,
        episodeUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val document = app.get(
                serverUrl,
                referer = episodeUrl,
                interceptor = cfKiller
            ).document

            val options = document
                .selectFirst("[data-module='OKVideo'][data-options]")
                ?.attr("data-options")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return false

            val root = JSONObject(options)
            val flashvars = root.optJSONObject("flashvars") ?: return false
            val metadata = flashvars.optJSONObject("metadata") ?: return false

            val hlsUrl = metadata
                .optString("hlsManifestUrl")
                .trim()
                .takeIf { it.startsWith("http") }
                ?: return false

            callback(
                newExtractorLink(
                    source = "OK.ru",
                    name = "OK.ru",
                    url = hlsUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = serverUrl
                    quality = Qualities.Unknown.value
                }
            )

            true
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
        val episodeUrl = data.trim()

        if (!episodeUrl.startsWith("http")) {
            return false
        }

        val document = try {
            app.get(
                episodeUrl,
                referer = mainUrl,
                interceptor = cfKiller
            ).document
        } catch (_: Exception) {
            return false
        }

        val serverUrls = document
            .select("a[data-etk-server-btn][data-etk-src]")
            .mapNotNull { server ->
                decodeServerUrl(server.attr("data-etk-src"))
            }
            .distinct()

        if (serverUrls.isEmpty()) {
            return false
        }

        var foundLinks = false

        val ult4vidUrls = serverUrls.filter {
            it.contains("ult4vid", ignoreCase = true)
        }

        for (serverUrl in ult4vidUrls) {
            if (
                loadUlt4vid(
                    serverUrl = serverUrl,
                    episodeUrl = episodeUrl,
                    callback = callback
                )
            ) {
                foundLinks = true
            }
        }

        val okRuUrls = serverUrls.filter {
            it.contains("ok.ru/", ignoreCase = true)
        }

        for (serverUrl in okRuUrls) {
            if (
                loadOkRu(
                    serverUrl = serverUrl,
                    episodeUrl = episodeUrl,
                    callback = callback
                )
            ) {
                foundLinks = true
            }
        }

        val otherUrls = serverUrls.filterNot {
            ult4vidUrls.contains(it) || okRuUrls.contains(it)
        }

        for (serverUrl in otherUrls) {
            try {
                val loaded = loadExtractor(
                    url = serverUrl,
                    referer = episodeUrl,
                    subtitleCallback = subtitleCallback
                ) { link ->
                    foundLinks = true
                    callback(link)
                }

                if (loaded) {
                    foundLinks = true
                }
            } catch (_: Exception) {
            }
        }

        return foundLinks
    }
}

class CloudflareKiller(
    private val getContext: () -> Context? = {
        try {
            val cls = Class.forName("com.lagradost.cloudstream3.MainActivity")
            val field = cls.getDeclaredField("activity")
            field.isAccessible = true
            field.get(null) as? Context
        } catch (_: Exception) {
            null
        }
    }
) : Interceptor {

    private val cookies = mutableMapOf<String, String>()

    private val challengeMarkers = listOf(
        "challenges.cloudflare.com",
        "/cdn-cgi/challenge-platform/",
        "jschl_vc",
        "cf_chl_",
        "Just a moment",
        "security verification"
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        val host = request.url.host

        val savedCookies = synchronized(cookies) {
            cookies[host]
        }

        val requestWithCookies =
            if (
                !savedCookies.isNullOrBlank() &&
                request.header("Cookie").isNullOrBlank()
            ) {
                request.newBuilder()
                    .header("Cookie", savedCookies)
                    .build()
            } else {
                request
            }

        val response = chain.proceed(requestWithCookies)

        val isChallenge = try {
            val peek = response.peekBody(512 * 1024).string()
            challengeMarkers.any {
                peek.contains(it, ignoreCase = true)
            }
        } catch (_: Exception) {
            false
        }

        if (!isChallenge) {
            return response
        }

        response.close()

        var cookieString = ""

        runBlocking {
            withContext(Dispatchers.Main) {
                val context = getContext() ?: return@withContext

                val latch = CountDownLatch(1)
                val webView = WebView(context)

                val settings = webView.settings
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptThirdPartyCookies(webView, true)
                cookieManager.removeAllCookies(null)

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(
                        view: WebView,
                        pageUrl: String
                    ) {
                        view.postDelayed({
                            val pageCookies = cookieManager.getCookie(pageUrl)

                            if (!pageCookies.isNullOrBlank()) {
                                cookieString = pageCookies
                                latch.countDown()
                            }
                        }, 4000)
                    }
                }

                webView.loadUrl(url)

                latch.await(40, TimeUnit.SECONDS)

                webView.stopLoading()
                webView.destroy()
            }
        }

        if (cookieString.isNotBlank()) {
            synchronized(cookies) {
                cookies[host] = cookieString
            }
        }

        val finalCookies = cookieString.ifBlank {
            synchronized(cookies) { cookies[host] } ?: ""
        }

        val newRequest = request.newBuilder()
            .header("Cookie", finalCookies)
            .build()

        return chain.proceed(newRequest)
    }
}
