package com.turk

import com.lagradost.cloudstream3.extractors.FilemoonV2

open class ByseJikuar : FilemoonV2() {
    override var name = "Filemoon"
    override var mainUrl = "https://bysejikuar.com"
}

open class ByseZoxexe : FilemoonV2() {
    override var name = "Filemoon"
    override var mainUrl = "https://bysezoxexe.com"
}
