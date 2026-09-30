package com.sagemoon1996.ourdrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class OurDramaProvider : MainAPI() {

    override var mainUrl = "https://s.ourdrama.pro"
    override var name = "OurDrama"
    override val lang = "ar"
    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "OurDrama"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data).document

        val items = document.select("article.post-movie h4 a[href]").mapNotNull {
            val title = it.text().trim()
            val href = it.attr("abs:href")

            if (title.isBlank() || href.isBlank()) null
            else newMovieSearchResponse(
                title,
                href,
                TvType.TvSeries
            )
        }

        return newHomePageResponse(
            request.name,
            items
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?s=${query.urlEncode()}"
        val document = app.get(url).document

        return document.select("article.post-movie h4 a[href]").mapNotNull {
            val title = it.text().trim()
            val href = it.attr("abs:href")

            if (title.isBlank() || href.isBlank()) null
            else newMovieSearchResponse(
                title,
                href,
                TvType.TvSeries
            )
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.title().substringBefore(" - ").trim()

        val episodes = document
            .select("a[href*='/episode/']")
            .filterNot { it.hasClass("watch_trailer") }
            .mapNotNull { element ->

                val href = element.attr("abs:href")
                if (href.isBlank()) return@mapNotNull null

                val text = element.text().trim()

                val number = Regex("""(\d+)\s*/\s*\d+""")
                    .find(text)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: Regex("""(\d+)""")
                        .find(text)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                    ?: return@mapNotNull null

                newEpisode(href) {
                    name = "الحلقة $number"
                    episode = number
                }
            }
            .sortedBy { it.episode }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val episode = app.get(data).document

        val csrf =
            episode.selectFirst("meta[name=csrf-token]")
                ?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?: episode.selectFirst("input[name=_token]")
                    ?.attr("value")
                    .orEmpty()

        val servers = episode.select(
            ".server-list-menu .getplay a[data-code]"
        )

        for (server in servers) {

            val code = server.attr("data-code")
            if (code.isBlank()) continue

            val response = app.post(
                "$mainUrl/ajax-request",
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "X-CSRF-TOKEN" to csrf
                ),
                data = mapOf(
                    "action" to "iframe_server",
                    "code" to code
                )
            )

            val json = runCatching {
                JSONObject(response.text)
            }.getOrNull() ?: continue

            val html = json.optString("codeplay")
            val iframe = Regex(
                """<iframe[^>]+src=["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.getOrNull(1)
                ?: continue

            val iframeUrl = URI(data).resolve(iframe).toString()

            when {
                iframeUrl.contains("rpmvid.site") ->
                    extractRpmshare(
                        iframeUrl,
                        callback
                    )

                iframeUrl.contains("vidmoly") ->
                    loadExtractor(
                        iframeUrl,
                        data,
                        subtitleCallback,
                        callback
                    )

                iframeUrl.contains("ok.ru") ->
                    loadExtractor(
                        iframeUrl,
                        data,
                        subtitleCallback,
                        callback
                    )

                iframeUrl.contains("ourdrama.cc") ->
                    extractEarnvids(
                        iframeUrl,
                        callback
                    )
            }
        }

        return true
    }

    private suspend fun extractEarnvids(
        url: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url).text

        val file = Regex(
            """["']file["']\s*:\s*["']([^"']+\.m3u8[^"']*)["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)
            ?: Regex(
                """https?://[^"'\\\s]+\.m3u8[^"'\\\s]*""",
                RegexOption.IGNORE_CASE
            ).find(html)?.value
            ?: return

        callback(
            newExtractorLink(
                "Earnvids",
                "Earnvids",
                URI(url).resolve(file).toString(),
                ExtractorLinkType.M3U8
            )
        )
    }

    private suspend fun extractRpmshare(
        url: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = url.substringAfter("#").substringBefore("&")
        if (videoId.isBlank()) return

        val base = URI(url).let {
            "${it.scheme}://${it.host}"
        }

        val encrypted = app.get(
            "$base/api/v1/video?id=$videoId&w=384&h=832&r="
        ).text.trim()

        val json = runCatching {
            JSONObject(
                decryptRpmshare(encrypted)
            )
        }.getOrNull() ?: return

        val config = runCatching {
            JSONObject(json.optString("streamingConfig"))
        }.getOrNull()

        val order = config
            ?.optJSONArray("order")

        val adjust = config
            ?.optJSONObject("adjust")

        val sources = mutableListOf<String>()

        if (order != null) {
            for (i in 0 until order.length()) {
                val provider = order.optString(i)
                val source = when (provider) {
                    "Tiktok" -> json.optString("hlsVideoTiktok")
                    "Google" -> json.optString("hlsVideoGoogle")
                    "Cloudflare" -> json.optString("cfNative")
                    "In-House" -> json.optString("source")
                    else -> ""
                }

                if (source.isBlank()) continue

                val providerConfig = adjust?.optJSONObject(provider)

                if (providerConfig?.optBoolean("disabled", false) == true)
                    continue

                var finalUrl = source

                val domain = providerConfig
                    ?.optString("domain")
                    .orEmpty()

                if (
                    provider == "Tiktok" &&
                    domain.isNotBlank() &&
                    finalUrl.contains("/hls/")
                ) {
                    finalUrl = finalUrl.replace(
                        "/hls/",
                        "/hlsmod/$domain/hls/"
                    )
                }

                if (finalUrl.startsWith("/"))
                    finalUrl = "$base$finalUrl"

                if (finalUrl.startsWith("https://") ||
                    finalUrl.startsWith("http://")
                ) {
                    sources += finalUrl
                }
            }
        }

        if (sources.isEmpty()) {
            json.optString("cfNative")
                .takeIf { it.isNotBlank() }
                ?.let { sources += it }

            json.optString("source")
                .takeIf { it.isNotBlank() }
                ?.let { sources += it }
        }

        sources.distinct().forEachIndexed { index, source ->
            callback(
                newExtractorLink(
                    "Rpmshare",
                    "Rpmshare ${index + 1}",
                    source,
                    ExtractorLinkType.M3U8
                )
            )
        }
    }

    private fun decryptRpmshare(hex: String): String {
        val bytes = hex
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")

        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(
                "kiemtienmua911ca".toByteArray(),
                "AES"
            ),
            IvParameterSpec(
                "1234567890oiuytr".toByteArray()
            )
        )

        return cipher.doFinal(bytes).toString(Charsets.UTF_8)
    }
}
