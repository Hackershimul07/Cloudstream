package shimul.com

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DesireMoviesProvider : MainAPI() {

    override var mainUrl = "https://1desiremovies.wales"
    override var name = "DesireMovies"
    override var lang = "bn"

    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Latest Movies",
        "$mainUrl/south-movieshindi/page/" to "South Indian",
        "$mainUrl/bollywood-movies-desiremovie/page/" to "Bollywood"
    )

    // =========================
    // HOME PAGE
    // =========================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = if (page <= 1) {
            request.data
        } else {
            "${request.data}$page/"
        }

        val document = app.get(url).document

        val home = document
            .select(
                "article.mh-loop-item, " +
                "article, " +
                ".mh-loop-item"
            )
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            request.name,
            home,
            hasNext = home.isNotEmpty()
        )
    }

    // =========================
    // SEARCH
    // =========================

    override suspend fun search(query: String): List<SearchResponse> {

        val encodedQuery = URLEncoder
            .encode(query.trim(), "UTF-8")

        val searchUrl = "$mainUrl/?s=$encodedQuery"

        val document = app.get(
            searchUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT
            )
        ).document

        return document
            .select(
                "article.mh-loop-item, " +
                "article, " +
                ".mh-loop-item, " +
                ".post"
            )
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    // =========================
    // SEARCH RESULT PARSER
    // =========================

    private fun Element.toSearchResult(): SearchResponse? {

        val titleElement = selectFirst(
            "h3.entry-title a, " +
            "h2.entry-title a, " +
            "h1.entry-title a, " +
            ".entry-title a, " +
            "a[href]"
        ) ?: return null

        val title = titleElement
            .text()
            .trim()

        if (title.isBlank()) return null

        val href = titleElement
            .absUrl("href")
            .ifBlank {
                titleElement.attr("href")
            }

        if (href.isBlank()) return null

        // Search result-এর ভিতরের unwanted links বাদ দেওয়ার চেষ্টা
        if (
            href.startsWith("#") ||
            href.startsWith("javascript:")
        ) {
            return null
        }

        val posterUrl = extractImageUrl(
            this,
            "figure.mh-loop-thumb img, " +
            ".mh-loop-thumb img, " +
            ".post-thumbnail img, " +
            ".entry-thumbnail img, " +
            "img"
        )

        return newMovieSearchResponse(
            title,
            href,
            TvType.Movie
        ) {
            this.posterUrl = posterUrl
        }
    }

    // =========================
    // LOAD
    // =========================

    override suspend fun load(url: String): LoadResponse {

        val document = app.get(
            url,
            headers = mapOf(
                "User-Agent" to USER_AGENT
            )
        ).document

        val title = document
            .selectFirst(
                "h1.entry-title, " +
                "h1.post-title, " +
                ".entry-title"
            )
            ?.text()
            ?.trim()
            ?: document.title().trim()

        val poster = extractImageUrl(
            document,
            "div.entry-content img, " +
            ".entry-content img, " +
            ".post-content img, " +
            "meta[property=og:image]"
        )

        val plot = document
            .select(
                "div.entry-content p, " +
                ".entry-content p, " +
                ".post-content p"
            )
            .map { it.text().trim() }
            .firstOrNull { it.length > 50 }

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            url
        ) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // =========================
    // LOAD LINKS
    // =========================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(
            data,
            headers = mapOf(
                "User-Agent" to USER_AGENT
            )
        ).document

        var found = false

        val entryLinks = document.select(
            "div.entry-content a[href], " +
            ".entry-content a[href], " +
            ".post-content a[href]"
        )

        for (entryLink in entryLinks) {

            val gateHref = entryLink
                .absUrl("href")
                .ifBlank {
                    entryLink.attr("href")
                }

            if (gateHref.isBlank()) continue

            if (
                gateHref.contains(
                    "gyanigurus",
                    ignoreCase = true
                )
            ) {

                val gateDocument = runCatching {
                    app.get(
                        gateHref,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT
                        ),
                        referer = data
                    ).document
                }.getOrNull()

                gateDocument
                    ?.select("a[href]")
                    ?.forEach { hostLink ->

                        val realHref = hostLink
                            .absUrl("href")
                            .ifBlank {
                                hostLink.attr("href")
                            }

                        if (realHref.isBlank()) return@forEach

                        when {

                            // =====================
                            // HUBCLOUD
                            // =====================

                            realHref.contains(
                                "hubcloud",
                                ignoreCase = true
                            ) -> {

                                runCatching {

                                    val links =
                                        HubCloudExtractor()
                                            .getUrl(
                                                realHref,
                                                gateHref
                                            )

                                    links.forEach {
                                        callback(it)
                                    }

                                    if (links.isNotEmpty()) {
                                        found = true
                                    }

                                }
                            }

                            // =====================
                            // GDFLIX
                            // =====================

                            realHref.contains(
                                "gdflix",
                                ignoreCase = true
                            ) -> {

                                runCatching {

                                    loadExtractor(
                                        realHref,
                                        data,
                                        subtitleCallback,
                                        callback
                                    )

                                    found = true

                                }
                            }

                            // =====================
                            // OTHER EXTRACTORS
                            // =====================

                            realHref.startsWith("http") -> {

                                runCatching {

                                    loadExtractor(
                                        realHref,
                                        data,
                                        subtitleCallback,
                                        callback
                                    )

                                    found = true

                                }
                            }
                        }
                    }
            }
        }

        return found
    }

    companion object {

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}

// ======================================================
// HUBCLOUD EXTRACTOR
// ======================================================

class HubCloudExtractor {

    suspend fun getUrl(
        driveUrl: String,
        referer: String
    ): List<ExtractorLink> {

        val links = mutableListOf<ExtractorLink>()

        val driveDoc = app.get(
            driveUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT
            ),
            referer = referer
        ).document

        val generatePageUrl =
            driveDoc
                .selectFirst("#download")
                ?.absUrl("href")
                ?.ifBlank {
                    driveDoc
                        .selectFirst("#download")
                        ?.attr("href")
                }
                ?: return links

        val fileName =
            driveDoc
                .selectFirst(".card-header")
                ?.text()
                ?.trim()
                ?: driveDoc.title()

        val detectedQuality =
            getQualityFromName(fileName)

        val finalDoc = app.get(
            generatePageUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT
            ),
            referer = driveUrl
        ).document

        finalDoc
            .select("a[href]")
            .forEach { element ->

                val href = element
                    .absUrl("href")
                    .ifBlank {
                        element.attr("href")
                    }

                val text = element.text().trim()

                if (href.isBlank()) return@forEach

                when {

                    // FSLv2
                    href.contains(
                        "bunker.monster",
                        ignoreCase = true
                    ) ||
                    text.contains(
                        "FSLv2",
                        ignoreCase = true
                    ) -> {

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

                    // FSL
                    href.contains(
                        "cloudflarestorage.com",
                        ignoreCase = true
                    ) ||
                    text.contains(
                        "FSL Server",
                        ignoreCase = true
                    ) -> {

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

                    // 10Gbps
                    href.contains(
                        "pixel.hubcloud",
                        ignoreCase = true
                    ) ||
                    text.contains(
                        "10Gbps",
                        ignoreCase = true
                    ) -> {

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

                    // PixelDrain
                    href.contains(
                        "pixeldrain",
                        ignoreCase = true
                    ) -> {

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

        return links.distinctBy { it.url }
    }

    companion object {

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}

// ======================================================
// IMAGE URL
// ======================================================

private fun extractImageUrl(
    root: Element,
    selector: String
): String? {

    val img = root.selectFirst(selector)
        ?: return null

    // meta[property=og:image]
    if (img.tagName() == "meta") {
        return img.attr("content")
            .takeIf { it.isNotBlank() }
    }

    val attributes = listOf(
        "data-src",
        "data-lazy-src",
        "data-original",
        "data-url",
        "src"
    )

    for (attribute in attributes) {

        val value = img.attr(attribute)

        if (value.isNotBlank()) {
            return value
        }
    }

    return null
}
