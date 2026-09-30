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

        val document = runCatching {
            app.get(
                episodeUrl,
                referer = "$mainUrl/"
            ).document
        }.getOrNull()
            ?: return false

        val csrfToken = extractCsrfToken(document)
            ?: return false

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
            return false
        }

        val loadedUrls = mutableSetOf<String>()

        var loaded = false

        /*
         * Important:
         * السيرفرات تتعالج sequentially.
         * ما نستعملوش parallel requests لأن الاختبار السابق
         * أثبت أن parallel requests تنجم تعمل ERR_CONNECTION_ABORTED.
         */
        for (serverCode in serverCodes) {

            val response = runCatching {

                app.post(
                    "$mainUrl/ajax-request",
                    data = mapOf(
                        "action" to "iframe_server",
                        "code" to serverCode
                    ),
                    headers = mapOf(
                        "X-CSRF-TOKEN" to csrfToken,
                        "X-Requested-With" to "XMLHttpRequest",
                        "Content-Type" to
                            "application/x-www-form-urlencoded; charset=UTF-8"
                    ),
                    referer = episodeUrl
                )

            }.getOrNull()
                ?: continue

            val json = runCatching {
                JSONObject(response.text)
            }.getOrNull()
                ?: continue

            /*
             * codeplay هو القيمة المهمة.
             * ما نربطوش نجاح الطلب بقيمة status لأن
             * codeplay نفسه هو اللي يحتوي iframe الحقيقي.
             */
            val codePlay = json
                .optString("codeplay")
                .trim()

            if (codePlay.isBlank()) {
                continue
            }

            val iframeUrls = extractIframeUrls(
                codePlay
            )

            if (iframeUrls.isEmpty()) {
                continue
            }

            for (iframeUrl in iframeUrls) {

                val provider = detectProvider(
                    iframeUrl
                )

                when (provider) {

                    "rpmshare" -> {
                        val sources = runCatching {
                            extractRpmshareSources(
                                iframeUrl
                            )
                        }.getOrDefault(emptyList())

                        for (source in sources) {

                            if (
                                source.url.isBlank() ||
                                !loadedUrls.add(source.url)
                            ) {
                                continue
                            }

                            callback(
                                newExtractorLink(
                                    source = "OurDrama - Rpmshare",
                                    name = "Rpmshare",
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
                        val success = runCatching {
                            loadExtractor(
                                iframeUrl,
                                episodeUrl,
                                subtitleCallback,
                                callback
                            )
                        }.getOrDefault(false)

                        if (success) {
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
                        val success = runCatching {
                            loadExtractor(
                                iframeUrl,
                                episodeUrl,
                                subtitleCallback,
                                callback
                            )
                        }.getOrDefault(false)

                        if (success) {
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
                        val success = runCatching {
                            loadExtractor(
                                iframeUrl,
                                episodeUrl,
                                subtitleCallback,
                                callback
                            )
                        }.getOrDefault(false)

                        if (success) {
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
                        val success = runCatching {
                            loadExtractor(
                                iframeUrl,
                                episodeUrl,
                                subtitleCallback,
                                callback
                            )
                        }.getOrDefault(false)

                        if (success) {
                            loaded = true
                        }
                    }
                }
            }
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

    /*
     * ============================================================
     * Rpmshare
     * ============================================================
     */

    private data class RpmSource(
        val url: String,
        val referer: String
    )

    private suspend fun extractRpmshareSources(
        iframeUrl: String
    ): List<RpmSource> {

        val videoId = iframeUrl
            .substringAfter("#", "")
            .substringBefore("&")
            .trim()

        if (videoId.isBlank()) {
            return emptyList()
        }

        val apiUrl =
            "https://7.rpmvid.site/api/v1/video" +
                "?id=${URLEncoder.encode(videoId, "UTF-8")}" +
                "&w=384" +
                "&h=832" +
                "&r="

        val encrypted = app
            .get(
                apiUrl,
                referer = iframeUrl
            )
            .text
            .trim()

        if (encrypted.isBlank()) {
            return emptyList()
        }

        val jsonText = decryptRpmshare(
            encrypted
        ) ?: return emptyList()

        val json = runCatching {
            JSONObject(jsonText)
        }.getOrNull()
            ?: return emptyList()

        val pk = json.optJSONObject("pk")

        val key = pk
            ?.optString("k")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val keyExpire = pk
            ?.optLong(
                "kx",
                0L
            )
            ?.takeIf { it > 0L }

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
            provider: String
        ) {

            var url = rawUrl
                ?.trim()
                ?.takeIf { it.isNotBlank() }
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

            if (
                provider.equals(
                    "Tiktok",
                    ignoreCase = true
                ) &&
                url.startsWith("/hls/") &&
                domain.isNotBlank()
            ) {
                url =
                    "https://7.rpmvid.site" +
                        "/hlsmod/" +
                        domain +
                        url
            } else {
                url = resolveRpmUrl(
                    url,
                    "https://7.rpmvid.site/"
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

            /*
             * The Rpmshare player adds k/kx to /v4/ URLs.
             * Values come dynamically from pk.
             */
            if (
                url.contains("/v4/") &&
                !url.contains(
                    "k=",
                    ignoreCase = true
                ) &&
                !key.isNullOrBlank() &&
                keyExpire != null
            ) {
                url = appendQuery(
                    url,
                    "k=${URLEncoder.encode(key, "UTF-8")}" +
                        "&kx=$keyExpire"
                )
            }

            if (
                url.startsWith("http://") ||
                url.startsWith("https://")
            ) {
                sources.add(
                    RpmSource(
                        url = url,
                        referer = "https://7.rpmvid.site/"
                    )
                )
            }
        }

        if (order != null) {

            for (i in 0 until order.length()) {

                val provider = order
                    .optString(i)
                    .trim()

                when {
                    provider.equals(
                        "Tiktok",
                        ignoreCase = true
                    ) -> {
                        addSource(
                            json.optString(
                                "hlsVideoTiktok"
                            ),
                            "Tiktok"
                        )
                    }

                    provider.equals(
                        "Google",
                        ignoreCase = true
                    ) -> {
                        addSource(
                            json.optString(
                                "hlsVideoGoogle"
                            ),
                            "Google"
                        )
                    }

                    provider.equals(
                        "Cloudflare",
                        ignoreCase = true
                    ) -> {
                        /*
                         * cfNative is the direct HLS source.
                         * cf is kept as fallback if cfNative is absent.
                         */
                        addSource(
                            json.optString(
                                "cfNative"
                            ).takeIf {
                                it.isNotBlank()
                            } ?: json.optString("cf"),
                            "Cloudflare"
                        )
                    }

                    provider.equals(
                        "In-House",
                        ignoreCase = true
                    ) -> {
                        addSource(
                            json.optString(
                                "source"
                            ),
                            "In-House"
                        )
                    }
                }
            }
        }

        /*
         * Fallback if the site's order is absent/changed.
         * Only dynamic values from the current JSON are used.
         */
        if (sources.isEmpty()) {

            addSource(
                json.optString(
                    "hlsVideoTiktok"
                ),
                "Tiktok"
            )

            addSource(
                json.optString(
                    "cfNative"
                ),
                "Cloudflare"
            )

            addSource(
                json.optString(
                    "source"
                ),
                "In-House"
            )
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

    /*
     * ============================================================
     * OK.ru
     * ============================================================
     */

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

    /*
     * ============================================================
     * JWPlayer fallback
     * ============================================================
     *
     * Earnvids / Vidmo عندهم JWPlayer حسب الاختبار.
     * نحاول استخراج نفس file/source URL من HTML/JS
     * إذا كان موجودًا server-side، وبعدها loadExtractor
     * يبقى هو الطريق الأول.
     */

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

        val match = Regex(
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

        return match
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
