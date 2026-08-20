package shimul.com

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class DesireMoviesProvider : MainAPI() {
    override var mainUrl = "https://1desiremovies.wales"
    override var name = "DesireMovies"
    override val hasMainPage = true
    override var lang = "hi"
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
        val posterUrl = extractImageUrl(this, "figure.mh-loop-thumb img")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        val poster = extractImageUrl(document, "div.entry-content img")
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
            if (gateHref.contains("gyanigurus")) {
                val gateDocument = runCatching { app.get(gateHref).document }.getOrNull()

                gateDocument?.select("a")?.forEach { hostLink ->
                    val realHref = hostLink.attr("href")

                    when {
                        realHref.contains("hubcloud") -> {
                            runCatching {
                                val links = HubCloudExtractor().getUrl(realHref, data)
                                links.forEach { callback(it) }
                                if (links.isNotEmpty()) found = true
                            }
                        }

                        realHref.contains("gdflix") -> {
                            runCatching {
                                loadExtractor(realHref, data, subtitleCallback, callback)
                            }.onSuccess { found = true }
                        }
                    }
                }
            }
        }
        return found
    }
}

/**
 * img ট্যাগ থেকে সঠিক ইমেজ URL বের করে — lazy-loaded ইমেজের জন্য
 * data-src / data-lazy-src ফলব্যাক সহ।
 */
private fun extractImageUrl(root: Element, selector: String): String? {
    val img = root.selectFirst(selector) ?: return null

    val dataSrc = img.attr("data-src")
    if (dataSrc.isNotBlank()) return dataSrc

    val lazySrc = img.attr("data-lazy-src")
    if (lazySrc.isNotBlank()) return lazySrc

    val normalSrc = img.attr("src")
    return normalSrc.ifBlank { null }
}

class HubCloudExtractor {
    suspend fun getUrl(driveUrl: String, referer: String): List<ExtractorLink> {
        val links = mutableListOf<ExtractorLink>()

        val driveDoc = app.get(driveUrl, referer = referer).document
        val generatePageUrl = driveDoc.selectFirst("#download")?.attr("href") ?: return links

        val fileName = driveDoc.selectFirst(".card-header")?.text()
            ?: driveDoc.title()
        val detectedQuality = getQualityFromName(fileName).value

        val finalDoc = app.get(generatePageUrl, referer = driveUrl).document

        finalDoc.select("a").forEach { el ->
            val href = el.attr("href")
            val text = el.text()

            when {
                href.contains("bunker.monster") || text.contains("FSLv2", ignoreCase = true) -> {
                    links.add(
                        newExtractorLink(
                            source = "DesireMovies",
                            name = "HubCloud [FSLv2]",
                            url = href,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = generatePageUrl
                            this.quality = detectedQuality
                        }
                    )
                }

                href.contains("cloudflarestorage.com") || text.contains("FSL Server", ignoreCase = true) -> {
                    links.add(
                        newExtractorLink(
                            source = "DesireMovies",
                            name = "HubCloud [FSL]",
                            url = href,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = generatePageUrl
                            this.quality = detectedQuality
                        }
                    )
                }

                href.contains("pixel.hubcloud") || text.contains("10Gbps", ignoreCase = true) -> {
                    links.add(
                        newExtractorLink(
                            source = "DesireMovies",
                            name = "HubCloud [10Gbps]",
                            url = href,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = generatePageUrl
                            this.quality = detectedQuality
                        }
                    )
                }

                href.contains("pixeldrain") -> {
                    links.add(
                        newExtractorLink(
                            source = "DesireMovies",
                            name = "HubCloud [PixelDrain]",
                            url = href,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = generatePageUrl
                            this.quality = detectedQuality
                        }
                    )
                }
            }
        }

        return links
    }
}
