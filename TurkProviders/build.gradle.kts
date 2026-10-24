version = 7

cloudstream {
    authors     = listOf("Custom")
    language    = "tr"
    description = "Türkçe Dizi, Film, Anime ve Canlı TV Sağlayıcıları"
    status      = 1
    tvTypes     = listOf("Movie", "TvSeries", "Anime", "Live")
    iconUrl     = "https://raw.githubusercontent.com/mrkstevn-ui/cs-mrk/main/icon.png"
}

android {
    sourceSets {
        getByName("main") {
            java.srcDirs("src")
        }
    }
}
