package dev.deeptelar.foodie.sources

import dev.deeptelar.foodie.Location
import dev.deeptelar.foodie.Restaurant
import dev.deeptelar.foodie.Sight
import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** Finds a place on the map. */
fun interface Geocoder {
    /** Where [place] is, or `null` if no place has that name. */
    suspend fun locate(place: String): Location?

    /** [place] as the map knows it, or `null`. A geocoder that cannot say how wide a place is takes each for a town. */
    suspend fun find(place: String): Placed? = locate(place)?.let { Placed(it) }
}

/**
 * A place the map found: where it is, and whether it is wider than a town, such as an island or a
 * region. The restaurants of a town are around its point; the point of an island is only its middle.
 */
data class Placed(
    val location: Location,
    val wide: Boolean = false,
)

/** A travel guide: what people say about eating in a place, and the restaurants they recommend. */
fun interface TravelGuide {
    suspend fun read(place: String): GuidePage
}

/**
 * What the guide has for one place.
 *
 * @property text what the guide says about the food of the place, as plain text.
 * @property restaurants the places to eat it lists, with the guide's words about each in `note`.
 */
@Serializable
data class GuidePage(
    val text: String = "",
    val restaurants: List<Restaurant> = emptyList(),
)

/** A map that knows the restaurants of a place and what they cook. */
fun interface StreetMap {
    /**
     * The restaurants within [radius] metres of [near] that match [search].
     *
     * @param stop the name of the stop the search is for. It is written into each result.
     */
    suspend fun restaurants(stop: String, near: Location, search: MapSearch, radius: Int): List<Restaurant>

    /**
     * The big sights within [radius] metres of [near]: museums, monuments, viewpoints and famous
     * squares. A map that does not know them has none.
     *
     * @param stop the name of the stop the search is for. It is written into each result.
     */
    suspend fun sights(stop: String, near: Location, radius: Int): List<Sight> = emptyList()
}

/** What a [StreetMap] is asked for: a kind of food, or a word in the name of a restaurant. */
sealed interface MapSearch {
    /** Restaurants whose cuisine is one of [words], such as "portuguese" or "regional". */
    data class Cuisine(
        val words: List<String>,
    ) : MapSearch

    /** Restaurants with [word] in their name. */
    data class Name(
        val word: String,
    ) : MapSearch

    /** Places known for a dish: one of [words] is in their name or their cuisine, such as "francesinha". */
    data class Dishes(
        val words: List<String>,
    ) : MapSearch
}

/** Reviews: restaurants for a search in words, with the stars people gave them. */
fun interface PlaceReviews {
    /** @param stop the name of the stop the search is for. It is written into each result. */
    suspend fun search(stop: String, query: String, near: Location): List<Restaurant>
}

/** Everything the planner reads from outside. [reviews] is `null` when the traveller gave no key for it. */
class Sources(
    val geocoder: Geocoder,
    val guide: TravelGuide,
    val map: StreetMap,
    val reviews: PlaceReviews? = null,
) {
    companion object {
        /**
         * The free services, and Google Places when there is a key. All of them answer a browser
         * directly; Google Places is asked at [placesUrl] when it is a proxy that keeps the real key.
         */
        fun online(client: HttpClient, googleKey: String? = null, placesUrl: String = ""): Sources =
            Sources(
                geocoder = Nominatim(client),
                guide = Wikivoyage(client),
                map = Overpass(client),
                reviews = googleKey?.takeIf { it.isNotBlank() }?.let { key -> if (placesUrl.isBlank()) GooglePlaces(client, key) else GooglePlaces(client, key, placesUrl.trim().trimEnd('/')) },
            )
    }
}

/** A source did not answer, or answered with something that could not be read. */
class SourceException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Whether a source can go on after this was thrown: an exception, or a plain `Error`, which is what
 * Ktor throws in a browser for a request that got no response ("Fail to fetch"). Cancellation and
 * the subclasses of `Error`, such as the `AssertionError` of a test, are not.
 */
internal fun Throwable.isFailure(): Boolean = this !is CancellationException && (this !is Error || this::class == Error::class)

internal val json: Json = Json { ignoreUnknownKeys = true }

/** The body of this response as JSON. A status that is not a success is a failure of [source]. */
internal suspend fun HttpResponse.jsonFrom(source: String): JsonElement {
    val body = bodyAsText()
    if (!status.isSuccess()) throw SourceException("$source answered with ${status.value}: ${body.take(ERROR_LENGTH)}")
    return try {
        json.parseToJsonElement(body)
    } catch (e: IllegalArgumentException) {
        throw SourceException("$source did not answer with JSON: ${body.take(ERROR_LENGTH)}", e)
    }
}

private const val ERROR_LENGTH = 200

internal fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

internal fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
