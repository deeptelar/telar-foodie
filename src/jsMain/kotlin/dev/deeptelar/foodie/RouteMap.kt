package dev.deeptelar.foodie

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import kotlin.js.json

/**
 * The route on a map of OpenStreetMap, drawn with Leaflet. The page loads Leaflet with a script
 * tag, so it is the global `L` here.
 */
class RouteMap(
    container: HTMLElement,
    trip: Trip,
    texts: Texts,
) {
    private val leaflet: dynamic = window.asDynamic().L
    private val map: dynamic = leaflet.map(container, json("scrollWheelZoom" to false))
    private val points = mutableMapOf<Int, MutableList<Array<Double>>>()

    init {
        leaflet
            .tileLayer(
                "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
                json("maxZoom" to 19, "attribution" to "&copy; <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors"),
            ).addTo(map)

        trip.route.forEachIndexed { index, meal ->
            val restaurant = trip.restaurant(meal.restaurantId) ?: return@forEachIndexed
            val point = arrayOf(restaurant.lat, restaurant.lon)
            points.getOrPut(meal.day) { mutableListOf() } += point
            val icon = leaflet.divIcon(json("className" to "pin ${dayClass(meal.day)}", "html" to "${index + 1}", "iconSize" to arrayOf(28, 28)))
            // The popup is an element with text in it, so a name cannot be read as HTML.
            val popup = (document.createElement("div") as HTMLElement).apply { textContent = "${texts.meal(meal.time)}: ${restaurant.name}" }
            leaflet.marker(point, json("icon" to icon)).addTo(map).bindPopup(popup)
        }
        points.forEach { (day, line) ->
            leaflet.polyline(line.toTypedArray(), json("color" to DAY_COLORS[(day - 1) % DAY_COLORS.size], "weight" to 3, "opacity" to 0.75, "dashArray" to "6 6")).addTo(map)
        }
        show(null)
    }

    /** Moves the map to the meals of [day], or to the whole route for `null`. */
    fun show(day: Int?) {
        val shown = (if (day == null) points.values.flatten() else points[day].orEmpty()).toTypedArray()
        if (shown.isEmpty()) {
            map.setView(arrayOf(20.0, 0.0), 2)
        } else {
            map.fitBounds(leaflet.latLngBounds(shown), json("padding" to arrayOf(36, 36), "maxZoom" to 16))
        }
    }

    /** Frees what Leaflet holds for the map. Call it before the container leaves the page. */
    fun remove() {
        map.remove()
    }

    companion object {
        /** One colour per day, repeated on a long trip. The pins get theirs from the style sheet, by [dayClass]. */
        val DAY_COLORS: List<String> = listOf("#c2410c", "#0f766e", "#7c3aed", "#b45309", "#be123c", "#1d4ed8")

        fun dayClass(day: Int): String = "day${(day - 1) % DAY_COLORS.size + 1}"
    }
}
