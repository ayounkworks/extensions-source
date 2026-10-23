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
import keiyoushi.utils.tryParse
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

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3) { it.host.endsWith("voratoon.com") || it.host.endsWith("voratoon.id") || it.host.endsWith("voratoon.co") }

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
        val image = card.selectFirst(
            """img[src*="/cover/"], img[data-src*="/cover/"], img[data-lazy-src*="/cover/"]""",
        ) ?: card.selectFirst("img")
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

        val description = findDescription(document)
        val author = readLabeledValue(document, "Author", "Penulis", "Mangaka")
            ?: document.selectFirst("meta[name=author], meta[property=book:author], [itemprop=author]")
                ?.let { cleanMetadataValue(if (it.tagName() == "meta") it.attr("content") else it.text()) }
        val artist = readLabeledValue(document, "Artist", "Artis", "Illustrator", "Ilustrator")
            ?: document.selectFirst("[itemprop=artist], [class*=artist]")
                ?.let { cleanMetadataValue(it.text()) }

        val statusText = readLabeledValue(document, "Status")
            ?: document.selectFirst("[data-status]")?.attr("data-status")
        val status = when (statusText?.lowercase()?.trim()) {
            "ongoing", "on going", "berjalan" -> SManga.ONGOING
            "completed", "complete", "tamat", "selesai" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "cancelled", "canceled", "cancel" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }

        val genres = parseGenres(document).toMutableList()
        val format = readLabeledValue(document, "Format", "Tipe")
            ?.lowercase()
            ?.replaceFirstChar { it.titlecase() }
        if (!format.isNullOrBlank() && genres.none { it.equals(format, ignoreCase = true) }) {
            genres.add(format)
        }

        return SManga.create().apply {
            url = manga.url
            this.title = title
            thumbnail_url = cover
            this.author = author
            this.artist = artist
            this.description = description
            this.genre = genres.distinct().joinToString(", ")
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
                val text = element.selectFirst("[class*=chapter-title], [class*=chapter_name]")?.text()
                    ?.takeIf { it.isNotBlank() }
                    ?: element.text()
                val name = cleanChapterName(text, indexString)

                SChapter.create().apply {
                    url = prefix + indexString
                    this.name = name
                    chapter_number = index
                    date_upload = parseChapterDate(element)
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
            .replace("\\u0026", "&")
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

    private fun findDescription(document: Document): String? {
        val selectors = listOf(
            ".synopsis",
            "[class*=synopsis]",
            "[id*=synopsis]",
            "[itemprop=description]",
            "[class*=story-description]",
            "[class*=series-description]",
            "[class*=description]",
            "[id*=description]",
            "[class*=summary]",
            "[id*=summary]",
        )

        for (selector in selectors) {
            val value = document.select(selector)
                .asSequence()
                .mapNotNull { cleanDescription(it.text()) }
                .sortedBy { it.length }
                .firstOrNull()
            if (value != null) return value
        }

        val openGraphDescription = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?.let(::cleanDescription)
        if (openGraphDescription != null) return openGraphDescription

        val paragraph = document.select("p")
            .mapNotNull { cleanDescription(it.text()) }
            .firstOrNull()
        if (paragraph != null) return paragraph

        return document.selectFirst("meta[name=description]")?.attr("content")
            ?.let(::cleanDescription)
    }

    private fun cleanDescription(value: String?): String? {
        val text = value?.replace(Regex("""\s+"""), " ")?.trim() ?: return null
        if (text.length !in 20..1800) return null
        if (text.startsWith("Baca manga, manhwa, manhua", ignoreCase = true)) return null
        if (text.contains("© 2026 VoraToon", ignoreCase = true)) return null
        if (text.equals("No description", ignoreCase = true)) return null
        return text
    }

    private fun parseGenres(document: Document): List<String> {
        val linkedGenres = document.select("a[href*=genre]")
            .mapNotNull { element ->
                val href = element.absUrl("href").ifEmpty { element.attr("href") }
                val value = cleanMetadataValue(element.text()) ?: return@mapNotNull null
                val isGenreLink = href.contains("genre=", ignoreCase = true) ||
                    Regex("""/genres?/[^/?#]+""", RegexOption.IGNORE_CASE).containsMatchIn(href)
                if (!isGenreLink || value.length > 35) return@mapNotNull null
                value
            }
            .distinct()

        if (linkedGenres.isNotEmpty()) return linkedGenres

        return readLabeledValue(document, "Genres", "Genre")
            ?.split(Regex("""[,/|•]"""))
            ?.mapNotNull(::cleanMetadataValue)
            ?.distinct()
            ?: emptyList()
    }

    private fun readLabeledValue(document: Document, vararg labels: String): String? {
        val alternatives = labels.joinToString("|") { Regex.escape(it) }
        val inlinePattern = Regex("""(?i)^(?:$alternatives)\s*(?:[:：–—-]\s*|\s+)(.+?)\s*$""")
        val exactPattern = Regex("""(?i)^(?:$alternatives)\s*[:：–—-]?\s*$""")
        val elements = document.getAllElements()

        for (element in elements) {
            if (element.childrenSize() > 3) continue
            val directText = normalizeLabelText(element.ownText())
            val match = inlinePattern.matchEntire(directText) ?: continue
            val value = cleanMetadataValue(match.groupValues[1])
            if (value != null) return value
        }

        for (element in elements) {
            val directText = normalizeLabelText(element.ownText())
            if (!exactPattern.matches(directText)) continue

            val candidates = mutableListOf<String>()
            element.nextElementSibling()?.let { candidates.add(it.text()) }
            element.children().forEach { child -> candidates.add(child.text()) }

            val parent = element.parent()
            if (parent != null) {
                val siblings = parent.children()
                val index = siblings.indexOf(element)
                if (index >= 0) {
                    for (siblingIndex in (index + 1) until siblings.size()) {
                        candidates.add(siblings[siblingIndex].text())
                    }
                }
            }

            val value = candidates.asSequence()
                .mapNotNull(::cleanMetadataValue)
                .firstOrNull { !exactPattern.matches(normalizeLabelText(it)) }
            if (value != null) return value
        }

        return null
    }

    private fun normalizeLabelText(value: String): String =
        value.trim().replace(Regex("""^[^\p{L}\p{N}]+"""), "").trim()

    private fun cleanMetadataValue(value: String?): String? {
        val text = value?.replace(Regex("""\s+"""), " ")?.trim()
            ?.trim(':', '-', '–', '—', '·')
            ?: return null
        if (text.isBlank()) return null
        if (text.equals("unknown", ignoreCase = true) || text.equals("n/a", ignoreCase = true)) return null
        if (text == "-") return null
        return text
    }

    private fun cleanChapterName(raw: String, indexString: String): String {
        val formattedIndex = indexString.removeSuffix(".0")
        val normalized = raw.replace(Regex("""\s+"""), " ").trim()
        val chapterPrefix = Regex("""(?i)^\s*(?:chapter|ch\.?)\s*\d+(?:\.\d+)?""")
        var titleSuffix = normalized.replaceFirst(chapterPrefix, "").trim()

        val relativeDate = RELATIVE_DATE_REGEX.find(titleSuffix)
        if (relativeDate != null) {
            titleSuffix = titleSuffix.substring(0, relativeDate.range.first)
            titleSuffix = titleSuffix.replace(Regex("""(?i)\s*\d+(?:[.,]\d+)?\s*[KMB]?\s*$"""), "")
            titleSuffix = titleSuffix.replace(Regex("""(?i)\s*(?:UP|NEW)\s*$"""), "")
        }

        titleSuffix = titleSuffix.trim().trimStart('—', '-', ':', '|', '·').trim()
        val baseName = "Chapter $formattedIndex"
        return if (titleSuffix.isBlank()) baseName else "$baseName: $titleSuffix"
    }

    private fun parseChapterDate(element: Element): Long {
        val dateElements = element.select("time[datetime], time[title], [data-timestamp], [data-date]")
        for (dateElement in dateElements) {
            for (attribute in listOf("datetime", "data-timestamp", "data-date", "title")) {
                val value = dateElement.attr(attribute).trim()
                if (value.isBlank()) continue
                val numeric = value.toLongOrNull()
                if (numeric != null && numeric > 0L) {
                    return if (numeric > 1_000_000_000_000L) numeric else numeric * 1000L
                }
                val parsed = runCatching { kotlin.time.Instant.tryParse(value) }.getOrDefault(0L)
                if (parsed > 0L) return parsed
            }
        }

        val text = element.text().replace(Regex("""\s+"""), " ").trim()
        if (Regex("""(?i)\b(?:today|hari ini|just now|baru saja)\b""").containsMatchIn(text)) {
            return System.currentTimeMillis()
        }

        val relative = RELATIVE_DATE_REGEX.find(text) ?: return 0L
        val amount = relative.groupValues[1].toLongOrNull() ?: return 0L
        val unit = relative.groupValues[2].lowercase()
        val milliseconds = when {
            unit.startsWith("detik") || unit.startsWith("second") -> 1_000L
            unit.startsWith("menit") || unit.startsWith("minute") -> 60_000L
            unit.startsWith("jam") || unit.startsWith("hour") -> 3_600_000L
            unit.startsWith("hari") || unit.startsWith("day") -> 86_400_000L
            unit.startsWith("minggu") || unit.startsWith("week") -> 604_800_000L
            unit.startsWith("bulan") || unit.startsWith("month") -> 2_592_000_000L
            else -> 31_536_000_000L
        }
        return (System.currentTimeMillis() - amount * milliseconds).coerceAtLeast(1L)
    }

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

    private fun cleanUrl(url: String): String = url.replace("\\u0026", "&")
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
        private val RELATIVE_DATE_REGEX = Regex(
            """(?i)(\d{1,3})\s*(seconds?|minutes?|hours?|days?|weeks?|months?|years?|detik|menit|jam|hari|minggu|bulan|tahun)\s*(?:ago|lalu)?\s*$""",
        )
        private val IMAGE_EXTENSION_REGEX = Regex(
            """\.(?:jpe?g|png|webp|avif)(?:[?#].*)?$""",
            RegexOption.IGNORE_CASE,
        )
    }
}
