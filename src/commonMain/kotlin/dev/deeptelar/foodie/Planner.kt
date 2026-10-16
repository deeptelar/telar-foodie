package dev.deeptelar.foodie

import dev.deeptelar.foodie.sources.MapSearch
import dev.deeptelar.foodie.sources.Sources
import dev.deeptelar.foodie.sources.isFailure
import dev.deeptelar.telar.CompiledGraph
import dev.deeptelar.telar.NodeRef
import dev.deeptelar.telar.Retry
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.ChatModel
import dev.deeptelar.telar.agent.ChatRequest
import dev.deeptelar.telar.agent.ask
import dev.deeptelar.telar.agent.toolLoop
import dev.deeptelar.telar.interrupt
import dev.deeptelar.telar.reportProgress
import dev.deeptelar.telar.serialization.effect
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/** The names of the nodes of the [planner], for the code that shows a run. */
object Nodes {
    const val BRIEF = "brief"
    const val GUIDE = "guide"
    const val MAP = "map"
    const val REVIEWS = "reviews"
    const val DISHES = "dishes"
    const val PLAN = "plan"
    const val TOOLS = "tools"
    const val ORDER = "order"
    const val REVIEW = "review"
}

/** What a node or a tool is doing right now, in words for the traveller. A stream gets it as progress of the node. */
data class Activity(
    val text: String,
)

/**
 * The graph that plans a trip:
 *
 * ```
 * START → brief → guide ┐
 *               → map   ├→ dishes → plan ⇄ tools → order → review → END
 *               → reviews ┘                 ↑                  │
 *                                           └── a change ──────┘
 * ```
 *
 * - `brief` reads the request with the model and finds its places on the map. When it cannot, it
 *   pauses the run with a question for the traveller.
 * - `guide`, `map` and `reviews` ask the three sources at the same time. `reviews` is only part of
 *   the graph when [sources] has a source of reviews.
 * - `dishes` asks the model what each stop is known for.
 * - `plan` and `tools` are an agent: the model chooses restaurants with the tools of `Tools.kt`,
 *   which search the sources again and write the route into the state.
 * - `order` puts the meals in order and measures the walks between them.
 * - `review` is where the run waits for the traveller: pause before it with
 *   `GraphConfig.interruptBefore`. With `approved` the run ends; with a new message from the
 *   traveller in `messages` the agent goes over the route again.
 *
 * @param tokenBudget the agent stops asking the model once it has used this many tokens for the
 *   route, or for one change of it.
 */
fun planner(model: ChatModel, sources: Sources, tokenBudget: Int = 300_000): CompiledGraph<Trip> =
    StateGraph<Trip> {
        // A model that is overloaded for a moment is asked again. The second try starts after a pause.
        val again = Retry(attempts = 2, pause = 2.seconds)

        val brief =
            node(Nodes.BRIEF, retry = again) { trip ->
                // The run waits here while a question is open. `resume` adds the answer to the request and clears the question.
                if (trip.question.isNotEmpty()) interrupt(trip)

                reportProgress(Activity("Reading your request"))
                val answer = model.ask<Brief>(ChatRequest(listOf(ChatMessage.User(briefPrompt(trip.request))), BRIEF_SYSTEM))
                val read = trip.copy(brief = answer.value, tokens = trip.tokens + answer.usage)
                val brief = answer.value
                if (brief.stops.isEmpty()) interrupt(read.copy(question = brief.question.ifBlank { "Where are you going, and for how many days?" }))

                val (stops, unknown) = located(brief, sources)
                if (unknown.isNotEmpty()) {
                    interrupt(read.copy(question = "I could not find ${unknown.joinToString(" or ")} on the map. Which place do you mean?"))
                }
                read.copy(stops = stops)
            }

        // The three sources. Each is a node with a `work`, the slow part that runs for all of them at
        // the same time, and an update that adds what it found to the state, one node after the other.
        // What a source answers for a stop is saved as an effect: when the step runs again, after a
        // reload of the page or a failure, it is not asked again. The street map is slow, and Google
        // bills each search.
        val guide =
            node(
                Nodes.GUIDE,
                work = { trip ->
                    gather(trip, "The travel guide") { stop ->
                        reportProgress(Activity("Reading the travel guide of ${stop.name}"))
                        val page = effect("guide ${stop.name}") { sources.guide.read(stop.name) }
                        Found(page.restaurants, guideNotes = if (page.text.isBlank()) emptyMap() else mapOf(stop.name to page.text))
                    }
                },
            ) { trip, found -> trip.with(found) }

        val map =
            node(
                Nodes.MAP,
                work = { trip ->
                    gather(trip, "The street map") { stop ->
                        reportProgress(Activity("Looking for ${stop.cuisines.firstOrNull() ?: "local"} restaurants on the map of ${stop.name}"))
                        Found(effect("map ${stop.name}") { sources.map.restaurants(stop.name, stop.location, MapSearch.Cuisine(stop.cuisines.ifEmpty { LOCAL_CUISINES }), MAP_RADIUS) })
                    }
                },
            ) { trip, found -> trip.with(found) }

        val reviews =
            sources.reviews?.let { reviews ->
                node(
                    Nodes.REVIEWS,
                    work = { trip ->
                        gather(trip, "Google reviews") { stop ->
                            reportProgress(Activity("Reading reviews in ${stop.name}"))
                            Found(effect("reviews ${stop.name}") { reviews.search(stop.name, reviewQuery(stop), stop.location) })
                        }
                    },
                ) { trip, found -> trip.with(found) }
            }

        val dishes =
            node(Nodes.DISHES, retry = again) { trip ->
                reportProgress(Activity("Finding out what each place is known for"))
                val answer = model.ask<Menu>(ChatRequest(listOf(ChatMessage.User(dishesPrompt(trip))), DISHES_SYSTEM))
                // A dish is kept under the name the stop has in the state, however the model wrote it.
                val known = answer.value.dishes.mapNotNull { dish -> (trip.stop(dish.stop) ?: trip.stops.singleOrNull())?.let { dish.copy(stop = it.name) } }
                trip.copy(dishes = known, tokens = trip.tokens + answer.usage)
            }

        val order = node(Nodes.ORDER) { trip -> trip.copy(route = ordered(trip)) }

        // The place where the run waits for the traveller. It runs when the traveller answered, and a
        // change starts a new turn of the agent, with a budget of its own.
        val review = node(Nodes.REVIEW) { trip -> trip.copy(turnStart = trip.tokens.total) }

        val plan =
            toolLoop(
                model = model,
                stateTools = routeTools(sources),
                messages = { it.messages },
                append = { trip, new -> trip.copy(messages = trip.messages + new) },
                firstMessage = ::planPrompt,
                system = PLAN_SYSTEM,
                then = order,
                modelNode = Nodes.PLAN,
                toolsNode = Nodes.TOOLS,
                countTokens = { trip, used -> trip.copy(tokens = trip.tokens + used) },
                stopWhen = { it.tokens.total - it.turnStart >= tokenBudget },
                retry = again,
            )

        START then brief
        listOfNotNull(guide, map, reviews).forEach { source -> brief then source then dishes }
        dishes then plan
        order then review
        conditionalEdge(review, targets = setOf(plan, NodeRef.END)) { if (it.approved) NodeRef.END else plan }
    }.compile()

/** The stops of [brief] that the map knows, with their days counted from the first day of the trip, and the places it does not know. */
private suspend fun located(brief: Brief, sources: Sources): Pair<List<Stop>, List<String>> {
    val stops = mutableListOf<Stop>()
    val unknown = mutableListOf<String>()
    var day = 1
    for (stop in brief.stops) {
        reportProgress(Activity("Finding ${stop.place} on the map"))
        val location = sources.geocoder.locate(stop.place)
        if (location == null) {
            unknown += stop.place
        } else {
            val days = stop.days.coerceIn(1, MAX_DAYS)
            stops += Stop(stop.place, days, firstDay = day, lat = location.lat, lon = location.lon, cuisines = stop.cuisines)
            day += days
        }
    }
    return stops to unknown
}

/** What one source found for the whole trip. */
internal data class Found(
    val restaurants: List<Restaurant> = emptyList(),
    val guideNotes: Map<String, String> = emptyMap(),
    val notes: List<String> = emptyList(),
)

/**
 * Asks a source for every stop of [trip]. A source that fails for a stop, or takes too long, does
 * not fail the run: the trip is planned with what the other sources found, and a note says what
 * is missing.
 */
private suspend fun gather(trip: Trip, source: String, ask: suspend (Stop) -> Found): Found {
    var all = Found()
    for (stop in trip.stops) {
        val found =
            try {
                withTimeoutOrNull(SOURCE_PATIENCE) { ask(stop) } ?: Found(notes = listOf("$source took too long for ${stop.name}."))
            } catch (e: Throwable) {
                if (!e.isFailure()) throw e
                Found(notes = listOf("$source gave no answer for ${stop.name}: ${e.message}"))
            }
        all = Found(all.restaurants + found.restaurants, all.guideNotes + found.guideNotes, all.notes + found.notes)
    }
    return all
}

private fun Trip.with(found: Found): Trip =
    copy(restaurants = restaurants.merged(found.restaurants), guideNotes = guideNotes + found.guideNotes, notes = notes + found.notes)

/** The meals in the order they are eaten, each with the walk from the meal before it on the same day. */
internal fun ordered(trip: Trip): List<Meal> {
    val sorted = trip.route.sortedWith(compareBy({ it.day }, { it.time }))
    return sorted.mapIndexed { index, meal ->
        val before = sorted.getOrNull(index - 1)?.takeIf { it.day == meal.day }
        val from = before?.let { trip.restaurant(it.restaurantId) }
        val to = trip.restaurant(meal.restaurantId)
        meal.copy(walk = if (from != null && to != null) metresBetween(from.location, to.location) else null)
    }
}

/** What the reviews are searched for at a stop before the agent asks for anything. */
private fun reviewQuery(stop: Stop): String = "traditional ${stop.cuisines.firstOrNull().orEmpty()} restaurant where locals eat".replace("  ", " ")

/** Around the middle of a stop, in metres: what a traveller reaches on foot or with a short ride. */
internal const val MAP_RADIUS = 3000

/** The cuisine words that mark local food on the map of any country. */
internal val LOCAL_CUISINES = listOf("regional", "local")

/** A trip is planned for at most this many days at one stop. */
private const val MAX_DAYS = 10

/** How long a source may take for one stop. */
private val SOURCE_PATIENCE = 60.seconds
