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
                    kind = known.kind.ifBlank { new.kind },
                    address = known.address.ifBlank { new.address },
                    website = known.website.ifBlank { new.website },
                    hours = known.hours.ifBlank { new.hours },
                    price = known.price.ifBlank { new.price },
                    phone = known.phone.ifBlank { new.phone },
                    reservation = known.reservation.ifBlank { new.reservation },
                    note = known.note.ifBlank { new.note },
                    googleId = known.googleId.ifBlank { new.googleId },
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
 * What [restaurant] is known for: the first dish of its stop whose words are in its name or its
 * cuisine, such as "francesinha" in "Café Santiago · francesinha". `null` for a place that is not
 * known for one thing.
 */
fun Trip.speciality(restaurant: Restaurant): Dish? {
    val text = " " + "${restaurant.name} ${restaurant.cuisine}".simplified() + " "
    return dishes.firstOrNull { dish ->
        dish.stop == restaurant.stop && dish.words.map { it.simplified() }.any { it.length >= MIN_WORD && " $it" in text }
    }
}

/** A word shorter than this matches too much: "pao" is in many names. */
private const val MIN_WORD = 4

/** The nearest big sight to [restaurant] within [SIGHT_METRES], and how far it is. */
fun Trip.nextToASight(restaurant: Restaurant): Pair<Sight, Int>? =
    sights.asSequence()
        .filter { it.stop == restaurant.stop }
        .map { it to metresBetween(it.location, restaurant.location) }
        .filter { it.second <= SIGHT_METRES }
        .minByOrNull { it.second }

/**
 * A place this close to a big sight lives off the visitors who pass, in metres. In the centre of
 * Lisbon, 60 m marks 1 place in 25, among them the tourist cafés under the Santa Justa lift; 120 m
 * marked 1 in 5, and most of the old taverns with them.
 */
internal const val SIGHT_METRES = 60

/**
 * How much speaks for [restaurant] before anyone has read about it. Being known for a dish of the
 * stop counts most, then a good rating that many people gave, then the stop's own cuisine and each
 * source that knows the place. Being in the travel guide is no more than being on the map: the
 * guide writes for visitors. A place next to a big sight counts less.
 */
fun Trip.evidence(restaurant: Restaurant): Double {
    var points = restaurant.sources.size.toDouble()
    if (speciality(restaurant) != null) points += 2.5
    val stars = restaurant.rating
    if (stars != null && (restaurant.ratings ?: 0) >= MIN_RATINGS) points += (stars - 3.5).coerceIn(0.0, 1.5) * 2
    val cuisines = stop(restaurant.stop)?.cuisines.orEmpty()
    if (cuisines.any { it.isNotBlank() && it.replace('_', ' ') in restaurant.cuisine.lowercase() }) points += 1.0
    if (nextToASight(restaurant) != null) points -= 1.5
    return points
}

/** A rating that fewer people gave says little. */
private const val MIN_RATINGS = 50

/**
 * The restaurants of [stop] a model reads first: for each dish of the stop the two places known for
 * it with the most evidence, and then the others with the most evidence, [limit] in all.
 */
fun Trip.best(stop: String, limit: Int): List<Restaurant> {
    val here = restaurants.filter { it.stop.equals(stop, ignoreCase = true) && it.id !in rejected }.sortedByDescending { evidence(it) }
    val specialists = dishes.filter { it.stop.equals(stop, ignoreCase = true) }.flatMap { dish -> here.filter { speciality(it) == dish }.take(2) }
    return (specialists + here).distinctBy { it.id }.take(limit)
}

/** [restaurant] as one line for the model. */
fun Trip.line(restaurant: Restaurant): String =
    buildString {
        with(restaurant) {
            append('[').append(id).append("] ").append(name)
            if (kind.isNotBlank() && kind != "restaurant") append(" · ").append(kind.replace('_', ' '))
            if (cuisine.isNotBlank()) append(" · ").append(cuisine)
            speciality(restaurant)?.let { append(" · known for: ").append(it.name) }
            append(" · found in: ").append(sources.joinToString(", ") { it.label })
            rating?.let { append(" · ").append(it).append(" stars") }
            ratings?.let { append(" (").append(it).append(" ratings)") }
            if (price.isNotBlank()) append(" · price: ").append(price)
            if (reservation.isNotBlank()) append(" · bookings: ").append(reservation)
            if (address.isNotBlank()) append(" · ").append(address)
            nextToASight(restaurant)?.let { (sight, metres) -> append(" · ").append(metres).append(" m from ").append(sight.name) }
            if (note.isNotBlank()) append(" · guide: \"").append(note.take(NOTE_LENGTH)).append('"')
        }
    }

private const val NOTE_LENGTH = 220
