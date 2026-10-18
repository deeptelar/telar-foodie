package dev.deeptelar.foodie.sources

import dev.deeptelar.foodie.Location
import dev.deeptelar.foodie.Restaurant
import dev.deeptelar.foodie.Source
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Text Search of the Places API (New) of Google. It is the only source with ratings, and the only
 * one that needs a key: the traveller creates one in the Google Cloud console, and Google bills
 * its owner for the searches.
 *
 * The key goes from the traveller's browser straight to Google. Never ship a key of your own in a
 * page: everyone who opens the page can read it.
 */
class GooglePlaces(
    private val client: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = "https://places.googleapis.com",
) : PlaceReviews {
    override suspend fun search(stop: String, query: String, near: Location): List<Restaurant> {
        val answer =
            client
                .post("$baseUrl/v1/places:searchText") {
                    header("X-Goog-Api-Key", apiKey)
                    // Google returns, and bills, only the fields that are named here.
                    header("X-Goog-FieldMask", FIELDS)
                    contentType(ContentType.Application.Json)
                    setBody(request(query, near).toString())
                }.jsonFrom("Google Places")
        return restaurants(stop, answer)
    }

    companion object {
        private const val FIELDS =
            "places.id,places.displayName,places.formattedAddress,places.location,places.rating,places.userRatingCount," +
                "places.priceLevel,places.websiteUri,places.primaryTypeDisplayName"

        /** Results come from around this distance of the stop first, in metres. */
        private const val RADIUS = 4000.0

        private const val RESULTS = 10

        internal fun request(query: String, near: Location): JsonObject =
            buildJsonObject {
                put("textQuery", query)
                put("maxResultCount", RESULTS)
                putJsonObject("locationBias") {
                    putJsonObject("circle") {
                        putJsonObject("center") {
                            put("latitude", near.lat)
                            put("longitude", near.lon)
                        }
                        put("radius", RADIUS)
                    }
                }
            }

        internal fun restaurants(stop: String, answer: JsonElement): List<Restaurant> {
            // A search that finds nothing answers with an empty object.
            val places = (answer as? JsonObject)?.get("places") as? JsonArray ?: return emptyList()
            return places.mapNotNull { element ->
                val place = element as? JsonObject ?: return@mapNotNull null
                val position = place.obj("location") ?: return@mapNotNull null
                Restaurant(
                    stop = stop,
                    name = place.obj("displayName")?.text("text")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                    lat = position.number("latitude") ?: return@mapNotNull null,
                    lon = position.number("longitude") ?: return@mapNotNull null,
                    cuisine = place.obj("primaryTypeDisplayName")?.text("text").orEmpty(),
                    address = place.text("formattedAddress"),
                    website = place.text("websiteUri"),
                    googleId = place.text("id"),
                    price = PRICES[place.text("priceLevel")].orEmpty(),
                    rating = place.number("rating"),
                    ratings = place.number("userRatingCount")?.toInt(),
                    sources = setOf(Source.REVIEWS),
                )
            }
        }

        private val PRICES =
            mapOf(
                "PRICE_LEVEL_INEXPENSIVE" to "low",
                "PRICE_LEVEL_MODERATE" to "medium",
                "PRICE_LEVEL_EXPENSIVE" to "high",
                "PRICE_LEVEL_VERY_EXPENSIVE" to "very high",
            )
    }
}
