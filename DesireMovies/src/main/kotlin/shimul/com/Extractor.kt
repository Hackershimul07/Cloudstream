package shimul.com

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class MyCustomExtractor : ExtractorApi() {
    override val name = "DesireMovies Extractor"
    override val mainUrl = "https://hubcloud.club" // উদাহরণ
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        // এখানে আপনার লিঙ্ক ডিকোড করার লজিক থাকবে
        return listOf(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = url // আসল ভিডিও লিঙ্ক
            ) {
                this.referer = referer ?: ""
                this.quality = Qualities.P1080.value
            }
        )
    }
}
