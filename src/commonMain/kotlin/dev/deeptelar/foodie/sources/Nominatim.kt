package dev.deeptelar.foodie.sources

import dev.deeptelar.foodie.Location
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Nominatim, the search of OpenStreetMap. It needs no key. Its public server asks for one request
 * a second at most, which a trip of a few stops stays far below.
 */
class Nominatim(
    private val client: HttpClient,
    private val baseUrl: String = "https://nominatim.openstreetmap.org",
) : Geocoder {
    override suspend fun locate(place: String): Location? = find(place)?.location

    override suspend fun find(place: String): Placed? {
        val found =
            client
                .get("$baseUrl/search") {
                    parameter("q", place)
                    parameter("format", "jsonv2")
                    parameter("limit", 1)
                    parameter("accept-language", "en")
                }.jsonFrom("The place search")
        val first = (found as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val location = Location(first.number("lat") ?: return null, first.number("lon") ?: return null)
        return Placed(location, wide = first.text("addresstype") in WIDE)
    }

    companion object {
        /** The kinds of place, as Nominatim names them in `addresstype`, that are wider than a town. */
        private val WIDE = setOf("island", "archipelago", "region", "state", "state_district", "province", "county", "country", "continent")
    }
}
