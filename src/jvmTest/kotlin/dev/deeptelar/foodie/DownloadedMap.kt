package dev.deeptelar.foodie

import dev.deeptelar.foodie.sources.MapSearch
import dev.deeptelar.foodie.sources.Overpass
import dev.deeptelar.foodie.sources.StreetMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.math.cos
import kotlin.math.hypot

/**
 * A street map that answers from a file instead of the servers of Overpass, which are too busy at
 * times for models to be compared fairly. The file holds an answer of Overpass with all the places
 * to eat of a city, under `restaurants`, and one with its sights, under `sights`. It searches them
 * as [Overpass.query] would.
 */
class DownloadedMap(
    file: File,
) : StreetMap {
    private val answers: Map<String, JsonElement> = Json.parseToJsonElement(file.readText()).jsonObject

    override suspend fun restaurants(stop: String, near: Location, search: MapSearch, radius: Int): List<Restaurant> =
        with(Overpass.Companion) {
            restaurants(stop, answers.getValue("restaurants"))
                .filter { metres(near, it.lat, it.lon) <= radius && found(it, search) }
                .spread(Overpass.KEPT)
        }

    override suspend fun sights(stop: String, near: Location, radius: Int): List<Sight> =
        with(Overpass.Companion) { sights(stop, answers.getValue("sights")).filter { metres(near, it.lat, it.lon) <= radius } }

    private fun found(place: Restaurant, search: MapSearch): Boolean {
        val eat = place.kind in EAT
        val market = place.kind == "marketplace"
        val sweet = place.kind == "pastry" || place.kind == "confectionery"
        return when (search) {
            is MapSearch.Cuisine -> (eat && search.words.any { place.cuisine.has(it) }) || sweet || market
            is MapSearch.Name -> place.name.has(search.word)
            is MapSearch.Dishes -> !market && search.words.any { place.name.has(it) || place.cuisine.has(it) }
        }
    }

    private fun String.has(word: String): Boolean = word.isNotBlank() && contains(word.trim().replace('_', ' '), ignoreCase = true)

    private fun metres(near: Location, lat: Double, lon: Double): Double = hypot(lat - near.lat, (lon - near.lon) * cos(Math.toRadians(near.lat))) * METRES_A_DEGREE

    private companion object {
        val EAT = setOf("restaurant", "cafe", "fast_food", "pub", "bar", "ice_cream")
        const val METRES_A_DEGREE = 111_320.0
    }
}
