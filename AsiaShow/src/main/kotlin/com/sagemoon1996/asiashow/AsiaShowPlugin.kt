package com.sagemoon1996.asiashow

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AsiaShowPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AsiaShowProvider())
    }
}
