version = 1

cloudstream {
    description = "KimoiTV - Hollywood, Korean, Chinese and Japanese movies, dramas and animation with every source, quality and subtitle"
    language = "en"
    authors = listOf("cookie 🍪")
    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "Cartoon"
    )
    iconUrl = "https://raw.githubusercontent.com/decodede/extensions/master/icons/kimoi.png"
}

dependencies {
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
}