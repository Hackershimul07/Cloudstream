package com.fsplusnetbd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Provider for fs.plus.net.bd — an h5ai powered BDIX file server.
 *
 * Movies:  /Movies/<Language>/<Year-folder>/<Title (Year)>/<file>.mp4
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
    private val TMDB_API_KEY = "4ef0d7355d9ffb5151e987764708ce96"

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

    /** Parses an h5ai directory listing page into a list of (name, url, isFolder) entries. */
    private suspend fun listDir(path: String): List<Triple<String, String, Boolean>> {
        val url = "$mainUrl/${path.trim('/')}/"
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
            val fullUrl = if (href.startsWith("http")) href else fixUrl(href)
            out.add(Triple(decodedName, fullUrl, isFolder))
        }
        return out
    }

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

    /** Looks up a poster on TMDB by title/year. Cached per-provider-instance. */
    private suspend fun fetchPoster(title: String, year: Int?, isTvSeries: Boolean): String? {
        val cacheKey = "$title|$year|$isTvSeries"
        if (posterCache.containsKey(cacheKey)) return posterCache[cacheKey]
        val poster = try {
            val endpoint = if (isTvSeries) "tv" else "movie"
            val yearParam = when {
                year == null -> ""
                isTvSeries -> "&first_air_date_year=$year"
                else -> "&year=$year"
            }
            val query = URLEncoder.encode(title, "UTF-8")
            val apiUrl = "https://api.themoviedb.org/3/search/$endpoint?api_key=$TMDB_API_KEY&query=$query$yearParam"
            val body = app.get(apiUrl).text
            val json = JSONObject(body)
            val results = json.optJSONArray("results")
            val posterPath = if (results != null && results.length() > 0) {
                results.getJSONObject(0).optString("poster_path", "")
            } else ""
            if (posterPath.isNotBlank()) "https://image.tmdb.org/t/p/w500$posterPath" else null
        } catch (e: Exception) {
            null
        }
        posterCache[cacheKey] = poster
        return poster
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (type, basePath) = request.data.split("|", limit = 2)

        return if (type == "MOVIE") {
            val yearFolders = sortYearFolders(listDir(basePath).filter { it.third })
            val index = page - 1
            if (index >= yearFolders.size) {
                return newHomePageResponse(request.name, emptyList(), hasNext = false)
            }
            val (yearName, yearUrl, _) = yearFolders[index]
            val entries = listDir(yearUrl.removePrefix("$mainUrl/")).filter { it.third }

            val items = coroutineScope {
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
            newHomePageResponse("${request.name} • $yearName", items, hasNext = index + 1 < yearFolders.size)
        } else {
            // SHOW: flat alphabetical listing, paginated manually since h5ai has no server paging.
            val chunkSize = 30
            val allShows = listDir(basePath).filter { it.third }.sortedBy { it.first.lowercase() }
            val startIdx = (page - 1) * chunkSize
            if (startIdx >= allShows.size) {
                return newHomePageResponse(request.name, emptyList(), hasNext = false)
            }
            val pageItems = allShows.drop(startIdx).take(chunkSize)

            val items = coroutineScope {
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
            newHomePageResponse(request.name, items, hasNext = startIdx + chunkSize < allShows.size)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()

        return coroutineScope {
            // Movies: scan every year-folder in every movie category (parallel).
            val movieYearJobs = movieCategories.values.map { basePath ->
                async { sortYearFolders(listDir(basePath).filter { it.third }) }
            }
            val movieYears = movieYearJobs.flatMap { it.await() }
            val movieEntryJobs = movieYears.map { (_, yearUrl, _) ->
                async { listDir(yearUrl.removePrefix("$mainUrl/")).filter { it.third } }
            }
            val movieEntries = movieEntryJobs.flatMap { it.await() }
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
                }
            )
        }
        return true
    }
}
