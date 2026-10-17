package dev.deeptelar.foodie

internal const val INTERVIEW_SYSTEM =
    "You interview a traveller before you plan where they eat on a trip, as a planner who knows the food of the world's regions. " +
        "You write down what the traveller said, in the request and in the answers, and you write the next questions. " +
        "Write down only what the traveller said: never assume an allergy, a diet or a budget. " +
        "Ask as a person who knows the places of this trip: name a dish, a price or a habit of the place when it helps the traveller answer."

/**
 * What a model is told about the traveller's language. The names of the stops stay in English: the
 * travel guide is the English Wikivoyage, and its pages have English names.
 */
internal fun languageRule(trip: Trip): String =
    "Write everything the traveller reads in ${trip.language.english}: questions and their answers, descriptions, reasons and your messages. " +
        "Keep in English the fixed words that the descriptions give in quotes, such as meals, budgets and cuisine tags, and the names of the stops."

internal fun interviewPrompt(trip: Trip): String =
    buildString {
        append(travellerWords(trip))
        val open = Topic.entries.filter { it !in trip.interview.covered }
        appendLine()
        appendLine("The topics a planner asks about, in order:")
        Topic.entries.forEach { appendLine("- ${it.name}: ${it.label}" + if (it in open) "" else " (done)") }
        appendLine()
        if (open.isEmpty()) {
            appendLine("Every topic is done. Write down the profile, and ask no questions.")
        } else {
            appendLine("Say which topics the traveller's words answer, and write a question for each of the first open topics they do not answer.")
        }
        appendLine()
        appendLine(languageRule(trip))
    }

internal const val BRIEF_SYSTEM =
    "You read what a traveller wrote about a trip and return it as data. " +
        "Return only what the traveller said. Do not add places the traveller did not name."

internal fun briefPrompt(trip: Trip): String = travellerWords(trip) + "\n" + languageRule(trip)

/** The traveller's request, and the answers to the planner's questions below it. */
private fun travellerWords(trip: Trip): String =
    buildString {
        appendLine("The traveller wrote:")
        appendLine()
        appendLine(trip.request.trim())
        if (trip.answers.isNotEmpty()) {
            appendLine()
            appendLine("The traveller's answers to the planner's questions:")
            trip.answers.forEach { appendLine("- ${it.question} ${it.text.trim().ifEmpty { "(no answer)" }}") }
        }
    }

internal const val DISHES_SYSTEM =
    "You know the food of the world's regions well. For each stop of a trip you name what the place itself is known for: " +
        "the dishes, street food, pastries and drinks that people who live there eat. " +
        "Prefer the speciality of the town over the dish of the whole country, and leave out what is made for tourists."

internal fun dishesPrompt(trip: Trip): String =
    buildString {
        appendLine("The stops of the trip:")
        trip.stops.forEach { appendLine("- ${it.name}") }
        val profile = trip.profile
        if (profile.allergies.isNotEmpty() || profile.diet.isNotBlank() || profile.pregnant) {
            appendLine()
            appendLine("Leave out what the traveller cannot eat:")
            if (profile.diet.isNotBlank()) appendLine("- Diet: ${profile.diet}")
            profile.allergies.forEach { appendLine("- " + it.line()) }
            if (profile.pregnant) appendLine("- $PREGNANCY_RULE")
        }
        profile.taste.takeIf { it.isNotBlank() }?.let { appendLine("\nHow adventurous the traveller is: $it.") }
        profile.season.takeIf { it.isNotBlank() }?.let { appendLine("The trip is in $it: name what is in season then.") }
        profile.drinks.takeIf { it.isNotBlank() }?.let { appendLine("What the traveller drinks: $it.") }
        profile.wishes.takeIf { it.isNotBlank() }?.let { appendLine("The traveller's wishes: $it.") }
        trip.guideNotes.forEach { (stop, text) ->
            appendLine("\nWhat the travel guide says about eating in $stop:")
            appendLine(text)
        }
        appendLine()
        appendLine(languageRule(trip))
    }

internal const val PLAN_SYSTEM = """You plan where a traveller eats: a route of meals at places where people who live there eat.

You choose from the candidates, restaurants found in three sources: a travel guide written by travellers, the street map, and, on some trips, reviews with ratings. You know only what these sources say about a restaurant.

How to choose:
- Every day gets the meals the traveller asked for. If the traveller did not say, every day gets a lunch and a dinner; add a breakfast or a snack on a day when the stop has a pastry, a street food or a market worth a detour.
- Each meal is at one candidate, named by its id, with one typical dish to order there. Use a restaurant once.
- Prefer a place that cooks the local cuisine, that more than one source knows, and that the guide describes in words that fit: small, old, simple, full of locals. A high rating from many people counts. A place whose description speaks of tourists, views or an international menu is a weaker choice.
- Follow what you know about the traveller: diet, budget, who comes along, how adventurous they are, what they drink and what they wish for. With one special meal, make one dinner of the trip a place to remember.
- Never put a dish on the route that the traveller's allergies or diet rule out. With an allergy, say in `caution` what in the dish or the kitchen may contain it and what to tell the staff.
- When someone on the trip is pregnant, choose dishes they can eat, and say in `caution` what to leave out or ask for at a place whose food often has it: raw or undercooked fish, meat or eggs, unpasteurised cheese, cured meats, fish high in mercury, alcohol.
- Keep the meals of a day close to each other where you can.
- When the candidates have no good place for a dish, search for one with the tools before you settle for less.

How to work:
- Start from the candidates you were given: they are enough for most meals. Write the route with `set_meal`, one call per meal; nothing is on the route until you call it. Put several calls in one answer, for example all the meals of a day.
- Search only for a dish or a kind of place that the candidates lack, with words such as "pastry" or "seafood", and only a few times. A search for a common word finds nothing new.
- Never name a restaurant that is not a candidate, and never invent an id.
- In `why`, say what speaks for the place, in one sentence, and only what the sources say. If little is known, say so.
- When the route is complete, answer the traveller in three to five sentences: what the trip tastes like and what to look forward to. Do not list the restaurants again; the traveller sees the route.
- When the traveller asks for a change later, change what was asked and leave the rest of the route as it is."""

/** How many candidates of each stop the agent reads at the start. The tool `candidates` shows the others. */
internal const val FIRST_CANDIDATES = 25

internal fun planPrompt(trip: Trip): String =
    buildString {
        appendLine("Plan the meals of this trip.")
        appendLine()
        appendLine("The traveller wrote: \"${trip.request.trim()}\"")
        appendLine()
        appendLine("What you know about the traveller:")
        profileLines(trip.profile).forEach { appendLine("- $it") }
        appendLine("- Meals of every day: ${trip.dailyMeals.joinToString { it.label.lowercase() }}" + if (trip.profile.meals.isEmpty()) " (not said)" else "")
        appendLine()
        appendLine("The days:")
        trip.stops.forEach { stop ->
            val last = stop.firstDay + stop.days - 1
            appendLine("- ${if (last == stop.firstDay) "Day ${stop.firstDay}" else "Days ${stop.firstDay} to $last"}: ${stop.name}")
        }
        trip.stops.forEach { stop ->
            appendLine()
            appendLine("What ${stop.name} is known for:")
            trip.dishes.filter { it.stop == stop.name }.forEach { appendLine("- ${it.name} (${it.meal}): ${it.what}") }
            val all = trip.restaurants.count { it.stop == stop.name }
            val shown = trip.restaurants.best(stop.name, FIRST_CANDIDATES)
            appendLine()
            appendLine("Candidates in ${stop.name}, ${shown.size} of $all:")
            shown.forEach { appendLine(it.line()) }
            if (shown.isEmpty()) appendLine("None yet. Search with the tools.")
        }
        if (trip.notes.isNotEmpty()) {
            appendLine()
            appendLine("What could not be read:")
            trip.notes.forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine(languageRule(trip) + " The ids and the tools stay as they are.")
    }

/** The profile for a model, one line for each thing the traveller said. */
private fun profileLines(profile: Profile): List<String> =
    listOfNotNull(
        if (profile.allergies.isEmpty()) "Allergies: none said" else "Allergies: " + profile.allergies.joinToString { it.line() },
        "Diet: " + profile.diet.ifBlank { "none said" },
        if (profile.pregnant) "Pregnancy: $PREGNANCY_RULE" else null,
        "Budget: ${profile.budget}" + if (profile.splurge) ", with one special meal" else "",
        profile.party.takeIf { it.isNotBlank() }?.let { "Who eats: $it" },
        profile.season.takeIf { it.isNotBlank() }?.let { "When: $it" },
        profile.taste.takeIf { it.isNotBlank() }?.let { "Taste: $it" },
        profile.drinks.takeIf { it.isNotBlank() }?.let { "Drinks: $it" },
        profile.wishes.takeIf { it.isNotBlank() }?.let { "Wishes: $it" },
    )

private fun Allergy.line(): String = if (severe) "$food, even traces" else food

/** What a model is told when someone on the trip is pregnant: the usual advice on food in pregnancy. */
private const val PREGNANCY_RULE =
    "someone on the trip is pregnant, so their dishes have no raw or undercooked fish, meat or eggs, no unpasteurised cheese, " +
        "no cured meats, no fish high in mercury and no alcohol"
