package dev.deeptelar.foodie.sources

import dev.deeptelar.foodie.Location
import dev.deeptelar.foodie.Restaurant
import dev.deeptelar.foodie.Source
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.http.parameters
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
 * that time fails at once, so an agent that searches five times does not wait five times.
 */
class Overpass(
    private val client: HttpClient,
    private val servers: List<String> = PUBLIC_SERVERS,
    private val patience: Duration = 12.seconds,
    private val rest: Duration = 1.minutes,
) : StreetMap {
    /** The index of the server that answered last. */
    private var preferred = 0

    /** When no server answered, the moment that was found out. */
    private var silentSince: TimeMark? = null

    override suspend fun restaurants(stop: String, near: Location, search: MapSearch, radius: Int): List<Restaurant> {
        silentSince?.let { since ->
            if (since.elapsedNow() < rest) throw SourceException("The street map does not answer at the moment. Go on without it.")
        }
        val query = query(near, search, radius)
        var failure = ""
        for (offset in servers.indices) {
            val index = (preferred + offset) % servers.size
            val answer =
                try {
                    withTimeoutOrNull(patience) { client.submitForm(servers[index], parameters { append("data", query) }).jsonFrom("The street map") }
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
            return restaurants(stop, answer)
        }
        silentSince = TimeSource.Monotonic.markNow()
        throw SourceException("No server of the street map answered: $failure")
    }

    companion object {
        val PUBLIC_SERVERS: List<String> =
            listOf(
                "https://overpass-api.de/api/interpreter",
                "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
                "https://overpass.private.coffee/api/interpreter",
            )

        /** The most restaurants one question returns. */
        const val LIMIT: Int = 120

        /**
         * Places to eat with a name around [near]. A place with a `brand` is left out: that tag
         * marks the branches of a chain.
         */
        internal fun query(near: Location, search: MapSearch, radius: Int): String {
            val filter =
                when (search) {
                    is MapSearch.Cuisine -> "[\"cuisine\"~\"${search.words.map(::word).filter { it.isNotEmpty() }.joinToString("|")}\",i]"
                    is MapSearch.Name -> "[\"name\"~\"${word(search.word)}\",i]"
                }
            return "[out:json][timeout:25];" +
                "nwr[\"amenity\"~\"^(restaurant|cafe|fast_food|pub|bar|ice_cream)$\"][\"name\"]$filter[!\"brand\"]" +
                "(around:$radius,${near.lat},${near.lon});" +
                "out tags center $LIMIT;"
        }

        /** [text] as it may stand in a regular expression inside a quoted string of a query: letters, digits, spaces and hyphens. */
        private fun word(text: String): String = text.trim().filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }

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
                    address = listOf(tags.text("addr:street"), tags.text("addr:housenumber")).filter { it.isNotBlank() }.joinToString(" "),
                    website = tags.text("website").ifBlank { tags.text("contact:website") },
                    hours = tags.text("opening_hours"),
                    sources = setOf(Source.MAP),
                )
            }
        }
    }
}
