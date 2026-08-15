// use an integer for version numbers
version = 2

android {
    namespace = "com.niloy"
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.14.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.14.2")
    implementation("org.jspecify:jspecify:1.0.1")
}

cloudstream {
    description = "CTGMovies Provider - Stream Movies, TV Shows, and Anime from ctgmovies.com"
    authors = listOf("Shimul_Ahmed")

    status = 1 // 1: Ok

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime"
    )
    language = "bn"
iconUrl = "https://raw.githubusercontent.com/Hackershimul07/Photo/refs/heads/main/images.jpeg"
}
