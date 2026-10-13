package dev.deeptelar.foodie

import dev.deeptelar.foodie.sources.Sources
import dev.deeptelar.telar.CompiledGraph
import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphEvent
import dev.deeptelar.telar.GraphResult
import dev.deeptelar.telar.agent.ChatEvent
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.ToolCall
import dev.deeptelar.telar.agent.pendingToolCalls
import dev.deeptelar.telar.checkpoint.browser.LocalStorageCheckpointer
import dev.deeptelar.telar.serialization.KotlinxStateSerializer
import io.ktor.client.HttpClient
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement

/** Where the page is in the life of a plan. */
private enum class Phase {
    /** Nothing is planned yet: the traveller writes a request. */
    START,

    /** The graph runs. */
    RUNNING,

    /** The run paused in `brief` with a question for the traveller. */
    QUESTION,

    /** The run paused before `review`: the route is there, and the traveller says yes or asks for a change. */
    REVIEW,

    /** The traveller said yes and the run ended. */
    DONE,

    /** A saved run that stopped in the middle, because the page was closed while it ran. */
    STOPPED,

    /** A step failed. The run can be continued from the step before it. */
    FAILED,
}

private enum class Status { WAITING, RUNNING, DONE, SKIPPED }

/** A line of the list that shows a run: one node of the graph, or the two nodes of the agent as one. */
private class Step(
    val node: String,
    val title: String,
) {
    var status: Status = Status.WAITING

    /** What the node says it is doing right now. */
    var activity: String = ""

    /** Whether what the node found is in the trip. A node is done before its step is, and only a finished step changes the trip. */
    var settled: Boolean = false
}

/**
 * The page. It holds no plan of its own: the trip is the state of the graph, saved in
 * `localStorage` after every step, so a reload shows the plan where it stopped.
 */
class App(
    private val root: HTMLElement,
) {
    private val scope = MainScope()
    private val client = HttpClient()
    private val checkpointer = LocalStorageCheckpointer(KotlinxStateSerializer<Trip>(), keyPrefix = "foodie.run.")
    private val config =
        GraphConfig(
            threadId = "trip",
            checkpointer = checkpointer,
            // The run stops when the route is ready and waits for the traveller.
            interruptBefore = setOf(Nodes.REVIEW),
            // Every round of the agent is two steps, and a trip of several days takes many rounds.
            maxIterations = 120,
        )

    private var settings = Settings.load()
    private var settingsOpen = false
    private var phase = Phase.START
    private var trip: Trip? = null
    private var request = ""
    private var sample = false
    private var failure = ""
    private var steps = emptyList<Step>()
    private var feed = emptyList<String>()
    private var writing = ""
    private var job: Job? = null
    private var map: RouteMap? = null
    private var runPanel: HTMLElement? = null

    /** Shows the page, with the saved plan when there is one. */
    fun open() {
        render()
        scope.launch {
            val saved =
                try {
                    planner().lastResult(config)
                } catch (_: Throwable) {
                    // A plan that cannot be read, for example one saved by an older version of the page, is as good as none.
                    null
                }
            when {
                saved != null -> show(saved)
                "sample" in window.location.search -> start(SampleModel.REQUEST, sample = true)
            }
        }
    }

    // The run

    /** The graph for the current settings. Without a key for a model, the sample script plans. */
    private fun planner(): CompiledGraph<Trip> {
        val model = if (sample) SampleModel() else settings.model(client) ?: SampleModel()
        return planner(model, Sources.online(client, settings.googleKey))
    }

    private fun start(request: String, sample: Boolean) {
        this.sample = sample
        this.request = request
        trip = null
        run { planner().stream(Trip(request), config) }
    }

    /** Continues the saved run, with [update] applied to its state first. */
    private fun resume(update: (Trip) -> Trip = { it }) {
        run { planner().streamResume(config) { update(it) } }
    }

    private fun run(events: () -> Flow<GraphEvent<Trip>>) {
        phase = Phase.RUNNING
        failure = ""
        writing = ""
        feed = emptyList()
        steps = steps(trip)
        render()
        job =
            scope.launch {
                try {
                    events().collect(::on)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    failure = e.message ?: "Something went wrong."
                    phase = Phase.FAILED
                    render()
                }
            }
    }

    private fun on(event: GraphEvent<Trip>) {
        when (event) {
            is GraphEvent.NodeStarted -> {
                step(event.node)?.status = Status.RUNNING
                when (event.node) {
                    Nodes.PLAN -> writing = ""
                    // The calls the model just asked for are the last message of the state the tools node starts with.
                    Nodes.TOOLS -> feed = (feed + event.state.messages.pendingToolCalls().map { describe(it, event.state) }).takeLast(FEED_LINES)
                    Nodes.ORDER -> step(Nodes.PLAN)?.status = Status.DONE
                }
                renderRun()
            }
            is GraphEvent.NodeProgress -> {
                when (val value = event.value) {
                    is Activity -> step(event.node)?.activity = value.text
                    is ChatEvent.TextDelta -> writing += value.text
                }
                renderRun()
            }
            is GraphEvent.NodeRetry -> {
                step(event.node)?.activity = "That did not work (${event.cause.message}). Trying again."
                renderRun()
            }
            is GraphEvent.NodeCompleted -> {
                // The agent is done when the node after it starts, not when one of its rounds ends.
                val step = step(event.node)
                if (step != null && step.node != Nodes.PLAN) step.status = Status.DONE
                step?.activity = ""
                renderRun()
            }
            is GraphEvent.StepCompleted -> {
                trip = event.state
                steps.filter { it.status == Status.DONE }.forEach { it.settled = true }
                renderRun()
            }
            is GraphEvent.Interrupted -> show(GraphResult.Interrupted(event.state, event.nextNodes))
            is GraphEvent.Completed -> show(GraphResult.Completed(event.state))
            is GraphEvent.SubgraphEvent -> Unit
        }
    }

    /** Shows where a run stopped: the end of a stream, or the saved run when the page opens. */
    private fun show(result: GraphResult<Trip>) {
        trip = result.state
        request = result.state.request
        phase =
            when {
                result is GraphResult.Completed -> Phase.DONE
                Nodes.REVIEW in (result as GraphResult.Interrupted).nextNodes -> Phase.REVIEW
                result.state.question.isNotEmpty() -> Phase.QUESTION
                else -> Phase.STOPPED
            }
        steps = steps(trip)
        render()
    }

    private fun startOver() {
        job?.cancel()
        scope.launch {
            try {
                checkpointer.delete(config.threadId)
            } catch (_: Throwable) {
                // The browser blocks its storage: then nothing was saved either.
            }
            trip = null
            request = ""
            sample = false
            phase = Phase.START
            render()
        }
    }

    /** After a failure: continues the saved run, or starts again when the first step never finished. */
    private fun tryAgain() {
        scope.launch {
            val saved =
                try {
                    checkpointer.load(config.threadId)
                } catch (_: Throwable) {
                    null
                }
            if (saved == null) start(request, sample) else resume()
        }
    }

    // What a run shows

    private fun steps(trip: Trip?): List<Step> {
        val steps =
            listOf(
                Step(Nodes.BRIEF, "Understanding your trip"),
                Step(Nodes.GUIDE, "Travel guide · Wikivoyage"),
                Step(Nodes.MAP, "Street map · OpenStreetMap"),
                Step(Nodes.REVIEWS, "Reviews · Google"),
                Step(Nodes.DISHES, "What each place is known for"),
                Step(Nodes.PLAN, "Choosing where to eat"),
                Step(Nodes.ORDER, "Putting the days in order"),
            )
        // What a saved trip already has was done by a run before this one.
        if (trip != null) {
            val done =
                when {
                    trip.dishes.isNotEmpty() -> setOf(Nodes.BRIEF, Nodes.GUIDE, Nodes.MAP, Nodes.REVIEWS, Nodes.DISHES)
                    trip.stops.isNotEmpty() -> setOf(Nodes.BRIEF)
                    else -> emptySet()
                }
            steps.filter { it.node in done }.forEach {
                it.status = Status.DONE
                it.settled = true
            }
        }
        if (settings.googleKey.isBlank()) steps.first { it.node == Nodes.REVIEWS }.status = Status.SKIPPED
        return steps
    }

    private fun step(node: String): Step? = steps.firstOrNull { it.node == if (node == Nodes.TOOLS) Nodes.PLAN else node }

    /** What a finished step found, read from the trip. */
    private fun result(step: Step, trip: Trip?): String {
        if (step.status == Status.SKIPPED) return "Not used. Add a Google key under Settings for ratings."
        // The agent writes the route step by step, so its count is shown while it works.
        if (trip == null || step.status == Status.WAITING || (!step.settled && step.node != Nodes.PLAN)) return ""
        fun from(source: Source) = trip.restaurants.count { source in it.sources }.let { "$it ${if (it == 1) "place" else "places"}" }
        return when (step.node) {
            Nodes.BRIEF -> trip.stops.joinToString(" · ") { "${it.days} ${if (it.days == 1) "day" else "days"} in ${it.name}" }
            Nodes.GUIDE -> from(Source.GUIDE)
            Nodes.MAP -> from(Source.MAP)
            Nodes.REVIEWS -> from(Source.REVIEWS)
            Nodes.DISHES -> trip.dishes.joinToString(", ") { it.name }
            Nodes.PLAN -> if (trip.route.isEmpty()) "" else "${trip.route.size} ${if (trip.route.size == 1) "meal" else "meals"} on the route"
            else -> ""
        }
    }

    /** A tool call of the agent, in words for the traveller. */
    private fun describe(call: ToolCall, trip: Trip): String {
        fun input(key: String) = (call.input[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return when (call.name) {
            "set_meal" -> "Day ${input("day")}, ${input("meal")}: ${trip.restaurant(input("restaurant"))?.name ?: input("restaurant")}"
            "remove_meal" -> "Took the ${input("meal")} of day ${input("day")} off the route"
            "search_map" -> "Searched the map of ${input("stop")} for ${input("name").ifBlank { input("cuisine") }}"
            "read_guide" -> "Read the travel guide of ${input("page")}"
            "search_reviews" -> "Searched the reviews for \"${input("query")}\""
            "candidates" -> "Looked through the places in ${input("stop")}" + input("words").let { if (it.isBlank()) "" else " for $it" }
            else -> call.name
        }
    }

    // The page

    private fun render() {
        map?.remove()
        map = null
        runPanel = null
        root.clear()

        root.add("header", "top") {
            add("div", "brand") {
                add("h1", text = "Foodie Route")
                add("p", "tagline", "Eat where the locals eat. Planned by an agent that runs in your browser.")
            }
            button(if (settingsOpen) "Close" else "Settings", "ghost") {
                settingsOpen = !settingsOpen
                render()
            }
        }
        if (settingsOpen) root.settingsPanel()

        val trip = trip
        when (phase) {
            Phase.START -> root.startForm()
            Phase.RUNNING -> runPanel = root.add("section", "card run").also { fillRun(it) }
            Phase.QUESTION -> root.questionCard(trip?.question.orEmpty())
            Phase.REVIEW, Phase.DONE -> if (trip != null) root.route(trip)
            Phase.STOPPED ->
                root.add("section", "card") {
                    add("h2", text = "Your plan stopped halfway")
                    add("p", text = "The page was closed while the planner worked on \"${request.trim()}\". Everything it had found is saved.")
                    add("div", "actions") {
                        button("Continue", "primary") { resume() }
                        button("Start over", "ghost") { startOver() }
                    }
                }
            Phase.FAILED ->
                root.add("section", "card failed") {
                    add("h2", text = "The planner got stuck")
                    add("p", "failure", failure)
                    add("p", text = "What it found before that is saved. You can try the step again.")
                    add("div", "actions") {
                        button("Try again", "primary") { tryAgain() }
                        button("Settings", "ghost") {
                            settingsOpen = true
                            render()
                        }
                        button("Start over", "ghost") { startOver() }
                    }
                }
        }

        root.add("footer", "credits") {
            add("p") {
                add("span", text = "Restaurants from ")
                link("Wikivoyage", "https://en.wikivoyage.org/")
                add("span", text = " (CC BY-SA) and ")
                link("OpenStreetMap", "https://www.openstreetmap.org/copyright")
                add("span", text = " contributors. Check opening hours before you go: a source can be out of date.")
            }
            add("p") {
                add("span", text = "The workflow is a graph of ")
                link("Telar", "https://github.com/deeptelar/telar")
                add("span", text = ", a Kotlin library for AI agents and workflows.")
            }
        }
    }

    private fun HTMLElement.startForm() {
        add("section", "card") {
            add("h2", text = "Where are you going?")
            add("p", "hint", "Say where, for how many days, and what you like or do not eat.")
            val text =
                (add("textarea") as HTMLTextAreaElement).apply {
                    rows = 3
                    placeholder = "3 days in Lisbon and 2 in Porto. No pork, and I love pastries."
                    value = request
                    oninput = { request = value }
                }
            add("div", "chips") {
                EXAMPLES.forEach { example ->
                    button(example, "chip") {
                        request = example
                        text.value = example
                    }
                }
            }
            add("div", "actions") {
                button("Plan my route", "primary") {
                    when {
                        request.isBlank() -> text.focus()
                        // Without a key there is no model to ask: the settings say where to put one.
                        !settings.hasModel -> {
                            settingsOpen = true
                            render()
                        }
                        else -> start(request.trim(), sample = false)
                    }
                }
                button("Try the sample, no key needed", "ghost") { start(SampleModel.REQUEST, sample = true) }
            }
            if (!settings.hasModel) {
                add("p", "hint", "To plan a trip of your own, add the key of a model under Settings. The sample plans two days in Lisbon with a script in place of the model.")
            }
        }
        add("section", "how") {
            add("h2", text = "How it works")
            add("ol") {
                add("li", text = "A model reads your request and finds your stops on the map. If it cannot, it asks you.")
                add("li", text = "A travel guide, a street map and, with a Google key, reviews are searched at the same time.")
                add("li", text = "The model names what each place is known for.")
                add("li", text = "An agent chooses a restaurant for each meal. It can search the sources again for a dish.")
                add("li", text = "You get the route on a map, and can ask for changes until you like it.")
            }
        }
    }

    private fun HTMLElement.settingsPanel() {
        add("section", "card settings") {
            add("h2", text = "Settings")
            add("p", "hint", "Keys are stored in this browser only and are sent to nobody but their provider.")
            add("label", "field") {
                add("span", "field-label", "Model")
                (add("select") as HTMLSelectElement).apply {
                    Provider.entries.forEach { provider ->
                        add("option", text = provider.label) {
                            setAttribute("value", provider.name)
                            if (provider == settings.provider) setAttribute("selected", "selected")
                        }
                    }
                    onchange = {
                        change(settings.copy(provider = Provider.valueOf(value)))
                        render()
                    }
                }
            }
            when (settings.provider) {
                Provider.CLAUDE -> {
                    field("Anthropic API key", settings.claudeKey, type = "password", placeholder = "sk-ant-...") { change(settings.copy(claudeKey = it)) }
                    add("label", "field") {
                        add("span", "field-label", "Claude model")
                        (add("select") as HTMLSelectElement).apply {
                            Settings.CLAUDE_MODELS.forEach { (id, name) ->
                                add("option", text = name) {
                                    setAttribute("value", id)
                                    if (id == settings.claudeModel) setAttribute("selected", "selected")
                                }
                            }
                            onchange = { change(settings.copy(claudeModel = value)) }
                        }
                    }
                }
                Provider.OPENAI -> {
                    field("Address of the API", settings.openAiUrl, placeholder = "https://api.openai.com/v1") { change(settings.copy(openAiUrl = it)) }
                    field("Model", settings.openAiModel, placeholder = "gpt-5") { change(settings.copy(openAiModel = it)) }
                    field("API key", settings.openAiKey, type = "password", placeholder = "Leave empty for a local server") { change(settings.copy(openAiKey = it)) }
                }
            }
            field("Google Places key, for ratings (optional)", settings.googleKey, type = "password", placeholder = "AIza...") { change(settings.copy(googleKey = it)) }
            add("p", "hint", "Google bills the owner of the key for each search. Without it, the planner works with the travel guide and the street map.")
            add("div", "actions") {
                button("Done", "primary") {
                    settingsOpen = false
                    render()
                }
            }
        }
    }

    private fun change(changed: Settings) {
        settings = changed
        try {
            settings.save()
        } catch (_: Throwable) {
            // The browser blocks its storage: the settings hold until the page is closed.
        }
    }

    /** Draws the list of steps again, and nothing else: a run changes it many times a second. */
    private fun renderRun() {
        runPanel?.let { panel ->
            panel.clear()
            fillRun(panel)
        }
    }

    private fun fillRun(panel: HTMLElement) {
        panel.add("h2", text = "Planning your route")
        panel.add("p", "hint", "\"${request.trim()}\"")
        panel.add("ul", "steps") {
            steps.forEach { step ->
                add("li", "step ${step.status.name.lowercase()}") {
                    add("span", "dot")
                    add("div", "step-text") {
                        add("div", "step-title", step.title)
                        val detail = step.activity.ifEmpty { result(step, trip) }
                        if (detail.isNotEmpty()) add("div", "step-detail", detail)
                        if (step.node == Nodes.PLAN && step.status != Status.WAITING) {
                            if (feed.isNotEmpty()) add("ul", "feed") { feed.forEach { add("li", text = it) } }
                            if (writing.isNotBlank()) add("p", "writing", writing)
                        }
                    }
                }
            }
        }
        if (sample) panel.add("p", "hint", "This is the sample: a script stands in for the model. The sources it searches are real.")
        panel.add("div", "actions") { button("Cancel", "ghost") { startOver() } }
    }

    private fun HTMLElement.questionCard(question: String) {
        add("section", "card") {
            add("h2", text = "One question")
            add("p", "question", question)
            val answer =
                (add("textarea") as HTMLTextAreaElement).apply {
                    rows = 2
                    placeholder = "Your answer"
                }
            add("div", "actions") {
                button("Continue", "primary") {
                    val text = answer.value.trim()
                    // The answer joins the request, and the node that asked reads the request again.
                    if (text.isEmpty()) answer.focus() else resume { it.copy(request = it.request + "\n" + text, question = "") }
                }
                button("Start over", "ghost") { startOver() }
            }
        }
    }

    private fun HTMLElement.route(trip: Trip) {
        add("section", "route") {
            add("h2", text = if (phase == Phase.DONE) "Your foodie route" else "Here is your route")
            add("p", "hint", trip.stops.joinToString(" · ") { "${it.days} ${if (it.days == 1) "day" else "days"} in ${it.name}" })
            if (trip.summary.isNotBlank()) add("p", "summary", trip.summary)

            if (trip.route.isEmpty()) {
                add("p", "failure", "The planner put no meal on the route. Tell it below what you are looking for, or start over.")
            } else {
                val container = add("div", "map")
                val days = trip.route.map { it.day }.distinct()
                if (days.size > 1) {
                    add("div", "chips") {
                        button("Whole trip", "chip") { map?.show(null) }
                        days.forEach { day -> button("Day $day", "chip ${RouteMap.dayClass(day)}") { map?.show(day) } }
                    }
                }
                map = RouteMap(container, trip)
            }

            var number = 0
            (1..trip.days).forEach { day ->
                val meals = trip.route.filter { it.day == day }
                add("h3", "day ${RouteMap.dayClass(day)}", "Day $day · ${trip.stopOn(day)?.name.orEmpty()}")
                if (meals.isEmpty()) add("p", "hint", "Nothing planned for this day.")
                meals.forEach { meal ->
                    number++
                    meal.walk?.let { add("p", "walk", "↓ about ${distance(it)} from there") }
                    meal(trip, meal, number)
                }
            }

            trip.stops.forEach { stop ->
                val dishes = trip.dishes.filter { it.stop == stop.name }
                if (dishes.isNotEmpty()) {
                    add("details", "known") {
                        add("summary", text = "What ${stop.name} is known for")
                        add("ul") { dishes.forEach { dish -> add("li") { add("strong", text = dish.name); add("span", text = ": ${dish.what}") } } }
                    }
                }
            }
            if (trip.notes.isNotEmpty()) {
                add("details", "known") {
                    add("summary", text = "What could not be read")
                    add("ul") { trip.notes.forEach { add("li", text = it) } }
                }
            }
            // The sample asks no model, so it has no tokens to show.
            add("p", "hint small", "${trip.restaurants.size} places compared" + if (trip.tokens.total > 0) " · ${trip.tokens.total} tokens used" else "")
        }

        add("section", "card") {
            if (phase == Phase.DONE) {
                add("h2", text = "Enjoy your trip")
                add("div", "actions") {
                    button("Print or save as PDF", "primary") { window.print() }
                    button("Plan another trip", "ghost") { startOver() }
                }
            } else {
                add("h2", text = "Want something different?")
                val wish =
                    (add("textarea") as HTMLTextAreaElement).apply {
                        rows = 2
                        placeholder = "A vegetarian dinner on day 2, and somewhere cheaper for the first lunch."
                    }
                add("div", "actions") {
                    button("Update the route", "primary") {
                        val text = wish.value.trim()
                        // The wish goes to the agent as the traveller's next message, and the graph loops back to it.
                        if (text.isEmpty()) wish.focus() else resume { it.copy(messages = it.messages + ChatMessage.User(text)) }
                    }
                    button("I'm happy with it", "ghost") { resume { it.copy(approved = true) } }
                    button("Start over", "ghost") { startOver() }
                }
            }
        }
    }

    private fun HTMLElement.meal(trip: Trip, meal: Meal, number: Int) {
        val restaurant = trip.restaurant(meal.restaurantId) ?: return
        add("article", "meal") {
            add("div", "pin ${RouteMap.dayClass(meal.day)}", "$number")
            add("div", "meal-text") {
                add("div", "meal-time", meal.time.label)
                add("h4", text = restaurant.name)
                restaurant.rating?.let { stars -> add("div", "rating", "★ $stars" + restaurant.ratings?.let { " · $it ratings" }.orEmpty()) }
                add("p", "order") {
                    add("strong", text = "Order: ")
                    add("span", text = meal.dish)
                    trip.dishes.firstOrNull { it.name.equals(meal.dish, ignoreCase = true) }?.let { add("span", "what", " · ${it.what}") }
                }
                add("p", "why", meal.why)
                add("div", "badges") { restaurant.sources.forEach { add("span", "badge", it.label) } }
                val facts = listOf(restaurant.cuisine, restaurant.address, restaurant.price, restaurant.hours).filter { it.isNotBlank() }
                if (facts.isNotEmpty()) add("p", "facts", facts.joinToString(" · "))
                add("div", "links") {
                    if (restaurant.website.isNotBlank()) link("Website", restaurant.website)
                    link("Open in Google Maps", "https://www.google.com/maps/search/?api=1&query=${encode("${restaurant.name}, ${restaurant.stop}")}")
                    link("OpenStreetMap", "https://www.openstreetmap.org/?mlat=${restaurant.lat}&mlon=${restaurant.lon}#map=18/${restaurant.lat}/${restaurant.lon}")
                }
            }
        }
    }

    private fun distance(metres: Int): String = if (metres < 1000) "${(metres + 25) / 50 * 50} m" else "${(metres + 50) / 100 / 10.0} km"

    private fun encode(text: String): String = js("encodeURIComponent")(text).unsafeCast<String>()

    private companion object {
        /** How many of the agent's last actions a run shows. */
        const val FEED_LINES = 8

        val EXAMPLES =
            listOf(
                "3 days in Lisbon and 2 in Porto. No pork, and I love pastries.",
                "A weekend in Naples: pizza and street food on a low budget.",
                "4 days in Oaxaca, vegetarian.",
                "Two days in Bologna and one in Modena. I want the classics.",
            )
    }
}
