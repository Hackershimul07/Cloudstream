package shimul.com

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DesireMoviesPlugin: Plugin() {
    override fun load(context: Context) {
        // এখানে আপনার প্রোভাইডার ক্লাসটি রেজিস্টার করতে হয়
        registerMainAPI(DesireMoviesProvider())
        
        // যদি আলাদা কোনো এক্সট্রাক্টর থাকে তাও এখানে রেজিস্টার করা যায়
        // registerExtractorAPI(MyCustomExtractor())
    }
}
