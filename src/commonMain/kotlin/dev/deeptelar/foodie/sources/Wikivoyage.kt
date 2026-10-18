package dev.deeptelar.foodie.sources

import dev.deeptelar.foodie.Restaurant
import dev.deeptelar.foodie.Source
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Wikivoyage, the travel guide of the Wikimedia projects, written by travellers. It needs no key.
 *
 * The page of a place has a section "Eat": some words about the local food, and a list of places
 * to eat, each with a position and a few words from someone who was there. A large city has a
 * page for each of its districts, named "City/District", and its restaurants are listed there.
 */
class Wikivoyage(
    private val client: HttpClient,
    private val baseUrl: String = "https://en.wikivoyage.org",
) : TravelGuide {
    override suspend fun read(place: String): GuidePage {
        val title = title(place) ?: return GuidePage()
        val page = wikitext(title) ?: return GuidePage()
        // One page after the other, as the rules of the Wikimedia APIs ask: a burst is answered with 429.
        val districts = districts(title).map { district(it) }
        return GuidePage(
            text = eatText(page).take(TEXT_LENGTH),
            restaurants = (eatListings(place, page) + districts.flatMap { eatListings(place, it) }).distinctBy { it.name },
        )
    }

    /** The title of the page about [place]: the page with its name, or else the first result of a search. */
    private suspend fun title(place: String): String? {
        val name = place.substringBefore(',').trim()
        if (wikitext(name) != null) return name
        val found = api { parameter("action", "query"); parameter("list", "search"); parameter("srsearch", place); parameter("srlimit", 1) }
        val results = found.obj("query")?.get("search") as? JsonArray
        return (results?.firstOrNull() as? JsonObject)?.text("title")?.takeIf { it.isNotBlank() }
    }

    /** The source text of a page, or `null` if there is no page with that title. */
    private suspend fun wikitext(title: String): String? {
        val page = api { parameter("action", "parse"); parameter("page", title); parameter("prop", "wikitext"); parameter("redirects", 1) }
        return page.obj("parse")?.obj("wikitext")?.text("*")?.takeIf { it.isNotBlank() }
    }

    private suspend fun districts(title: String): List<String> {
        val found = api { parameter("action", "query"); parameter("list", "prefixsearch"); parameter("pssearch", "$title/"); parameter("pslimit", DISTRICTS) }
        val pages = found.obj("query")?.get("prefixsearch") as? JsonArray ?: return emptyList()
        return pages.mapNotNull { (it as? JsonObject)?.text("title") }.filter { it.startsWith("$title/") }
    }

    /** A district page. One that cannot be read costs its restaurants, not the whole guide. */
    private suspend fun district(title: String): String =
        try {
            wikitext(title).orEmpty()
        } catch (e: Throwable) {
            if (!e.isFailure()) throw e
            ""
        }

    private suspend fun api(parameters: io.ktor.client.request.HttpRequestBuilder.() -> Unit): JsonObject =
        client
            .get("$baseUrl/w/api.php") {
                parameter("format", "json")
                // Without this, the API does not let a page of another site read the answer.
                parameter("origin", "*")
                // Wikimedia asks every client to say who it is. A browser does not let a page set User-Agent, so this header is it.
                header("Api-User-Agent", USER_AGENT)
                parameters()
            }.jsonFrom("The travel guide") as? JsonObject ?: throw SourceException("The travel guide sent no object.")

    companion object {
        private const val USER_AGENT = "FoodieRoute/0.1 (an example app of Telar; https://github.com/deeptelar/telar-foodie)"

        /** The most district pages that are read for one place. */
        private const val DISTRICTS = 8

        /** The most text about the food of one place that is kept, in characters. */
        private const val TEXT_LENGTH = 4000

        /** The section "Eat" of a page, from its heading to the next heading of the same level. */
        internal fun eatSection(wikitext: String): String {
            val start = Regex("^==\\s*Eat\\s*==\\s*$", RegexOption.MULTILINE).find(wikitext) ?: return ""
            val after = wikitext.substring(start.range.last + 1)
            val next = Regex("^==[^=].*==\\s*$", RegexOption.MULTILINE).find(after)
            return if (next == null) after else after.substring(0, next.range.first)
        }

        /** The restaurants a page lists under "Eat". A listing without a position is left out: it cannot be on a route. */
        internal fun eatListings(stop: String, wikitext: String): List<Restaurant> =
            templates(eatSection(wikitext))
                .filter { it.name == "eat" || (it.name == "listing" && it["type"] == "eat") }
                .mapNotNull { listing ->
                    Restaurant(
                        stop = stop,
                        name = plain(listing["name"]).takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                        lat = listing["lat"].toDoubleOrNull() ?: return@mapNotNull null,
                        lon = listing["long"].toDoubleOrNull() ?: return@mapNotNull null,
                        address = plain(listing["address"]),
                        website = listing["url"],
                        hours = plain(listing["hours"]),
                        price = plain(listing["price"]),
                        phone = plain(listing["phone"]),
                        note = plain(listing["content"]),
                        sources = setOf(Source.GUIDE),
                    )
                }

        /** What the section "Eat" says in words, without its listings and its markup. */
        internal fun eatText(wikitext: String): String {
            var text = eatSection(wikitext)
            for (template in templates(text).sortedByDescending { it.range.first }) text = text.removeRange(template.range)
            return plain(text)
        }

        /** Wiki markup as plain text: a link becomes its label, and comments, files and formatting go. */
        internal fun plain(markup: String): String =
            markup
                .replace(Regex("<!--[\\s\\S]*?-->"), "")
                .replace(Regex("\\[\\[(?:File|Image):[^\\]]*\\]\\]", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\[\\[(?:[^\\]|]*\\|)?([^\\]]*)\\]\\]"), "$1")
                .replace(Regex("\\[https?://\\S+\\s+([^\\]]*)\\]"), "$1")
                .replace(Regex("\\[https?://\\S+\\]"), "")
                .replace(Regex("<[^>]+>"), "")
                .replace(Regex("'{2,}"), "")
                .replace(Regex("^=+\\s*(.*?)\\s*=+\\s*$", RegexOption.MULTILINE), "$1:")
                .replace(Regex("^\\*\\s*$", RegexOption.MULTILINE), "")
                .replace(Regex("[ \\t]+"), " ")
                .replace(Regex("\\n\\s*\\n+"), "\n")
                .trim()

        /** A template of a page, `{{name | key=value | ...}}`, and where it stands in the text. */
        internal class Template(
            val name: String,
            private val values: Map<String, String>,
            val range: IntRange,
        ) {
            /** The value of [key], or an empty string. */
            operator fun get(key: String): String = values[key].orEmpty()
        }

        /** The templates of [text] that are not inside another template. */
        internal fun templates(text: String): List<Template> {
            val found = mutableListOf<Template>()
            var index = text.indexOf("{{")
            while (index >= 0) {
                val end = closing(text, index)
                if (end < 0) break
                found += template(text.substring(index + 2, end), index..end + 1)
                index = text.indexOf("{{", end + 2)
            }
            return found
        }

        /** The index of the `}}` that closes the template opened at [start], or -1. */
        private fun closing(text: String, start: Int): Int {
            var depth = 0
            var i = start
            while (i < text.length - 1) {
                when {
                    text.startsWith("{{", i) -> { depth++; i += 2 }
                    text.startsWith("}}", i) -> { depth--; if (depth == 0) return i; i += 2 }
                    else -> i++
                }
            }
            return -1
        }

        private fun template(body: String, range: IntRange): Template {
            val parts = split(body)
            val values =
                parts.drop(1).mapNotNull { part ->
                    val equals = part.indexOf('=')
                    if (equals < 0) null else part.substring(0, equals).trim().lowercase() to part.substring(equals + 1).trim()
                }
            return Template(parts.first().trim().lowercase(), values.toMap(), range)
        }

        /** [body] cut at each `|` that is not inside a link or another template. */
        private fun split(body: String): List<String> {
            val parts = mutableListOf<String>()
            var depth = 0
            var from = 0
            var i = 0
            while (i < body.length) {
                when {
                    body.startsWith("{{", i) || body.startsWith("[[", i) -> { depth++; i += 2 }
                    body.startsWith("}}", i) || body.startsWith("]]", i) -> { depth--; i += 2 }
                    body[i] == '|' && depth == 0 -> { parts += body.substring(from, i); from = i + 1; i++ }
                    else -> i++
                }
            }
            parts += body.substring(from)
            return parts
        }
    }
}
