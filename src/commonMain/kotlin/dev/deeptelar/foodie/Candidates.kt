package dev.deeptelar.foodie

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** The distance between two points in metres, along the surface of the earth. */
fun metresBetween(a: Location, b: Location): Int {
    val lat = (b.lat - a.lat).toRadians()
    val lon = (b.lon - a.lon).toRadians()
    val h = sin(lat / 2).pow(2) + cos(a.lat.toRadians()) * cos(b.lat.toRadians()) * sin(lon / 2).pow(2)
    return (2 * EARTH_RADIUS_METRES * asin(sqrt(h))).roundToInt()
}

private fun Double.toRadians(): Double = this * PI / 180

private const val EARTH_RADIUS_METRES = 6_371_000.0

/** Two entries closer than this, with the same name, are one restaurant. */
private const val SAME_PLACE_METRES = 150

/**
 * These restaurants with [found] added. A restaurant that is already known, by its name and where
 * it is, is not added a second time: the known entry gets the source of the new one and whatever
 * the new one knows that it did not. A new restaurant gets the next free id.
 */
fun List<Restaurant>.merged(found: List<Restaurant>): List<Restaurant> {
    val all = toMutableList()
    for (new in found) {
        val index = all.indexOfFirst { it.isSamePlaceAs(new) }
        if (index < 0) {
            all += new.copy(id = "r${all.size + 1}")
        } else {
            val known = all[index]
            all[index] =
                known.copy(
                    cuisine = known.cuisine.ifBlank { new.cuisine },
                    address = known.address.ifBlank { new.address },
                    website = known.website.ifBlank { new.website },
                    hours = known.hours.ifBlank { new.hours },
                    price = known.price.ifBlank { new.price },
                    note = known.note.ifBlank { new.note },
                    rating = known.rating ?: new.rating,
                    ratings = known.ratings ?: new.ratings,
                    sources = known.sources + new.sources,
                )
        }
    }
    return all
}

/** Whether two entries, possibly of two sources, are the same restaurant: the same name at the same spot. */
internal fun Restaurant.isSamePlaceAs(other: Restaurant): Boolean =
    metresBetween(location, other.location) <= SAME_PLACE_METRES && name.simplified() == other.name.simplified()

/**
 * A name without what differs from source to source: case, accents, punctuation and a leading
 * word such as "Restaurante".
 */
internal fun String.simplified(): String {
    val plain = lowercase().map { ACCENTS[it] ?: it }.joinToString("").replace(Regex("[^a-z0-9 ]"), " ")
    val words = plain.split(' ').filter { it.isNotBlank() }
    return words.dropWhile { it in KIND_WORDS && words.size > 1 }.joinToString(" ")
}

private val KIND_WORDS = setOf("restaurante", "restaurant", "ristorante", "trattoria", "osteria", "taberna", "tasca", "cafe", "the", "o", "a", "el", "la", "le")

private val ACCENTS: Map<Char, Char> =
    buildMap {
        "àáâãäå".forEach { put(it, 'a') }
        "èéêë".forEach { put(it, 'e') }
        "ìíîï".forEach { put(it, 'i') }
        "òóôõö".forEach { put(it, 'o') }
        "ùúûü".forEach { put(it, 'u') }
        put('ç', 'c')
        put('ñ', 'n')
    }

/**
 * How much speaks for a restaurant before anyone has read about it: every source that knows it,
 * a description in the travel guide, and a rating that many people gave.
 */
fun Restaurant.evidence(): Double {
    var points = sources.size * 2.0
    if (note.isNotBlank()) points += 1.5
    val stars = rating
    if (stars != null && (ratings ?: 0) >= MIN_RATINGS) points += (stars - 3.5).coerceIn(0.0, 1.5) * 2
    if (cuisine.isNotBlank()) points += 0.5
    return points
}

/** A rating that fewer people gave says little. */
private const val MIN_RATINGS = 50

/** The restaurants of [stop], the ones with the most evidence first. */
fun List<Restaurant>.best(stop: String, limit: Int): List<Restaurant> =
    filter { it.stop.equals(stop, ignoreCase = true) }.sortedByDescending { it.evidence() }.take(limit)

/** A restaurant as one line for the model. */
fun Restaurant.line(): String =
    buildString {
        append('[').append(id).append("] ").append(name)
        if (cuisine.isNotBlank()) append(" · ").append(cuisine)
        append(" · found in: ").append(sources.joinToString(", ") { it.label })
        rating?.let { append(" · ").append(it).append(" stars") }
        ratings?.let { append(" (").append(it).append(" ratings)") }
        if (price.isNotBlank()) append(" · price: ").append(price)
        if (address.isNotBlank()) append(" · ").append(address)
        if (note.isNotBlank()) append(" · guide: \"").append(note.take(NOTE_LENGTH)).append('"')
    }

private const val NOTE_LENGTH = 220
