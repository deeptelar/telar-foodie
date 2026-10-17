package dev.deeptelar.foodie

import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.Description
import dev.deeptelar.telar.agent.TokenUsage
import kotlinx.serialization.Serializable

/**
 * The state of one planning run: what the traveller asked for and everything the planner found
 * and decided. Each node of the graph in `Planner.kt` returns a copy with its part filled in, and
 * a checkpoint holds the whole of it, so a plan survives a reload of the page.
 */
@Serializable
data class Trip(
    /** What the traveller wrote. */
    val request: String,
    /** The language the traveller reads: what the model writes for the traveller is in it. */
    val language: Language = Language.ENGLISH,
    /** The planner's questions the traveller has not answered yet. */
    val questions: List<Question> = emptyList(),
    /** The traveller's answers to the planner's questions, in the order they were given. */
    val answers: List<Answer> = emptyList(),
    /** Where the interview before the planning is. */
    val interview: Interview = Interview(),
    /** What the planner knows about the traveller, read from the request and the answers. */
    val profile: Profile = Profile(),
    /** The request as the model understood it. */
    val brief: Brief? = null,
    /** The places of the itinerary in the order they are visited, with where they are. */
    val stops: List<Stop> = emptyList(),
    /** What the travel guide says about eating at each stop, by the name of the stop. */
    val guideNotes: Map<String, String> = emptyMap(),
    /** The food each stop is known for. */
    val dishes: List<Dish> = emptyList(),
    /** Every restaurant the sources found. The route is chosen from these. */
    val restaurants: List<Restaurant> = emptyList(),
    /** The route: where to eat, on which day and at which meal. */
    val route: List<Meal> = emptyList(),
    /** The conversation of the agent that chooses the route. */
    val messages: List<ChatMessage> = emptyList(),
    /** The tokens the models used for this trip. */
    val tokens: TokenUsage = TokenUsage(),
    /** [tokens] when the agent began its current turn: the first plan, or a change the traveller asked for. */
    val turnStart: Int = 0,
    /** Written by the traveller at the review: `true` ends the run. */
    val approved: Boolean = false,
    /** What did not work but did not stop the run, such as a source that gave no answer. */
    val notes: List<String> = emptyList(),
) {
    fun stop(name: String): Stop? = stops.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    fun restaurant(id: String): Restaurant? = restaurants.firstOrNull { it.id == id.trim() }

    /** The number of days of the whole trip. */
    val days: Int get() = stops.sumOf { it.days }

    /** The stop the traveller is at on [day], which counts from 1. */
    fun stopOn(day: Int): Stop? = stops.firstOrNull { day in it.firstDay until it.firstDay + it.days }

    /** What the agent wrote when it had finished the route. */
    val summary: String get() = (messages.lastOrNull() as? ChatMessage.Assistant)?.text.orEmpty()
}

/** The places of the trip, as the model reads them out of the traveller's words. What the traveller likes is in the [Profile]. The descriptions are what the model sees. */
@Serializable
data class Brief(
    @Description("The places the traveller stays at, in the order of the trip. Empty if the request names none.")
    val stops: List<BriefStop> = emptyList(),
    @Description("One short question to the traveller, only if the request names no place to go to. Empty otherwise.")
    val question: String = "",
)

@Serializable
data class BriefStop(
    @Description("The city, town or island, with its country, in English as the English Wikipedia names them, for example \"Porto, Portugal\".")
    val place: String,
    @Description("The same place as the traveller's language names it, without the country, for example \"Oporto\" in Spanish. Empty if it is the same.")
    val shown: String = "",
    @Description("How many days the traveller eats there. 1 if the request does not say.")
    val days: Int = 1,
    @Description(
        "The values OpenStreetMap uses in its `cuisine` tag for the traditional food of this place, in lower case, " +
            "the most specific first. For example [\"portuguese\", \"regional\", \"seafood\"] for Lisbon.",
    )
    val cuisines: List<String> = emptyList(),
    @Description("Where the traveller sleeps in this place: a neighbourhood, a street or the name of a hotel, as the traveller wrote it. Empty if not said.")
    val stay: String = "",
)

/** A place of the itinerary that was found on the map. */
@Serializable
data class Stop(
    /** The place in English, as the travel guide names it. The planner knows the stop by it. */
    val name: String,
    val days: Int,
    /** The first day of the trip spent here, counted from 1. */
    val firstDay: Int,
    val lat: Double,
    val lon: Double,
    /** The cuisine words of this place, as OpenStreetMap writes them. */
    val cuisines: List<String> = emptyList(),
    /** Where the traveller sleeps here, when the map found it. The first walk of each day starts there. */
    val stay: Location? = null,
    /** The place as the traveller's language names it. Empty when it is [name]. */
    val shown: String = "",
) {
    /** The place as the traveller reads it. */
    val title: String get() = shown.ifBlank { name }

    val location: Location get() = Location(lat, lon)
}

@Serializable
data class Location(
    val lat: Double,
    val lon: Double,
)

/** A dish, a drink or a pastry a place is known for. */
@Serializable
data class Dish(
    @Description("The name of the stop this belongs to, exactly as it was given.")
    val stop: String,
    @Description("The local name, for example \"bacalhau à brás\".")
    val name: String,
    @Description("What it is, in one short sentence for someone who has never had it.")
    val what: String,
    @Description("When people eat it: \"breakfast\", \"lunch\", \"snack\" or \"dinner\".")
    val meal: String = "lunch",
)

/** The typed answer of the `dishes` node. */
@Serializable
data class Menu(
    @Description("Five to eight things each stop is known for: dishes, street food, pastries and drinks.")
    val dishes: List<Dish>,
)

/** Where a restaurant was found. A place that several sources know is a safer choice. */
@Serializable
enum class Source(val label: String) {
    /** Wikivoyage, a travel guide written by travellers. */
    GUIDE("Travel guide"),

    /** OpenStreetMap. */
    MAP("Street map"),

    /** Google Places, when the traveller gave a key for it. */
    REVIEWS("Google reviews"),
}

@Serializable
data class Restaurant(
    /** A short id the agent names a restaurant by. Given when the restaurant enters the state. */
    val id: String = "",
    /** The name of the stop it was found for. */
    val stop: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val cuisine: String = "",
    val address: String = "",
    val website: String = "",
    val hours: String = "",
    val price: String = "",
    /** What a source says about the place in words: the guide's description. */
    val note: String = "",
    /** Stars out of five, and how many people rated. Only Google has these. */
    val rating: Double? = null,
    val ratings: Int? = null,
    val sources: Set<Source> = emptySet(),
) {
    val location: Location get() = Location(lat, lon)
}

@Serializable
enum class MealTime(val label: String) {
    BREAKFAST("Breakfast"),
    LUNCH("Lunch"),
    SNACK("Snack"),
    DINNER("Dinner"),
    ;

    companion object {
        fun of(word: String): MealTime? = entries.firstOrNull { it.name.equals(word.trim(), ignoreCase = true) }
    }
}

/** One meal of the route. */
@Serializable
data class Meal(
    /** The day of the trip, counted from 1. */
    val day: Int,
    val time: MealTime,
    val restaurantId: String,
    /** What to order there. */
    val dish: String,
    /** Why this place, in the agent's words. */
    val why: String,
    /** What to watch out for with the traveller's allergies, and what to tell the staff. Empty when there is nothing to say. */
    val caution: String = "",
    /**
     * How far it is on foot, in metres, from the meal before it on the same day, or for the first meal
     * of a day from where the traveller sleeps. Filled by the `order` node.
     */
    val walk: Int? = null,
)
