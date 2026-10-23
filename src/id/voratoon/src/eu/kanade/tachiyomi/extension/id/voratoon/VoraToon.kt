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
import keiyoushi.utils.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class VoraToon : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3) { it.host.endsWith("voratoon.com") || it.host.endsWith("voratoon.id") }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        set("Accept-Language", "en-US,en;q=0.9,id;q=0.8")
        set("Referer", "$baseUrl/")
        set("User-Agent", USER_AGENT)
    }

    override val supportsFilterFetching: Boolean get() = false

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/browse".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "popularity")
            .addQueryParameter("sortOrder", "desc")
            .addQueryParameter("page", page.toString())
            .build()

        return parseSeriesList(client.get(url.toString()).asJsoup(), page)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/updates".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .build()

        return parseSeriesList(client.get(url.toString()).asJsoup(), page)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return getPopularManga(page)

        val url = "$baseUrl/browse".toHttpUrl().newBuilder()
            .addQueryParameter("search", query)
            .addQueryParameter("page", page.toString())
            .build()

        return parseSeriesList(client.get(url.toString()).asJsoup(), page)
    }

    private fun parseSeriesList(document: Document, page: Int): MangasPage {
        val cards = document.select("article")
        val candidates = if (cards.isNotEmpty()) {
            cards.mapNotNull { card ->
                val link = card.select("a[href*=/series/]")
                    .firstOrNull { seriesSlug(it.attr("href")) != null }
                    ?: return@mapNotNull null
                parseSeriesCard(link, card)
            }
        } else {
            document.select("a[href*=/series/]")
                .mapNotNull { link ->
                    if (seriesSlug(link.attr("href")) == null) return@mapNotNull null
                    parseSeriesCard(link, link)
                }
        }

        val mangas = candidates.distinctBy { it.url }
        val nextPage = page + 1
        val hasNextPage = document.select("a[rel=next], a[aria-label*=Next], a[href*=page]")
            .any { link ->
                val href = link.absUrl("href").ifEmpty { link.attr("href") }
                val targetPage = href.toHttpUrlOrNull()?.queryParameter("page")?.toIntOrNull()
                targetPage == nextPage || link.attr("rel").equals("next", true)
            }

        return MangasPage(mangas, hasNextPage)
    }

    private fun parseSeriesCard(link: Element, card: Element): SManga? {
        val slug = seriesSlug(link.attr("href")) ?: return null
        val image = card.selectFirst("img")
        val title = card.selectFirst("a[class*=title]")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: image?.attr("alt")?.removePrefix("Cover ")?.trim()?.takeIf { it.isNotBlank() }
            ?: slug.replace('-', ' ').replaceFirstChar { it.uppercase() }

        return SManga.create().apply {
            url = "/series/$slug"
            this.title = title
            thumbnail_url = image?.let(::imageUrl)
            initialized = true
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val slug = url.pathSegments.getOrNull(1)
            ?.takeIf { url.pathSegments.firstOrNull() == "series" }
            ?: return null

        val manga = SManga.create().apply {
            this.url = "/series/$slug"
        }
        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (!fetchDetails && !fetchChapters) return SMangaUpdate(manga, chapters)

        val document = client.get(getMangaUrl(manga)).asJsoup()
        val slug = seriesSlug(manga.url) ?: manga.url.substringAfterLast('/')

        val updatedManga = if (fetchDetails) parseMangaDetails(document, manga) else manga
        val updatedChapters = if (fetchChapters) parseChapters(document, slug) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(document: Document, manga: SManga): SManga {
        val title = document.selectFirst("h1")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore("|")?.removePrefix("Baca")?.trim()
            ?: manga.title

        val cover = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.startsWith("http") }
            ?: document.select("img[src], img[data-src], img[data-lazy-src]")
                .firstOrNull { imageUrl(it)?.contains("/cover/") == true }
                ?.let(::imageUrl)

        val description = document.selectFirst(".synopsis")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[name=description]")?.attr("content")
                ?.takeIf { it.isNotBlank() }

        val fullText = document.text().lowercase()
        val status = when {
            "completed" in fullText || "complete" in fullText || "tamat" in fullText -> SManga.COMPLETED
            "hiatus" in fullText -> SManga.ON_HIATUS
            "ongoing" in fullText -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }

        return SManga.create().apply {
            url = manga.url
            this.title = title
            thumbnail_url = cover
            this.description = description
            this.status = status
            initialized = true
        }
    }

    private fun parseChapters(document: Document, slug: String): List<SChapter> {
        val prefix = "/series/$slug/chapter/"
        return document.select("a[href*=/series/$slug/chapter/]")
            .mapNotNull { element ->
                val absoluteUrl = element.absUrl("href").ifEmpty { element.attr("href") }
                val path = absoluteUrl.toHttpUrlOrNull()?.encodedPath ?: absoluteUrl.substringBefore('?')
                if (!path.startsWith(prefix)) return@mapNotNull null

                val indexString = path.removePrefix(prefix).trimEnd('/')
                val index = indexString.toFloatOrNull() ?: return@mapNotNull null
                val name = element.text().trim().ifEmpty {
                    "Chapter ${indexString.removeSuffix(".0")}"
                }

                SChapter.create().apply {
                    url = prefix + indexString
                    this.name = name
                    chapter_number = index
                    date_upload = 0L
                }
            }
            .distinctBy { it.url }
            .sortedByDescending { it.chapter_number }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val (slug, chapterIndex) = chapter.getSlugAndIndex(baseUrl)
        val url = "$baseUrl/series/$slug/chapter/$chapterIndex"
        val document = client.get(url).asJsoup()
        val candidates = linkedSetOf<String>()

        document.select("img[src], img[data-src], img[data-lazy-src], img[data-original]")
            .mapNotNullTo(candidates) { imageUrl(it) }

        val embeddedHtml = document.html()
            .replace("\\/", "/")
        url.replace("\\u0026", "&")
            .replace("&amp;", "&")
        READER_IMAGE_REGEX.findAll(embeddedHtml)
            .map { cleanUrl(it.value) }
            .filter(::isReaderImage)
            .forEach(candidates::add)

        return candidates
            .filter(::isReaderImage)
            .distinct()
            .mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }

    override fun imageRequest(page: Page): Request = Request.Builder()
        .url(page.imageUrl!!)
        .headers(
            headersBuilder()
                .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                .set("Referer", "$baseUrl/")
                .build(),
        )
        .get()
        .build()

    private fun imageUrl(element: Element): String? {
        for (attribute in listOf("data-src", "data-lazy-src", "data-original", "src")) {
            val raw = element.attr(attribute).trim()
            if (raw.isBlank() || raw.startsWith("data:")) continue
            val absolute = element.absUrl(attribute).ifEmpty {
                if (raw.startsWith("//")) {
                    "https:$raw"
                } else if (raw.startsWith("/")) {
                    "$baseUrl$raw"
                } else {
                    raw
                }
            }
            if (absolute.startsWith("http")) return cleanUrl(absolute)
        }
        return null
    }

    private fun seriesSlug(href: String): String? = SERIES_LINK_REGEX.find(href.substringBefore('?').substringBefore('#').trimEnd('/'))
        ?.groupValues?.getOrNull(1)

    private fun isReaderImage(url: String): Boolean = url.contains("voratoon", ignoreCase = true) &&
        IMAGE_EXTENSION_REGEX.containsMatchIn(url) &&
        !url.contains("/cover/", ignoreCase = true) &&
        !url.contains("logo", ignoreCase = true) &&
        !url.contains("avatar", ignoreCase = true) &&
        !url.contains("banner", ignoreCase = true)

    private fun cleanUrl(url: String): String =
        url.replace("\\u0026", "&")
            .replace("&amp;", "&")
            .replace("\\/", "/")
            .trimEnd('\\', '"', '\'', ',', ')')

    private companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

        private val SERIES_LINK_REGEX = Regex(""".*/series/([A-Za-z0-9_-]+)$""")
        private val READER_IMAGE_REGEX = Regex(
            """https?://[^"'\s<>]+""",
            RegexOption.IGNORE_CASE,
        )
        private val IMAGE_EXTENSION_REGEX = Regex(
            """\.(?:jpe?g|png|webp|avif)(?:[?#].*)?$""",
            RegexOption.IGNORE_CASE,
        )
    }
}
