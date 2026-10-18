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
 * A link that opens the meals of [day] in Google Maps as a walk, in their order, from where the
 * traveller sleeps when the map found it. `null` for a day without meals.
 *
 * A place Google found goes by its name and its id, which shows its name in the app; every other by
 * where it is. The stops between need ids for all of them or for none, so they go by where they
 * are unless Google found each one.
 */
fun Trip.dayInGoogleMaps(day: Int): String? {
    val places = route.filter { it.day == day }.sortedBy { it.time }.mapNotNull { restaurant(it.restaurantId) }
    if (places.isEmpty()) return null
    // Without where the traveller sleeps, the walk starts at the first meal.
    val stay = stopOn(day)?.stay
    val first = places.first().takeIf { stay == null }
    val visits = if (first == null) places else places.drop(1)
    val last = visits.lastOrNull() ?: return places.first().googleMapsLink()
    val between = visits.dropLast(1)
    return buildString {
        append("$MAPS/dir/?api=1&travelmode=walking")
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

/** Where Google My Maps is, to import the file of [toKml]. */
const val MY_MAPS: String = "https://www.google.com/maps/d/"

/**
 * The route as a KML file for Google My Maps: a folder for each day, and a pin for each meal with
 * what to order there, why, and what to watch out for.
 */
fun Trip.toKml(): String {
    val texts = language.texts
    return buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<kml xmlns="http://www.opengis.net/kml/2.2">""")
        appendLine("<Document>")
        appendLine("<name>${xml("Foodie Route · " + stops.joinToString(" · ") { it.title })}</name>")
        var number = 0
        (1..days).forEach { day ->
            val meals = route.filter { it.day == day }.sortedBy { it.time }
            if (meals.isEmpty()) return@forEach
            appendLine("<Folder>")
            appendLine("<name>${xml(texts.dayAt(day, stopOn(day)?.title.orEmpty()))}</name>")
            meals.forEach { meal ->
                number++
                val place = restaurant(meal.restaurantId) ?: return@forEach
                val lines =
                    listOf(
                        texts.order + meal.dish,
                        meal.why,
                        meal.caution.takeIf { it.isNotBlank() }?.let { "⚠ $it" },
                        place.address.takeIf { it.isNotBlank() },
                        listOfNotNull(texts.booking(place.reservation), place.phone.takeIf { it.isNotBlank() }).joinToString(" · ").takeIf { it.isNotBlank() },
                        place.website.takeIf { it.isNotBlank() },
                    ).filterNotNull()
                appendLine("<Placemark>")
                appendLine("<name>${xml("$number. ${texts.meal(meal.time)} · ${place.name}")}</name>")
                appendLine("<description>${xml(lines.joinToString("\n"))}</description>")
                appendLine("<Point><coordinates>${place.lon},${place.lat}</coordinates></Point>")
                appendLine("</Placemark>")
            }
            appendLine("</Folder>")
        }
        appendLine("</Document>")
        appendLine("</kml>")
    }
}

/** [text] as it may stand in the text of an XML element. */
private fun xml(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** The name of the KML file of [trip], beside the file of the plan. */
fun kmlName(trip: Trip, day: String): String = fileName(trip, day).removeSuffix(".json") + ".kml"
