package com.turk

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class InatTVPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(InatTV())
    }
}
