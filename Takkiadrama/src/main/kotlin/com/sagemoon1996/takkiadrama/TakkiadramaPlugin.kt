package com.sagemoon1996.takkiadrama

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TakkiadramaPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        registerMainAPI(TakkiadramaProvider())
    }
}
