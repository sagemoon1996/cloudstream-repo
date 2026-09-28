package com.sagemoon1996.ourdrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

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
        "$mainUrl/serie/cate/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D8%A3%D8%B3%D9%8A%D9%88%D9%8A%D8%A9" to "المسلسلات الآسيوية"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val response = app.get(
            request.data,
            timeout = 15
        )

        println(
            "OURDRAMA TEST: status=${response.code}, length=${response.text.length}"
        )

        return newHomePageResponse(
            request.name,
            emptyList(),
            hasNext = false
        )
    }
}
