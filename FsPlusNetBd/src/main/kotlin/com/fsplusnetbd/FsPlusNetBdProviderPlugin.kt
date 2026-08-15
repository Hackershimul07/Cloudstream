package com.fsplusnetbd

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class FsPlusNetBdProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FsPlusNetBdProvider())
    }
}
