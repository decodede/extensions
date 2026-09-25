version = 3

cloudstream {
    description = "Short drama streaming: every ReelFren site as its own provider"
    language = "en"
    authors = listOf("dronzer11")
    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "AsianDrama"
    )
    iconUrl = "https://raw.githubusercontent.com/decodede/extensions/master/ReelFren/icon.png"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
