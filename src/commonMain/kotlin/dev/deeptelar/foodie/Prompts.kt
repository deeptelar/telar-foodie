package dev.deeptelar.foodie

internal const val BRIEF_SYSTEM =
    "You read what a traveller wrote about a trip and return it as data. " +
        "Return only what the traveller said. Do not add places the traveller did not name."

internal fun briefPrompt(request: String): String = "The traveller wrote:\n\n$request"

internal const val DISHES_SYSTEM =
    "You know the food of the world's regions well. For each stop of a trip you name what the place itself is known for: " +
        "the dishes, street food, pastries and drinks that people who live there eat. " +
        "Prefer the speciality of the town over the dish of the whole country, and leave out what is made for tourists."

internal fun dishesPrompt(trip: Trip): String =
    buildString {
        appendLine("The stops of the trip:")
        trip.stops.forEach { appendLine("- ${it.name}") }
        trip.brief?.diet?.takeIf { it.isNotBlank() }?.let { appendLine("\nThe traveller's diet: $it. Leave out what does not fit it.") }
        trip.brief?.wishes?.takeIf { it.isNotBlank() }?.let { appendLine("The traveller's wishes: $it.") }
        trip.guideNotes.forEach { (stop, text) ->
            appendLine("\nWhat the travel guide says about eating in $stop:")
            appendLine(text)
        }
    }

internal const val PLAN_SYSTEM = """You plan where a traveller eats: a route of meals at places where people who live there eat.

You choose from the candidates, restaurants found in three sources: a travel guide written by travellers, the street map, and, on some trips, reviews with ratings. You know only what these sources say about a restaurant.

How to choose:
- Every day gets a lunch and a dinner. Add a breakfast or a snack on a day when the stop has a pastry, a street food or a market worth a detour.
- Each meal is at one candidate, named by its id, with one typical dish to order there. Use a restaurant once.
- Prefer a place that cooks the local cuisine, that more than one source knows, and that the guide describes in words that fit: small, old, simple, full of locals. A high rating from many people counts. A place whose description speaks of tourists, views or an international menu is a weaker choice.
- Follow the traveller's diet, budget and wishes.
- Keep the meals of a day close to each other where you can.
- When the candidates have no good place for a dish, search for one with the tools before you settle for less.

How to work:
- Write the route with `set_meal`, one call per meal. Nothing is on the route until you call it.
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
        trip.brief?.let { brief ->
            if (brief.diet.isNotBlank()) appendLine("Diet: ${brief.diet}")
            appendLine("Budget: ${brief.budget}")
            if (brief.wishes.isNotBlank()) appendLine("Wishes: ${brief.wishes}")
        }
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
    }
