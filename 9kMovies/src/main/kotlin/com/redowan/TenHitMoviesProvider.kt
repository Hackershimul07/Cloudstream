package com.shimul

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class TenHitMoviesProvider : MainAPI() {
    override var mainUrl = "https://10hitmovies.study"
    override var name = "10HitMovies"
    override var lang = "bn"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.NSFW
    )

    override val mainPage = mainPageOf(
        "" to "Latest Movies",
        "/category/18-movies/" to "18+ Movies",
        "/category/bengali-movies/" to "Bangla Movies",
        "/category/dual-audio/" to "Dual Audio",
        "/category/hindi-dubbed/" to "Hindi Dubbed",
        "/category/hollywood-movies/" to "Hollywood"
    )

override suspend fun getMainPage(
    page: Int,
    request: MainPageRequest
): HomePageResponse {
    val url = if (request.data.isEmpty()) {
        "${mainUrl}page/$page"
    } else {
        "${mainUrl}${request.data.removePrefix("/")}/page/$page"
    }

    val doc = app.get(url).document
    val home = doc.select(".thumb.col-md-2.col-sm-4.col-xs-6")
        .mapNotNull { toResult(it) }

    return newHomePageResponse(request.name, home, hasNext = true)
}
private fun toResult(post: Element): SearchResponse {
    val url = post.select("figure figcaption a").attr("href")
    val title = post.select("figure figcaption a").text()
    val imageUrl = post.select("figure img").attr("src")

    return newMovieSearchResponse(title, url, TvType.Movie) {
        posterUrl = imageUrl
        posterHeaders = mapOf(
            "Referer" to "https://10hitmovies.study"
        )
    }
}

override suspend fun search(query: String): List<SearchResponse> {
    val doc = app.get("$mainUrl/search/$query").document
    return doc.select(".thumb.col-md-2.col-sm-4.col-xs-6")
        .mapNotNull { toResult(it) }
}

    override suspend fun load(url: String): LoadResponse {
    val doc = app.get(url).document

    val title = doc.select("span.material-text").text()
    val imageUrl = doc.select(".page-body img").attr("src")
    val info = doc.select(".page-body p:nth-of-type(1)").text()

    val story = ("(?<=Storyline,).*|(?<=Story : ).*|(?<=Storyline : ).*|(?<=Description : ).*|(?<=Description,).*")
        .toRegex()
        .find(info)
        ?.value

    return newMovieLoadResponse(title, url, TvType.Movie, url) {
        posterUrl = imageUrl
        plot = story?.trim()
    }
}

override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val doc = app.get(data).document

    val buttonElements = doc.select("a[href^=https://mysavelinks]")

        buttonElements.forEach { item ->
        val shortLinkUrl = item.attr("href")
        val sDoc = app.post(shortLinkUrl).document

        sDoc.select(".col-sm-8.col-sm-offset-2.well.view-well a").forEach {
            loadExtractor(it.attr("href"), subtitleCallback, callback)
        }
    }

    return true
}
}
