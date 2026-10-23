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
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class VoraToon : KeiSource() {

    private val baseHost get() = baseUrl.toHttpUrl().host

    private val apiUrl get() = "https://$baseHost/api/proxy"

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        addInterceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)

            if (response.code == 403 &&
                request.url.host == baseHost &&
                request.url.encodedPath.contains("/api/")
            ) {
                response.close()
                // Visiting the homepage refreshes the vt_proxy cookie before retrying the API call.
                chain.proceed(
                    request.newBuilder()
                        .url(baseUrl.toHttpUrl())
                        .headers(headersBuilder().removeAll("Accept").build())
                        .build(),
                ).close()

                chain.proceed(request)
            } else {
                response
            }
        }
        rateLimit(3) {
            it.host.endsWith("voratoon.com") ||
                it.host.endsWith("voratoon.id") ||
                it.host.endsWith("voratoon.co") ||
                it.encodedPath.contains("/api/")
        }
    }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        set("Accept-Language", "en-US,en;q=0.9,id;q=0.8")
        set("Referer", "$baseUrl/")
        set("User-Agent", USER_AGENT)
    }

    private fun apiHeaders(): Headers = headersBuilder()
        .set("Accept", "application/json")
        .set("sec-fetch-site", "same-origin")
        .build()

    override val supportsFilterFetching: Boolean get() = true

    override suspend fun fetchFilterData(): JsonElement = runCatching {
        client.get("$apiUrl/genres", apiHeaders()).parseAs<JsonElement>()
    }.getOrDefault(JsonNull)

    override fun getFilterList(data: JsonElement?): FilterList {
        val genres = runCatching {
            data?.parseAs<GenreResponse>()?.data?.map { it.toPair() }
        }.getOrNull()

        return FilterList(
            buildList {
                add(SortFilter())
                add(SortOrderFilter())
                add(StatusFilter())
                add(FormatFilter())
                add(TypeFilter())
                if (!genres.isNullOrEmpty()) {
                    add(GenreFilter(genres.toTypedArray()))
                }
            },
        )
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val apiRequest = "$apiUrl/series".toHttpUrl().newBuilder()
            .addQueryParameter("includeMeta", "true")
            .addQueryParameter("take", "30")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("sort", "popularity")
            .addQueryParameter("sortOrder", "desc")
            .build()

        parseApiSeriesList(apiRequest)?.let { return it }

        val htmlRequest = "$baseUrl/browse".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "popularity")
            .addQueryParameter("sortOrder", "desc")
            .addQueryParameter("page", page.toString())
            .build()
        return parseSeriesList(client.get(htmlRequest.toString()).asJsoup(), page)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val apiRequest = "$apiUrl/series".toHttpUrl().newBuilder()
            .addQueryParameter("includeMeta", "true")
            .addQueryParameter("take", "30")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("sort", "latest")
            .addQueryParameter("sortOrder", "desc")
            .build()

        parseApiSeriesList(apiRequest)?.let { return it }

        val htmlRequest = "$baseUrl/updates".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .build()
        return parseSeriesList(client.get(htmlRequest.toString()).asJsoup(), page)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val apiRequest = "$apiUrl/series".toHttpUrl().newBuilder()
            .addQueryParameter("includeMeta", "true")
            .addQueryParameter("take", "30")
            .addQueryParameter("page", page.toString())
            .apply {
                if (query.isNotBlank()) addQueryParameter("title", query)
                val filterBuilder = StringBuilder()
                filters.filterIsInstance<UriFilter>().forEach { it.addToFilter(filterBuilder) }
                if (filterBuilder.isNotEmpty()) addQueryParameter("filter", filterBuilder.toString())
                filters.filterIsInstance<UriQueryFilter>().forEach { it.addToQuery(this) }
            }
            .build()

        parseApiSeriesList(apiRequest)?.let { return it }

        val htmlRequest = "$baseUrl/browse".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .apply {
                if (query.isNotBlank()) addQueryParameter("search", query)
                val filterBuilder = StringBuilder()
                filters.filterIsInstance<UriFilter>().forEach { it.addToFilter(filterBuilder) }
                if (filterBuilder.isNotEmpty()) addQueryParameter("filter", filterBuilder.toString())
                filters.filterIsInstance<UriQueryFilter>().forEach { it.addToQuery(this) }
            }
            .build()
        return parseSeriesList(client.get(htmlRequest.toString()).asJsoup(), page)
    }

    private suspend fun parseApiSeriesList(url: HttpUrl): MangasPage? = runCatching {
        parseSeriesListResponse(client.get(url, apiHeaders()))
    }.getOrNull()

    private fun parseSeriesListResponse(response: Response): MangasPage {
        val result = response.parseAs<SeriesListResponse>()
        val hasNextPage = result.meta?.let { (it.page ?: 0) < (it.lastPage ?: 0) } ?: false
        return MangasPage(result.data.map { it.toSManga() }, hasNextPage)
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
        val images = card.select(
            "img[src], img[data-src], img[data-lazy-src], img[data-original], img[data-original-src], img[data-srcset], img[srcset]",
        )
        val image = images.firstOrNull { element ->
            val candidate = imageUrl(element) ?: return@firstOrNull false
            isCoverImage(element, candidate)
        } ?: images.firstOrNull { element ->
            imageUrl(element) != null
        }
        val title = card.selectFirst("a[class*=title]")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: card.selectFirst("h1, h2, h3, h4, [class*=series-title], [class*=manga-title]")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: image?.attr("alt")?.removePrefix("Cover ")?.trim()?.takeIf { it.isNotBlank() }
            ?: link.text().replace(Regex("""\s+"""), " ").trim()
                .takeIf { it.isNotBlank() && it.length <= 180 }
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

        val slug = seriesSlug(manga.url) ?: manga.url.substringAfterLast('/')
        val apiManga = if (fetchDetails) fetchApiMangaDetails(slug) else null

        // Keep the HTML path as a fallback and for metadata that the API does not expose.
        val document = client.get(getMangaUrl(manga)).asJsoup()
        val htmlManga = if (fetchDetails) parseMangaDetails(document, manga) else manga
        val updatedManga = if (fetchDetails && apiManga != null) {
            mergeMangaDetails(apiManga, htmlManga, manga, document.location())
        } else {
            htmlManga
        }
        val updatedChapters = if (fetchChapters) parseChapters(document, slug) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private suspend fun fetchApiMangaDetails(slug: String): SManga? = runCatching {
        client.get("$apiUrl/series/$slug", apiHeaders())
            .parseAs<SeriesDetailResponse>()
            .data
            .toSManga()
    }.getOrNull()

    private fun parseMangaDetails(document: Document, manga: SManga): SManga {
        val title = document.selectFirst("h1")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore("|")?.removePrefix("Baca")?.trim()
            ?: manga.title

        val coverFromMetadata = document.select(
            "meta[property=og:image], meta[name=twitter:image], meta[property=twitter:image], meta[name=twitter:image:src]",
        ).asSequence()
            .mapNotNull { element ->
                absoluteImageUrl(element.attr("content"), document.location())
                    ?.takeUnless(::isPlaceholderImage)
            }
            .firstOrNull()

        val coverFromImages = document.select(
            "img[src], img[data-src], img[data-lazy-src], img[data-original], img[data-original-src], img[data-srcset], img[srcset]",
        ).firstNotNullOfOrNull { element ->
            val candidate = imageUrl(element) ?: return@firstNotNullOfOrNull null
            if (isPlaceholderImage(candidate)) return@firstNotNullOfOrNull null

            if (isCoverImage(element, candidate)) candidate else null
        }

        // Never overwrite a saved cover with null or a generic logo/placeholder when refreshing details.
        val savedCover = absoluteImageUrl(manga.thumbnail_url, document.location())
            ?.takeUnless(::isPlaceholderImage)
        val cover = coverFromMetadata ?: coverFromImages ?: savedCover

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

    private fun mergeMangaDetails(
        apiManga: SManga,
        htmlManga: SManga,
        original: SManga,
        pageUrl: String,
    ): SManga = SManga.create().apply {
        val apiCover = absoluteImageUrl(apiManga.thumbnail_url, pageUrl)?.takeUnless(::isPlaceholderImage)
        val htmlCover = absoluteImageUrl(htmlManga.thumbnail_url, pageUrl)?.takeUnless(::isPlaceholderImage)
        val savedCover = absoluteImageUrl(original.thumbnail_url, pageUrl)?.takeUnless(::isPlaceholderImage)

        url = original.url
        title = apiManga.title.takeIf { it.isNotBlank() } ?: htmlManga.title.ifBlank { original.title }
        thumbnail_url = apiCover ?: htmlCover ?: savedCover
        author = apiManga.author?.takeIf { it.isNotBlank() } ?: htmlManga.author ?: original.author
        artist = htmlManga.artist ?: original.artist
        description = apiManga.description?.takeIf { it.isNotBlank() } ?: htmlManga.description ?: original.description
        genre = apiManga.genre?.takeIf { it.isNotBlank() } ?: htmlManga.genre ?: original.genre
        status = apiManga.status.takeIf { it != SManga.UNKNOWN } ?: htmlManga.status
        initialized = true
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
        val emptyValuePattern = Regex("""(?i)^(?:$alternatives)\s*[-–—]\s*$""")

        for (element in document.getAllElements()) {
            if (element.childrenSize() > 3) continue
            val directText = normalizeLabelText(element.ownText())
            val match = inlinePattern.matchEntire(directText) ?: continue
            val value = cleanMetadataValue(match.groupValues[1])
            if (value != null) return value
        }

        for (element in document.getAllElements()) {
            val directText = normalizeLabelText(element.ownText())
            if (!exactPattern.matches(directText)) continue

            // A value nested inside the label container is safer than reading a larger parent.
            val nestedValue = element.children()
                .asSequence()
                .filter { it.childrenSize() <= 1 && it.select("a").isEmpty() }
                .mapNotNull { cleanMetadataValue(it.text()) }
                .firstOrNull { !exactPattern.matches(normalizeLabelText(it)) }
            if (nestedValue != null) return nestedValue

            val next = element.nextElementSibling()
            if (next != null) {
                val nextText = next.text().trim()
                val nextValue = cleanMetadataValue(nextText)
                val simpleAdjacentValue = next.childrenSize() <= 1 &&
                    next.select("a").isEmpty() &&
                    nextText.length <= 120
                if (simpleAdjacentValue && nextValue != null &&
                    !exactPattern.matches(normalizeLabelText(nextValue))
                ) {
                    return nextValue
                }

                // Do not accidentally treat the following genre/metadata block as this field's value.
                if (nextText.isNotBlank()) continue
            }

            // VoraToon sometimes renders an empty value as "Author-".
            if (emptyValuePattern.matches(directText)) continue
        }

        return null
    }

    private fun normalizeLabelText(value: String): String = value.trim().replace(Regex("""^[^\p{L}\p{N}]+"""), "").trim()

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

        // Use the chapter index from the link URL as the source of truth.
        // Some pages concatenate the chapter index, view count and update age:
        // "Chapter 11084121 jam lalu" => index 110 + views 8412 + "1 jam lalu".
        val titleWithoutChapterPrefix = normalized.replaceFirst(
            Regex("""(?i)^\s*(?:chapter|ch\.?)\s*"""),
            "",
        )
        val afterIndex = if (titleWithoutChapterPrefix.startsWith(formattedIndex)) {
            titleWithoutChapterPrefix.removePrefix(formattedIndex)
        } else {
            titleWithoutChapterPrefix
        }

        var titleSuffix = afterIndex.trim()
        val relativeDate = RELATIVE_DATE_REGEX.find(titleSuffix)
        if (relativeDate != null) {
            titleSuffix = titleSuffix.substring(0, relativeDate.range.first)
            titleSuffix = titleSuffix.replace(Regex("""(?i)\s*\d+(?:[.,]\d+)?\s*[KMB]?\s*$"""), "")
            titleSuffix = titleSuffix.replace(Regex("""(?i)\s*(?:UP|NEW)\s*$"""), "")
        } else {
            // Remove the common fused view-count suffix even if there is no relative date.
            titleSuffix = titleSuffix.replace(Regex("""(?i)\s+\d+(?:[.,]\d+)?\s*[KMB]?\s*$"""), "")
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
        val numberText = relative.groupValues[1]
        var amount = numberText.toDoubleOrNull()?.toLong() ?: return 0L
        val unit = relative.groupValues[2].lowercase()
        val plausibleMaximum = when {
            unit.startsWith("detik") || unit.startsWith("second") -> 59L
            unit.startsWith("menit") || unit.startsWith("minute") -> 59L
            unit.startsWith("jam") || unit.startsWith("hour") -> 72L
            unit.startsWith("hari") || unit.startsWith("day") -> 31L
            unit.startsWith("minggu") || unit.startsWith("week") -> 52L
            unit.startsWith("bulan") || unit.startsWith("month") -> 12L
            else -> 50L
        }
        // The page concatenates views and relative time without spaces (e.g. 84121 jam lalu).
        // If that produces an impossible interval, use the final two digits as the time count.
        if (amount > plausibleMaximum && numberText.length > 2) {
            amount = numberText.filter(Char::isDigit).takeLast(2).toLongOrNull() ?: amount
        }
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
        for (attribute in listOf("data-src", "data-lazy-src", "data-original", "data-original-src", "data-srcset", "srcset", "src")) {
            val raw = element.attr(attribute).trim()
            if (raw.isBlank()) continue

            // srcset values may contain multiple URLs and width/density descriptors.
            val candidate = if (attribute.endsWith("srcset")) {
                raw.substringBefore(',').trim().split(Regex("""\s+""")).firstOrNull().orEmpty()
            } else {
                raw
            }
            val absolute = absoluteImageUrl(candidate, element.baseUri()) ?: continue
            if (isPlaceholderImage(absolute)) continue
            return absolute
        }
        return null
    }

    private fun absoluteImageUrl(rawValue: String?, pageUrl: String): String? {
        val raw = rawValue?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (raw.startsWith("data:", ignoreCase = true)) return null

        val candidate = if (raw.startsWith("//")) "https:$raw" else raw
        val base = pageUrl.toHttpUrlOrNull() ?: baseUrl.toHttpUrl()
        val absolute = if (candidate.startsWith("http://", ignoreCase = true) ||
            candidate.startsWith("https://", ignoreCase = true)
        ) {
            candidate
        } else {
            base.resolve(candidate)?.toString() ?: return null
        }

        return cleanUrl(absolute).takeIf {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        }
    }

    private fun isCoverImage(element: Element, candidate: String): Boolean {
        if (isPlaceholderImage(candidate)) return false
        if (candidate.contains("/cover/", ignoreCase = true)) return true

        val context = (
            element.parents().map { "${it.id()} ${it.className()}" } +
                "${element.id()} ${element.className()} ${element.attr("alt")} ${element.attr("title")}"
            ).joinToString(" ").lowercase()
        return COVER_CONTEXT_REGEX.containsMatchIn(context)
    }

    private fun isPlaceholderImage(url: String): Boolean {
        val normalized = url.lowercase()
        return listOf(
            "logo",
            "avatar",
            "banner",
            "placeholder",
            "no-image",
            "no_image",
            "noimage",
            "default-cover",
        ).any(normalized::contains)
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
        private val COVER_CONTEXT_REGEX = Regex(
            """\b(cover|poster|thumbnail|series[-_ ]?image|manga[-_ ]?image|book[-_ ]?cover)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val READER_IMAGE_REGEX = Regex(
            """https?://[^"'\s<>]+""",
            RegexOption.IGNORE_CASE,
        )
        private val RELATIVE_DATE_REGEX = Regex(
            """(?i)(\d+(?:[.,]\d+)?)\s*(seconds?|minutes?|hours?|days?|weeks?|months?|years?|detik|menit|jam|hari|minggu|bulan|tahun)\s*(?:ago|lalu)?\s*$""",
        )
        private val IMAGE_EXTENSION_REGEX = Regex(
            """\.(?:jpe?g|png|webp|avif)(?:[?#].*)?$""",
            RegexOption.IGNORE_CASE,
        )
    }
}
