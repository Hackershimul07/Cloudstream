// use an integer for version numbers
version = 9

cloudstream {
    language = "bn"
    // All of these properties are optional, you can safely remove them

    description = "BDIX file server (Movies & Shows)"
    authors = listOf("Shimul_Ahmed")

    /**
     * Status int as follows:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    tvTypes = listOf("Movie", "TvSeries")

    iconUrl = "https://raw.githubusercontent.com/Hackershimul07/Photo/refs/heads/main/images.png"
}
