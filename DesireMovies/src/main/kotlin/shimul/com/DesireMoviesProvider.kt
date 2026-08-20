package shimul.com

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
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
        "$mainUrl/bollywood-movies-desiremovie/page/" to "Bollywood"
        
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
            // gyanigurus.online একটা লিংক-প্রোটেকশন/গেট পেজ — আসল hubcloud/gdflix/hubdrive
            // লিংক এর ভেতরে থাকে, article পেজে সরাসরি থাকে না
            if (gateHref.contains("gyanigurus")) {
                val gateDocument = runCatching { app.get(gateHref).document }.getOrNull()

                gateDocument?.select("a")?.forEach { hostLink ->
                    val realHref = hostLink.attr("href")

                    when {
                        // hubcloud সাধারণত সবচেয়ে রিলায়েবল — static redirect
                        realHref.contains("hubcloud") -> {
                            runCatching {
                                loadExtractor(realHref, data, subtitleCallback, callback)
                            }.onSuccess { found = true }
                        }

                        // hubdrive-এর জন্য কাস্টম হ্যান্ডলিং (AJAX ভিত্তিক)
                        realHref.contains("hubdrive") -> {
                            runCatching {
                                val hubDriveLink = getHubDriveDirectLink(realHref)
                                if (hubDriveLink != null) {
                                    callback(
                                        newExtractorLink(
                                            source = name,
                                            name = "HubDrive",
                                            url = hubDriveLink,
                                            type = ExtractorLinkType.VIDEO
                                        ) {
                                            this.referer = realHref
                                            this.quality = Qualities.Unknown.value
                                        }
                                    )
                                    found = true
                                }
                            }
                        }

                        // gdflix — Turnstile/JS প্রোটেকশন থাকায় guaranteed কাজ নাও করতে পারে,
                        // তবু built-in extractor দিয়ে try করা হচ্ছে (fallback হিসেবে)
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

    /**
     * HubDrive-এর পেজ থেকে ফাইল id বের করে /ajax.php?ajax=direct-download-এ POST করে
     * আসল ডাউনলোড লিংক বের করে।
     *
     * NOTE: response-এর exact JSON structure ভিন্ন হতে পারে — যদি লিংক না পাওয়া যায়,
     * তাহলে ব্রাউজার devtools দিয়ে /ajax.php?ajax=direct-download রেসপন্স চেক করে
     * নিচের "data"/"url" key নাম ঠিক করে নিতে হবে।
     */
    private suspend fun getHubDriveDirectLink(hubDriveUrl: String): String? {
        val doc = app.get(hubDriveUrl).document
        val fileId = doc.selectFirst("#down-id")?.text()?.trim() ?: return null

        val baseUrl = hubDriveUrl.substringBefore("/file")
        val ajaxUrl = "$baseUrl/ajax.php?ajax=direct-download"

        val responseText = app.post(
            ajaxUrl,
            data = mapOf("id" to fileId),
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to hubDriveUrl
            )
        ).text

        return runCatching {
            val json = JSONObject(responseText)
            if (json.optString("code") == "200") {
                val dataField = json.opt("data")
                when (dataField) {
                    is String -> dataField
                    is JSONObject -> dataField.optString("url").takeIf { it.isNotBlank() }
                    else -> null
                }
            } else null
        }.getOrNull()
    }
}
