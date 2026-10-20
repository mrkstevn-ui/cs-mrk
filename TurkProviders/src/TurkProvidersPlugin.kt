package com.turk

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class TurkProvidersPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HDFilmCehennemi())
        registerMainAPI(FilmModu())
        registerMainAPI(Dizigom())
        registerMainAPI(KultFilmler())
        registerMainAPI(DiziBox())
        registerMainAPI(AnimeciX())
        registerMainAPI(CizgiMax())
        registerMainAPI(DiziFilmizle())
        registerMainAPI(BelgeselX())
        registerMainAPI(DiziGecesi())
        registerMainAPI(DiziPal())
        registerMainAPI(FullHDFilmizlesene())
        registerMainAPI(FilmKovasi())
        registerMainAPI(HDFilmDelisi())
        registerMainAPI(RareFilmm())
        registerMainAPI(DiziYou())
        registerMainAPI(InatTV())
        registerMainAPI(Patronspor())
        registerMainAPI(SetFilmIzle())
        registerMainAPI(SezonlukDizi())
    }
}
