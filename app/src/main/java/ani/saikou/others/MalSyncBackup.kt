package ani.saikou.others

import ani.saikou.client
import ani.saikou.parsers.ShowResponse
import ani.saikou.tryWithSuspend
import com.fasterxml.jackson.annotation.JsonProperty

object MalSyncBackup {
    data class MalBackUpSync(
        @JsonProperty("Pages")
        val pages: Map<String, Map<String, Page>>? = null
    )

    data class Page(
        val identifier: String,
        val title: String,
        val url: String? = null,
        val image: String? = null,
        val active: Boolean? = null,
    )

    suspend fun get(id: Int, name: String, dub: Boolean = false): ShowResponse? {
        return tryWithSuspend {
            val json =
                client.get("https://raw.githubusercontent.com/MALSync/MAL-Sync-Backup/master/data/anilist/anime/$id.json")
            if (json.text != "404: Not Found")
                json.parsed<MalBackUpSync>().pages?.get(name)?.forEach {
                    val page = it.value
                    val isDub = page.title.lowercase().replace(" ", "").endsWith("(dub)")
                    val slug = if (dub == isDub) page.identifier else null
                    if (slug != null && page.active == true) {
                        return@tryWithSuspend ShowResponse(page.title, slug, page.image ?: "")
                    }
                }
            return@tryWithSuspend null
        }
    }
}