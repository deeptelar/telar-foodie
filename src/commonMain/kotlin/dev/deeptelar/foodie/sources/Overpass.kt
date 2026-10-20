package dev.deeptelar.foodie.sources

import dev.deeptelar.foodie.Location
import dev.deeptelar.foodie.Restaurant
import dev.deeptelar.foodie.Sight
import dev.deeptelar.foodie.Source
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.http.parameters
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.math.floor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The Overpass API, which answers questions about the data of OpenStreetMap. It needs no key.
 *
 * The public servers are run by volunteers, and one of them is busy or down now and then. So a
 * question goes to each of [servers] in turn, starting with the one that answered last, and a
 * server gets [patience] to answer. When none did, the map is left alone for [rest]: a search in
 * that time fails at once, so an agent that searches five times does not wait five times. The rest
 * outlasts a plan: when the servers are down, they are seldom up again a minute later.
 */
class Overpass(
    private val client: HttpClient,
    private val servers: List<String> = PUBLIC_SERVERS,
    private val patience: Duration = 12.seconds,
    private val rest: Duration = 10.minutes,
) : StreetMap {
    /** The index of the server that answered last. */
    private var preferred = 0

    /** When no server answered, the moment that was found out. */
    private var silentSince: TimeMark? = null

    override suspend fun restaurants(stop: String, near: Location, search: MapSearch, radius: Int): List<Restaurant> =
        restaurants(stop, ask(query(near, search, radius))).spread(KEPT)

    // The sights are a help: when no server answers for them, the map is not left alone for the restaurants.
    override suspend fun sights(stop: String, near: Location, radius: Int): List<Sight> = sights(stop, ask(sightsQuery(near, radius), rests = false))

    /** The answer to [query] of the first server that gives one. When none does and [rests], the map is left alone for [rest]. */
    private suspend fun ask(query: String, rests: Boolean = true): JsonElement {
        silentSince?.let { since ->
            if (since.elapsedNow() < rest) throw SourceException("The street map does not answer at the moment. Go on without it.")
        }
        var failure = ""
        for (offset in servers.indices) {
            val index = (preferred + offset) % servers.size
            val answer =
                try {
                    withTimeoutOrNull(patience) { client.submitForm(servers[index], parameters { append("data", query) }).jsonFrom("The street map").withoutError() }
                } catch (e: Throwable) {
                    if (!e.isFailure()) throw e
                    failure = e.message.orEmpty()
                    continue
                }
            if (answer == null) {
                failure = "no answer within $patience"
                continue
            }
            preferred = index
            silentSince = null
            return answer
        }
        if (rests) silentSince = TimeSource.Monotonic.markNow()
        throw SourceException("No server of the street map answered: $failure")
    }

    companion object {
        val PUBLIC_SERVERS: List<String> =
            listOf(
                "https://overpass-api.de/api/interpreter",
                // A machine of the same service, asked directly: it answered when the address above gave 502.
                "https://z.overpass-api.de/api/interpreter",
                "https://overpass.kumi.systems/api/interpreter",
                "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
                "https://overpass.private.coffee/api/interpreter",
            )

        /** The most places one question returns. */
        const val LIMIT: Int = 1000

        /** The most places of one question that are kept, spread over the area: see [spread]. */
        const val KEPT: Int = 150

        /**
         * Places to eat with a name around [near]: restaurants, cafés and bars, and also pastry
         * shops and markets, where many people who live there eat. A place with a `brand` is left
         * out: that tag marks the branches of a chain. A search by cuisine finds every pastry shop
         * and market, which seldom have a cuisine, and a search by name or dish finds bakeries too.
         */
        internal fun query(near: Location, search: MapSearch, radius: Int): String {
            val around = "(around:$radius,${near.lat},${near.lon})"
            val eat = "nwr[\"amenity\"~\"^(restaurant|cafe|fast_food|pub|bar|ice_cream)$\"][\"name\"][!\"brand\"]"
            val sweet = "nwr[\"shop\"~\"^(pastry|confectionery)$\"][\"name\"][!\"brand\"]"
            val shops = "nwr[\"shop\"~\"^(bakery|pastry|confectionery)$\"][\"name\"][!\"brand\"]"
            val market = "nwr[\"amenity\"=\"marketplace\"][\"name\"]"
            val statements =
                when (search) {
                    is MapSearch.Cuisine -> listOf(eat + matching("cuisine", search.words), sweet, market)
                    is MapSearch.Name -> listOf(eat, shops, market).map { it + matching("name", listOf(search.word)) }
                    is MapSearch.Dishes -> listOf(eat, shops).flatMap { listOf(it + matching("name", search.words), it + matching("cuisine", search.words)) }
                }
            return "[out:json][timeout:25];(" + statements.joinToString("") { "$it$around;" } + ");out tags center $LIMIT;"
        }

        /**
         * The big sights around [near]: the attractions, viewpoints and squares that have a page in
         * Wikipedia. Museums, churches and fountains are left out: a city has so many that nearly
         * every place of its centre stands next to one, and few of them draw crowds.
         */
        internal fun sightsQuery(near: Location, radius: Int): String {
            val around = "(around:$radius,${near.lat},${near.lon})"
            val statements =
                listOf(
                    "nwr[\"tourism\"~\"^(attraction|viewpoint)$\"]",
                    "nwr[\"place\"=\"square\"]",
                ).map { it + "[\"name\"][\"wikipedia\"]" }
            return "[out:json][timeout:25];(" + statements.joinToString("") { "$it$around;" } + ");out tags center $SIGHTS;"
        }

        /** The most sights one question returns. */
        private const val SIGHTS = 300

        /** A filter of a query: the tag [key] has one of [words] in it. A space matches the underscore of a cuisine too. */
        private fun matching(key: String, words: List<String>): String =
            "[\"$key\"~\"${words.map(::word).filter { it.isNotEmpty() }.joinToString("|") { it.replace(" ", "[ _]") }}\",i]"

        /** [text] as it may stand in a regular expression inside a quoted string of a query: letters, digits, spaces and hyphens. */
        private fun word(text: String): String = text.trim().filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }

        /**
         * At most [limit] of these places, spread over the area. The map answers in the order the
         * places were added to it, which favours the old centre, where the visitors are: the area
         * is cut into cells, and each cell gives a place in turn.
         */
        internal fun List<Restaurant>.spread(limit: Int): List<Restaurant> {
            if (size <= limit) return this
            val cells = groupBy { floor(it.lat / CELL) to floor(it.lon / CELL) }.values.map { it.iterator() }
            val kept = mutableListOf<Restaurant>()
            while (kept.size < limit) cells.forEach { if (it.hasNext() && kept.size < limit) kept += it.next() }
            return kept
        }

        /**
         * This answer, unless the server says it failed. A busy server that runs out of time or
         * memory still answers with 200 and a list, an empty one or a part, and says so in `remark`:
         * read as it is, a city would have no restaurants.
         */
        internal fun JsonElement.withoutError(): JsonElement {
            val remark = (this as? JsonObject)?.text("remark").orEmpty()
            if ("error" in remark) throw SourceException("The street map failed: $remark")
            return this
        }

        /** The values of the map's `reservation` tag that the page knows how to say. */
        private val RESERVATIONS = setOf("required", "recommended", "yes", "no")

        /** The size of a cell of [spread], in degrees: about 400 metres. */
        private const val CELL = 0.004

        internal fun sights(stop: String, answer: JsonElement): List<Sight> {
            val elements = (answer as? JsonObject)?.get("elements") as? JsonArray ?: throw SourceException("The street map sent no list of places.")
            return elements.mapNotNull { element ->
                val place = element as? JsonObject ?: return@mapNotNull null
                val name = place.obj("tags")?.text("name").orEmpty()
                val position = place.obj("center") ?: place
                Sight(stop, name.ifBlank { return@mapNotNull null }, position.number("lat") ?: return@mapNotNull null, position.number("lon") ?: return@mapNotNull null)
            }
        }

        internal fun restaurants(stop: String, answer: JsonElement): List<Restaurant> {
            val elements = (answer as? JsonObject)?.get("elements") as? JsonArray ?: throw SourceException("The street map sent no list of places.")
            return elements.mapNotNull { element ->
                val place = element as? JsonObject ?: return@mapNotNull null
                val tags = place.obj("tags") ?: return@mapNotNull null
                // A point has its position itself. A building has the middle of its outline.
                val position = place.obj("center") ?: place
                val lat = position.number("lat") ?: return@mapNotNull null
                val lon = position.number("lon") ?: return@mapNotNull null
                if (tags.text("operational_status") == "closed" || tags.text("name").isBlank()) return@mapNotNull null
                Restaurant(
                    stop = stop,
                    name = tags.text("name"),
                    lat = lat,
                    lon = lon,
                    cuisine = tags.text("cuisine").replace(";", ", ").replace('_', ' '),
                    kind = tags.text("amenity").ifBlank { tags.text("shop") },
                    address = listOf(tags.text("addr:street"), tags.text("addr:housenumber")).filter { it.isNotBlank() }.joinToString(" "),
                    website = tags.text("website").ifBlank { tags.text("contact:website") },
                    hours = tags.text("opening_hours"),
                    phone = tags.text("phone").ifBlank { tags.text("contact:phone") }.substringBefore(';').trim(),
                    reservation = tags.text("reservation").takeIf { it in RESERVATIONS }.orEmpty(),
                    sources = setOf(Source.MAP),
                )
            }
        }
    }
}
