package shimul.com

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

class MyCustomExtractor : ExtractorApi() {
    override val name = "DesireMovies Extractor"
    override val mainUrl = "https://hubcloud.club" // উদাহরণ
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        // এখানে আপনার লিঙ্ক ডিকোড করার লজিক থাকবে
        return listOf(
            ExtractorLink(
                this.name,
                this.name,
                url, // আসল ভিডিও লিঙ্ক
                referer ?: "",
                Qualities.P1080.value
            )
        )
    }
}
