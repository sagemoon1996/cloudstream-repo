package com.sagemoon1996.ourdrama

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink

class OurDramaExtractor : ExtractorApi() {

    override val name = "OurDrama"

    override val mainUrl = "https://ourdrama.cc"

    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(
            url,
            referer = referer ?: mainUrl
        )

        val html = response.text

        val unpacked = getAndUnpack(html)

        val hls2 = Regex(
            """"hls2"\s*:\s*"([^"]+)""""
        ).find(unpacked)?.groupValues?.get(1)

        val hls3 = Regex(
            """"hls3"\s*:\s*"([^"]+)""""
        ).find(unpacked)?.groupValues?.get(1)

        val streamUrl = hls2 ?: hls3 ?: return

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = streamUrl,
                type = ExtractorLinkType.M3U8
            ) {
                quality = Qualities.P720.value
                this.referer = mainUrl
            }
        )
    }
}
