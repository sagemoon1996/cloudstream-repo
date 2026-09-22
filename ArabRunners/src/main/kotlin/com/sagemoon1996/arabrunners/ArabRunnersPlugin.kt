package com.sagemoon1996.arabrunners

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ArabRunnersPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ArabRunnersProvider())
    }
}
