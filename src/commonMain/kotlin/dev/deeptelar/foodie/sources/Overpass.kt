package dev.deeptelar.foodie.sources

import dev.deeptelar.foodie.Location
import dev.deeptelar.foodie.Restaurant
import dev.deeptelar.foodie.Source
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The Overpass API, which answers questions about the data of OpenStreetMap. It needs no key.
 *
 * The public servers are run by volunteers and one of them is busy or down now and then, so a
 * question goes to each of [servers] in turn until one answers.
 */
class Overpass(
    private val client: HttpClient,
    private val servers: List<String> = PUBLIC_SERVERS,
) : StreetMap {
    override suspend fun restaurants(stop: String, near: Location, search: MapSearch, radius: Int): List<Restaurant> {
        val query = query(near, search, radius)
        var failure: Exception? = null
        for (server in servers) {
            try {
                val answer = client.submitForm(server, parameters { append("data", query) }).jsonFrom("The street map")
                return restaurants(stop, answer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
            }
        }
        throw SourceException("No server of the street map answered: ${failure?.message}", failure)
    }

    companion object {
        val PUBLIC_SERVERS: List<String> =
            listOf(
                "https://overpass-api.de/api/interpreter",
                "https://overpass.private.coffee/api/interpreter",
                "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
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
