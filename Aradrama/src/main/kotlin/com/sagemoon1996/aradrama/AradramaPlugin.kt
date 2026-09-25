package com.sagemoon1996.aradrama

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AradramaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AradramaProvider())
    }
}
