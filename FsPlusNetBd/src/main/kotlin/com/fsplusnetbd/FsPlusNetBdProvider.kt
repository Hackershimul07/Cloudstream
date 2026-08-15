package com.fsplusnetbd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder

/**
 * Provider for fs.plus.net.bd — an h5ai powered BDIX file server.
 * Structure: /Movies|Shows/<Language>/<Year-folder>/<Title (Year)>/<file>.mp4
 * Same directory-listing pattern as BdixDhakaFlix, so the parsing logic
 * (dynamic year-folder detection, reverse-chronological ordering) is reused.
 */
class FsPlusNetBdProvider : MainAPI() {
    override var mainUrl = "https://fs.plus.net.bd"
    override var name = "FS Plus (BDIX)"
    override val hasMainPage = true
    override var lang = "bn"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // category name -> path on the server
    private val categories = mapOf(
        "English Movies" to "Movies/English",
        "Hindi Movies" to "Movies/Hindi",
        "Indian Bangla Movies" to "Movies/Indian-Bangla",
        "South Indian Movies" to "Movies/South-Indian",
        "Asian / Anime Movies" to "Movies/Asian-Anime",
        "TV Shows" to "Shows"
    )

    override val mainPage = categories.map { (name, path) -> MainPageData(name, path) }.toMutableList()

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
            if (range != null) return range.groupValues[2].toInt() // sort by range end
            return 0
        }
        return folders.sortedByDescending { keyFor(it.first) }
    }

    private fun cleanTitle(raw: String): String {
        // "Movie Name (2026)" -> "Movie Name"
        return raw.replace(Regex("""\s*\(\d{4}\)\s*$"""), "").trim()
    }

    private fun yearOf(raw: String): Int? =
        Regex("""\((\d{4})\)\s*$""").find(raw)?.groupValues?.get(1)?.toIntOrNull()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val basePath = request.data
        val yearFolders = sortYearFolders(listDir(basePath).filter { it.third })

        // Each "page" (load-more) walks one more year-folder deeper.
        val index = page - 1
        if (index >= yearFolders.size) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
        val (yearName, yearUrl, _) = yearFolders[index]
        val entries = listDir(yearUrl.removePrefix("$mainUrl/")).filter { it.third }

        val items = entries.map { (name, url, _) ->
            newMovieSearchResponse(cleanTitle(name), url, TvType.Movie) {
                this.posterUrl = null
            }
        }
        return newHomePageResponse(
            "${request.name} • $yearName",
            items,
            hasNext = index + 1 < yearFolders.size
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()

        // h5ai has no server-side search API, so we scan every year-folder
        // in every category. Fetched in parallel (per year) to keep it
        // reasonably fast despite covering the full archive.
        return kotlinx.coroutines.coroutineScope {
            val allYearJobs = categories.values.map { basePath ->
                kotlinx.coroutines.async {
                    sortYearFolders(listDir(basePath).filter { it.third })
                }
            }
            val allYears = allYearJobs.flatMap { it.await() }

            val entryJobs = allYears.map { (_, yearUrl, _) ->
                kotlinx.coroutines.async {
                    listDir(yearUrl.removePrefix("$mainUrl/")).filter { it.third }
                }
            }
            val allEntries = entryJobs.flatMap { it.await() }

            allEntries
                .filter { it.first.lowercase().contains(q) }
                .map { (name, url, _) ->
                    newMovieSearchResponse(cleanTitle(name), url, TvType.Movie) {
                        this.posterUrl = null
                    }
                }
                .distinctBy { it.url }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val path = url.removePrefix("$mainUrl/")
        val entries = listDir(path)
        val folderName = URLDecoder.decode(url.trimEnd('/').substringAfterLast('/'), "UTF-8")
        val title = cleanTitle(folderName)
        val year = yearOf(folderName)

        // Direct movie files sit right inside this folder.
        val videoFiles = entries.filter { !it.third && it.first.matches(Regex(""".*\.(mp4|mkv|avi|webm)$""", RegexOption.IGNORE_CASE)) }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.year = year
            this.plot = "Source: fs.plus.net.bd\n" + videoFiles.joinToString("\n") { it.first }
            this.posterUrl = null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val path = data.removePrefix("$mainUrl/")
        val entries = listDir(path)
        val videoFiles = entries.filter { !it.third && it.first.matches(Regex(""".*\.(mp4|mkv|avi|webm)$""", RegexOption.IGNORE_CASE)) }

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
