// use an integer for version numbers
version = 1

android {
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    language = "bn"
    // All of these properties are optional, you can safely remove them

    description = "Multi Language Movies and Series Provider"
    authors = listOf("Shimul_Ahmed")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )

    iconUrl = "https://raw.githubusercontent.com/Hackershimul07/Cloudstream/refs/heads/main/MovieBoxProvider/icon.png"
}
