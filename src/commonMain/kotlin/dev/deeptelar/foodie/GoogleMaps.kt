package dev.deeptelar.foodie

import io.ktor.http.encodeURLParameter

/*
 * The route in Google Maps. A web page cannot put places into someone's saved places, so the
 * route offers what Google Maps opens: a link to each place, a link to each day as a walk, and a
 * KML file that Google My Maps imports as one map, which the app then shows under its saved maps.
 * The links are Google's Maps URLs, which need no key.
 */

/** A link that opens [restaurant] in Google Maps: exactly that place when Google found it, or else a search for it. */
fun Restaurant.googleMapsLink(): String {
    // The guide writes a street without its town, and Google a whole address: the town goes with the street, or a street of another town is found.
    val town = stop.substringBefore(',').trim()
    val words = listOf(name, address, town.takeUnless { ',' in address || it in address }.orEmpty()).filter { it.isNotBlank() }
    val link = "$MAPS/search/?api=1&query=" + words.joinToString(", ").encodeURLParameter()
    return if (googleId.isBlank()) link else link + "&query_place_id=" + googleId.encodeURLParameter()
}

/**
 * A link that opens the meals of [day] in Google Maps as a route, in their order, from where the
 * traveller sleeps when the map found it. `null` for a day without meals. A day whose places are
 * all a walk apart is a walk; on a day with a longer way, Google Maps offers the bus, a taxi or the car.
 *
 * A place Google found goes by its name and its id, which shows its name in the app; every other by
 * where it is. The stops between need ids for all of them or for none, so they go by where they
 * are unless Google found each one.
 */
fun Trip.dayInGoogleMaps(day: Int): String? {
    val meals = route.filter { it.day == day }.sortedBy { it.time }
    val places = meals.mapNotNull { restaurant(it.restaurantId) }
    if (places.isEmpty()) return null
    // Without where the traveller sleeps, the walk starts at the first meal.
    val stay = stayFor(day, meals.first().time)
    val first = places.first().takeIf { stay == null }
    val visits = if (first == null) places else places.drop(1)
    val last = visits.lastOrNull() ?: return places.first().googleMapsLink()
    val between = visits.dropLast(1)
    return buildString {
        append("$MAPS/dir/?api=1")
        val points = listOfNotNull(stay) + places.map { it.location }
        if (points.zipWithNext().all { (from, to) -> metresBetween(from, to) <= WALKABLE }) append("&travelmode=walking")
        append("&origin=").append((first?.named() ?: "${stay!!.lat},${stay.lon}").encodeURLParameter())
        if (first != null && first.googleId.isNotBlank()) append("&origin_place_id=").append(first.googleId.encodeURLParameter())
        append("&destination=").append(last.named().encodeURLParameter())
        if (last.googleId.isNotBlank()) append("&destination_place_id=").append(last.googleId.encodeURLParameter())
        if (between.isNotEmpty()) {
            val all = between.all { it.googleId.isNotBlank() }
            append("&waypoints=").append(between.joinToString("|") { if (all) it.named() else it.point() }.encodeURLParameter())
            if (all) append("&waypoint_place_ids=").append(between.joinToString("|") { it.googleId }.encodeURLParameter())
        }
    }
}

/** A place by its name when Google knows its id, which then finds it; by where it is otherwise, which no name can mistake. */
private fun Restaurant.named(): String = if (googleId.isBlank()) point() else name

private fun Restaurant.point(): String = "$lat,$lon"

private const val MAPS = "https://www.google.com/maps"

/** The longest way between two places of a day that is still a walk, in metres. */
private const val WALKABLE = 2000

/** Where Google My Maps is, to import the file of [toKml]. */
const val MY_MAPS: String = "https://www.google.com/maps/d/"

/** A pin of the route for a file of Google My Maps: a meal, with what to read about it. */
private class Pin(
    val day: Int,
    val name: String,
    val lat: Double,
    val lon: Double,
    val lines: List<String>,
)

/** A pin for each meal of the route, numbered in the order they are eaten. */
private fun Trip.pins(): List<Pin> {
    val texts = language.texts
    return route.sortedWith(compareBy({ it.day }, { it.time })).mapNotNull { meal -> restaurant(meal.restaurantId)?.let { meal to it } }.mapIndexed { index, (meal, place) ->
        val lines =
            listOfNotNull(
                texts.order + meal.dish,
                meal.why,
                meal.caution.takeIf { it.isNotBlank() }?.let { "⚠ $it" },
                place.address.takeIf { it.isNotBlank() },
                listOfNotNull(texts.booking(place.reservation), place.phone.takeIf { it.isNotBlank() }).joinToString(" · ").takeIf { it.isNotBlank() },
                place.website.takeIf { it.isNotBlank() },
            )
        Pin(meal.day, "${index + 1}. ${texts.meal(meal.time)} · ${place.name}", place.lat, place.lon, lines)
    }
}

/**
 * The route as a KML file for Google My Maps: a folder for each day, and a pin for each meal with
 * what to order there, why, and what to watch out for.
 */
fun Trip.toKml(): String {
    val texts = language.texts
    val pins = pins()
    return buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<kml xmlns="http://www.opengis.net/kml/2.2">""")
        appendLine("<Document>")
        appendLine("<name>${xml(mapTitle())}</name>")
        pins.groupBy { it.day }.forEach { (day, pinsOfDay) ->
            appendLine("<Folder>")
            appendLine("<name>${xml(texts.dayAt(day, stopOn(day)?.title.orEmpty()))}</name>")
            pinsOfDay.forEach { pin ->
                appendLine("<Placemark>")
                appendLine("<name>${xml(pin.name)}</name>")
                appendLine("<description>${xml(pin.lines.joinToString("\n"))}</description>")
                appendLine("<Point><coordinates>${pin.lon},${pin.lat}</coordinates></Point>")
                appendLine("</Placemark>")
            }
            appendLine("</Folder>")
        }
        appendLine("</Document>")
        appendLine("</kml>")
    }
}

/**
 * The route as a CSV file for Google My Maps, for where a KML file cannot go: the share sheet of a
 * phone's browser takes text files, and no KML. My Maps asks which columns say where a pin is and
 * what its name is: "Latitude", "Longitude" and "Name".
 */
fun Trip.toCsv(): String =
    (listOf("Name,Latitude,Longitude,Description") + pins().map { pin -> listOf(csv(pin.name), "${pin.lat}", "${pin.lon}", csv(pin.lines.joinToString("\n"))).joinToString(",") })
        .joinToString("\r\n", postfix = "\r\n")

/** The name of the map in My Maps: the trip's places. */
fun Trip.mapTitle(): String = "Foodie Route · " + stops.joinToString(" · ") { it.title }

/** [text] as a field of a CSV file: in quotes, with its quotes doubled. */
private fun csv(text: String): String = "\"" + text.replace("\"", "\"\"") + "\""

/** [text] as it may stand in the text of an XML element. */
private fun xml(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** The name of the KML file of [trip], beside the file of the plan. */
fun kmlName(trip: Trip, day: String): String = fileName(trip, day).removeSuffix(".json") + ".kml"

/** The name of the CSV file of [trip]. */
fun csvName(trip: Trip, day: String): String = fileName(trip, day).removeSuffix(".json") + ".csv"
