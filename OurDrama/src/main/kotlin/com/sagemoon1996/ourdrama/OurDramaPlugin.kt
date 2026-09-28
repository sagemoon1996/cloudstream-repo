package com.sagemoon1996.ourdrama

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class OurDramaPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(OurDramaProvider())
    }
}
