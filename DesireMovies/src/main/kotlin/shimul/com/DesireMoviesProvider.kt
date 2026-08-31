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

    // FIX: mainUrl must include the scheme (https://), otherwise
    // app.get() requests can fail or misbehave.
    override var mainUrl = "https://1desiremovies.nexus"
    override var name = "DesireMovies"
    override var lang = "bn"

    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // FIX: URLs no longer hardcode "/page/" — WordPress sites serve
    // page 1 at the plain category URL (e.g. "mainUrl/") and only
    // use "/page/N/" starting from page 2. The old code always
    // requested ".../page/" on page 1, which is an invalid/404 URL
    // on this site, so every section loaded an empty list.
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Movies",
        "$mainUrl/south-movieshindi/" to "South Indian",
        "$mainUrl/bollywood-movies-desiremovie/" to "Bollywood"
    )

    // =========================
    // HOME PAGE
    // =========================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        // FIX: page 1 -> request.data as-is (e.g. "mainUrl/south-movieshindi/")
        //      page N -> request.data + "page/N/" (e.g. ".../page/2/")
        val url = if (page == 1) {
            request.data
        } else {
            "${request.data}page/$page/"
        }

        val document = app.get(
            url,
            headers = mapOf("User-Agent" to USER_AGENT)
        ).document

        val home = document
            .select("article.mh-loop-item")
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

    // FIX: Previously this used the WordPress REST API route
    // (/wp-json/wp/v2/search) because of a wrong assumption that the
    // normal "?s=" search URL always returned the SAME cached homepage
    // HTML regardless of the query (LiteSpeed full-page cache).
    //
    // That assumption was WRONG — confirmed by manually checking
    // "https://1desiremovies.nexus/search/Toxic/" (which the theme
    // internally treats the same as "?s=Toxic"): it returns real,
    // query-specific results (Toxic (2026), The Toxic Avenger,
    // Toxic Town, etc). LiteSpeed does NOT cache search result pages
    // by default (it excludes ?s= / search pages from full-page cache),
    // so this was the actual bug — not the wp-json endpoint choice.
    //
    // Root cause of "search kaj korche na": the wp-json REST search
    // route is blocked/disabled on this site (likely by security /
    // anti-bot rules), so it silently returned nothing and search()
    // fell through to emptyList() every time.
    //
    // FIX: switched back to plain "?s=" HTML search, parsed with the
    // same "article.mh-loop-item" selector already used on the main
    // page — this is confirmed working and needs no JSON parsing.
    override suspend fun search(query: String): List<SearchResponse> {

        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")

        val document = runCatching {
            app.get(
                "$mainUrl/?s=$encodedQuery",
                headers = mapOf("User-Agent" to USER_AGENT)
            ).document
        }.getOrNull() ?: return emptyList()

        return document
            .select("article.mh-loop-item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    // =========================
    // SEARCH RESULT PARSER
    // =========================

    private fun Element.toSearchResult(): SearchResponse? {

        // Movie title
        // FIX: selectFirst() takes ONE CSS selector string.
        // Multiple selectors must be comma-joined *inside* the same string.
        val titleElement = selectFirst(
            "h3.entry-title a, h2.entry-title a, .entry-title a"
        ) ?: return null

        val title = titleElement.text().trim()

        if (title.isBlank()) return null

        val href = titleElement.absUrl("href").ifBlank {
            titleElement.attr("href")
        }

        if (href.isBlank()) return null

        // FIX: extractImageUrl now needs mainUrl passed in explicitly,
        // since it's a top-level function and can't see the class property.
        val posterUrl = extractImageUrl(
            this,
            mainUrl,
            ".mh-loop-thumb img, .mh-loop-thumb a img, .entry-thumbnail img, img"
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
                "h1.entry-title, h1.post-title, .entry-title"
            )
            ?.text()
            ?.trim()
            ?: document.title().trim()

        val poster = extractImageUrl(
            document,
            mainUrl,
            "div.entry-content img, .entry-content img, .post-content img, meta[property=og:image]"
        )

        val plot = document
            .select(
                "div.entry-content p, .entry-content p, .post-content p"
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
            "div.entry-content a[href], .entry-content a[href], .post-content a[href]"
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
// FIX: mainUrl/baseUrl is now a parameter instead of an
// implicit reference to the class property, since this is
// a top-level function outside DesireMoviesProvider.
// ======================================================

private fun extractImageUrl(
    root: Element,
    baseUrl: String,
    vararg selectors: String
): String? {

    for (selector in selectors) {

        val img = root.selectFirst(selector) ?: continue

        val url = listOf(
            img.attr("data-src"),
            img.attr("data-lazy-src"),
            img.attr("data-original"),
            img.attr("src")
        ).firstOrNull { it.isNotBlank() }

        if (!url.isNullOrBlank()) {
            return if (url.startsWith("http")) {
                url
            } else {
                baseUrl + "/" + url.trimStart('/')
            }
        }
    }

    return null
}
