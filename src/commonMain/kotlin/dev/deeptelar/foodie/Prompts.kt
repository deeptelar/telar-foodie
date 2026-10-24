package dev.deeptelar.foodie

internal const val INTERVIEW_SYSTEM =
    "You interview a traveller before you plan where they eat on a trip, as a planner who knows the food of the world's regions. " +
        "You write down what the traveller said, in the request and in the answers, and you write the next questions. " +
        "Write down only what the traveller said: never assume an allergy, a diet or a budget. " +
        "Ask as a person who knows the places of this trip: name a dish, a price or a habit of the place when it helps the traveller answer. " +
        "The traveller reads the questions and their answers: write them in plain words, never with the names of the topics " +
        "or with the values of the profile, such as LUNCH or medium."

/**
 * What a model is told about the traveller's language. The names of the stops stay in English: the
 * travel guide is the English Wikivoyage, and its pages have English names.
 */
internal fun languageRule(trip: Trip): String =
    "Write everything the traveller reads in ${trip.language.english}: questions and their answers, descriptions, reasons and your messages. " +
        "Keep in English only the fixed values of the data that the descriptions give in quotes, such as meals, budgets and cuisine tags, " +
        "and the names of the stops. Speak to the traveller informally, as the page does and as a friend would: in Spanish and Catalan with tú, never usted, and in German with du, never Sie."

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
    "You read what a traveller wrote about a trip and return its places as data: each place the traveller named, in the order of the trip, " +
        "with its days, where the traveller sleeps there if they said it, and the cuisine words of OpenStreetMap that you know for the place. " +
        "Do not add places the traveller did not name. " +
        "For an island or a region the traveller named, give one town in it: where they sleep there, or else its main town, " +
        "because the restaurants are searched around the place."

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
        profile.season.takeIf { it.isNotBlank() }?.let { appendLine("The trip is in $it: name what is in season then, and say in the description of a dish that is made or caught only in another season.") }
        profile.drinks.takeIf { it.isNotBlank() }?.let { appendLine("What the traveller drinks: $it.") }
        profile.wishes.takeIf { it.isNotBlank() }?.let {
            appendLine("The traveller's wishes: $it. Name two or three dishes for each wish first: for a kind of food, such as street food, seafood or sweets, the ones of that kind people there eat.")
        }
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
- A traveller who wishes for street food can have a lunch or a dinner of it, at a stall or a shop that makes a meal of it: one of the stop's street foods is then the dish of that meal.
- Give each meal its own dish: order a dish once on the trip, unless the traveller asks for it again. A stop's famous pastry makes one breakfast, not every one: look for what else people there eat in the morning, at a bakery, a café or a market.
- Prefer the places where people who live there eat, over the places made for visitors:
  - A place "known for" a dish of the stop is the strongest choice for that dish: the one place in town for it, such as a francesinha in Porto. Order that dish there.
  - Then a small place of the local cuisine: a tasca, a family kitchen, a pastry shop, a stall of a market. The guide's words count when they fit: small, old, simple, full of locals.
  - Being in the travel guide is no reason by itself: the guide is written for visitors, and many of its places live off them.
  - A place a few metres from a big sight (the line says so) lives off the visitors who pass: choose it only when a source speaks for it in words.
  - A place whose description speaks of tourists, views, a menu in many languages or an international menu is a weak choice.
  - A high rating from many people counts, but a very famous place is often full of visitors: a good rating at a small place counts more.
- Follow what you know about the traveller: diet, budget, who comes along, how adventurous they are, what they drink and what they wish for. With one special meal, make one dinner of the trip a place to remember.
- When you know the season of the trip, order dishes of that season: a dish whose description says it is made or caught in another season, such as a seafood out of its season, is no choice then. At a place by the sea or in the mountains that often closes in that season, say in `caution` to check that it is open.
- Never put a dish on the route that the traveller's allergies or diet rule out. With an allergy, say in `caution` what in the dish or the kitchen may contain it and what to tell the staff.
- When someone on the trip is pregnant, choose dishes they can eat, and say in `caution` what to leave out or ask for at a place whose food often has it: raw or undercooked fish, meat or eggs, unpasteurised cheese, cured meats, fish high in mercury, alcohol.
- Choose each meal for its dish, not for being near the meal before: the traveller is on holiday, and goes across town for the right place. The places of a day need not be close to each other. Only between two places that are as good, take the one that fits the day better.
- A meal the traveller wants near where they sleep is a short walk from the place of that night, and a breakfast from the place of the night before; the list of days says where they sleep. List or search the candidates with `near` set to it.
- When the candidates have no good place for a dish, search for one with the tools before you settle for less.

How to work:
- Start from the candidates you were given: they are enough for most meals. Write the whole route in one answer, with one call of `set_route` that has every meal of every day and your message to the traveller; nothing is on the route until you call it.
- Search only for a dish or a kind of place that the candidates lack, with words such as "pastry" or "seafood", and only a few times. A search for a common word finds nothing new.
- Never name a restaurant that is not a candidate, and never invent an id, not even in your message.
- When the tools find no place for a meal either, leave the meal open. Tell the traveller in a few sentences which meals have no place yet and why, such as a source that gave no answer, and what would help: where they sleep, or trying again later.
- In `why`, say what speaks for the place, in one sentence, and only what the sources say. If little is known, say so.
- If `set_route` says that something is wrong, fix it with `set_meal` or `remove_meal`, and then answer the traveller in three to five sentences: what the trip tastes like and what to look forward to. Do not list the restaurants again; the traveller sees the route.
- When the traveller asks for a change later, change what was asked and leave the rest of the route as it is. When they ask for another place for a meal, offer two or three with `offer_choices`, the one you recommend first, and say why you recommend it.
- After a change, answer in one or two sentences: what changed. The traveller reads the rest on the route, and the reason for your recommendation on its card.
- When the traveller stays longer or leaves sooner, change the days of the stop with `set_days`, then plan the meals of the new days.
- When the traveller goes to another place too, add it with `add_stop`, and plan its meals from what it finds. When they go there instead of a stop, add it, then give that stop fewer days, or 0 to take it off the trip."""

/** How many candidates of each stop the agent reads at the start. The tool `candidates` shows the others. */
internal const val FIRST_CANDIDATES = 25

internal fun planPrompt(trip: Trip): String =
    buildString {
        appendLine("Plan the meals of this trip.")
        appendLine()
        appendLine("The traveller wrote: \"${trip.request.trim()}\"")
        appendLine()
        appendLine("What you know about the traveller:")
        profileLines(trip.profile, trip.sleepsSomewhereKnown).forEach { appendLine("- $it") }
        appendLine("- Meals of every day: ${trip.dailyMeals.joinToString { it.label.lowercase() }}" + if (trip.profile.meals.isEmpty()) " (not said)" else "")
        appendLine()
        appendLine("The days:")
        trip.stops.forEach { stop ->
            val last = stop.firstDay + stop.days - 1
            append("- ${if (last == stop.firstDay) "Day ${stop.firstDay}" else "Days ${stop.firstDay} to $last"}: ${stop.name}")
            if (stop.stayName.isNotBlank()) append(", sleeping at ${stop.stayName}")
            stop.stays.forEach { append(", from the night of day ${stop.firstDay + it.night - 1} at ${it.name}") }
            appendLine()
        }
        trip.stops.forEach { stop ->
            appendLine()
            appendLine("What ${stop.name} is known for:")
            trip.dishes.filter { it.stop == stop.name }.forEach { appendLine("- ${it.name} (${it.meal}): ${it.what}") }
            val all = trip.restaurants.count { it.stop == stop.name }
            val shown = trip.best(stop.name, FIRST_CANDIDATES)
            appendLine()
            appendLine("Candidates in ${stop.name}, ${shown.size} of $all:")
            shown.forEach { appendLine(trip.line(it)) }
            if (shown.isEmpty()) appendLine("None yet. Search with the tools; if they find none either, leave the meals open.")
        }
        if (trip.notes.isNotEmpty()) {
            appendLine()
            appendLine("What could not be read:")
            trip.notes.forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine(languageRule(trip) + " The ids and the tools stay as they are.")
    }

/** The profile on one line, with the meals, as an answer of the traveller says it. */
internal fun profileWords(profile: Profile): String =
    (profileLines(profile) + listOfNotNull(profile.meals.takeIf { it.isNotEmpty() }?.let { meals -> "Meals: " + meals.sorted().joinToString { it.label.lowercase() } })).joinToString("; ")

/** What the agent is told when the traveller changes the profile of a route it planned. */
internal fun profileChangePrompt(trip: Trip): String =
    buildString {
        appendLine("I changed what you know about me. It is now:")
        profileLines(trip.profile, trip.sleepsSomewhereKnown).forEach { appendLine("- $it") }
        appendLine("- Meals of every day: ${trip.dailyMeals.joinToString { it.label.lowercase() }}")
        appendLine()
        append("Change the meals that no longer fit, add or remove meals so that every day has these meals, and keep the rest of the route.")
    }

/** What the agent is told when the traveller paused it to add something, or to send it another way. */
internal fun asidePrompt(text: String): String =
    "While you worked, I paused you to say this:\n\n${text.trim()}\n\n" +
        "Plan the route with it in mind: change what it changes, keep what still fits, and go on."

/** What the agent is told when the traveller asks to change the places of some meals, and why. */
internal fun swapPrompt(trip: Trip, swaps: List<MealSwap>): String =
    buildString {
        appendLine("Change the place of these meals, and keep every other meal of the route as it is:")
        swaps.forEach { swap ->
            val meal = trip.route.firstOrNull { it.day == swap.day && it.time == swap.time }
            val place = meal?.let { trip.restaurant(it.restaurantId) }
            append("- Day ${swap.day} ${swap.time.label.lowercase()}")
            if (meal != null && place != null) append(", now ${place.name} [${place.id}] with ${meal.dish}")
            appendLine(".")
            if (swap.reasons.isNotEmpty()) appendLine("  Why not: the traveller " + swap.reasons.joinToString("; ") { it.label } + ".")
            if (swap.instead.isNotBlank()) appendLine("  Instead, the traveller would like: ${swap.instead.trim()}.")
            if (swap.near.isNotBlank()) appendLine("  Closer to: ${swap.near.trim()}. Search with near set to it.")
            if (swap.note.isNotBlank()) appendLine("  In the traveller's words: ${swap.note.trim()}")
        }
        appendLine()
        append(
            "The traveller does not want these places on the route again. For each meal, offer two or three new places with `offer_choices`, " +
                "from the candidates or what you search for, the one you recommend first; the traveller taps the one they want. " +
                "Then tell the traveller in one or two sentences what you offer.",
        )
    }

/** How the reminder of [swapReminder] begins: the agent is reminded once in a turn. */
internal const val SWAP_REMINDER = "These meals that the traveller asked to change have no place yet:"

/** What the agent is told when its turn ends with [open], meals the traveller asked to change, left with no place. */
internal fun swapReminder(open: List<MealSwap>): String =
    SWAP_REMINDER + "\n" + open.joinToString("\n") { "- Day ${it.day} ${it.time.label.lowercase()}" } + "\n\n" +
        "For each meal, offer two or three new places with `offer_choices`, from the candidates or what you search for, the one you recommend first. " +
        "Do not take the meal off the route. Then tell the traveller in one or two sentences what you offer."

/**
 * The profile for a model, one line for each thing the traveller said. The meals near where they
 * sleep are left out when the map knows no place where they do: nothing could be measured from it.
 */
private fun profileLines(profile: Profile, sleeping: Boolean = true): List<String> =
    listOfNotNull(
        if (profile.allergies.isEmpty()) "Allergies: none said" else "Allergies: " + profile.allergies.joinToString { it.line() },
        "Diet: " + profile.diet.ifBlank { "none said" },
        if (profile.pregnant) "Pregnancy: $PREGNANCY_RULE" else null,
        "Budget: ${profile.budget}" + if (profile.splurge) ", with one special meal" else "",
        profile.nearStay.takeIf { it.isNotEmpty() && sleeping }?.let { meals -> "Near where they sleep, within $NEAR_STAY m: " + meals.sorted().joinToString { it.label.lowercase() } },
        profile.party.takeIf { it.isNotBlank() }?.let { "Who eats: $it" },
        profile.season.takeIf { it.isNotBlank() }?.let { "When: $it" },
        profile.taste.takeIf { it.isNotBlank() }?.let { "Taste: $it" },
        profile.drinks.takeIf { it.isNotBlank() }?.let { "Drinks: $it" },
        profile.wishes.takeIf { it.isNotBlank() }?.let { "Wishes: $it" },
    )

/** Whether the map knows a place where the traveller sleeps on the trip. */
private val Trip.sleepsSomewhereKnown: Boolean get() = stops.any { it.stay != null || it.stays.isNotEmpty() }

private fun Allergy.line(): String = if (severe) "$food, even traces" else food

/** What a model is told when someone on the trip is pregnant: the usual advice on food in pregnancy. */
private const val PREGNANCY_RULE =
    "someone on the trip is pregnant, so their dishes have no raw or undercooked fish, meat or eggs, no unpasteurised cheese, " +
        "no cured meats, no fish high in mercury and no alcohol"
