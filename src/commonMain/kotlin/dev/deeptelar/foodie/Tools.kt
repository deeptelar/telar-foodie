package dev.deeptelar.foodie

import dev.deeptelar.foodie.sources.MapSearch
import dev.deeptelar.foodie.sources.Sources
import dev.deeptelar.foodie.sources.isFailure
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
    @Description(
        "A place to search around, within about a kilometre, instead of the middle of the stop: a neighbourhood, a street, a sight, " +
            "or where the traveller sleeps, as the list of days names it. Empty for the whole stop.",
    )
    val near: String = "",
)

@Serializable
data class MapLookup(
    @Description("The name of the stop, as in the list of days.")
    val stop: String,
    @Description("A cuisine as OpenStreetMap tags it, in lower case, for example \"seafood\", \"pastry\" or \"tapas\". Leave empty to search by name.")
    val cuisine: String = "",
    @Description("A word in the name of the place, for example \"tasca\" or \"mercado\". Leave empty to search by cuisine.")
    val name: String = "",
    @Description(
        "A place to search around, within about a kilometre, instead of the middle of the stop: a neighbourhood, a street, a sight, " +
            "or where the traveller sleeps, as the list of days names it. Empty for the whole stop.",
    )
    val near: String = "",
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
    @Description(
        "A place to search around, within about a kilometre, instead of the middle of the stop: a neighbourhood, a street, a sight, " +
            "or where the traveller sleeps, as the list of days names it. Empty for the whole stop.",
    )
    val near: String = "",
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

@Serializable
data class MealOption(
    @Description("The id of a candidate, for example \"r12\".")
    val restaurant: String,
    @Description("The typical dish to order there.")
    val dish: String,
    @Description("One sentence on what speaks for this place, from what the sources say.")
    val why: String,
    @Description("Only if the traveller has a food allergy or someone is pregnant: what to watch out for, in one sentence. Empty otherwise.")
    val caution: String = "",
)

@Serializable
data class MealOffer(
    @Description("The day of the trip, counted from 1.")
    val day: Int,
    @Description("\"breakfast\", \"lunch\", \"snack\" or \"dinner\".")
    val meal: String,
    @Description("Two or three places for the meal, the one you recommend first.")
    val options: List<MealOption>,
    @Description("One sentence for the traveller: why you recommend the first over the others.")
    val recommendation: String,
)

@Serializable
data class StopDays(
    @Description("The name of the stop, as in the list of days.")
    val stop: String,
    @Description("How many days the traveller eats there, in all: the days already planned count.")
    val days: Int,
)

@Serializable
data class NewStop(
    @Description("The city, town or island, with its country, in English as the English Wikipedia names them, for example \"Sóller, Spain\".")
    val place: String,
    @Description("The same place as the traveller's language names it, without the country. Empty if it is the same.")
    val shown: String = "",
    @Description("How many days the traveller eats there.")
    val days: Int,
    @Description("The name of the stop it comes after, as in the list of days. Empty to add it at the end of the trip.")
    val after: String = "",
    @Description("The values OpenStreetMap uses in its `cuisine` tag for the traditional food of this place, in lower case, the most specific first.")
    val cuisines: List<String> = emptyList(),
)

/** How many restaurants a search shows the model. */
private const val SHOWN = 15

internal fun routeTools(sources: Sources): List<StateTool<Trip>> =
    searchTools(sources).map { it.nudging() } + routeEditors + stopAdder(sources)

/** The tools that look for candidates. */
private fun searchTools(sources: Sources): List<StateTool<Trip>> =
    listOfNotNull(
        StateTool<Trip, CandidateSearch>("candidates", "Lists candidates of a stop that are already known, the ones with the most evidence first.") { trip, search ->
            val stop = trip.knownStop(search.stop)
            val words = search.words.lowercase().split(' ', ',').filter { it.isNotBlank() }
            if (words.any { it.length < 3 }) throw IllegalArgumentException("Search for a word of at least three letters, such as a dish or a cuisine.")
            val around = trip.around(stop, search.near, sources)
            val matching =
                trip.restaurants
                    .filter { it.stop == stop.name && it.id !in trip.rejected && it.matches(words) }
                    .filter { around == null || metresBetween(it.location, around.second) <= NEAR_RADIUS }
                    .sortedByDescending { trip.evidence(it) }
            val nothing = "No candidate in ${stop.name} matches \"${search.words}\"" + (around?.let { " near ${it.first}" } ?: "") + ". Search the map or the guide."
            ToolOutcome(trip, trip.listed(matching, nothing, around))
        },
        StateTool<Trip, MapLookup>("search_map", "Searches the street map around a stop, by cuisine or by a word in the name. What it finds joins the candidates.") { trip, lookup ->
            val stop = trip.knownStop(lookup.stop)
            val search =
                when {
                    lookup.name.isNotBlank() -> MapSearch.Name(lookup.name)
                    lookup.cuisine.isNotBlank() -> MapSearch.Cuisine(listOf(lookup.cuisine))
                    else -> throw IllegalArgumentException("Give a cuisine or a name to search for.")
                }
            val around = trip.around(stop, lookup.near, sources)
            reportProgress(Activity(trip.language.texts.searchingTheMap(around?.first ?: stop.title, lookup.name.ifBlank { lookup.cuisine })))
            val found = sources.map.restaurants(stop.name, around?.second ?: stop.centre, search, if (around == null) MAP_RADIUS else NEAR_RADIUS)
            trip.found(found, "The map has no such place in ${stop.name}" + (around?.let { " near ${it.first}" } ?: "") + ".", around)
        },
        StateTool<Trip, GuideLookup>("read_guide", "Reads another page of the travel guide. Returns what it says about the food, and adds the restaurants it lists to the candidates of the stop.") { trip, lookup ->
            val stop = trip.knownStop(lookup.stop)
            reportProgress(Activity(trip.language.texts.readingTheGuide(lookup.page)))
            val page = sources.guide.read(lookup.page)
            // The page is read for the stop, so its restaurants are candidates of the stop, as long as they are within reach of it.
            val near = page.restaurants.map { it.copy(stop = stop.name) }.filter { stop.reaches(it.location, GUIDE_REACH) }
            val outcome = trip.found(near, "The page lists no restaurants near ${stop.name}.")
            outcome.copy(text = page.text.take(GUIDE_TEXT).ifBlank { "The guide has no text about eating in ${lookup.page}." } + "\n\n" + outcome.text)
        },
        sources.reviews?.let { reviews ->
            StateTool<Trip, ReviewSearch>("search_reviews", "Searches Google for places at a stop, with their ratings. What it finds joins the candidates.") { trip, search ->
                val stop = trip.knownStop(search.stop)
                val around = trip.around(stop, search.near, sources)
                reportProgress(Activity(trip.language.texts.searchingReviews(around?.first ?: stop.title, search.query)))
                val found = reviews.search(stop.name, search.query, around?.second ?: stop.centre)
                trip.copy(googleSearches = trip.googleSearches + 1).found(found, "Google found nothing for \"${search.query}\".", around)
            }
        },
    )

/**
 * The tools that write the route. The agent writes the whole route with `set_route` in one answer,
 * with its message to the traveller: every round of the agent sends the whole conversation to the
 * model again, so a route written meal by meal takes many slow rounds. `set_meal` and `remove_meal`
 * are for the changes after it, and `set_days` for a traveller who stays longer or leaves sooner.
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
                    val set = after.route.filter { meal -> plan.meals.any { it.day == meal.day && MealTime.of(it.meal) == meal.time } }
                    append(after.farFromStay(set))
                    append(after.repeatedDishes(set))
                    append(after.lightMeals(set))
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
                    " " + after.openMeals() +
                    after.route.filter { it.day == choice.day && it.time == mealTime(choice.meal) }.let { after.farFromStay(it) + after.repeatedDishes(it) + after.lightMeals(it) },
            )
        },
        StateTool<Trip, MealOffer>(
            "offer_choices",
            "Offers the traveller two or three places for one meal, the one you recommend first, which goes on the route. " +
                "The traveller taps the one they want; you hear of it with their next words.",
        ) { trip, offer ->
            if (offer.options.size !in 2..3) throw IllegalArgumentException("Offer two or three places.")
            if (offer.options.map { it.restaurant.trim() }.distinct().size < offer.options.size) throw IllegalArgumentException("Offer a place once.")
            val choices = offer.options.map { MealChoice(offer.day, offer.meal, it.restaurant, it.dish, it.why, it.caution) }
            // Each option must be a meal the route could have: the same checks as a meal put on it.
            val options = choices.map { choice -> trip.withMeal(choice).route.first { it.day == choice.day && it.time == mealTime(choice.meal) } }
            val time = options.first().time
            val after = trip.withMeal(choices.first()).let { it.copy(offers = it.offers + Offer(offer.day, time, options, offer.recommendation.trim())) }
            val names = options.mapNotNull { trip.restaurant(it.restaurantId)?.name }
            ToolOutcome(
                after,
                "Offered for day ${offer.day} ${time.label.lowercase()}: ${names.joinToString()}. ${names.first()} is on the route until the traveller chooses. " +
                    after.openMeals() + after.farFromStay(options) + after.repeatedDishes(options.take(1)) + after.lightMeals(options),
            )
        },
        StateTool<Trip, MealSlot>("remove_meal", "Takes a meal off the route.") { trip, slot ->
            val time = mealTime(slot.meal)
            if (trip.route.none { it.day == slot.day && it.time == time }) throw IllegalArgumentException("Day ${slot.day} has no ${time.label.lowercase()} on the route.")
            val after = trip.copy(route = trip.route.filterNot { it.day == slot.day && it.time == time }, offers = trip.offers.filterNot { it.day == slot.day && it.time == time })
            ToolOutcome(after, "Removed. " + after.openMeals())
        },
        StateTool<Trip, StopDays>(
            "set_days",
            "Changes how many days the traveller stays at a stop; 0 takes the stop off the trip. The days of the stops after it move, with their meals; " +
                "the meals of days that are no longer part of the trip are taken off the route.",
        ) { trip, change ->
            val stop =
                trip.stopNamed(change.stop)
                    ?: throw IllegalArgumentException("There is no stop named \"${change.stop}\". The stops are: ${trip.stops.joinToString { it.name }}. Add a new place with add_stop.")
            if (change.days !in 0..MAX_DAYS) throw IllegalArgumentException("A stop has 1 to $MAX_DAYS days, or 0 to take it off the trip.")
            if (change.days == 0 && trip.stops.size == 1) throw IllegalArgumentException("${stop.name} is the only stop. Add the place the traveller goes to instead with add_stop first.")
            val after = trip.withDays(stop, change.days)
            val removed = trip.route.size - after.route.size
            ToolOutcome(
                after,
                "The trip has ${after.days} days now: " + after.dayList() + "." +
                    (if (removed > 0) " $removed meals of the days that are gone are off the route." else "") +
                    " " + after.openMeals(),
            )
        },
    )

/**
 * The trip with [days] days at [stop]: the stops after it start later or sooner, and so do their
 * meals. The meals of the days the stop no longer has are dropped.
 */
internal fun Trip.withDays(stop: Stop, days: Int): Trip {
    val shift = days - stop.days
    val end = stop.firstDay + stop.days
    var day = 1
    val moved =
        stops.map { if (it.name == stop.name) it.copy(days = days) else it }.filter { it.days > 0 }.map { it.copy(firstDay = day).also { moved -> day += moved.days } }
    val meals =
        route.mapNotNull { meal ->
            when {
                meal.day >= end -> meal.copy(day = meal.day + shift)
                meal.day >= stop.firstDay + days -> null
                else -> meal
            }
        }
    // The offers are for days that moved or are gone: the meals on the route stay.
    return copy(stops = moved, route = meals, offers = emptyList())
}

/**
 * The tool that adds a place to the trip, such as another town of the same country. The new stop
 * gets candidates the way the first stops did, from the guide and the street map; a source that
 * fails is said in the result, and the agent can search it again.
 */
private fun stopAdder(sources: Sources): StateTool<Trip> =
    StateTool<Trip, NewStop>(
        "add_stop",
        "Adds a place to the trip for some days, after a stop or at the end; the days after it move, with their meals. " +
            "Reads the travel guide and the street map for it, and lists what they found. To go there instead of a stop, add it, then set the days of that stop.",
    ) { trip, new ->
        if (new.days !in 1..MAX_DAYS) throw IllegalArgumentException("A stop has 1 to $MAX_DAYS days.")
        trip.stopNamed(new.place)?.let { throw IllegalArgumentException("${it.name} is a stop already. Change its days with set_days.") }
        val after =
            new.after.takeIf { it.isNotBlank() }?.let { name ->
                trip.stopNamed(name) ?: throw IllegalArgumentException("There is no stop named \"$name\". The stops are: ${trip.stops.joinToString { it.name }}.")
            }
        val texts = trip.language.texts
        reportProgress(Activity(texts.findingOnTheMap(new.shown.ifBlank { new.place })))
        val found = sources.geocoder.find(new.place) ?: throw IllegalArgumentException("The map does not know \"${new.place}\". Name it as the English Wikipedia does, with its country.")
        val stop =
            Stop(
                new.place.trim(), new.days, 0, found.location.lat, found.location.lon, cuisines = new.cuisines.map { it.trim().lowercase() }.filter { it.isNotEmpty() },
                shown = new.shown.trim(), wide = found.wide,
            )
        val problems = mutableListOf<String>()
        suspend fun <T> tried(source: String, read: suspend () -> T): T? =
            try {
                read()
            } catch (e: Throwable) {
                if (!e.isFailure()) throw e
                problems += "The $source gave no answer for ${stop.name}: ${e.message}"
                null
            }
        reportProgress(Activity(texts.readingTheGuide(stop.title)))
        val page = tried("travel guide") { sources.guide.read(stop.name) }
        reportProgress(Activity(texts.lookingOnTheMap(stop.cuisines.firstOrNull() ?: "local", stop.title)))
        val onMap = tried("street map") { sources.map.restaurants(stop.name, stop.centre, MapSearch.Cuisine(stop.cuisines.ifEmpty { LOCAL_CUISINES }), MAP_RADIUS) }.orEmpty()
        val inGuide = page?.restaurants.orEmpty().map { it.copy(stop = stop.name) }.filter { stop.reaches(it.location, GUIDE_REACH) }
        val added = trip.withStop(stop, after).let { if (page == null || page.text.isBlank()) it else it.copy(guideNotes = it.guideNotes + (stop.name to page.text)) }
        val outcome = added.found(inGuide + onMap, "No candidates yet in ${stop.name}. Search the map, the guide or the reviews for it.")
        val placed = outcome.state.stopNamed(stop.name)!!
        val last = placed.firstDay + placed.days - 1
        outcome.copy(
            text =
                "Added ${stop.name}: ${if (last == placed.firstDay) "day ${placed.firstDay}" else "days ${placed.firstDay} to $last"}. The trip has ${outcome.state.days} days now: " +
                    outcome.state.dayList() + ". " + outcome.state.openMeals() +
                    problems.joinToString("") { "\n$it" } +
                    (page?.text?.takeIf { it.isNotBlank() }?.let { "\n\nWhat the guide says about eating there:\n" + it.take(GUIDE_TEXT) } ?: "") +
                    "\n\nCandidates in ${stop.name}:\n" + outcome.text,
        )
    }

/** The trip with [stop] after the stop [after], or at the end without it: the days after it move by its days, with their meals. */
internal fun Trip.withStop(stop: Stop, after: Stop?): Trip {
    val at = if (after == null) stops.size else stops.indexOfFirst { it.name == after.name } + 1
    val start = stops.take(at).sumOf { it.days } + 1
    var day = 1
    val moved = (stops.take(at) + stop + stops.drop(at)).map { it.copy(firstDay = day).also { placed -> day += placed.days } }
    return copy(stops = moved, route = route.map { if (it.day >= start) it.copy(day = it.day + stop.days) else it }, offers = emptyList())
}

/** The days of the trip, for the model: "days 1 to 4 in Palma de Mallorca, Spain, day 5 in Sóller, Spain". */
private fun Trip.dayList(): String =
    stops.joinToString { stop ->
        val last = stop.firstDay + stop.days - 1
        (if (last == stop.firstDay) "day ${stop.firstDay}" else "days ${stop.firstDay} to $last") + " in ${stop.name}"
    }

/** The stop the model means by [name]: its name, its name without the country, or as the traveller reads it. */
private fun Trip.stopNamed(name: String): Stop? {
    val wanted = name.trim()
    return stops.firstOrNull { stop -> listOf(stop.name, stop.name.substringBefore(','), stop.title).any { it.trim().equals(wanted, ignoreCase = true) } }
}

/**
 * The trip with [choice] on its route, in the place of the meal of its day and time.
 *
 * @throws IllegalArgumentException with what is wrong, for the model, when the day, the meal or the restaurant is not one of the trip.
 */
private fun Trip.withMeal(choice: MealChoice): Trip {
    val time = mealTime(choice.meal)
    val stop = stopOn(choice.day) ?: throw IllegalArgumentException("The trip has $days days. There is no day ${choice.day}. To make a stay longer, call set_days first.")
    val restaurant = restaurant(choice.restaurant) ?: throw IllegalArgumentException("There is no candidate with the id \"${choice.restaurant}\". Use an id from the list.")
    if (restaurant.stop != stop.name) throw IllegalArgumentException("${restaurant.name} is in ${restaurant.stop}, but day ${choice.day} is in ${stop.name}.")
    if (restaurant.id in rejected) throw IllegalArgumentException("The traveller asked to change ${restaurant.name}: choose another place.")
    val meal = Meal(choice.day, time, restaurant.id, choice.dish.trim(), choice.why.trim(), choice.caution.trim())
    // An offer for the meal is over once another place is put there.
    return copy(route = route.filterNot { it.day == meal.day && it.time == meal.time } + meal, offers = offers.filterNot { it.day == meal.day && it.time == meal.time })
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

private val EDITORS = setOf("set_route", "set_meal", "remove_meal", "offer_choices", "set_days", "add_stop")

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
private fun Trip.found(found: List<Restaurant>, nothing: String, around: Pair<String, Location>? = null): ToolOutcome<Trip> {
    val after = copy(restaurants = restaurants.merged(found))
    // Looked up in the merged list: a restaurant that was already known keeps its id.
    val listed = found.mapNotNull { new -> after.restaurants.firstOrNull { it.isSamePlaceAs(new) } }.filter { it.id !in rejected }
    return ToolOutcome(after, after.listed(listed.distinctBy { it.id }.sortedByDescending { after.evidence(it) }, nothing, around))
}

/** [found] as lines for the model, each with how far it is from the place [around], when the search was near one. */
private fun Trip.listed(found: List<Restaurant>, nothing: String, around: Pair<String, Location>? = null): String =
    if (found.isEmpty()) {
        nothing
    } else {
        found.take(SHOWN).joinToString("\n") { restaurant ->
            line(restaurant) + (around?.let { (name, place) -> " · ${metresBetween(restaurant.location, place)} m from $name" } ?: "")
        } + if (found.size > SHOWN) "\n(${found.size - SHOWN} more. Search with other words to see them.)" else ""
    }

/**
 * The place a search of [stop] is [near], with its name: a place where the traveller sleeps, by
 * the name the list of days gives it, or else what the map finds by that name at the stop. `null`
 * for a search of the whole stop.
 *
 * @throws IllegalArgumentException when the map does not know the place at the stop.
 */
private suspend fun Trip.around(stop: Stop, near: String, sources: Sources): Pair<String, Location>? {
    val name = near.trim().takeIf { it.isNotEmpty() } ?: return null
    val sleeping = listOfNotNull(stop.stay?.let { stop.stayName to it }) + stop.stays.map { it.name to it.location }
    sleeping.firstOrNull { it.first.equals(name, ignoreCase = true) }?.let { return it }
    val place =
        sources.geocoder.locate("$name, ${stop.name}")?.takeIf { stop.reaches(it, STAY_REACH) }
            ?: throw IllegalArgumentException("The map does not know \"$name\" in ${stop.name}. Name a neighbourhood, a street or a sight there.")
    return name to place
}

/** What the model is told about [meals] that the traveller wants near where they sleep, and that are not. Empty when all are. */
internal fun Trip.farFromStay(meals: List<Meal>): String =
    meals.filter { it.time in profile.nearStay }.mapNotNull { meal ->
        val (name, stay) = sleepingPlace(meal.day, meal.time) ?: return@mapNotNull null
        val place = restaurant(meal.restaurantId) ?: return@mapNotNull null
        val metres = metresBetween(place.location, stay)
        if (metres <= NEAR_STAY) {
            null
        } else {
            "\nDay ${meal.day} ${meal.time.label.lowercase()} at ${place.name} is $metres m from $name, but the traveller wants it near where they sleep: " +
                "choose a place within $NEAR_STAY m, and search with near \"$name\" if no candidate is."
        }
    }.joinToString("")

/**
 * What to tell the model when one of [meals] orders a dish that another meal of the route orders
 * too, in words for it; empty when none does. A dish is the stop's dish it names, such as
 * "ensaïmada amb xocolata" in "an ensaïmada amb xocolata, warm", or else its own words.
 */
internal fun Trip.repeatedDishes(meals: List<Meal>): String {
    fun dish(meal: Meal): String = dishOf(meal)?.name?.lowercase()?.trim() ?: meal.dish.lowercase().trim()
    return route.filter { it.dish.isNotBlank() }.groupBy(::dish).filterValues { it.size > 1 }.filterValues { same -> same.any { meal -> meals.any { it.day == meal.day && it.time == meal.time } } }
        .map { (dish, same) ->
            "\nThe same dish more than once: $dish on " + same.sortedWith(compareBy({ it.day }, { it.time })).joinToString { "day ${it.day} ${it.time.label.lowercase()}" } +
                ". Give each meal its own dish unless the traveller asked for this one again: a stop has more to eat at that time of the day than its best-known dish."
        }.joinToString("")
}

/**
 * What to tell the model when one of [meals] is a lunch or a dinner of a dish people eat at
 * breakfast or as a snack, such as a sfogliatella for dinner; empty when none is. It is not a
 * mistake at a place that makes a meal of it, as a stall of street food does.
 */
internal fun Trip.lightMeals(meals: List<Meal>): String =
    meals.filter { it.time == MealTime.LUNCH || it.time == MealTime.DINNER }.mapNotNull { meal ->
        val dish = dishOf(meal) ?: return@mapNotNull null
        val eaten =
            when (MealTime.of(dish.meal)) {
                MealTime.BREAKFAST -> "at breakfast"
                MealTime.SNACK -> "as a snack"
                else -> return@mapNotNull null
            }
        "\nDay ${meal.day} ${meal.time.label.lowercase()} orders ${dish.name}, which people there eat $eaten, not as a ${meal.time.label.lowercase()}. " +
            "Unless the place makes a meal of it, as a stall of street food does, order a dish people eat at ${meal.time.label.lowercase()}."
    }.joinToString("")

/** The stop's dish [meal] orders, the longest one its words name, such as "ensaïmada amb xocolata" in "an ensaïmada amb xocolata, warm". */
private fun Trip.dishOf(meal: Meal): Dish? {
    val words = meal.dish.lowercase()
    return dishes.filter { it.name.isNotBlank() && it.name.lowercase().trim() in words }.maxByOrNull { it.name.trim().length }
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

/** How far around a place a search `near` it looks, in metres. */
private const val NEAR_RADIUS = 1000

/** A meal the traveller wants near where they sleep is this close to it, in metres: a short walk. */
internal const val NEAR_STAY = 700

/** A restaurant of another page of the guide is a candidate of a stop when it is within this distance of it, in metres. */
private const val GUIDE_REACH = 40_000

private const val GUIDE_TEXT = 3000
