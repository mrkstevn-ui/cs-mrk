package com.turk

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.utils.ExtractorApi
import android.content.Context

@CloudstreamPlugin
class TurkProvidersPlugin: Plugin() {
    private fun safeRegister(name: String, block: () -> MainAPI) {
        try {
            registerMainAPI(block())
        } catch (e: Throwable) {
            println("TurkProviders: Failed to register $name: ${e.message}")
        }
    }

    private fun safeExtractor(name: String, block: () -> ExtractorApi) {
        try {
            registerExtractorAPI(block())
        } catch (e: Throwable) {
            println("TurkProviders: Failed to register extractor $name: ${e.message}")
        }
    }

    override fun load(context: Context) {
        safeRegister("HDFilmcehennemi") { HDFilmCehennemi() }
        safeRegister("FilmModu") { FilmModu() }
        safeRegister("Dizigom") { Dizigom() }
        safeRegister("KultFilmler") { KultFilmler() }
        safeRegister("DiziBOX") { DiziBox() }
        safeRegister("AnimeciX") { AnimeciX() }
        safeRegister("CizgiMax") { CizgiMax() }
        safeRegister("DiziFilmizle") { DiziFilmizle() }
        safeRegister("BelgeselX") { BelgeselX() }
        safeRegister("DiziGecesi") { DiziGecesi() }
        safeRegister("DiziPal") { DiziPal() }
        safeRegister("FullHDFilmizlesene") { FullHDFilmizlesene() }
        safeRegister("FilmKovasi") { FilmKovasi() }
        safeRegister("HDFilmDelisi") { HDFilmDelisi() }
        safeRegister("RareFilmm") { RareFilmm() }
        safeRegister("DiziYou") { DiziYou() }
        safeRegister("InatTV") { InatTV() }
        safeRegister("Patronspor") { Patronspor() }
        safeRegister("SetFilmIzle") { SetFilmIzle() }
        safeRegister("SezonlukDizi") { SezonlukDizi() }
        safeRegister("YabanciDizi") { YabanciDizi() }
        safeRegister("WebteIzle") { WebteIzle() }

        safeExtractor("ByseJikuar") { ByseJikuar() }
        safeExtractor("ByseZoxexe") { ByseZoxexe() }
    }
}
