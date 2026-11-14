package eu.kanade.tachiyomi.extension.id.voratoon

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Source
abstract class VoraToon : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = apply {
        rateLimit(3)
    }

    private fun rscHeaders(referer: String? = null): Headers = headers.newBuilder()
        .set("RSC", "1")
        .set("Accept", "*/*")
        .set("Sec-Fetch-Dest", "empty")
        .set("Sec-Fetch-Mode", "cors")
        .set("Sec-Fetch-Site", "same-origin")
        .apply {
            if (referer != null) set("Referer", referer)
        }
        .build()

    override suspend fun getPopularManga(page: Int): MangasPage = parseSeriesList(
        "$baseUrl/browse".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("sort", "popular")
            .build(),
    )

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseSeriesList(
        "$baseUrl/browse".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("sort", "new")
            .build(),
    )

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/browse".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            if (query.isNotEmpty()) {
                addQueryParameter("q", query)
            }
            filters.filterIsInstance<UriQueryFilter>().forEach {
                it.addToQuery(this)
            }
        }.build()

        return parseSeriesList(url)
    }

    private suspend fun parseSeriesList(url: HttpUrl): MangasPage {
        val dto = client.get(url, rscHeaders()).extractNextJs<BrowseDto> {
            it is JsonObject && "series" in it && "seriesMeta" in it
        } ?: return MangasPage(emptyList(), false)

        val mangas = dto.series.map { it.toSManga() }
        val hasNextPage = dto.seriesMeta?.let { (it.page ?: 0) < (it.lastPage ?: 0) } ?: false
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (!fetchDetails && !fetchChapters) {
            return SMangaUpdate(manga, chapters)
        }

        val slug = manga.getSlug(baseUrl)
        val dto = client.get("$baseUrl/series/$slug", rscHeaders()).extractNextJs<SeriesDetailsDto> {
            it is JsonObject && "series" in it && "chapters" in it
        } ?: return SMangaUpdate(manga, chapters)

        val updatedManga = if (fetchDetails) {
            dto.series.firstOrNull()?.toSManga() ?: manga
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            dto.chapters.map { it.toSChapter(slug) }
        } else {
            chapters
        }

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        if (url.pathSegments.getOrNull(0) != "series") return null
        val slug = url.pathSegments.getOrNull(1) ?: return null
        val manga = SManga.create().apply {
            this.url = "/series/$slug"
        }
        return getMangaUpdate(
            manga,
            emptyList(),
            fetchDetails = true,
            fetchChapters = false,
        ).manga
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val (slug, chapterIndex) = chapter.getSlugAndIndex(baseUrl)
        val chapterUrl = "$baseUrl/series/$slug/chapter/$chapterIndex"
        val seriesUrl = "$baseUrl/series/$slug"
        val chapterDtoPredicate: (JsonElement) -> Boolean = {
            it is JsonObject && "chapter" in it
        }

        // Try the Next.js RSC response first, matching the site's XHR request pattern.
        val rscDto = try {
            client.get(chapterUrl, rscHeaders(seriesUrl)).extractNextJs<ChapterPageDto>(chapterDtoPredicate)
        } catch (_: Exception) {
            null
        }

        // Some site/CDN responses serve a normal HTML document or reject the RSC request.
        // The HTML extractor is supported by extractNextJs too, so fall back to a browser-like page request.
        val documentHeaders = headers.newBuilder()
            .set("Referer", seriesUrl)
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .set("Sec-Fetch-Dest", "document")
            .set("Sec-Fetch-Mode", "navigate")
            .set("Sec-Fetch-Site", "same-origin")
            .set("Upgrade-Insecure-Requests", "1")
            .build()

        val dto = rscDto ?: try {
            client.get(chapterUrl, documentHeaders).extractNextJs<ChapterPageDto>(chapterDtoPredicate)
        } catch (_: Exception) {
            null
        } ?: error("Failed to extract chapter pages from both RSC and HTML responses")

        return dto.chapter.toPageList(chapterUrl)
    }

    override fun imageRequest(page: Page): Request = Request.Builder()
        .url(page.imageUrl!!)
        .headers(
            headers.newBuilder()
                .set("Referer", page.url)
                .build(),
        )
        .get()
        .build()

    override val supportsFilterFetching: Boolean get() = true

    override suspend fun fetchFilterData(): JsonElement {
        val dto = client.get("$baseUrl/browse", rscHeaders()).extractNextJs<FilterPayloadDto> {
            it is JsonObject && "genres" in it
        } ?: error("Failed to fetch filter data")
        return dto.genres.toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val genres = data?.parseAs<List<GenreItem>>()?.map { it.toPair() }
        return FilterList(
            buildList {
                add(SortFilter())
                add(StatusFilter())
                add(FormatFilter())
                add(TypeFilter())
                add(AdaptationFilter())
                add(MinChapterFilter())
                if (!genres.isNullOrEmpty()) {
                    add(GenreFilter(genres.toTypedArray()))
                }
            },
        )
    }
}
