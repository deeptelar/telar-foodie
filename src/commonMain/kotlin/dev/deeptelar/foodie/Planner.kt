package dev.deeptelar.foodie

import dev.deeptelar.foodie.sources.GuidePage
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
import dev.deeptelar.telar.agent.TokenUsage
import dev.deeptelar.telar.agent.ChatRequest
import dev.deeptelar.telar.agent.ask
import dev.deeptelar.telar.agent.toolLoop
import dev.deeptelar.telar.interrupt
import dev.deeptelar.telar.reportProgress
import dev.deeptelar.telar.serialization.effect
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

/** The names of the nodes of the [planner], for the code that shows a run. */
object Nodes {
    const val INTERVIEW = "interview"
    const val BRIEF = "brief"
    const val GUIDE = "guide"
    const val MAP = "map"
    const val REVIEWS = "reviews"
    const val DISHES = "dishes"
    const val SPECIALISTS = "specialists"
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
 * The dishes the `dishes` node named, with the tokens that took. A stream gets them as progress as
 * soon as they are named: the state has them only when the sources are done too.
 */
@Serializable
data class Named(
    val dishes: List<Dish>,
    val usage: TokenUsage = TokenUsage(),
)

/**
 * The graph that plans a trip:
 *
 * ```
 * START → interview ⟲ → brief → guide   ┐
 *                            → map     ├→ specialists → plan ⇄ tools → order → review → END
 *                            → reviews │                  ↑                  │
 *                            → dishes  ┘                  └── a change ──────┘
 * ```
 *
 * - `interview` asks the traveller what a planner who knows food asks, a few questions at a time,
 *   and reads the answers into a [Profile]. It loops until no topic is open and the traveller has
 *   checked the profile. Each round pauses the run until the traveller answers.
 * - `brief` reads the places of the trip with the model and finds them on the map. When it cannot,
 *   it pauses the run with a question for the traveller.
 * - `guide`, `map` and `reviews` ask the three sources at the same time. `reviews` is only part of
 *   the graph when [sources] has a source of reviews. The map also gives the big sights, since a
 *   place next to one lives off the visitors who pass.
 * - `dishes` asks the model what each stop is known for, at the same time: it needs only the guide.
 * - `specialists` searches the map, and the reviews, for the places known for each of those dishes.
 * - `plan` and `tools` are an agent: the model chooses restaurants with the tools of `Tools.kt`,
 *   which search the sources again and write the route into the state.
 * - `order` puts the meals in order and measures the walks between them.
 * - `review` is where the run waits for the traveller: pause before it with
 *   `GraphConfig.interruptBefore`. With `approved` the run ends; with a new message from the
 *   traveller in `messages` the agent goes over the route again.
 *
 * @param tokenBudget the agent stops asking the model once it has used this many tokens for the
 *   route, or for one change of it.
 * @param planModel the model of the agent that chooses the restaurants, for example [model] set to
 *   think less: its rounds are the longest wait of a plan.
 */
fun planner(model: ChatModel, sources: Sources, tokenBudget: Int = 300_000, planModel: ChatModel = model): CompiledGraph<Trip> =
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
                // The profile comes first in the answer, then the questions: each is shown when it is written.
                val writing = model.watched { written -> finishedValues(written, "text").lastOrNull()?.let(texts::asking) ?: texts.notingWhatYouSaid }
                val answer = writing.ask<Reading>(ChatRequest(listOf(ChatMessage.User(interviewPrompt(trip))), INTERVIEW_SYSTEM))
                trip.copy(tokens = trip.tokens + answer.usage).read(answer.value)
            }

        val brief =
            node(Nodes.BRIEF, retry = again) { trip ->
                // The run waits here while a question is open. `resume` adds the answer and clears the question.
                if (trip.questions.isNotEmpty()) interrupt(trip)

                val texts = trip.language.texts
                reportProgress(Activity(texts.findingYourPlaces))
                val writing = model.watched { written -> finishedValues(written, "place").takeIf { it.isNotEmpty() }?.let(texts::foundPlaces) }
                val answer = writing.ask<Brief>(ChatRequest(listOf(ChatMessage.User(briefPrompt(trip))), BRIEF_SYSTEM))
                val read = trip.copy(brief = answer.value, tokens = trip.tokens + answer.usage)
                val brief = answer.value
                if (brief.stops.isEmpty()) interrupt(read.asking(brief.question.ifBlank { texts.whereQuestion }))

                val (stops, unknown) = located(brief, sources, texts)
                if (unknown.isNotEmpty()) interrupt(read.asking(texts.notOnTheMap(unknown)))
                read.copy(stops = stops)
            }

        // The three sources and the dishes. Each is a node with a `work`, the slow part that runs for
        // all of them at the same time, and an update that adds what it found to the state, one node
        // after the other. What a source answers for a stop is saved as an effect: when the step runs
        // again, after a reload of the page or a failure, it is not asked again. The street map is
        // slow, and Google bills each search.
        // The dishes need what the guide says, and not the map: the guide is read once for both, so
        // the dishes are named while the map is still searched.
        val pages = Shared(sources.guide::read)
        val guide =
            node(
                Nodes.GUIDE,
                work = { trip ->
                    gather(trip, trip.language.texts.theGuide) { stop ->
                        reportProgress(Activity(trip.language.texts.readingTheGuide(stop.title)))
                        val page = effect("guide ${stop.name}") { pages.get(stop.name) }
                        Found(page.restaurants, guideNotes = if (page.text.isBlank()) emptyMap() else mapOf(stop.name to page.text))
                    }
                },
            ) { trip, found -> trip.with(found) }

        val map =
            node(
                Nodes.MAP,
                work = { trip ->
                    gather(trip, trip.language.texts.theMap) { stop ->
                        reportProgress(Activity(trip.language.texts.lookingOnTheMap(stop.cuisines.firstOrNull() ?: "local", stop.title)))
                        val found = effect("map ${stop.name}") { sources.map.restaurants(stop.name, stop.location, MapSearch.Cuisine(stop.cuisines.ifEmpty { LOCAL_CUISINES }), MAP_RADIUS) }
                        // The sights are a help, not a need: without them, or when they are slow, the restaurants are still candidates.
                        val sights =
                            try {
                                withTimeoutOrNull(SIGHTS_PATIENCE) { effect("sights ${stop.name}") { sources.map.sights(stop.name, stop.location, MAP_RADIUS) } }.orEmpty()
                            } catch (e: Throwable) {
                                if (!e.isFailure()) throw e
                                emptyList()
                            }
                        Found(found, sights = sights)
                    }
                },
            ) { trip, found -> trip.with(found) }

        val reviews =
            sources.reviews?.let { reviews ->
                node(
                    Nodes.REVIEWS,
                    work = { trip ->
                        gather(trip, trip.language.texts.theReviews) { stop ->
                            reportProgress(Activity(trip.language.texts.readingReviews(stop.title)))
                            Found(effect("reviews ${stop.name}") { reviews.search(stop.name, reviewQuery(stop), stop.location) })
                        }
                    },
                ) { trip, found -> trip.with(found) }
            }

        val dishes =
            node(
                Nodes.DISHES,
                retry = again,
                work = { trip ->
                    val texts = trip.language.texts
                    reportProgress(Activity(texts.readingTheGuideFirst))
                    val notes = guideNotes(trip, pages)
                    reportProgress(Activity(texts.findingDishes))
                    val prompt = dishesPrompt(trip.copy(guideNotes = notes))
                    // Saved, as the sources are: a step that runs again does not ask the model again. A changed profile is a new prompt, and asks.
                    effect("dishes ${prompt.hashCode()}") {
                        val writing = model.watched { written -> finishedValues(written, "name").takeIf { it.isNotEmpty() }?.let { texts.namingDishes(it.takeLast(4)) } }
                        val answer = writing.ask<Menu>(ChatRequest(listOf(ChatMessage.User(prompt)), DISHES_SYSTEM))
                        // A dish is kept under the name the stop has in the state, however the model wrote it.
                        val known = answer.value.dishes.mapNotNull { dish -> (trip.stop(dish.stop) ?: trip.stops.singleOrNull())?.let { dish.copy(stop = it.name) } }
                        Named(known, answer.usage ?: TokenUsage())
                    }.also { reportProgress(Named(it.dishes)) }
                },
            ) { trip, named -> trip.copy(dishes = named.dishes, tokens = trip.tokens + named.usage) }

        // The places known for a dish of the stop: a planner who knows food goes to the one place for a francesinha.
        val specialists =
            node(
                Nodes.SPECIALISTS,
                work = { trip ->
                    gather(trip, trip.language.texts.theMap) { stop ->
                        val dishes = trip.dishes.filter { it.stop == stop.name && it.words.isNotEmpty() }
                        if (dishes.isEmpty()) return@gather Found()
                        reportProgress(Activity(trip.language.texts.lookingForSpecialists(stop.title)))
                        val words = dishes.flatMap { it.words }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                        // A map that does not answer leaves the reviews to search: the map's own step already said that it did not.
                        val onMap =
                            try {
                                effect("specialists ${stop.name} ${words.hashCode()}") { sources.map.restaurants(stop.name, stop.location, MapSearch.Dishes(words), MAP_RADIUS) }
                            } catch (e: Throwable) {
                                if (!e.isFailure()) throw e
                                emptyList()
                            }
                        // Google bills each search: only the first few dishes of a stop, which a model names first, are searched there.
                        val reviewed =
                            sources.reviews?.let { reviews ->
                                dishes.take(REVIEWED_DISHES).flatMap { dish ->
                                    try {
                                        effect("specialists ${stop.name} ${dish.name}") { reviews.search(stop.name, "best ${dish.name} in ${stop.name}", stop.location) }
                                    } catch (e: Throwable) {
                                        if (!e.isFailure()) throw e
                                        emptyList()
                                    }
                                }
                            }.orEmpty()
                        Found(onMap + reviewed)
                    }
                },
            ) { trip, found -> trip.with(found) }

        val order = node(Nodes.ORDER) { trip -> trip.copy(route = ordered(trip)) }

        // The place where the run waits for the traveller. It runs when the traveller answered, and a
        // change starts a new turn of the agent, with a budget of its own.
        // What the traveller chose of the places the agent offered goes before their words.
        val review = node(Nodes.REVIEW) { trip -> trip.copy(turnStart = trip.tokens.total).withPicksTold() }

        val plan =
            toolLoop(
                model = planModel,
                stateTools = routeTools(sources),
                messages = { it.messages },
                append = { trip, new -> trip.copy(messages = trip.messages + new) },
                firstMessage = ::planPrompt,
                system = PLAN_SYSTEM,
                then = order,
                modelNode = Nodes.PLAN,
                toolsNode = Nodes.TOOLS,
                countTokens = { trip, used -> trip.copy(tokens = trip.tokens + used) },
                // A route that the agent's last answer finished needs no more rounds: its message is the summary.
                stopWhen = { it.tokens.total - it.turnStart >= tokenBudget || it.finishingPlan != null },
                retry = again,
            )

        START then interview
        // Each round of questions is a step of its own, and a checkpoint: a reload in the middle of the interview keeps the answers.
        conditionalEdge(interview, targets = setOf(interview, brief), labels = mapOf(interview to "a round of questions", brief to "checked")) {
            if (it.interview.stage == InterviewStage.DONE) brief else interview
        }
        listOfNotNull(guide, map, reviews, dishes).forEach { node -> brief then node then specialists }
        specialists then plan
        order then review
        conditionalEdge(review, targets = setOf(plan, NodeRef.END), labels = mapOf(plan to "a change", NodeRef.END to "yes")) { if (it.approved) NodeRef.END else plan }
    }.compile()

/** The trip with a question about where it goes, the one thing the planner cannot go on without. */
private fun Trip.asking(question: String): Trip = copy(questions = listOf(Question(Topic.WHERE, question)))

/** The stops of [brief] that the map knows, with their days counted from the first day of the trip, and the places it does not know. */
private suspend fun located(brief: Brief, sources: Sources, texts: Texts): Pair<List<Stop>, List<String>> {
    val stops = mutableListOf<Stop>()
    val unknown = mutableListOf<String>()
    var day = 1
    for (stop in brief.stops) {
        reportProgress(Activity(texts.findingOnTheMap(stop.shown.ifBlank { stop.place })))
        val location = sources.geocoder.locate(stop.place)
        if (location == null) {
            unknown += stop.place
        } else {
            val days = stop.days.coerceIn(1, MAX_DAYS)
            // Where the traveller sleeps is a help, not a need: a place the map does not know is left out.
            suspend fun sleeping(place: String): Location? {
                reportProgress(Activity(texts.findingOnTheMap(place)))
                return sources.geocoder.locate("$place, ${stop.place}")?.takeIf { metresBetween(it, location) <= STAY_REACH }
            }
            val stay = stop.stay.trim().takeIf { it.isNotEmpty() }?.let { sleeping(it) }
            // A later hotel counts by its night of this stop; one the map does not know is left out, and the one before it goes on.
            val stays =
                stop.moves.filter { it.place.isNotBlank() && it.fromDay - day + 1 in 2..days }.sortedBy { it.fromDay }.mapNotNull { move ->
                    sleeping(move.place.trim())?.let { Stay(move.place.trim(), move.fromDay - day + 1, it.lat, it.lon) }
                }
            stops +=
                Stop(
                    stop.place, days, firstDay = day, lat = location.lat, lon = location.lon, cuisines = stop.cuisines, stay = stay, shown = stop.shown.trim(),
                    stayName = if (stay == null) "" else stop.stay.trim(), stays = stays,
                )
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
    val sights: List<Sight> = emptyList(),
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
        all = Found(all.restaurants + found.restaurants, all.guideNotes + found.guideNotes, all.notes + found.notes, all.sights + found.sights)
    }
    return all
}

/**
 * What the guide says about eating at each stop, for the dishes. The guide's node reads the same
 * pages, and [pages] reads each once. A stop the guide has nothing for, or no answer within the
 * patience of a source, has no notes: the dishes are named without them.
 */
private suspend fun guideNotes(trip: Trip, pages: Shared<String, GuidePage>): Map<String, String> =
    coroutineScope {
        trip.stops.map { stop ->
            async {
                val text =
                    try {
                        withTimeoutOrNull(SOURCE_PATIENCE) { pages.get(stop.name) }?.text.orEmpty()
                    } catch (e: Throwable) {
                        if (!e.isFailure()) throw e
                        ""
                    }
                stop.name to text
            }
        }.awaitAll().filter { it.second.isNotBlank() }.toMap()
    }

private fun Trip.with(found: Found): Trip =
    copy(restaurants = restaurants.merged(found.restaurants), guideNotes = guideNotes + found.guideNotes, notes = notes + found.notes, sights = sights + found.sights)

/** The meals in the order they are eaten, each with the walk from the meal before it on the same day, or from where the traveller slept. */
internal fun ordered(trip: Trip): List<Meal> {
    val sorted = trip.route.sortedWith(compareBy({ it.day }, { it.time }))
    return sorted.mapIndexed { index, meal ->
        val before = sorted.getOrNull(index - 1)?.takeIf { it.day == meal.day }
        val from = if (before == null) trip.stayFor(meal.day, meal.time) else trip.restaurant(before.restaurantId)?.location
        val to = trip.restaurant(meal.restaurantId)
        meal.copy(walk = if (from != null && to != null) metresBetween(from, to.location) else null)
    }
}

/** What the reviews are searched for at a stop before the agent asks for anything. */
private fun reviewQuery(stop: Stop): String = "traditional ${stop.cuisines.firstOrNull().orEmpty()} restaurant where locals eat".replace("  ", " ")

/** How many dishes of a stop are searched in the reviews, when there are reviews. */
private const val REVIEWED_DISHES = 3

/** Around the middle of a stop, in metres: what a traveller reaches on foot or with a short ride. */
internal const val MAP_RADIUS = 3000

/** The cuisine words that mark local food on the map of any country. */
internal val LOCAL_CUISINES = listOf("regional", "local")

/** Where the traveller sleeps counts only this close to the stop, in metres: a hotel of the same name in another town does not. */
internal const val STAY_REACH = 30_000

/** A trip is planned for at most this many days at one stop. */
internal const val MAX_DAYS = 10

/** How long a source may take for one stop: long enough for the map to try each of its five servers. */
private val SOURCE_PATIENCE = 80.seconds

/** How long the map may take for the sights of a stop, after its restaurants: together they stay within [SOURCE_PATIENCE]. */
private val SIGHTS_PATIENCE = 15.seconds
