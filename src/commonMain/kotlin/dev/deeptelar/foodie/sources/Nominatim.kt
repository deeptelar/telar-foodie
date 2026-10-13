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
    override suspend fun locate(place: String): Location? {
        val found =
            client
                .get("$baseUrl/search") {
                    parameter("q", place)
                    parameter("format", "jsonv2")
                    parameter("limit", 1)
                    parameter("accept-language", "en")
                }.jsonFrom("The place search")
        val first = (found as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        return Location(first.number("lat") ?: return null, first.number("lon") ?: return null)
    }
}
