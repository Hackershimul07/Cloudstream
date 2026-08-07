package com.Shimul

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class CineFreakPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CineFreakProvider())
    }
}
