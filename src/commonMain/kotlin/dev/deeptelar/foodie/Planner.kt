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
    const val INTERVIEW = "interview"
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
 * START → interview ⟲ → brief → guide ┐
 *                            → map   ├→ dishes → plan ⇄ tools → order → review → END
 *                            → reviews ┘                 ↑                  │
 *                                                        └── a change ──────┘
 * ```
 *
 * - `interview` asks the traveller what a planner who knows food asks, a few questions at a time,
 *   and reads the answers into a [Profile]. It loops until no topic is open and the traveller has
 *   checked the profile. Each round pauses the run until the traveller answers.
 * - `brief` reads the places of the trip with the model and finds them on the map. When it cannot,
 *   it pauses the run with a question for the traveller.
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

        val interview =
            node(Nodes.INTERVIEW, retry = again) { trip ->
                // The run waits here while questions are open, and while the traveller checks the profile.
                // `resume` adds the answers, or the traveller's yes, and the node goes on from here.
                if (trip.questions.isNotEmpty() || trip.interview.stage == InterviewStage.CHECKING) interrupt(trip)
                // A yes to the profile: nothing new to read.
                if (trip.profileIsCurrent) return@node trip

                val texts = trip.language.texts
                reportProgress(Activity(if (trip.answers.isEmpty()) texts.readingYourRequest else texts.readingYourAnswers))
                val answer = model.ask<Reading>(ChatRequest(listOf(ChatMessage.User(interviewPrompt(trip))), INTERVIEW_SYSTEM))
                trip.copy(tokens = trip.tokens + answer.usage).read(answer.value)
            }

        val brief =
            node(Nodes.BRIEF, retry = again) { trip ->
                // The run waits here while a question is open. `resume` adds the answer and clears the question.
                if (trip.questions.isNotEmpty()) interrupt(trip)

                val texts = trip.language.texts
                reportProgress(Activity(texts.findingYourPlaces))
                val answer = model.ask<Brief>(ChatRequest(listOf(ChatMessage.User(briefPrompt(trip))), BRIEF_SYSTEM))
                val read = trip.copy(brief = answer.value, tokens = trip.tokens + answer.usage)
                val brief = answer.value
                if (brief.stops.isEmpty()) interrupt(read.asking(brief.question.ifBlank { texts.whereQuestion }))

                val (stops, unknown) = located(brief, sources, texts)
                if (unknown.isNotEmpty()) interrupt(read.asking(texts.notOnTheMap(unknown)))
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
                    gather(trip, trip.language.texts.theGuide) { stop ->
                        reportProgress(Activity(trip.language.texts.readingTheGuide(stop.name)))
                        val page = effect("guide ${stop.name}") { sources.guide.read(stop.name) }
                        Found(page.restaurants, guideNotes = if (page.text.isBlank()) emptyMap() else mapOf(stop.name to page.text))
                    }
                },
            ) { trip, found -> trip.with(found) }

        val map =
            node(
                Nodes.MAP,
                work = { trip ->
                    gather(trip, trip.language.texts.theMap) { stop ->
                        reportProgress(Activity(trip.language.texts.lookingOnTheMap(stop.cuisines.firstOrNull() ?: "local", stop.name)))
                        Found(effect("map ${stop.name}") { sources.map.restaurants(stop.name, stop.location, MapSearch.Cuisine(stop.cuisines.ifEmpty { LOCAL_CUISINES }), MAP_RADIUS) })
                    }
                },
            ) { trip, found -> trip.with(found) }

        val reviews =
            sources.reviews?.let { reviews ->
                node(
                    Nodes.REVIEWS,
                    work = { trip ->
                        gather(trip, trip.language.texts.theReviews) { stop ->
                            reportProgress(Activity(trip.language.texts.readingReviews(stop.name)))
                            Found(effect("reviews ${stop.name}") { reviews.search(stop.name, reviewQuery(stop), stop.location) })
                        }
                    },
                ) { trip, found -> trip.with(found) }
            }

        val dishes =
            node(Nodes.DISHES, retry = again) { trip ->
                reportProgress(Activity(trip.language.texts.findingDishes))
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

        START then interview
        // Each round of questions is a step of its own, and a checkpoint: a reload in the middle of the interview keeps the answers.
        conditionalEdge(interview, targets = setOf(interview, brief)) { if (it.interview.stage == InterviewStage.DONE) brief else interview }
        listOfNotNull(guide, map, reviews).forEach { source -> brief then source then dishes }
        dishes then plan
        order then review
        conditionalEdge(review, targets = setOf(plan, NodeRef.END)) { if (it.approved) NodeRef.END else plan }
    }.compile()

/** The trip with a question about where it goes, the one thing the planner cannot go on without. */
private fun Trip.asking(question: String): Trip = copy(questions = listOf(Question(Topic.WHERE, question)))

/** The stops of [brief] that the map knows, with their days counted from the first day of the trip, and the places it does not know. */
private suspend fun located(brief: Brief, sources: Sources, texts: Texts): Pair<List<Stop>, List<String>> {
    val stops = mutableListOf<Stop>()
    val unknown = mutableListOf<String>()
    var day = 1
    for (stop in brief.stops) {
        reportProgress(Activity(texts.findingOnTheMap(stop.place)))
        val location = sources.geocoder.locate(stop.place)
        if (location == null) {
            unknown += stop.place
        } else {
            val days = stop.days.coerceIn(1, MAX_DAYS)
            // Where the traveller sleeps is a help, not a need: a place the map does not know is left out.
            val stay =
                stop.stay.trim().takeIf { it.isNotEmpty() }?.let { stay ->
                    reportProgress(Activity(texts.findingOnTheMap(stay)))
                    sources.geocoder.locate("$stay, ${stop.place}")?.takeIf { metresBetween(it, location) <= STAY_REACH }
                }
            stops += Stop(stop.place, days, firstDay = day, lat = location.lat, lon = location.lon, cuisines = stop.cuisines, stay = stay, shown = stop.shown.trim())
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
                withTimeoutOrNull(SOURCE_PATIENCE) { ask(stop) } ?: Found(notes = listOf(trip.language.texts.tookTooLong(source, stop.name)))
            } catch (e: Throwable) {
                if (!e.isFailure()) throw e
                Found(notes = listOf(trip.language.texts.gaveNoAnswer(source, stop.name, e.message.orEmpty())))
            }
        all = Found(all.restaurants + found.restaurants, all.guideNotes + found.guideNotes, all.notes + found.notes)
    }
    return all
}

private fun Trip.with(found: Found): Trip =
    copy(restaurants = restaurants.merged(found.restaurants), guideNotes = guideNotes + found.guideNotes, notes = notes + found.notes)

/** The meals in the order they are eaten, each with the walk from the meal before it on the same day, or from where the traveller sleeps. */
internal fun ordered(trip: Trip): List<Meal> {
    val sorted = trip.route.sortedWith(compareBy({ it.day }, { it.time }))
    return sorted.mapIndexed { index, meal ->
        val before = sorted.getOrNull(index - 1)?.takeIf { it.day == meal.day }
        val from = if (before == null) trip.stopOn(meal.day)?.stay else trip.restaurant(before.restaurantId)?.location
        val to = trip.restaurant(meal.restaurantId)
        meal.copy(walk = if (from != null && to != null) metresBetween(from, to.location) else null)
    }
}

/** What the reviews are searched for at a stop before the agent asks for anything. */
private fun reviewQuery(stop: Stop): String = "traditional ${stop.cuisines.firstOrNull().orEmpty()} restaurant where locals eat".replace("  ", " ")

/** Around the middle of a stop, in metres: what a traveller reaches on foot or with a short ride. */
internal const val MAP_RADIUS = 3000

/** The cuisine words that mark local food on the map of any country. */
internal val LOCAL_CUISINES = listOf("regional", "local")

/** Where the traveller sleeps counts only this close to the stop, in metres: a hotel of the same name in another town does not. */
private const val STAY_REACH = 30_000

/** A trip is planned for at most this many days at one stop. */
private const val MAX_DAYS = 10

/** How long a source may take for one stop. */
private val SOURCE_PATIENCE = 60.seconds
