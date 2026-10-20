package dev.deeptelar.foodie.sources

import dev.deeptelar.foodie.Location
import dev.deeptelar.foodie.Restaurant
import dev.deeptelar.foodie.Shared
import dev.deeptelar.foodie.Sight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToLong

/**
 * What the sources answered once, kept to answer the same questions again without asking them.
 * The sample plans from one: the free services are run for everyone, and a sample that many
 * visitors try at the same time would be many questions to them. Each answer is kept under the
 * question it answers.
 */
@Serializable
data class Recording(
    val places: Map<String, Location?> = emptyMap(),
    val guides: Map<String, GuidePage> = emptyMap(),
    val restaurants: Map<String, List<Restaurant>> = emptyMap(),
    val sights: Map<String, List<Sight>> = emptyMap(),
) {
    /** The recording as JSON, one answer to a line or more, so that a change of it can be read. */
    fun toJson(): String = pretty.encodeToString(this)

    companion object {
        fun fromJson(text: String): Recording = json.decodeFromString(text)

        private val pretty = Json { prettyPrint = true }
    }
}

/**
 * Sources that ask [live] and keep what it answered, for a [recording], and answer from what they
 * kept when they are asked again. Each question to [live] runs in [scope], so it is still kept
 * when the planner stops waiting for it: a busy server that answers late is not asked again on
 * the next run. Reviews are not kept: the sample has none.
 */
class Recorder(
    private val live: Sources,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()

    /** What the sources answered so far. */
    var recording: Recording = Recording()
        private set

    val sources: Sources =
        Sources(
            geocoder = { place ->
                if (place in recording.places) recording.places[place] else ask({ live.geocoder.locate(place) }) { copy(places = places + (place to it)) }
            },
            guide = { place -> recording.guides[place] ?: ask({ live.guide.read(place) }) { copy(guides = guides + (place to it)) } },
            map =
                object : StreetMap {
                    override suspend fun restaurants(stop: String, near: Location, search: MapSearch, radius: Int): List<Restaurant> {
                        val key = restaurantsKey(stop, near, search, radius)
                        return recording.restaurants[key] ?: ask({ live.map.restaurants(stop, near, search, radius) }) { copy(restaurants = restaurants + (key to it)) }
                    }

                    override suspend fun sights(stop: String, near: Location, radius: Int): List<Sight> {
                        val key = sightsKey(stop, near, radius)
                        return recording.sights[key] ?: ask({ live.map.sights(stop, near, radius) }) { copy(sights = sights + (key to it)) }
                    }
                },
        )

    private suspend fun <T> ask(question: suspend () -> T, keep: Recording.(T) -> Recording): T =
        scope.async { question().also { answer -> mutex.withLock { recording = recording.keep(answer) } } }.await()
}

/**
 * Sources that answer from a recording, and ask [live] what it does not have. [load] reads the
 * recording when it is first needed; when it cannot, every question goes to [live]. They have no
 * reviews: the sample, which plans from them, searches no Google.
 */
fun replay(load: suspend () -> Recording, live: Sources): Sources {
    val recorded =
        Shared<Unit, Recording?> {
            try {
                load()
            } catch (e: Throwable) {
                if (!e.isFailure()) throw e
                null
            }
        }
    return Sources(
        geocoder = { place ->
            val recording = recorded.get(Unit)
            if (recording != null && place in recording.places) recording.places[place] else live.geocoder.locate(place)
        },
        guide = { place -> recorded.get(Unit)?.guides?.get(place) ?: live.guide.read(place) },
        map =
            object : StreetMap {
                override suspend fun restaurants(stop: String, near: Location, search: MapSearch, radius: Int) =
                    recorded.get(Unit)?.restaurants?.get(restaurantsKey(stop, near, search, radius)) ?: live.map.restaurants(stop, near, search, radius)

                override suspend fun sights(stop: String, near: Location, radius: Int) =
                    recorded.get(Unit)?.sights?.get(sightsKey(stop, near, radius)) ?: live.map.sights(stop, near, radius)
            },
    )
}

private fun restaurantsKey(stop: String, near: Location, search: MapSearch, radius: Int): String {
    val what =
        when (search) {
            is MapSearch.Cuisine -> "cuisine " + search.words.joinToString(",")
            is MapSearch.Name -> "name " + search.word
            is MapSearch.Dishes -> "dishes " + search.words.joinToString(",")
        }
    return "$stop · ${near.key()} · $what · $radius m"
}

private fun sightsKey(stop: String, near: Location, radius: Int): String = "$stop · ${near.key()} · $radius m"

/** A place to about ten metres, in whole numbers: a browser and the JVM write a decimal number differently. */
private fun Location.key(): String = "${(lat * 10_000).roundToLong()},${(lon * 10_000).roundToLong()}"
