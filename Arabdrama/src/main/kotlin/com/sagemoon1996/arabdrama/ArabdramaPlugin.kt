package com.sagemoon1996.arabdrama

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ArabdramaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ArabdramaProvider())
    }
}
