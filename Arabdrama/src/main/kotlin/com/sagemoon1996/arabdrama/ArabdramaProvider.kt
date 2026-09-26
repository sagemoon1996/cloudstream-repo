package com.sagemoon1996.arabdrama

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType

class ArabdramaProvider : MainAPI() {

    override var mainUrl = "https://www.arab-drama.me"
    override var name = "Arabdrama"

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )
}
