package dev.deeptelar.foodie

import dev.deeptelar.foodie.sources.MapSearch
import dev.deeptelar.foodie.sources.Sources
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.Description
import dev.deeptelar.telar.agent.StateTool
import dev.deeptelar.telar.agent.ToolOutcome
import dev.deeptelar.telar.reportProgress
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/*
 * The tools of the agent that chooses the route. All of them are state tools: they read the trip,
 * and what they find or decide is written into it, so a checkpoint holds it and the page shows it.
 *
 * A tool that throws does not end the run. The model gets the message as the result of its call
 * and can try again, so the messages say what to do next.
 */

@Serializable
data class CandidateSearch(
    @Description("The name of the stop, as in the list of days.")
    val stop: String,
    @Description("Words to look for in the name, the cuisine or the description, for example \"seafood\" or \"market\". Empty lists the best of the stop.")
    val words: String = "",
)

@Serializable
data class MapLookup(
    @Description("The name of the stop, as in the list of days.")
    val stop: String,
    @Description("A cuisine as OpenStreetMap tags it, in lower case, for example \"seafood\", \"pastry\" or \"tapas\". Leave empty to search by name.")
    val cuisine: String = "",
    @Description("A word in the name of the place, for example \"tasca\" or \"mercado\". Leave empty to search by cuisine.")
    val name: String = "",
)

@Serializable
data class GuideLookup(
    @Description("The name of the stop the restaurants are for, as in the list of days.")
    val stop: String,
    @Description("The page of the travel guide to read: a district such as \"Lisbon/Alfama\", a town nearby, or the region or country for its cuisine.")
    val page: String,
)

@Serializable
data class ReviewSearch(
    @Description("The name of the stop, as in the list of days.")
    val stop: String,
    @Description("What to search for, as a person would type it, for example \"best pastel de nata\" or \"family-run seafood restaurant\".")
    val query: String,
)

@Serializable
data class MealChoice(
    @Description("The day of the trip, counted from 1.")
    val day: Int,
    @Description("\"breakfast\", \"lunch\", \"snack\" or \"dinner\".")
    val meal: String,
    @Description("The id of a candidate, for example \"r12\".")
    val restaurant: String,
    @Description("The typical dish to order there.")
    val dish: String,
    @Description("One sentence on what speaks for this place, from what the sources say.")
    val why: String,
    @Description(
        "Only if the traveller has a food allergy: what in this dish or at this place may contain it, and what to tell the staff, in one sentence. " +
            "Empty otherwise.",
    )
    val caution: String = "",
)

@Serializable
data class RoutePlan(
    @Description("The meals to put on the route: when you plan the trip, every meal of every day. A meal that is already there is replaced.")
    val meals: List<MealChoice>,
    @Description(
        "When these meals complete the route: your words to the traveller, three to five sentences on what the trip tastes like and what to look forward to. " +
            "Do not list the restaurants; the traveller sees the route. Empty when the route is not complete yet.",
    )
    val message: String = "",
)

@Serializable
data class MealSlot(
    @Description("The day of the trip, counted from 1.")
    val day: Int,
    @Description("\"breakfast\", \"lunch\", \"snack\" or \"dinner\".")
    val meal: String,
)

/** How many restaurants a search shows the model. */
private const val SHOWN = 15

internal fun routeTools(sources: Sources): List<StateTool<Trip>> =
    searchTools(sources).map { it.nudging() } + routeEditors

/** The tools that look for candidates. */
private fun searchTools(sources: Sources): List<StateTool<Trip>> =
    listOfNotNull(
        StateTool<Trip, CandidateSearch>("candidates", "Lists candidates of a stop that are already known, the ones with the most evidence first.") { trip, search ->
            val stop = trip.knownStop(search.stop)
            val words = search.words.lowercase().split(' ', ',').filter { it.isNotBlank() }
            if (words.any { it.length < 3 }) throw IllegalArgumentException("Search for a word of at least three letters, such as a dish or a cuisine.")
            val matching = trip.restaurants.filter { it.stop == stop.name && it.matches(words) }.sortedByDescending { trip.evidence(it) }
            ToolOutcome(trip, trip.listed(matching, "No candidate in ${stop.name} matches \"${search.words}\". Search the map or the guide."))
        },
        StateTool<Trip, MapLookup>("search_map", "Searches the street map around a stop, by cuisine or by a word in the name. What it finds joins the candidates.") { trip, lookup ->
            val stop = trip.knownStop(lookup.stop)
            val search =
                when {
                    lookup.name.isNotBlank() -> MapSearch.Name(lookup.name)
                    lookup.cuisine.isNotBlank() -> MapSearch.Cuisine(listOf(lookup.cuisine))
                    else -> throw IllegalArgumentException("Give a cuisine or a name to search for.")
                }
            reportProgress(Activity(trip.language.texts.searchingTheMap(stop.title, lookup.name.ifBlank { lookup.cuisine })))
            trip.found(sources.map.restaurants(stop.name, stop.location, search, MAP_RADIUS), "The map has no such place in ${stop.name}.")
        },
        StateTool<Trip, GuideLookup>("read_guide", "Reads another page of the travel guide. Returns what it says about the food, and adds the restaurants it lists to the candidates of the stop.") { trip, lookup ->
            val stop = trip.knownStop(lookup.stop)
            reportProgress(Activity(trip.language.texts.readingTheGuide(lookup.page)))
            val page = sources.guide.read(lookup.page)
            // The page is read for the stop, so its restaurants are candidates of the stop, as long as they are within reach of it.
            val near = page.restaurants.map { it.copy(stop = stop.name) }.filter { metresBetween(it.location, stop.location) <= GUIDE_REACH }
            val outcome = trip.found(near, "The page lists no restaurants near ${stop.name}.")
            outcome.copy(text = page.text.take(GUIDE_TEXT).ifBlank { "The guide has no text about eating in ${lookup.page}." } + "\n\n" + outcome.text)
        },
        sources.reviews?.let { reviews ->
            StateTool<Trip, ReviewSearch>("search_reviews", "Searches Google for places at a stop, with their ratings. What it finds joins the candidates.") { trip, search ->
                val stop = trip.knownStop(search.stop)
                reportProgress(Activity(trip.language.texts.searchingReviews(stop.title, search.query)))
                trip.found(reviews.search(stop.name, search.query, stop.location), "Google found nothing for \"${search.query}\".")
            }
        },
    )

/**
 * The tools that write the route. The agent writes the whole route with `set_route` in one answer,
 * with its message to the traveller: every round of the agent sends the whole conversation to the
 * model again, so a route written meal by meal takes many slow rounds. `set_meal` and `remove_meal`
 * are for the changes after it.
 */
private val routeEditors: List<StateTool<Trip>> =
    listOf(
        StateTool<Trip, RoutePlan>(
            "set_route",
            "Puts several meals on the route at once, and when they complete it, gives the traveller your message. " +
                "A meal that cannot be put on the route is named in the result, and the others are on it.",
        ) { trip, plan ->
            if (plan.meals.isEmpty()) throw IllegalArgumentException("Give the meals to put on the route.")
            var after = trip
            val problems =
                plan.meals.mapNotNull { choice ->
                    try {
                        after = after.withMeal(choice)
                        null
                    } catch (e: IllegalArgumentException) {
                        "- Day ${choice.day} ${choice.meal}: ${e.message}"
                    }
                }
            val twice = after.route.groupBy { it.restaurantId }.filterValues { it.size > 1 }.keys.mapNotNull { after.restaurant(it)?.name }
            val text =
                buildString {
                    append("On the route: ${plan.meals.size - problems.size} of ${plan.meals.size} meals.")
                    if (problems.isNotEmpty()) append("\nNot on the route:\n" + problems.joinToString("\n"))
                    if (twice.isNotEmpty()) append("\nMore than once on the route: ${twice.joinToString()}. Use a restaurant once.")
                    append(" " + after.openMeals())
                    if (plan.message.isNotBlank()) {
                        append(if (after.finishedBy(plan)) " The route is done, and the traveller reads your message." else " Your message is not shown yet: fix this, then send the message again.")
                    }
                }
            ToolOutcome(after, text)
        },
        StateTool<Trip, MealChoice>("set_meal", "Puts a restaurant on the route for one meal of one day. A meal that is already there is replaced.") { trip, choice ->
            val after = trip.withMeal(choice)
            val restaurant = trip.restaurant(choice.restaurant)!!
            val twice = after.route.count { it.restaurantId == restaurant.id } > 1
            ToolOutcome(
                after,
                "Day ${choice.day}, ${mealTime(choice.meal).label.lowercase()}: ${restaurant.name}." +
                    (if (twice) " It is on the route more than once now." else "") +
                    " " + after.openMeals(),
            )
        },
        StateTool<Trip, MealSlot>("remove_meal", "Takes a meal off the route.") { trip, slot ->
            val time = mealTime(slot.meal)
            if (trip.route.none { it.day == slot.day && it.time == time }) throw IllegalArgumentException("Day ${slot.day} has no ${time.label.lowercase()} on the route.")
            val after = trip.copy(route = trip.route.filterNot { it.day == slot.day && it.time == time })
            ToolOutcome(after, "Removed. " + after.openMeals())
        },
    )

/**
 * The trip with [choice] on its route, in the place of the meal of its day and time.
 *
 * @throws IllegalArgumentException with what is wrong, for the model, when the day, the meal or the restaurant is not one of the trip.
 */
private fun Trip.withMeal(choice: MealChoice): Trip {
    val time = mealTime(choice.meal)
    val stop = stopOn(choice.day) ?: throw IllegalArgumentException("The trip has $days days. There is no day ${choice.day}.")
    val restaurant = restaurant(choice.restaurant) ?: throw IllegalArgumentException("There is no candidate with the id \"${choice.restaurant}\". Use an id from the list.")
    if (restaurant.stop != stop.name) throw IllegalArgumentException("${restaurant.name} is in ${restaurant.stop}, but day ${choice.day} is in ${stop.name}.")
    val meal = Meal(choice.day, time, restaurant.id, choice.dish.trim(), choice.why.trim(), choice.caution.trim())
    return copy(route = route.filterNot { it.day == meal.day && it.time == meal.time } + meal)
}

/** Whether [plan] finished the route: it has a message, each of its meals is on the route, no meal is open and no restaurant is there twice. */
private fun Trip.finishedBy(plan: RoutePlan): Boolean =
    plan.message.isNotBlank() &&
        plan.meals.all { choice -> route.any { it.day == choice.day && it.time == MealTime.of(choice.meal) && it.restaurantId == choice.restaurant.trim() } } &&
        (1..days).all { day -> dailyMeals.all { time -> route.any { it.day == day && it.time == time } } } &&
        route.groupBy { it.restaurantId }.values.none { it.size > 1 }

/**
 * The route plan of the agent's last answer, when that answer finished the route and all of its
 * calls ran: then the model need not be asked again, and its message is the summary.
 */
internal val Trip.finishingPlan: RoutePlan?
    get() {
        val results = messages.takeLastWhile { it is ChatMessage.ToolResult }.filterIsInstance<ChatMessage.ToolResult>()
        val answer = messages.getOrNull(messages.size - results.size - 1) as? ChatMessage.Assistant ?: return null
        if (results.isEmpty() || results.size < answer.toolCalls.size || results.any { it.isError }) return null
        val call = answer.toolCalls.lastOrNull { it.name == "set_route" } ?: return null
        val plan = runCatching { LENIENT.decodeFromJsonElement<RoutePlan>(call.input) }.getOrNull() ?: return null
        return plan.takeIf { finishedBy(it) }
    }

private val LENIENT = Json { ignoreUnknownKeys = true }

/**
 * This search tool, with a reminder in its result once the agent has searched [SEARCHES] times in
 * this turn: a model that keeps searching never writes the route, and its token budget runs out.
 */
private fun StateTool<Trip>.nudging(): StateTool<Trip> =
    StateTool(spec) { trip, input ->
        val outcome = execute(trip, input)
        val searches = trip.searchesThisTurn()
        if (searches < SEARCHES) {
            outcome
        } else {
            outcome.copy(text = outcome.text + "\n\nYou have searched $searches times. Stop searching and write the route now with set_route, from the candidates you have. ${outcome.state.openMeals()}")
        }
    }

/** How many searches the agent asked for since the last message of the traveller, the calls of the answer that runs now included. */
private fun Trip.searchesThisTurn(): Int {
    val turn = messages.drop(messages.indexOfLast { it is ChatMessage.User } + 1)
    return turn.filterIsInstance<ChatMessage.Assistant>().sumOf { answer -> answer.toolCalls.count { it.name !in EDITORS } }
}

private val EDITORS = setOf("set_route", "set_meal", "remove_meal")

/** After this many searches in one turn, every search result asks the agent to write the route. */
private const val SEARCHES = 8

private fun Trip.knownStop(name: String): Stop =
    stop(name) ?: stops.singleOrNull() ?: throw IllegalArgumentException("There is no stop named \"$name\". The stops are: ${stops.joinToString { it.name }}.")

private fun mealTime(word: String): MealTime =
    MealTime.of(word) ?: throw IllegalArgumentException("\"$word\" is no meal. Use breakfast, lunch, snack or dinner.")

private fun Restaurant.matches(words: List<String>): Boolean {
    val text = "$name $kind $cuisine $note".lowercase()
    return words.all { it in text }
}

/** The trip with [found] among its candidates, and the found restaurants as text, with the ids they have now. */
private fun Trip.found(found: List<Restaurant>, nothing: String): ToolOutcome<Trip> {
    val after = copy(restaurants = restaurants.merged(found))
    // Looked up in the merged list: a restaurant that was already known keeps its id.
    val listed = found.mapNotNull { new -> after.restaurants.firstOrNull { it.isSamePlaceAs(new) } }
    return ToolOutcome(after, after.listed(listed.distinctBy { it.id }.sortedByDescending { after.evidence(it) }, nothing))
}

private fun Trip.listed(found: List<Restaurant>, nothing: String): String =
    if (found.isEmpty()) {
        nothing
    } else {
        found.take(SHOWN).joinToString("\n") { line(it) } + if (found.size > SHOWN) "\n(${found.size - SHOWN} more. Search with other words to see them.)" else ""
    }

/** The meals of every day: the ones the traveller asked for, or a lunch and a dinner. */
internal val Trip.dailyMeals: List<MealTime> get() = profile.meals.distinct().sorted().ifEmpty { listOf(MealTime.LUNCH, MealTime.DINNER) }

/** The meals of [dailyMeals] the route still lacks, in words for the model. */
internal fun Trip.openMeals(): String {
    val open =
        (1..days).flatMap { day ->
            dailyMeals.filter { time -> route.none { it.day == day && it.time == time } }.map { "day $day ${it.label.lowercase()}" }
        }
    return if (open.isEmpty()) "Every day has its ${dailyMeals.joinToString(" and ") { it.label.lowercase() }}." else "Still open: ${open.joinToString()}."
}

/** A restaurant of another page of the guide is a candidate of a stop when it is within this distance of it, in metres. */
private const val GUIDE_REACH = 40_000

private const val GUIDE_TEXT = 3000
