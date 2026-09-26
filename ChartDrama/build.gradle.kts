version = 3

cloudstream {
    description = "ChartDrama: short dramas from every source, one provider each"
    language = "en"
    authors = listOf("dronzer11")
    status = 1
    tvTypes = listOf(
        "TvSeries",
        "AsianDrama",
        "Movie"
    )
    iconUrl = "https://raw.githubusercontent.com/decodede/extensions/master/ChartDrama/icon.png"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
