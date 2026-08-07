package com.Shimul

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class CineFreakProvider : MainAPI() {
    override var mainUrl = "https://cinefreak.nl"
    override var name = "CineFreak"
    override val hasMainPage = true
    override var lang = "bn"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "" to "Latest Releases",
        "hindi-movies/" to "Hindi Movies",
        "english-movies/" to "English Movies",
        "bangla-movies/" to "Bangla Movies",
        "bangla-dubbed/" to "Bangla Dubbed",
        "hindi-dubbed-movies/" to "Hindi Dubbed Movies",
        "dual-audio/" to "Dual Audio",
        "web-series/" to "WEB-Series",
        "k-drama/" to "K-Drama",
        "korean/" to "Korean",
        "animation/" to "Animation",
        "chinese/" to "Chinese",
        "japanese/" to "Japanese",
        "kannada/" to "Kannada",
        "telugu/" to "Telugu",
        "tamil/" to "Tamil",
        "malayalam/" to "Malayalam",
        "indonesian/" to "Indonesian",
        "spanish/" to "Spanish",
        "others/" to "Others",
        "horror/" to "Horror",
        "mcu/" to "MCU"
    )

    // A movie/series card on a listing (home, category, or search) page.
    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.attr("href")
        if (href.isBlank()) return null

        val title = this.selectFirst("h3.movie-card-title")?.text()?.trim() ?: return null
        val poster = this.selectFirst("img.wp-post-image")?.attr("src")

        val isSeries = href.contains("full-series-download") ||
                title.contains("Web series", ignoreCase = true) ||
                title.contains("Season", ignoreCase = true)

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun getMainPage(
        page: Int, request: MainPageRequest
    ): HomePageResponse {
        val url = if (page <= 1) {
            "$mainUrl/${request.data}"
        } else {
            "$mainUrl/${request.data}page/$page/"
        }

        val doc = app.get(url).document
        val items = doc.select("a.movie-card").mapNotNull { it.toSearchResult() }
        val hasNext = doc.selectFirst(".pagination-item.next") != null

        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/fast-search/?s=$query").document
        return doc.select("a.movie-card").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: "Unknown Title"

        val poster = doc.selectFirst(".poster-image img")?.attr("src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")

        val tags = doc.select("a[href*=/genre/]").map { it.text().trim() }.filter { it.isNotBlank() }

        val isSeries = url.contains("full-series-download")

        // Each quality/link block on the page is a heading (h4.movie-title, e.g.
        // "1920 (2008) [Hindi] HD 1080p [2.5 GB]") followed by a .dlbtn-container
        // holding the Download/Watch buttons for that quality.
        val qualityBlocks = doc.select(".dlbtn-container")
        val links = qualityBlocks.mapNotNull { block ->
            val downloadHref = block.selectFirst("a.dlbtn-download")?.attr("href")
            val watchHref = block.selectFirst("a.dlbtn-watch")?.attr("href")
            val qualityLabel = block.previousElementSibling()?.text()?.trim()
                ?: block.parent()?.selectFirst("h4.movie-title")?.text()?.trim()
                ?: "Unknown Quality"
            if (downloadHref == null && watchHref == null) return@mapNotNull null
            Triple(qualityLabel, downloadHref, watchHref)
        }

        // Store the resolver links as a JSON-ish pipe separated data string so
        // loadLinks can re-fetch and resolve them.
        val dataString = links.joinToString("||") { (label, dl, watch) ->
            "$label:::${dl.orEmpty()}:::${watch.orEmpty()}"
        }

        return if (isSeries) {
            // NOTE: CineFreak lists web series as one page per season with a
            // single set of quality/download blocks (batch links), not
            // separate per-episode pages. We expose the whole page as a
            // single "episode" entry; refine this if per-episode pages exist.
            val episodes = listOf(
                newEpisode(dataString) {
                    this.name = title
                    this.season = 1
                    this.episode = 1
                }
            )
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, dataString) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        }
    }

    // The "Download Links"/"Watch Online" buttons point to
    // generate.php?id=BASE64. That page is a fake JS-driven ad/timer page
    // that eventually runs `window.location.href = "<cinecloud url>"`.
    // The BASE64 id decodes directly to that same CineCloud URL, just with
    // a fixed junk suffix "newgo32" appended -- so we can skip generate.php
    // entirely and jump straight to the CineCloud page.
    private fun resolveCineCloudUrl(generatePhpUrl: String): String? {
        return try {
            val idParam = Regex("""[?&]id=([^&]+)""").find(generatePhpUrl)?.groupValues?.get(1)
                ?: return null
            val decodedBytes = java.util.Base64.getDecoder().decode(idParam)
            var decoded = String(decodedBytes, Charsets.UTF_8)
            if (decoded.endsWith("newgo32")) {
                decoded = decoded.removeSuffix("newgo32")
            }
            decoded
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        data.split("||").forEach { entry ->
            val parts = entry.split(":::")
            if (parts.size < 3) return@forEach
            val quality = parts[0]
            val downloadUrl = parts[1]
            val watchUrl = parts[2]

            listOf(downloadUrl to "Download", watchUrl to "Watch").forEach { (link, kind) ->
                if (link.isBlank()) return@forEach
                try {
                    val cineCloudUrl = resolveCineCloudUrl(link) ?: return@forEach

                    // On the CineCloud page, the direct video link is
                    // already present in the raw HTML as the href of the
                    // "Instant Download" button (class "fsl-btn"), no JS
                    // execution needed.
                    val resolvedDoc = app.get(cineCloudUrl, allowRedirects = true).document

                    val directLink = resolvedDoc.selectFirst("a.fsl-btn")?.attr("href")
                        ?: resolvedDoc.selectFirst("a.server-btn")?.attr("href")
                        ?: resolvedDoc.select("a[href*=googleusercontent.com], a[href*=.mkv], a[href*=.mp4], a[href*=drive.google.com]")
                            .map { it.attr("href") }
                            .firstOrNull()

                    if (!directLink.isNullOrBlank()) {
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "$name $kind $quality",
                                url = directLink,
                                type = ExtractorLinkType.VIDEO
                            )
                        )
                        found = true
                    }
                } catch (e: Exception) {
                    // Skip this link if resolution failed.
                }
            }
        }

        return found
    }
}
