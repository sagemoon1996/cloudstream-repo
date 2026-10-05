package com.sagemoon1996.hayyashoot

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HayyaShootPlugin : Plugin() {

    override fun load(context: android.content.Context) {
        registerMainAPI(HayyaShootProvider())
    }
}
