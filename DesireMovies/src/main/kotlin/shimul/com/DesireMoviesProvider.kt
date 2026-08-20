package shimul.com

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class DesireMoviesProvider : MainAPI() {
    override var mainUrl = "https://1desiremovies.wales"
    override var name = "DesireMovies"
    override val hasMainPage = true
    override var lang = "bn"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Latest Movies",
        "$mainUrl/south-movieshindi/page/" to "South Indian",
        "$mainUrl/bollywood-movies-desiremovie/page/" to "Bollywood",
        "$mainUrl/web-series/page/" to "Web Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("article.mh-loop-item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.mh-loop-item").mapNotNull {
            it.toSearchResult()
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = selectFirst("h3.entry-title a") ?: return null
        val title = titleElement.text()
        val href = titleElement.attr("href")
        val posterUrl = selectFirst("figure.mh-loop-thumb img")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst("div.entry-content img")?.attr("src")
        val plot = document.select("div.entry-content p").find { it.text().length > 50 }?.text()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var found = false

        document.select("div.entry-content a").forEach { entryLink ->
            val gateHref = entryLink.attr("href")
            // gyanigurus.online একটা লিংক-প্রোটেকশন/গেট পেজ — আসল hubcloud/gdflix লিংক এর ভেতরে থাকে,
            // article পেজে সরাসরি থাকে না
            if (gateHref.contains("gyanigurus")) {
                val gateDocument = runCatching { app.get(gateHref).document }.getOrNull()
                gateDocument?.select("a")?.forEach { hostLink ->
                    val realHref = hostLink.attr("href")
                    if (realHref.contains("hubcloud") || realHref.contains("gdflix") ||
                        realHref.contains("hubdrive") || realHref.contains("multicloudlinks") ||
                        realHref.contains("drive")
                    ) {
                        loadExtractor(realHref, data, subtitleCallback, callback)
                        found = true
                    }
                }
            }
        }
        return found
    }
}
