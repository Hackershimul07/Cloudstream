package com.cncverse

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import android.content.Context
import org.json.JSONObject
import java.net.URLEncoder

class MovieLinkBDProvider : MainAPI() {
    companion object {
        var appContext: Context? = null
        private const val FALLBACK_URL = "https://dxhdjd.movielinkbd.li"
    }

    override var mainUrl = "https://dxhdjd.movielinkbd.li"
    override var name = "MovieLinkBD"
    override var lang = "bn"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false

    // FIX: "/" (root) is a STATIC SEO LANDING PAGE on this site — live-tested,
    // it contains marketing text/testimonials/genre-links but ZERO movie
    // cards. Using it as "Recently Updated" always returned an empty row.
    // Replaced with "/new" ("Latest Published"), which is a real listing
    // page — confirmed to contain actual movie/series cards.
    override val mainPage = mainPageOf(
        "/new" to "Recently Updated",
        "/type/movies" to "All Movies",
        "/type/series" to "All Web Series",
        "/language/hindi" to "Hindi Movies",
        "/language/bangla" to "Bangla Movies",
        "/language/bangla-dubbed" to "Bangla Dubbed",
        "/language/dual-audio" to "Dual Audio",
        "/language/english" to "English",
        "/southIndian" to "South Indian",
        "/language/korean" to "Korean",
        "/anime" to "Anime Zone",
        "/drama" to "K/J/C Drama",
        "/ongoing" to "Ongoing Series",
        "/genre/action" to "Action",
        "/genre/thriller" to "Thriller",
        "/genre/horror" to "Horror",
        "/genre/romance" to "Romance",
        "/category/wwe" to "WWE"
    )

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.AnimeMovie,
        TvType.Anime,
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    @Volatile private var resolvedBase: String? = null

    private suspend fun getBase(): String {
        resolvedBase?.let { return it }
        return try {
            val resp = app.get(
                mainUrl, headers = headers,
                allowRedirects = true, timeout = 15
            )
            val finalUrl = resp.url.trimEnd('/')
            val uri = java.net.URI(finalUrl)
            val base = "${uri.scheme}://${uri.host}"
            resolvedBase = base
            base
        } catch (_: Exception) {
            FALLBACK_URL
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = getBase()
        val path = request.data
        val url = when {
            path == "/" && page == 1 -> "$base/"
            path == "/" -> "$base/page/$page"
            page == 1 -> "$base$path"
            else -> "$base$path/page/$page"
        }
        val doc = app.get(url, headers = headers, timeout = 30).document
        val items = parseMovieCards(doc, base)
        return newHomePageResponse(HomePageList(request.name, items, isHorizontalImages = true), hasNext = items.isNotEmpty())
    }

    // =========================
    // SEARCH
    // =========================
    //
    // FIX: The old "$base/?search=$query" call was live-tested and confirmed
    // BROKEN — it returns the exact same static SEO landing page regardless
    // of the query (same bug pattern as root "/" above; this site appears
    // to serve a fixed shell for unrecognized routes/params).
    //
    // IMPORTANT — HONESTY NOTE: I could not find the site's real search
    // endpoint without inspecting live Network requests in a browser
    // (Chrome DevTools), which requires JS execution I don't have access
    // to. Rather than guess a single URL and risk being wrong again, this
    // tries several plausible candidate URLs (common patterns used by
    // sites like this: query param variants and a path-based search route)
    // and returns the FIRST one that actually yields real result cards.
    // If Shimul later confirms the exact working URL via DevTools, this
    // whole list can be replaced with a single direct call.
    // FIX (CONFIRMED — 2nd round): got the actual search-results page HTML
    // directly from Shimul (fetched via real browser). The search form is:
    //   <form action="/search" method="GET"><input name="q" ...>
    // So the real, confirmed endpoint is:
    //   GET $base/search?q=<query>
    // This is now the primary candidate. Also confirmed from the same HTML:
    // card container is exactly ".movie-card" (already in our selector list),
    // title is "a.title" inside ".content", poster is img[data-src]. So the
    // existing parseMovieCards() logic needs no changes — only the URL did.
    // A couple of extra fallbacks are kept (harmless, only tried if the
    // confirmed one somehow returns nothing) in case the site changes again.
    private val searchUrlCandidates = listOf(
        { base: String, q: String -> "$base/search?q=$q" },   // confirmed working
        { base: String, q: String -> "$base/search/$q" },
        { base: String, q: String -> "$base/?s=$q" }
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val base = getBase()
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")

        for (candidate in searchUrlCandidates) {
            val url = candidate(base, encodedQuery)

            val doc = runCatching {
                app.get(url, headers = headers, timeout = 30).document
            }.getOrNull() ?: continue

            val results = parseMovieCards(doc, base)

            // A candidate is only "working" if it returned real cards AND
            // isn't just showing the static landing page fallback content
            // (cheap sanity check: landing page has none of the movie/series
            // detail links, so parseMovieCards would return empty for it —
            // meaning a non-empty result here is a good signal we hit a
            // real search/listing route).
            if (results.isNotEmpty()) {
                return results
            }
        }

        return emptyList()
    }

    private fun parseMovieCards(doc: org.jsoup.nodes.Document, base: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val cards = doc.select("div.movie-item, div.item-box, div.film-item, div.post-item, .movie-card")

        if (cards.isNotEmpty()) {
            cards.forEach { card ->
                val aTag = card.selectFirst("a[href*='/movie/'], a[href*='/series/'], a[href*='/anime/'], a[href*='/drama/'], a[href*='/download18plus/']")
                    ?: return@forEach
                val href = aTag.attr("abs:href").ifEmpty { base + aTag.attr("href") }
                val title = card.selectFirst(".title, .movie-title, h3, h2")?.text()?.trim()
                    ?.ifBlank { null }
                    ?: aTag.attr("title").trim().ifBlank { null }
                    ?: return@forEach
                val img = card.selectFirst("img")
                val poster = img?.attr("data-src")?.ifEmpty { img.attr("src") }
                    ?: img?.attr("src")

                val type = if (href.contains("/series/") || href.contains("/anime/") || href.contains("/drama/"))
                    TvType.TvSeries else TvType.Movie

                results.add(newMovieSearchResponse(title, href, type) {
                    this.posterUrl = poster
                })
            }
            return results
        }

        val movieLinkPattern = "a[href*='/movie/'], a[href*='/series/'], a[href*='/anime/'], a[href*='/drama/'], a[href*='/download18plus/']"
        val seen = mutableSetOf<String>()
        doc.select(movieLinkPattern).forEach { a ->
            val href = a.attr("abs:href").ifEmpty { base + a.attr("href") }
            if (!seen.add(href)) return@forEach
            val img = a.selectFirst("img") ?: return@forEach
            val poster = img.attr("data-src").ifEmpty { img.attr("src") }
            val titleEl = a.parent()?.selectFirst(".title, .movie-title, h3, h2, [class*='name']")
            // FIX: attr()/text() never return null in Kotlin (Jsoup returns
            // non-null Strings), so the old "?:" chain here was dead code —
            // an empty string would slip through and never hit the
            // "return@forEach" fallback. Using .ifBlank { null } makes the
            // empty-string case actually fall through as intended.
            val title = titleEl?.text()?.trim()?.ifBlank { null }
                ?: a.attr("title").trim().ifBlank { null }
                ?: a.text().trim().ifBlank { null }
                ?: return@forEach

            val type = if (href.contains("/series/") || href.contains("/anime/") || href.contains("/drama/"))
                TvType.TvSeries else TvType.Movie

            results.add(newMovieSearchResponse(title, href, type) {
                this.posterUrl = poster.takeIf { it.isNotEmpty() }
            })
        }

        if (results.isEmpty()) {
            doc.select(movieLinkPattern).forEach { a ->
                val href = a.attr("abs:href").ifEmpty { base + a.attr("href") }
                if (!seen.add(href)) return@forEach
                val title = a.text().trim().ifBlank { null } ?: return@forEach
                if (title.length < 4 || title.all { it.isUpperCase() || it == ' ' }) return@forEach
                val type = if (href.contains("/series/") || href.contains("/anime/") || href.contains("/drama/"))
                    TvType.TvSeries else TvType.Movie
                results.add(newMovieSearchResponse(title, href, type))
            }
        }

        return results
    }

    // ── New: parse the inline player JSON (mlbdInlinePlayerData) ──────────
    private data class PlayerSource(val quality: Int, val url: String, val audio: String)
    private data class PlayerEpisode(
        val kind: String,
        val season: Int?,
        val number: Int?,
        val label: String,
        val sources: List<PlayerSource>
    )

    private fun parsePlayerJson(doc: org.jsoup.nodes.Document): List<PlayerEpisode> {
        return try {
            val scriptData = doc.selectFirst("script#mlbdInlinePlayerData")?.data() ?: return emptyList()
            val json = JSONObject(scriptData)
            val episodesArr = json.optJSONArray("episodes") ?: return emptyList()

            val result = mutableListOf<PlayerEpisode>()
            for (i in 0 until episodesArr.length()) {
                val ep = episodesArr.getJSONObject(i)
                val kind = ep.optString("kind", "movie")
                val season = if (ep.isNull("season")) null else ep.optInt("season")
                val number = if (ep.isNull("number")) null else ep.optInt("number")
                val label = ep.optString("label", "Episode")

                val sourcesArr = ep.optJSONArray("sources")
                val sources = mutableListOf<PlayerSource>()
                if (sourcesArr != null) {
                    for (j in 0 until sourcesArr.length()) {
                        val src = sourcesArr.getJSONObject(j)
                        val quality = src.optInt("quality", 0)
                        val downloadUrl = src.optString("download_url", "")
                        val streamUrl = src.optString("url", "")
                        val finalUrl = downloadUrl.ifEmpty { streamUrl }
                        val audio = src.optString("audio", "")
                        if (finalUrl.isNotEmpty()) {
                            sources.add(PlayerSource(quality, finalUrl, audio))
                        }
                    }
                }
                if (sources.isNotEmpty()) {
                    result.add(PlayerEpisode(kind, season, number, label, sources))
                }
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun sourcesToLinksData(sources: List<PlayerSource>): String {
        return sources.joinToString(" ; ") { src ->
            val qualityLabel = if (src.quality > 0) "${src.quality}p" else "Unknown"
            "$qualityLabel|${src.url}"
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers, timeout = 30).document

        val rawTitle = doc.selectFirst("h1, .movie-title, .film-title, [class*='title']")?.text()?.trim()
            ?: doc.title().substringBefore("•").trim()

        val year = Regex("\\((\\d{4})\\)").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        val poster = doc.selectFirst("img.poster, img[class*='poster'], .poster img, .thumb img, img[src*='poster'], img[src*='uploads']")
            ?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
            ?.takeIf { it.isNotEmpty() }

        fun metaVal(label: String): String? {
            return doc.select("li, p, span, div").firstOrNull { el ->
                el.text().contains(label, ignoreCase = true)
            }?.text()?.substringAfter(":")?.trim()
        }

        val plot = doc.selectFirst(".storyline p, .storyline, .story-text, [class*='story'] p, [class*='plot']")
            ?.text()?.trim()
            ?: metaVal("Storyline")

        val genre = metaVal("Genre")
        val cast = metaVal("Cast")
        val language = metaVal("Language")
        val rating = doc.selectFirst("[class*='imdb'], [class*='rating']")?.text()
            ?.let { Regex("[0-9.]+").find(it)?.value?.toFloatOrNull() }

        val fullPlot = buildString {
            language?.let { append("Language: $it\n") }
            genre?.let { append("Genre: $it\n") }
            cast?.let { append("Cast: $it\n") }
            plot?.let { append("\n$it") }
        }.trim()

        val isSeries = url.contains("/series/") || url.contains("/anime/") || url.contains("/drama/")

        // Try the new inline player JSON first
        val playerEpisodes = parsePlayerJson(doc)

        val linkAnchors = doc.select("a[href*='/getLink/']")
        val watchAnchors = doc.select("a[href*='/getWatch/']")
        val fileAnchors = doc.select("div.mlbd-download-button-wrap a[href*='/file/'], a[href*='/file/']")

        if (!isSeries) {
            val jsonMovieLinks = playerEpisodes.firstOrNull { it.kind == "movie" }
                ?.let { sourcesToLinksData(it.sources) }

            val fileLinksData = fileAnchors.mapNotNull { a ->
                val href = a.attr("abs:href").ifEmpty {
                    val h = a.attr("href")
                    if (h.startsWith("http")) h else "$mainUrl$h"
                }
                val text = a.text().trim()
                val quality = extractQualityLabel(text)
                "$quality|$href"
            }.joinToString(" ; ").takeIf { it.isNotEmpty() }

            val oldLinksData = (linkAnchors + watchAnchors).mapNotNull { a ->
                val href = a.attr("abs:href").ifEmpty {
                    val h = a.attr("href")
                    if (h.startsWith("http")) h else "$mainUrl$h"
                }
                val text = a.text().trim()
                val quality = extractQualityLabel(text)
                "$quality|$href"
            }.joinToString(" ; ").takeIf { it.isNotEmpty() }

            val linksData = jsonMovieLinks ?: fileLinksData ?: oldLinksData ?: ""

            return newMovieLoadResponse(rawTitle, url, TvType.Movie, linksData) {
                this.posterUrl = poster
                this.year = year
                this.plot = fullPlot.takeIf { it.isNotEmpty() }
                this.score = rating?.let { Score.from10(it) }
            }
        }

        // ───────── Series ─────────
        val episodesData = mutableListOf<Episode>()

        val jsonEpisodes = playerEpisodes.filter { it.kind != "movie" }
        if (jsonEpisodes.isNotEmpty()) {
            jsonEpisodes.forEach { ep ->
                val linksData = sourcesToLinksData(ep.sources)
                if (linksData.isNotEmpty()) {
                    episodesData.add(newEpisode(linksData) {
                        this.name = ep.label.ifEmpty { "Episode ${ep.number ?: 1}" }
                        this.season = ep.season ?: 1
                        this.episode = ep.number ?: (episodesData.size + 1)
                    })
                }
            }
        }

        if (episodesData.isEmpty()) {
            val episodeSections = doc.select(
                "div.episode-section, div.season-section, h3:contains(Episode), h4:contains(Episode), " +
                "div[class*='episode'], div[class*='season'], strong:contains(Ep), b:contains(Ep)"
            )

            if (episodeSections.isNotEmpty()) {
                episodeSections.forEach { section ->
                    val sectionText = section.text()
                    val epRange = Regex("(?:Ep|Episode)[^\\d]*(\\d+)(?:[^\\d]+(\\d+))?", RegexOption.IGNORE_CASE)
                        .find(sectionText)
                    val start = epRange?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val end = epRange?.groupValues?.get(2)?.toIntOrNull() ?: start

                    val sectionLinks = mutableListOf<String>()
                    var sib = section.nextElementSibling()
                    while (sib != null && !sib.tagName().matches(Regex("h[1-6]"))) {
                        sib.select("a[href*='/getLink/'], a[href*='/getWatch/'], a[href*='/file/']").forEach { a ->
                            val href = a.attr("abs:href").ifEmpty {
                                val h = a.attr("href")
                                if (h.startsWith("http")) h else "$mainUrl$h"
                            }
                            val quality = extractQualityLabel(a.text())
                            sectionLinks.add("$quality|$href")
                        }
                        sib = sib.nextElementSibling()
                    }

                    if (sectionLinks.isNotEmpty()) {
                        val epUrl = sectionLinks.joinToString(" ; ")
                        for (epNum in start..end) {
                            episodesData.add(newEpisode(epUrl) {
                                this.name = "Episode $epNum"
                                this.season = 1
                                this.episode = epNum
                            })
                        }
                    }
                }
            }
        }

        if (episodesData.isEmpty() && (linkAnchors.isNotEmpty() || fileAnchors.isNotEmpty())) {
            val allLinks = (linkAnchors + watchAnchors + fileAnchors).mapNotNull { a ->
                val href = a.attr("abs:href").ifEmpty {
                    val h = a.attr("href")
                    if (h.startsWith("http")) h else "$mainUrl$h"
                }
                val quality = extractQualityLabel(a.text())
                "$quality|$href"
            }.joinToString(" ; ")

            episodesData.add(newEpisode(allLinks) {
                this.name = "Full Season"
                this.season = 1
                this.episode = 1
            })
        }

        return newTvSeriesLoadResponse(rawTitle, url, TvType.TvSeries, episodesData) {
            this.posterUrl = poster
            this.year = year
            this.plot = fullPlot.takeIf { it.isNotEmpty() }
            this.score = rating?.let { Score.from10(it) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!data.contains("|")) return false
        data.split(" ; ").forEach { item ->
            val parts = item.split("|")
            val qualityLabel = parts.getOrNull(0)?.trim() ?: ""
            val linkUrl = parts.getOrNull(1)?.trim() ?: item.trim()
            if (linkUrl.isEmpty()) return@forEach

            when {
                linkUrl.contains("cdn.dramalinkbd.tv") ||
                linkUrl.contains(".mkv") || linkUrl.contains(".mp4") -> {
                    val quality = labelToQuality(qualityLabel)
                    callback(
                        ExtractorLink(
                            source = name,
                            name = "$name [$qualityLabel]",
                            url = linkUrl,
                            referer = mainUrl,
                            quality = quality,
                            type = ExtractorLinkType.VIDEO,
                            headers = headers
                        )
                    )
                }
                linkUrl.contains("/getLink/") -> {
                    resolveGetLink(linkUrl, qualityLabel, callback)
                }
                linkUrl.contains("/getWatch/") -> {
                    resolveGetWatch(linkUrl, qualityLabel, callback)
                }
                linkUrl.contains("/file/") -> {
                    val quality = labelToQuality(qualityLabel)
                    callback(
                        ExtractorLink(
                            source = name,
                            name = "$name [$qualityLabel]",
                            url = linkUrl,
                            referer = mainUrl,
                            quality = quality,
                            type = ExtractorLinkType.VIDEO,
                            headers = headers
                        )
                    )
                }
                else -> {
                    com.lagradost.cloudstream3.utils.loadExtractor(linkUrl, mainUrl, subtitleCallback, callback)
                }
            }
        }
        return true
    }

    private suspend fun resolveGetLink(
        getLinkUrl: String,
        qualityLabel: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(getLinkUrl, headers = headers, timeout = 20).document
            val fileAnchor = doc.selectFirst("a[href*='/file/']")
            if (fileAnchor != null) {
                val fileUrl = fileAnchor.attr("abs:href")
                    .ifEmpty { fileAnchor.attr("href") }
                val quality = labelToQuality(qualityLabel)
                callback(
                    ExtractorLink(
                        source = name,
                        name = "$name [$qualityLabel]",
                        url = fileUrl,
                        referer = getLinkUrl,
                        quality = quality,
                        type = ExtractorLinkType.VIDEO,
                        headers = headers
                    )
                )
            }

            doc.select("a[href]").forEach { a ->
                val href = a.attr("href").trim()
                if (href.isEmpty() || href.contains("/file/")) return@forEach
                if (href.startsWith("http") && !href.contains("movielinkbd") &&
                    !href.contains("telegram") && !href.contains("google.com/store")) {
                    com.lagradost.cloudstream3.utils.loadExtractor(
                        href, getLinkUrl,
                        subtitleCallback = {},
                        callback = callback
                    )
                }
            }
        } catch (_: Exception) { }
    }

    private suspend fun resolveGetWatch(
        getWatchUrl: String,
        qualityLabel: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(getWatchUrl, headers = headers, timeout = 20).document
            val videoSrc = doc.selectFirst("video source, video[src]")?.attr("src")
                ?: doc.selectFirst("iframe[src]")?.attr("src")
            if (!videoSrc.isNullOrEmpty()) {
                val quality = labelToQuality(qualityLabel)
                if (videoSrc.contains("m3u8")) {
                    callback(
                        ExtractorLink(
                            source = name,
                            name = "$name [Stream]",
                            url = videoSrc,
                            referer = getWatchUrl,
                            quality = quality,
                            type = ExtractorLinkType.M3U8,
                            headers = headers
                        )
                    )
                } else {
                    callback(
                        ExtractorLink(
                            source = name,
                            name = "$name [Stream]",
                            url = videoSrc,
                            referer = getWatchUrl,
                            quality = quality,
                            type = ExtractorLinkType.VIDEO,
                            headers = headers
                        )
                    )
                }
            }
        } catch (_: Exception) { }
    }

    private fun extractQualityLabel(text: String): String {
        return when {
            text.contains("4K", ignoreCase = true) || text.contains("2160", ignoreCase = true) -> "4K"
            text.contains("1080", ignoreCase = true) -> "1080p"
            text.contains("720p HEVC", ignoreCase = true) || text.contains("720 HEVC", ignoreCase = true) -> "720p HEVC"
            text.contains("720", ignoreCase = true) -> "720p"
            text.contains("480", ignoreCase = true) -> "480p"
            text.contains("360", ignoreCase = true) -> "360p"
            text.contains("Best Quality", ignoreCase = true) -> "Best Quality"
            text.contains("Watch Online", ignoreCase = true) -> "Stream"
            text.contains("Download", ignoreCase = true) -> "Download"
            else -> text.take(30).trim().ifEmpty { "Unknown" }
        }
    }

    private fun labelToQuality(label: String): Int {
        return when {
            label.contains("4K", ignoreCase = true) || label.contains("2160", ignoreCase = true) -> Qualities.P2160.value
            label.contains("1080", ignoreCase = true) || label.contains("Best Quality", ignoreCase = true) -> Qualities.P1080.value
            label.contains("720", ignoreCase = true) -> Qualities.P720.value
            label.contains("480", ignoreCase = true) -> Qualities.P480.value
            label.contains("360", ignoreCase = true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
