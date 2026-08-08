version = 1

android {
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("org.jspecify:jspecify:1.0.0")
}

cloudstream {
    description = "MovieLinkBD - Largest Movie Download Site in Bangladesh"
    authors = listOf("Shimul_Ahmed")
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "AnimeMovie", "AsianDrama")
    language = "bn"
    iconUrl = "https://movielinkbd.one/img/favicon-192x192.png"
}
