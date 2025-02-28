package ani.saikou.parsers.anime

import ani.saikou.FileUrl
import ani.saikou.client
import ani.saikou.levenshtein
import ani.saikou.media.Media
import ani.saikou.parsers.*
import com.fasterxml.jackson.annotation.JsonProperty

class Kamyroll : AnimeParser() {

    override val name: String = "Kamyroll"
    override val saveName: String = "kamy_roll"
    override val hostUrl: String = apiUrl
    override val isDubAvailableSeparately: Boolean = false

    override suspend fun loadEpisodes(animeLink: String, extra: Map<String, String>?): List<Episode> {
        return if (extra?.get("type") == "series") {
            val idHeader = "id" to animeLink
            val filter = extra["filter"]
            val eps = client.get(
                "$hostUrl/content/v1/seasons",
                getHeaders(),
                params = if (filter == null) mapOf(channelHeader, localeHeader, idHeader)
                else mapOf(channelHeader, localeHeader, idHeader, "filter" to filter),
                timeout = 100
            ).parsed<EpisodesResponse>()

            data class Temp(
                val type: String,
                val thumb: String?,
                val title: String?,
                val description: String?,
                val series: MutableMap<String, String> = mutableMapOf()
            )

            val epMap = mutableMapOf<Long, Temp>()
            val dataList = (eps.items ?: return listOf()).mapNotNull { item ->
                val tit = item.title ?: return@mapNotNull null
                (item.episodes ?: return@mapNotNull null).map {
                    Pair(
                        it.sequenceNumber,
                        Temp(
                            it.type,
                            it.images?.thumbnail?.getOrNull(6)?.source,
                            it.title,
                            it.description,
                            mutableMapOf(tit to it.id)
                        )
                    )
                }
            }.flatten()
            dataList.forEach {
                epMap[it.first] = epMap[it.first] ?: it.second
                epMap[it.first]?.series?.putAll(it.second.series)
            }
            epMap.map {
                if (it.value.thumb != null)
                    Episode(it.key.toString(),it.value.type,it.value.title,it.value.thumb!!,it.value.description, false,it.value.series.toMap())
                else
                    Episode(it.key.toString(), it.value.type, extra = it.value.series.toMap())
            }
        } else {
            val eps = client.get(
                "$hostUrl/content/v1/movies",
                getHeaders(),
                params = mapOf(
                    channelHeader,
                    localeHeader,
                    "id" to animeLink
                ),
                timeout = 100
            ).parsed<MovieResponse>()
            val ep = eps.items?.sortedByDescending { it.duration }?.get(0)?.let {
                val thumb = it.images?.thumbnail?.getOrNull(5)?.source
                if (thumb != null) Episode("1", it.id, thumbnail = thumb)
                else Episode("1", it.id)
            } ?: return listOf()
            listOf(ep)
        }
    }

    override suspend fun loadVideoServers(episodeLink: String, extra: Any?): List<VideoServer> {
        return if (extra is Map<*, *>) {
            extra.map {
                VideoServer(it.key.toString(), it.value.toString())
            }
        } else {
            listOf(VideoServer(channel, episodeLink))
        }
    }

    override suspend fun getVideoExtractor(server: VideoServer): VideoExtractor = KamyrollExtractor(server)

    class KamyrollExtractor(override val server: VideoServer) : VideoExtractor() {
        override suspend fun extract(): VideoContainer {
            val eps = client.get(
                "$apiUrl/videos/v1/streams",
                getHeaders(),
                params = mapOf(
                    channelHeader,
                    "id" to server.embed.url,
                    localeHeader,
                    "type" to "adaptive_hls",
                    "format" to "vtt",
                    "service" to service,
                ),
                timeout = 60
            ).parsed<StreamsResponse>()

            var foundSub = false
            val link = FileUrl(
                eps.streams?.find {
                    it.hardsubLocale == locale
                }?.url ?: eps.streams?.find {
                    it.hardsubLocale == ""
                }?.also { foundSub = true }?.url ?: return VideoContainer(listOf()),
                mapOf("accept" to "*/*")
            )
            val vid = listOf(Video(null, true, link))
            val subtitle = if (foundSub) eps.subtitles?.find { it.locale == locale || it.locale == "en-GB" }
                .let { listOf(Subtitle("English", it?.url ?: return@let null, "ass")) } else null
            return VideoContainer(vid, subtitle ?: listOf())
        }

        private data class StreamsResponse(
            val subtitles: List<Subtitle>? = null,
            val streams: List<Stream>? = null
        ) {
            data class Stream(
                @JsonProperty("hardsub_locale")
                val hardsubLocale: String? = null,
                val url: String? = null
            )

            data class Subtitle(
                val locale: String? = null,
                val url: String? = null,
                val format: String? = null
            )
        }
    }

    override suspend fun autoSearch(mediaObj: Media): ShowResponse? {
        var response = loadSavedShowResponse(mediaObj.id)
        if (response != null) {
            saveShowResponse(mediaObj.id, response, true)
        } else {
            response = if (mediaObj.crunchySlug != null || mediaObj.vrvId != null) ShowResponse(
                "Automatically",
                mediaObj.vrvId ?: mediaObj.crunchySlug!!,
                "",
                extra = mapOf(
                    "type" to if (mediaObj.format == "TV") "series" else "",
                    "filter" to (mediaObj.alName())
                )
            ) else null
            if (response == null) {
                setUserText("Searching : ${mediaObj.mainName()}")
                response = search("$" + mediaObj.mainName()).let { if (it.isNotEmpty()) it[0] else null }
            }
            if (response == null) {
                setUserText("Searching : ${mediaObj.nameRomaji}")
                response = search("$" + mediaObj.nameRomaji).let { if (it.isNotEmpty()) it[0] else null }
            }
            saveShowResponse(mediaObj.id, response)
        }
        return response
    }

    override suspend fun search(query: String): List<ShowResponse> {
        val res = client.get(
            "$hostUrl/content/v1/search",
            getHeaders(),
            params = mapOf(
                channelHeader,
                localeHeader,
                "limit" to "25",
                "query" to query
            )
        ).parsed<SearchResponse>()
        return (res.items ?: listOf()).map { item ->
            val filter = if (query.startsWith("$")) query.substringAfter("$") else null
            item.items.map {
                val type = "type" to it.type
                ShowResponse(
                    name = it.title,
                    link = it.id,
                    coverUrl = it.images?.posterTall?.getOrNull(5)?.source ?: "",
                    extra = if (filter == null) mapOf(type) else mapOf(type, "filter" to filter)
                )
            }
        }.flatten().sortedBy { levenshtein(it.name, query) }
    }

    companion object {
        private const val apiUrl = "https://beta-kamyroll.herokuapp.com"
        private const val channel = "crunchyroll"
        private const val locale = "en-US"
        private const val service = "google"

        private var headers: Map<String, String>? = null
        private val channelHeader = "channel_id" to channel
        private val localeHeader = "locale" to locale

        suspend fun getHeaders(): Map<String, String> {
            headers = headers ?: let {
                val res = client.post(
                    "$apiUrl/auth/v1/token",
                    mapOf(
                        "authorization" to "Basic vrvluizpdr2eby+RjSKM17dOLacExxq1HAERdxQDO6+2pHvFHTKKnByPD7b6kZVe1dJXifb6SG5NWMz49ABgJA=="
                    ),
                    data = mapOf(
                        "refresh_token" to "BhETbpDeWFU9hh7awo640SIDo+Jwl7HgUiyoxnizZyldtMmlB4VmJGA4XH3v6ux3kgprIm2zLCQFhmTUjNvqF6Nw5bZk7dqYbu0QWxW5k8f8dtqcW2xiK1fCQpUdbwPFv9vr5WM3Jq3AlBB82127/iHN+Ndzo0msCYWrF94yrX86lsm3V8EESBYfXhdaTibJnBZlfFCvDtn66CtnBqlnIhJrn1LkSdeY5rm8QaqOyS0r75KfOwCEVBv/vZ1VgU76fWzjm4DlSPtDjRTS0UUn2BuA/by0xTaV6H3RJxw13kIdp3hraoNPk79A4NpCgc7PNJ+9+P9kSU4eq07P0o+WEw==",
                        "grant_type" to "refresh_token",
                        "scope" to "offline_access",
                    )
                ).parsed<AccessToken>()
                mapOf("authorization" to "${res.tokenType} ${res.accessToken}")
            }
            return headers!!
        }

        private data class AccessToken(
            @JsonProperty("access_token")
            val accessToken: String,
            @JsonProperty("token_type")
            val tokenType: String,
        )
    }

    private data class MovieResponse(
        val items: List<KamyEpisode>? = null,
    )

    private data class EpisodesResponse(
        val total: Long? = null,
        val items: List<Item>? = null
    ) {
        data class Item(
            val title: String? = null,

            @JsonProperty("season_number")
            val seasonNumber: Long? = null,

            @JsonProperty("episode_count")
            val episodeCount: Long? = null,

            val episodes: List<KamyEpisode>? = null
        )
    }

    data class KamyEpisode(
        val id: String,
        val type: String,

        @JsonProperty("season_number")
        val seasonNumber: Long? = null,

        val episode: String? = null,

        @JsonProperty("sequence_number")
        val sequenceNumber: Long,

        val title: String? = null,
        val description: String? = null,

        @JsonProperty("is_subbed")
        val isSubbed: Boolean? = null,

        @JsonProperty("is_dubbed")
        val isDubbed: Boolean? = null,

        val images: Images? = null,

        @JsonProperty("duration_ms")
        val duration: Long? = null,
    ) {
        data class Images(
            val thumbnail: List<Thumbnail>? = null
        )

        data class Thumbnail(
            val width: Long? = null,
            val height: Long? = null,
            val source: String? = null
        )
    }

    private data class SearchResponse(
        val total: Long? = null,
        val items: List<ResponseItem>? = null
    ) {
        data class ResponseItem(val items: List<ItemItem>)

        data class ItemItem(
            val id: String,
            @JsonProperty("media_type")
            val type: String,
            val title: String,
            val images: Images? = null,
        )

        data class Images(
            @JsonProperty("poster_tall")
            val posterTall: List<PosterTall>
        )

        data class PosterTall(
            val source: String,
        )
    }
}