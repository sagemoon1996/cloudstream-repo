package com.sagemoon1996.ourdrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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
        "$mainUrl/serie/cate/مسلسلات-أسيوية" to "مسلسلات آسيوية",
        "$mainUrl/serie/cate/مسلسلات-كورية" to "مسلسلات كورية",
        "$mainUrl/serie/cate/مسلسلات-صينية" to "مسلسلات صينية",
        "$mainUrl/serie/cate/مسلسلات-يابانية" to "مسلسلات يابانية",
        "$mainUrl/serie/cate/مسلسلات-تايوانية" to "مسلسلات تايوانية",
        "$mainUrl/serie/cate/مسلسلات-تايلاندية" to "مسلسلات تايلاندية"
    )

    private fun absoluteUrl(url: String): String {
        val value = url.trim()

        return when {
            value.startsWith("http://") -> value
            value.startsWith("https://") -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$mainUrl$value"
            else -> "$mainUrl/${value.trimStart('/')}"
        }
    }

    private fun extractPoster(element: Element): String? {
        val image = element.selectFirst(
            "img[data-src], img[data-lazy-src], img[data-original], img[src]"
        ) ?: return null

        val candidates = listOf(
            image.attr("data-src"),
            image.attr("data-lazy-src"),
            image.attr("data-original"),
            image.attr("src")
        )

        return candidates
            .map { it.trim() }
            .firstOrNull {
                it.startsWith("http") &&
                    !it.contains("/images/pixel.gif")
            }
    }

    private fun extractPosterFromDocument(document: Document): String? {
        val meta = document
            .selectFirst("meta[property='og:image']")
            ?.attr("content")
            ?.trim()

        if (
            !meta.isNullOrBlank() &&
            meta.startsWith("http")
        ) {
            return meta
        }

        val image = document.selectFirst(
            "img[data-src], img[data-lazy-src], img[data-original], img[src]"
        ) ?: return null

        return listOf(
            image.attr("data-src"),
            image.attr("data-lazy-src"),
            image.attr("data-original"),
            image.attr("src")
        )
            .map { it.trim() }
            .firstOrNull {
                it.startsWith("http") &&
                    !it.contains("/images/pixel.gif")
            }
    }

    private fun parseResults(
        document: Document
    ): List<SearchResponse> {

        return document
            .select("article.post-movie h4 a[href]")
            .mapNotNull { link ->

                val title = link
                    .text()
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val href = link
                    .attr("href")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val article = link.closest("article.post-movie")

                newTvSeriesSearchResponse(
                    title,
                    absoluteUrl(href),
                    TvType.TvSeries
                ) {
                    posterUrl = article?.let {
                        extractPoster(it)
                    }
                }
            }
            .distinctBy { it.url }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val baseUrl = request.data.trimEnd('/')

        val url = if (page == 1) {
            baseUrl
        } else {
            "$baseUrl?page=$page"
        }

        val document = app
            .get(
                url,
                referer = "$mainUrl/"
            )
            .document

        return newHomePageResponse(
            request.name,
            parseResults(document)
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val encodedQuery = URLEncoder.encode(
            query,
            "UTF-8"
        )

        val document = app
            .get(
                "$mainUrl/search?s=$encodedQuery",
                referer = "$mainUrl/"
            )
            .document

        return parseResults(document)
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document = app
            .get(
                url,
                referer = "$mainUrl/"
            )
            .document

        val title = document
            .selectFirst(
                "h1, h2.entry-title, h1.entry-title"
            )
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document
                .selectFirst(
                    "meta[property='og:title']"
                )
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: return null

        val poster =
            document
                .selectFirst(
                    "meta[property='og:image']"
                )
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.startsWith("http") }
                ?: extractPosterFromDocument(document)

        val plot = document
            .selectFirst(
                "meta[name='description'], meta[property='og:description']"
            )
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val year = document
            .selectFirst(".post-date")
            ?.text()
            ?.trim()
            ?.toIntOrNull()

        val episodes = document
            .select("a[href*='/episode/']")
            .filterNot {
                it.hasClass("watch_trailer")
            }
            .mapNotNull { link ->

                val href = link
                    .attr("href")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val episodeUrl = absoluteUrl(href)

                val episodeText = link
                    .text()
                    .trim()
                    .replace(
                        Regex("\\s+"),
                        " "
                    )

                val episode = Regex(
                    """(\d+)\s*/\s*\d+"""
                )
                    .find(episodeText)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: Regex(
                        """(?:الحلقة|episode|ep)[^\d]*(\d+)""",
                        RegexOption.IGNORE_CASE
                    )
                        .find(episodeText)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                    ?: return@mapNotNull null

                newEpisode(
                    episodeUrl
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
            .sortedWith(
                compareBy<Episode> {
                    it.season ?: 1
                }.thenBy {
                    it.episode ?: 0
                }
            )

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            posterUrl = poster
            this.year = year
            this.plot = plot
        }
    }

    private fun extractCsrfToken(
        document: Document
    ): String? {

        document
            .selectFirst(
                "meta[name='csrf-token'], meta[name='csrf_token']"
            )
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let {
                return it
            }

        document
            .selectFirst(
                "input[name='_token']"
            )
            ?.attr("value")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let {
                return it
            }

        val html = document.html()

        val patterns = listOf(
            Regex(
                """['"]X-CSRF-TOKEN['"]?\s*[:=]\s*['"]([^'"]+)['"]"""
            ),
            Regex(
                """X-CSRF-TOKEN\s*:\s*['"]([^'"]+)['"]"""
            ),
            Regex(
                """csrfToken\s*[:=]\s*['"]([^'"]+)['"]""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """csrf-token['"]?\s*[:=]\s*['"]([^'"]+)['"]""",
                RegexOption.IGNORE_CASE
            )
        )

        for (pattern in patterns) {

            val token = pattern
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()

            if (!token.isNullOrBlank()) {
                return token
            }
        }

        return null
    }

    private fun extractIframeUrls(
        codePlay: String
    ): List<String> {

        val document = org.jsoup.Jsoup.parseBodyFragment(
            codePlay
        )

        return document
            .select("iframe[src], iframe[data-src]")
            .mapNotNull { iframe ->

                val src = iframe
                    .attr("src")
                    .ifBlank {
                        iframe.attr("data-src")
                    }
                    .trim()

                when {
                    src.startsWith("https://") -> src
                    src.startsWith("http://") -> src
                    src.startsWith("//") -> "https:$src"
                    src.startsWith("/") ->
                        "https://ourdrama.cc$src"
                    else -> null
                }
            }
            .distinct()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val episodeUrl = data

        // رسائل التشخيص: تظهر على الشاشة كان ما لقاش حتى link
        val dbg = mutableListOf<String>()

        // (تعديل 1) نخزنو الـ cookies باش الـ CSRF token يخدم مع الـ POST
        val page = try {
            app.get(
                episodeUrl,
                referer = "$mainUrl/"
            )
        } catch (e: Exception) {
            throw ErrorLoadingException(
                "0: episode page failed: ${e.message}"
            )
        }

        val document = page.document
        val cookies = page.cookies

        val csrfToken = extractCsrfToken(document)
            ?: throw ErrorLoadingException("1: csrf not found")

        val serverCodes = document
            .select(
                ".server-list-menu .getplay a[data-code]"
            )
            .mapNotNull { server ->
                server
                    .attr("data-code")
                    .trim()
                    .takeIf { it.isNotBlank() }
            }
            .distinct()

        if (serverCodes.isEmpty()) {
            throw ErrorLoadingException("2: no data-code found")
        }

        val loadedUrls = mutableSetOf<String>()

        var loaded = false

        for (serverCode in serverCodes) {

            val response = try {

                app.post(
                    "$mainUrl/ajax-request",
                    data = mapOf(
                        "action" to "iframe_server",
                        "code" to serverCode
                    ),
                    headers = mapOf(
                        "X-CSRF-TOKEN" to csrfToken,
                        "X-Requested-With" to "XMLHttpRequest"
                    ),
                    cookies = cookies,
                    referer = episodeUrl
                )

            } catch (e: Exception) {
                dbg.add("3: post failed ${e.message}")
                continue
            }

            val json = runCatching {
                JSONObject(response.text)
            }.getOrNull()

            if (json == null) {
                dbg.add(
                    "4: not json [${response.code}] " +
                        response.text.take(100)
                )
                continue
            }

            val codePlay = json
                .optString("codeplay")
                .trim()

            if (codePlay.isBlank()) {
                dbg.add(
                    "5: codeplay empty " +
                        response.text.take(100)
                )
                continue
            }

            val iframeUrls = extractIframeUrls(
                codePlay
            )

            if (iframeUrls.isEmpty()) {
                dbg.add("6: no iframe " + codePlay.take(100))
                continue
            }

            for (iframeUrl in iframeUrls) {

                val provider = detectProvider(
                    iframeUrl
                )

                dbg.add("p:$provider")

                when (provider) {

                    "rpmshare" -> {

                        val sources = try {
                            extractRpmshareSources(
                                iframeUrl
                            )
                        } catch (e: Exception) {
                            dbg.add("7: rpm ${e.message}")
                            null
                        }

                        if (sources != null && sources.isEmpty()) {
                            dbg.add("8: rpm 0 sources")
                        }

                        for (source in sources.orEmpty()) {

                            if (
                                source.url.isBlank() ||
                                !loadedUrls.add(source.url)
                            ) {
                                continue
                            }

                            callback(
                                newExtractorLink(
                                    source = "OurDrama - Rpmshare",
                                    name = "Rpmshare ${source.label}",
                                    url = source.url,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    referer = source.referer
                                    quality =
                                        Qualities.Unknown.value
                                }
                            )

                            loaded = true
                        }
                    }

                    "okru" -> {
                        var extractorLoaded = false

                        runCatching {
                            loadExtractor(
                                iframeUrl,
                                episodeUrl,
                                subtitleCallback
                            ) { link ->
                                extractorLoaded = true
                                callback(link)
                            }
                        }

                        if (extractorLoaded) {
                            loaded = true
                        } else {
                            val hls = runCatching {
                                extractOkRuHls(
                                    iframeUrl
                                )
                            }.getOrNull()

                            if (
                                !hls.isNullOrBlank() &&
                                loadedUrls.add(hls)
                            ) {
                                callback(
                                    newExtractorLink(
                                        source = "OurDrama - OK.ru",
                                        name = "OK.ru",
                                        url = hls,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        referer = iframeUrl
                                        quality =
                                            Qualities.Unknown.value
                                    }
                                )

                                loaded = true
                            }
                        }
                    }

                    "vidmo" -> {
                        var extractorLoaded = false

                        runCatching {
                            loadExtractor(
                                iframeUrl,
                                episodeUrl,
                                subtitleCallback
                            ) { link ->
                                extractorLoaded = true
                                callback(link)
                            }
                        }

                        if (extractorLoaded) {
                            loaded = true
                        } else {
                            val hls = runCatching {
                                extractPlayerHls(
                                    iframeUrl,
                                    episodeUrl
                                )
                            }.getOrNull()

                            if (
                                !hls.isNullOrBlank() &&
                                loadedUrls.add(hls)
                            ) {
                                callback(
                                    newExtractorLink(
                                        source = "OurDrama - Vidmo",
                                        name = "Vidmo",
                                        url = hls,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        referer = iframeUrl
                                        quality =
                                            Qualities.Unknown.value
                                    }
                                )

                                loaded = true
                            }
                        }
                    }

                    "earnvids" -> {
                        var extractorLoaded = false

                        runCatching {
                            loadExtractor(
                                iframeUrl,
                                episodeUrl,
                                subtitleCallback
                            ) { link ->
                                extractorLoaded = true
                                callback(link)
                            }
                        }

                        if (extractorLoaded) {
                            loaded = true
                        } else {
                            val hls = runCatching {
                                extractPlayerHls(
                                    iframeUrl,
                                    episodeUrl
                                )
                            }.getOrNull()

                            if (
                                !hls.isNullOrBlank() &&
                                loadedUrls.add(hls)
                            ) {
                                callback(
                                    newExtractorLink(
                                        source = "OurDrama - Earnvids",
                                        name = "Earnvids",
                                        url = hls,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        referer = iframeUrl
                                        quality =
                                            Qualities.Unknown.value
                                    }
                                )

                                loaded = true
                            }
                        }
                    }

                    else -> {
                        var extractorLoaded = false

                        runCatching {
                            loadExtractor(
                                iframeUrl,
                                episodeUrl,
                                subtitleCallback
                            ) { link ->
                                extractorLoaded = true
                                callback(link)
                            }
                        }

                        if (extractorLoaded) {
                            loaded = true
                        } else {
                            dbg.add("9: no extractor for $iframeUrl")
                        }
                    }
                }
            }
        }

        if (!loaded) {
            throw ErrorLoadingException(
                dbg.joinToString(" | ").take(500)
            )
        }

        return loaded
    }

    private fun detectProvider(
        iframeUrl: String
    ): String {

        val host = runCatching {
            URI(iframeUrl).host
                ?.lowercase()
                .orEmpty()
        }.getOrDefault(
            iframeUrl.lowercase()
        )

        return when {

            host.contains("rpmvid.site") ||
                host.contains("rpmshare") ->
                "rpmshare"

            host == "ok.ru" ||
                host.endsWith(".ok.ru") ->
                "okru"

            host.contains("vidmoly") ||
                host.contains("vidmo") ||
                host.contains("vmnow") ->
                "vidmo"

            host.contains("ourdrama.cc") ->
                "earnvids"

            else ->
                "unknown"
        }
    }

    private data class RpmSource(
        val url: String,
        val referer: String,
        val label: String
    )

    private suspend fun extractRpmshareSources(
        iframeUrl: String
    ): List<RpmSource> {

        // (تعديل 2) الـ host ياخذ من الـ iframe بروحو موش ثابت
        val iframeUri = URI(iframeUrl)
        val origin = "${iframeUri.scheme}://${iframeUri.host}"

        val videoId = iframeUrl
            .substringAfter("#", "")
            .substringBefore("&")
            .trim()

        if (videoId.isBlank()) {
            throw ErrorLoadingException("rpm: no id in $iframeUrl")
        }

        // r = host متاع الموقع اللي مضمّن الـ player (كيما يعمل الـ player)
        val siteHost = URI(mainUrl).host
            .orEmpty()
            .removePrefix("www.")

        val apiUrl =
            "$origin/api/v1/video" +
                "?id=${URLEncoder.encode(videoId, "UTF-8")}" +
                "&w=384" +
                "&h=832" +
                "&r=$siteHost"

        // الـ Referer كيما في المتصفح: صفحة الـ player
        val encrypted = app
            .get(
                apiUrl,
                referer = "$origin/"
            )
            .text
            .trim()

        if (encrypted.isBlank()) {
            throw ErrorLoadingException("rpm: empty response")
        }

        val jsonText = decryptRpmshare(
            encrypted
        ) ?: throw ErrorLoadingException(
            "rpm decrypt fail: ${encrypted.take(120)}"
        )

        val json = runCatching {
            JSONObject(jsonText)
        }.getOrNull()
            ?: throw ErrorLoadingException(
                "rpm json fail: ${jsonText.take(120)}"
            )

        val pk = json.optJSONObject("pk")

        val key = pk
            ?.optString("k")
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "null" }

        // (تعديل) kx كـ String خاطر نوعو مجهول
        val keyExpire = pk
            ?.optString("kx")
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "null" }

        val streamingConfig = runCatching {
            JSONObject(
                json.optString(
                    "streamingConfig"
                )
            )
        }.getOrNull()

        val order = streamingConfig
            ?.optJSONArray("order")

        val sources = mutableListOf<RpmSource>()

        fun addSource(
            rawUrl: String?,
            provider: String,
            label: String
        ) {

            var url = rawUrl
                ?.trim()
                ?.takeIf { it.isNotBlank() && it != "null" }
                ?: return

            val adjust = streamingConfig
                ?.optJSONObject("adjust")
                ?.optJSONObject(provider)

            if (
                adjust?.optBoolean(
                    "disabled",
                    false
                ) == true
            ) {
                return
            }

            val domain = adjust
                ?.optString("domain")
                ?.trim()
                .orEmpty()

            val params = adjust
                ?.optJSONObject("params")

            url = resolveRpmUrl(
                url,
                "$origin/"
            )

            // (تعديل 3) نفس منطق الـ player: "/hls/" يتبدّل بـ "/hlsmod/<domain>/"
            val path = runCatching {
                URI(url).path
            }.getOrNull().orEmpty()

            if (domain.isNotBlank() && path.contains("/hls/")) {
                url = url.replaceFirst(
                    "/hls/",
                    "/hlsmod/$domain/"
                )
            }

            if (params != null) {

                val query = StringBuilder()

                val keys = params.keys()

                while (keys.hasNext()) {

                    val keyName = keys.next()

                    val value = params
                        .optString(keyName)
                        .trim()

                    if (value.isBlank()) {
                        continue
                    }

                    if (query.isNotEmpty()) {
                        query.append("&")
                    }

                    query.append(
                        URLEncoder.encode(
                            keyName,
                            "UTF-8"
                        )
                    )

                    query.append("=")

                    query.append(
                        URLEncoder.encode(
                            value,
                            "UTF-8"
                        )
                    )
                }

                if (query.isNotEmpty()) {
                    url = appendQuery(
                        url,
                        query.toString()
                    )
                }
            }

            if (
                url.contains("/v4/") &&
                !url.contains(
                    "k=",
                    ignoreCase = true
                ) &&
                key != null &&
                keyExpire != null
            ) {
                url = appendQuery(
                    url,
                    "k=${URLEncoder.encode(key, "UTF-8")}" +
                        "&kx=${URLEncoder.encode(keyExpire, "UTF-8")}"
                )
            }

            if (
                url.startsWith("http://") ||
                url.startsWith("https://")
            ) {
                sources.add(
                    RpmSource(
                        url = url,
                        referer = "$origin/",
                        label = label
                    )
                )
            }
        }

        fun addProvider(provider: String) {
            when (provider.lowercase()) {
                "tiktok" -> addSource(
                    json.optString("hlsVideoTiktok"),
                    "Tiktok",
                    "Tiktok"
                )

                "google" -> addSource(
                    json.optString("hlsVideoGoogle"),
                    "Google",
                    "Google"
                )

                "cloudflare" -> {
                    // (تعديل) الاثنين كـ links مفرّقين، ما نعرفوش أنهو يخدم
                    addSource(
                        json.optString("cf"),
                        "Cloudflare",
                        "CF"
                    )
                    addSource(
                        json.optString("cfNative"),
                        "Cloudflare",
                        "CF native"
                    )
                }

                "in-house", "inhouse" -> addSource(
                    json.optString("source"),
                    "In-House",
                    "In-House"
                )
            }
        }

        val done = mutableSetOf<String>()

        if (order != null) {
            for (i in 0 until order.length()) {
                val provider = order
                    .optString(i)
                    .trim()

                if (provider.isNotBlank() && done.add(provider.lowercase())) {
                    addProvider(provider)
                }
            }
        }

        // الباقي اللي ما جاش في الـ order
        for (provider in listOf("Tiktok", "Google", "Cloudflare", "In-House")) {
            if (done.add(provider.lowercase())) {
                addProvider(provider)
            }
        }

        return sources.distinctBy {
            it.url
        }
    }

    private fun decryptRpmshare(
        encrypted: String
    ): String? {

        val cleanHex = encrypted
            .trim()
            .removePrefix("0x")

        if (
            cleanHex.isBlank() ||
            cleanHex.length % 2 != 0
        ) {
            return null
        }

        val bytes = runCatching {

            ByteArray(
                cleanHex.length / 2
            ) { index ->

                cleanHex
                    .substring(
                        index * 2,
                        index * 2 + 2
                    )
                    .toInt(16)
                    .toByte()
            }

        }.getOrNull()
            ?: return null

        return runCatching {

            val cipher = Cipher.getInstance(
                "AES/CBC/PKCS5Padding"
            )

            val key = SecretKeySpec(
                "kiemtienmua911ca"
                    .toByteArray(Charsets.UTF_8),
                "AES"
            )

            val iv = IvParameterSpec(
                "1234567890oiuytr"
                    .toByteArray(Charsets.UTF_8)
            )

            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                iv
            )

            String(
                cipher.doFinal(bytes),
                Charsets.UTF_8
            )

        }.getOrNull()
    }

    private fun resolveRpmUrl(
        url: String,
        base: String
    ): String {

        return when {

            url.startsWith("https://") ||
                url.startsWith("http://") ->
                url

            url.startsWith("//") ->
                "https:$url"

            else ->
                URI(base)
                    .resolve(url)
                    .toString()
        }
    }

    private fun appendQuery(
        url: String,
        query: String
    ): String {

        if (query.isBlank()) {
            return url
        }

        return if (url.contains("?")) {
            "$url&$query"
        } else {
            "$url?$query"
        }
    }

    private suspend fun extractOkRuHls(
        iframeUrl: String
    ): String? {

        val html = app
            .get(
                iframeUrl,
                referer = "$mainUrl/"
            )
            .text

        if (html.isBlank()) {
            return null
        }

        val patterns = listOf(

            Regex(
                """["']hlsManifestUrl["']\s*:\s*["']([^"']+\.m3u8[^"']*)["']""",
                RegexOption.IGNORE_CASE
            ),

            Regex(
                """https?://[^"'\\\s<>]+\.m3u8[^"'\\\s<>]*""",
                RegexOption.IGNORE_CASE
            )
        )

        for (pattern in patterns) {

            val value = pattern
                .find(html)
                ?.groupValues
                ?.getOrNull(
                    if (pattern == patterns.first()) 1 else 0
                )
                ?.replace(
                    "\\/",
                    "/"
                )
                ?.trim()

            if (
                !value.isNullOrBlank() &&
                (
                    value.startsWith("https://") ||
                        value.startsWith("http://")
                )
            ) {
                return value
            }
        }

        return null
    }

    private suspend fun extractPlayerHls(
        iframeUrl: String,
        episodeUrl: String
    ): String? {

        val html = app
            .get(
                iframeUrl,
                referer = episodeUrl
            )
            .text

        if (html.isBlank()) {
            return null
        }

        val unpacked = runCatching {
            getAndUnpack(html)
        }.getOrDefault(html)

        val contents = listOf(
            unpacked,
            html
        ).distinct()

        for (content in contents) {

            val direct = extractM3u8FromText(
                content
            )

            if (!direct.isNullOrBlank()) {
                return direct
            }

            val file = extractPlayerFile(
                content
            )

            if (
                !file.isNullOrBlank() &&
                file.contains(
                    ".m3u8",
                    ignoreCase = true
                )
            ) {
                return resolvePlayerUrl(
                    file,
                    iframeUrl
                )
            }
        }

        return null
    }

    private fun extractM3u8FromText(
        content: String
    ): String? {

        return Regex(
            """https?://[^"'\\\s<>]+\.m3u8[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        )
            .find(content)
            ?.value
            ?.replace(
                "\\/",
                "/"
            )
            ?.trim()
    }

    private fun extractPlayerFile(
        content: String
    ): String? {

        val patterns = listOf(

            Regex(
                """["']file["']\s*:\s*["']((?:\\.|[^"'\\])+)["']""",
                RegexOption.IGNORE_CASE
            ),

            Regex(
                """file\s*:\s*["']((?:\\.|[^"'\\])+)["']""",
                RegexOption.IGNORE_CASE
            ),

            Regex(
                """["']src["']\s*:\s*["']((?:\\.|[^"'\\])+)["']""",
                RegexOption.IGNORE_CASE
            )
        )

        for (pattern in patterns) {

            val value = pattern
                .find(content)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?: continue

            val decoded = value
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("\\&", "&")

            if (
                decoded.startsWith("http://") ||
                decoded.startsWith("https://") ||
                decoded.startsWith("//") ||
                decoded.startsWith("/")
            ) {
                return decoded
            }
        }

        return null
    }

    private fun resolvePlayerUrl(
        url: String,
        iframeUrl: String
    ): String {

        return when {

            url.startsWith("https://") ||
                url.startsWith("http://") ->
                url

            url.startsWith("//") ->
                "https:$url"

            else ->
                URI(iframeUrl)
                    .resolve(url)
                    .toString()
        }
    }
}
