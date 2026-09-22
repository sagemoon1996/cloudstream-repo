override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {

    val document = app.get(data).document

    val iframeLinks = document
        .select("iframe[src], iframe[data-src]")
        .mapNotNull { iframe ->
            iframe
                .attr("src")
                .ifBlank {
                    iframe.attr("data-src")
                }
                .takeIf {
                    it.startsWith("http")
                }
        }
        .distinct()

    var loaded = false

    for (link in iframeLinks) {

        if (link.contains("71stream.one")) {

            val streamDocument = app.get(link).document

            val appData = streamDocument
                .selectFirst("#app")
                ?.attr("data-page")
                ?: continue

            val normalizedData = appData.replace("\\/", "/")

            val m3u8 = Regex(
                """https://cdnvid\.dramalvr\.com/hls/[^"]+/playlist\.m3u8"""
            )
                .find(normalizedData)
                ?.value
                ?: continue

            callback(
                newExtractorLink(
                    source = "71Stream",
                    name = "71Stream",
                    url = m3u8,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = link
                    quality = Qualities.Unknown.value
                }
            )

            loaded = true

        } else {

            loadExtractor(
                link,
                data,
                subtitleCallback,
                callback
            )

            loaded = true
        }
    }

    return loaded
}
