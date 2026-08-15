package com.fsplusnetbd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Provider for fs.plus.net.bd — an h5ai powered BDIX file server.
 *
 * Movies:  either
 *          /Movies/<Language>/<Year-folder>/<Title (Year)>/<file>.mp4   (year-layout categories)
 *          or
 *          /Movies/<Language>/<Title (Year)>/<file>.mp4                (flat categories, e.g. Indian-Bangla, South-Indian, Asian-Anime)
 * Shows:   /Shows/<Tv-Shows|Anime-Shows|Indian-Web-Series>/<Title (Year)>/Season X/<file>.mp4
 *          (shows are listed directly, alphabetically — no year-folder layer)
 */
class FsPlusNetBdProvider : MainAPI() {
    override var mainUrl = "https://fs.plus.net.bd"
    override var name = "FS Plus (BDIX)"
    override val hasMainPage = true
    override var lang = "bn"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val videoRegex = Regex(""".*\.(mp4|mkv|avi|webm)$""", RegexOption.IGNORE_CASE)
    private val yearFolderRegex = Regex("""^\d{4}(-\d{4})?$""")
    private val TMDB_API_KEY = "4ef0d7355d9ffb5151e987764708ce96"

    // BDIX/h5ai servers commonly block or drop requests carrying the default OkHttp
    // user-agent — normal (sequential) playback can slip through on the first
    // connection, but a fresh Range request triggered by seeking gets rejected,
    // which surfaces in the player as an IO error mid-scrub. A browser-like UA
    // fixes this for both the initial request and subsequent seek requests.
    private val streamHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to mainUrl
    )

    private val movieCategories = mapOf(
        "English Movies" to "Movies/English",
        "Hindi Movies" to "Movies/Hindi",
        "Indian Bangla Movies" to "Movies/Indian-Bangla",
        "South Indian Movies" to "Movies/South-Indian",
        "Asian / Anime Movies" to "Movies/Asian-Anime"
    )

    private val showCategories = mapOf(
        "TV Shows" to "Shows/Tv-Shows",
        "Anime Shows" to "Shows/Anime-Shows",
        "Indian Web Series" to "Shows/Indian-Web-Series"
    )

    override val mainPage = (
        movieCategories.map { (name, path) -> MainPageData(name, "MOVIE|$path") } +
            showCategories.map { (name, path) -> MainPageData(name, "SHOW|$path") }
        ).toMutableList()

    /**
     * Percent-encodes characters that are safe for Jsoup/OkHttp to fetch HTML with,
     * but break ExoPlayer's HttpDataSource when used as a final playback URL
     * (raw spaces, parentheses, brackets, etc. in the file name).
     * Leaves already-encoded sequences (%xx) untouched — uses java.net.URI as the
     * primary strict parser, falling back to targeted manual replacement since URI
     * throws on the exact "unescaped space" case we're trying to fix.
     */
    private fun safeEncodePath(url: String): String {
        return try {
            java.net.URI(url).toASCIIString()
        } catch (e: Exception) {
            url
                .replace(" ", "%20")
                .replace("(", "%28")
                .replace(")", "%29")
                .replace("[", "%5B")
                .replace("]", "%5D")
                .replace("'", "%27")
                .replace("#", "%23")
        }
    }

    /** Parses an h5ai directory listing page into a list of (name, url, isFolder) entries.
     *  Bounded to 15s and never throws — a slow or failed request returns an empty
     *  list instead of hanging the whole page load indefinitely. */
    private suspend fun listDir(path: String): List<Triple<String, String, Boolean>> {
        val url = "$mainUrl/${path.trim('/')}/"
        return try {
            kotlinx.coroutines.withTimeoutOrNull(15000L) {
                val doc = app.get(url).document
                val rows = doc.select("table#fallback tr, table.fallback tr, tr")
                val out = ArrayList<Triple<String, String, Boolean>>()
                for (row in rows) {
                    val a = row.selectFirst("td.fb-n a, a") ?: continue
                    val href = a.attr("href")
                    if (href.isBlank() || href.contains("..") || a.text().trim().equals("Parent Directory", true)) continue
                    val isFolder = href.endsWith("/")
                    val decodedName = try {
                        URLDecoder.decode(href.trimEnd('/').substringAfterLast('/'), "UTF-8")
                    } catch (e: Exception) {
                        a.text().trim()
                    }
                    val rawUrl = if (href.startsWith("http")) href else fixUrl(href)
                    val fullUrl = safeEncodePath(rawUrl)
                    out.add(Triple(decodedName, fullUrl, isFolder))
                }
                out
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** True if every folder name in the list looks like a year or year-range (e.g. "2014", "2001-2010"). */
    private fun isYearLayout(folders: List<Triple<String, String, Boolean>>): Boolean =
        folders.isNotEmpty() && folders.all { yearFolderRegex.matches(it.first) }

    /** Sorts year-range folder names newest first: 2026, 2025, ..., 2001-2010, 1900-2000 */
    private fun sortYearFolders(folders: List<Triple<String, String, Boolean>>): List<Triple<String, String, Boolean>> {
        fun keyFor(name: String): Int {
            val singleYear = Regex("""^(\d{4})$""").find(name)
            if (singleYear != null) return singleYear.groupValues[1].toInt()
            val range = Regex("""^(\d{4})-(\d{4})$""").find(name)
            if (range != null) return range.groupValues[2].toInt()
            return 0
        }
        return folders.sortedByDescending { keyFor(it.first) }
    }

    private fun cleanTitle(raw: String): String = raw.replace(Regex("""\s*\(\d{4}\)\s*$"""), "").trim()

    private fun yearOf(raw: String): Int? =
        Regex("""\((\d{4})\)\s*$""").find(raw)?.groupValues?.get(1)?.toIntOrNull()

    private fun extractNumber(raw: String): Int? = Regex("""\d+""").find(raw)?.value?.toIntOrNull()

    private val posterCache = HashMap<String, String?>()
    // Limits concurrent TMDB calls so a burst of posters on one page doesn't hit
    // rate limiting. With the smaller 16-item chunk size, this now covers almost
    // a whole page's worth of posters in a single concurrent batch.
    private val tmdbSemaphore = Semaphore(16)
    // Hard cap on how long a single page is allowed to wait on poster lookups
    // before it falls back to showing items without posters. Raised from 8s -> 25s
    // now that listDir() has its own 15s bound, so this timeout is purely a safety
    // net rather than the main thing preventing a hang — we can afford to give
    // posters more room to actually finish instead of falling back early.
    private val posterBudgetMs = 25000L

    /** Looks up a poster on TMDB by title/year. Cached per-provider-instance. */
    private suspend fun fetchPoster(title: String, year: Int?, isTvSeries: Boolean): String? {
        val cacheKey = "$title|$year|$isTvSeries"
        if (posterCache.containsKey(cacheKey)) return posterCache[cacheKey]
        val poster = tmdbSemaphore.withPermit {
            try {
                val endpoint = if (isTvSeries) "tv" else "movie"
                val yearParam = when {
                    year == null -> ""
                    isTvSeries -> "&first_air_date_year=$year"
                    else -> "&year=$year"
                }
                val query = URLEncoder.encode(title, "UTF-8")
                val apiUrl = "https://api.themoviedb.org/3/search/$endpoint?api_key=$TMDB_API_KEY&query=$query$yearParam"

                // Single attempt only — retry+delay here just eats into the shared
                // posterBudgetMs and slows down every other item on the page.
                val body = try {
                    app.get(apiUrl).text
                } catch (e: Exception) {
                    null
                }
                val json = JSONObject(body ?: return@withPermit null)
                val results = json.optJSONArray("results")
                val posterPath = if (results != null && results.length() > 0) {
                    results.getJSONObject(0).optString("poster_path", "")
                } else ""
                if (posterPath.isNotBlank()) "https://image.tmdb.org/t/p/w500$posterPath" else null
            } catch (e: Exception) {
                null
            }
        }
        posterCache[cacheKey] = poster
        return poster
    }

    /**
     * Resolves the list of *title* folders for a movie category, regardless of whether
     * that category uses a year-folder layer or lists titles flat.
     */
    private suspend fun resolveMovieTitleFolders(basePath: String): List<Triple<String, String, Boolean>> {
        val topFolders = listDir(basePath).filter { it.third }
        return if (isYearLayout(topFolders)) {
            coroutineScope {
                topFolders.map { (_, yearUrl, _) ->
                    async { listDir(yearUrl.removePrefix("$mainUrl/")).filter { it.third } }
                }.awaitAll()
            }.flatten()
        } else {
            topFolders
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (type, basePath) = request.data.split("|", limit = 2)

        return if (type == "MOVIE") {
            val topFolders = listDir(basePath).filter { it.third }

            if (isYearLayout(topFolders)) {
                val yearFolders = sortYearFolders(topFolders)
                val index = page - 1
                if (index >= yearFolders.size) {
                    return newHomePageResponse(request.name, emptyList(), hasNext = false)
                }
                val (yearName, yearUrl, _) = yearFolders[index]
                val entries = listDir(yearUrl.removePrefix("$mainUrl/")).filter { it.third }

                val items = kotlinx.coroutines.withTimeoutOrNull(posterBudgetMs) {
                    coroutineScope {
                        entries.map { (name, url, _) ->
                            async {
                                val title = cleanTitle(name)
                                val year = yearOf(name)
                                val poster = fetchPoster(title, year, false)
                                newMovieSearchResponse(title, url, TvType.Movie) {
                                    this.posterUrl = poster
                                }
                            }
                        }.awaitAll()
                    }
                } ?: entries.map { (name, url, _) ->
                    newMovieSearchResponse(cleanTitle(name), url, TvType.Movie) { this.posterUrl = null }
                }
                newHomePageResponse("${request.name} • $yearName", items, hasNext = index + 1 < yearFolders.size)
            } else {
                // Flat layout: titles listed directly under the category (no year-folder layer).
                // Smaller chunk = fewer poster lookups per page = faster initial open.
                // Remaining titles still load fine on scroll via the next page.
                val chunkSize = 16
                val allMovies = topFolders.sortedBy { it.first.lowercase() }
                val startIdx = (page - 1) * chunkSize
                if (startIdx >= allMovies.size) {
                    return newHomePageResponse(request.name, emptyList(), hasNext = false)
                }
                val pageItems = allMovies.drop(startIdx).take(chunkSize)

                val items = kotlinx.coroutines.withTimeoutOrNull(posterBudgetMs) {
                    coroutineScope {
                        pageItems.map { (name, url, _) ->
                            async {
                                val title = cleanTitle(name)
                                val year = yearOf(name)
                                val poster = fetchPoster(title, year, false)
                                newMovieSearchResponse(title, url, TvType.Movie) {
                                    this.posterUrl = poster
                                }
                            }
                        }.awaitAll()
                    }
                } ?: pageItems.map { (name, url, _) ->
                    newMovieSearchResponse(cleanTitle(name), url, TvType.Movie) { this.posterUrl = null }
                }
                newHomePageResponse(request.name, items, hasNext = startIdx + chunkSize < allMovies.size)
            }
        } else {
            // SHOW: flat alphabetical listing, paginated manually since h5ai has no server paging.
            // Smaller chunk = fewer poster lookups per page = faster initial open.
            val chunkSize = 16
            val allShows = listDir(basePath).filter { it.third }.sortedBy { it.first.lowercase() }
            val startIdx = (page - 1) * chunkSize
            if (startIdx >= allShows.size) {
                return newHomePageResponse(request.name, emptyList(), hasNext = false)
            }
            val pageItems = allShows.drop(startIdx).take(chunkSize)

            val items = kotlinx.coroutines.withTimeoutOrNull(posterBudgetMs) {
                coroutineScope {
                    pageItems.map { (name, url, _) ->
                        async {
                            val title = cleanTitle(name)
                            val year = yearOf(name)
                            val poster = fetchPoster(title, year, true)
                            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                                this.posterUrl = poster
                            }
                        }
                    }.awaitAll()
                }
            } ?: pageItems.map { (name, url, _) ->
                newTvSeriesSearchResponse(cleanTitle(name), url, TvType.TvSeries) { this.posterUrl = null }
            }
            newHomePageResponse(request.name, items, hasNext = startIdx + chunkSize < allShows.size)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()

        return coroutineScope {
            // Movies: resolve title folders for every movie category (handles both
            // year-layout and flat categories), then filter by query.
            val movieTitleJobs = movieCategories.values.map { basePath ->
                async { resolveMovieTitleFolders(basePath) }
            }
            val movieEntries = movieTitleJobs.flatMap { it.await() }
                .filter { it.first.lowercase().contains(q) }
                .map { (name, url, _) ->
                    newMovieSearchResponse(cleanTitle(name), url, TvType.Movie) { this.posterUrl = null }
                }

            // Shows: flat listing, no year layer, scanned directly.
            val showEntryJobs = showCategories.values.map { basePath ->
                async { listDir(basePath).filter { it.third } }
            }
            val showEntries = showEntryJobs.flatMap { it.await() }
                .filter { it.first.lowercase().contains(q) }
                .map { (name, url, _) ->
                    newTvSeriesSearchResponse(cleanTitle(name), url, TvType.TvSeries) { this.posterUrl = null }
                }

            (movieEntries + showEntries).distinctBy { it.url }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val path = url.removePrefix("$mainUrl/")
        val entries = listDir(path)
        val folderName = URLDecoder.decode(url.trimEnd('/').substringAfterLast('/'), "UTF-8")
        val title = cleanTitle(folderName)
        val year = yearOf(folderName)

        val seasonFolders = entries.filter { it.third && it.first.contains("season", ignoreCase = true) }

        return if (seasonFolders.isNotEmpty()) {
            // TV series: gather episodes from every Season folder.
            val episodes = ArrayList<Episode>()
            for ((seasonName, seasonUrl, _) in seasonFolders.sortedBy { extractNumber(it.first) ?: 0 }) {
                val seasonNum = extractNumber(seasonName) ?: 1
                val epEntries = listDir(seasonUrl.removePrefix("$mainUrl/"))
                    .filter { !it.third && it.first.matches(videoRegex) }
                    .sortedBy { it.first }
                epEntries.forEachIndexed { idx, (epName, epUrl, _) ->
                    val epNum = Regex("""[Ee](\d{1,3})""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: (idx + 1)
                    episodes.add(
                        newEpisode(epUrl) {
                            this.name = epName
                            this.season = seasonNum
                            this.episode = epNum
                        }
                    )
                }
            }
            val poster = fetchPoster(title, year, true)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.year = year
                this.posterUrl = poster
                this.plot = "Source: fs.plus.net.bd"
            }
        } else {
            val videoFiles = entries.filter { !it.third && it.first.matches(videoRegex) }
            val poster = fetchPoster(title, year, false)
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.year = year
                this.posterUrl = poster
                this.plot = "Source: fs.plus.net.bd\n" + videoFiles.joinToString("\n") { it.first }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // TV episodes: `data` is already a direct file URL from load().
        if (data.matches(videoRegex)) {
            val name = URLDecoder.decode(data.substringAfterLast('/'), "UTF-8")
            val quality = Regex("""(2160p|1080p|720p|480p|360p)""", RegexOption.IGNORE_CASE)
                .find(name)?.value ?: "Unknown"
            callback(
                newExtractorLink(
                    source = this.name,
                    name = "$name [$quality]",
                    url = data,
                    type = INFER_TYPE
                ) {
                    this.referer = mainUrl
                    this.quality = getQualityFromName(quality)
                    this.headers = streamHeaders
                }
            )
            return true
        }

        // Movies: `data` is a folder URL, list video files inside it.
        val path = data.removePrefix("$mainUrl/")
        val entries = listDir(path)
        val videoFiles = entries.filter { !it.third && it.first.matches(videoRegex) }
        if (videoFiles.isEmpty()) return false

        for ((name, fileUrl, _) in videoFiles) {
            val quality = Regex("""(2160p|1080p|720p|480p|360p)""", RegexOption.IGNORE_CASE)
                .find(name)?.value ?: "Unknown"
            callback(
                newExtractorLink(
                    source = this.name,
                    name = "$name [$quality]",
                    url = fileUrl,
                    type = INFER_TYPE
                ) {
                    this.referer = mainUrl
                    this.quality = getQualityFromName(quality)
                    this.headers = streamHeaders
                }
            )
        }
        return true
    }
}
