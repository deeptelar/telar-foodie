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
import kotlin.js.Date
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement

/** Where the page is in the life of a plan. */
private enum class Phase {
    /** Nothing is planned yet: the traveller writes a request. */
    START,

    /** The graph runs. */
    RUNNING,

    /** The run paused in `interview` or `brief` with questions for the traveller. */
    QUESTION,

    /** The interview is over: the traveller checks what the planner understood. */
    CHECKING,

    /** The run paused before `review`: the route is there, and the traveller says yes or asks for a change. */
    REVIEW,

    /** The traveller said yes and the run ended. */
    DONE,

    /** The traveller paused the run, to add something or to send it another way. */
    PAUSED,

    /** A saved run that stopped in the middle, because the page was closed while it ran. */
    STOPPED,

    /** A step failed. The run can be continued from the step before it. */
    FAILED,
}

private enum class Status { WAITING, RUNNING, DONE, SKIPPED }

/** The two tabs of the page: the conversation with the planner, and what it knows about the traveller. */
private enum class Tab { PLAN, DETAILS }

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

    /** When the node started, and when it last said something, in milliseconds of `Date.now()`. */
    var started: Double = 0.0
    var heard: Double = 0.0

    /** When the node was done, in milliseconds of `Date.now()`; 0 when it was not done in this run. */
    var finished: Double = 0.0
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

    /** The language of the page, and of the next trip. */
    private val language: Language get() = settings.language ?: Language.of(window.navigator.language)

    /** The texts of the page, in its language. */
    private val texts: Texts get() = language.texts
    private var settingsOpen = false
    private var tab = Tab.PLAN

    /** Whether the details were just saved, for a word under them. */
    private var detailsSaved = false
    private var phase = Phase.START
    private var trip: Trip? = null
    private var request = ""
    private var sample = false
    private var failure = ""
    private var steps = emptyList<Step>()
    private var feed = emptyList<String>()
    private var writing = ""

    /** Which of the dishes shown while the agent chooses is up, since when, and which one was drawn last: -1 before the first. */
    private var dishShown = 0
    private var dishShownAt = 0.0
    private var dishDrawn = -1

    /** The dishes as soon as the node named them: the trip has them only when the sources are done too. */
    private var named = emptyList<Dish>()

    /** What the traveller tapped and wrote for each open question, by its text, so that drawing the page again keeps it. */
    private var drafts = emptyMap<String, Draft>()

    /** The profile as the traveller is changing it, before it is sent. `null` until the profile is shown. */
    private var editing: ProfileDraft? = null
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
                "sample" in window.location.search -> start(texts.sampleRequest, sample = true)
            }
        }
    }

    // The run

    /** The graph for the current settings. Without a key for a model, the sample script plans. */
    private fun planner(): CompiledGraph<Trip> {
        val model = if (sample) SampleModel() else settings.model(client) ?: SampleModel()
        val planModel = if (sample) model else settings.planModel(client) ?: model
        return planner(model, Sources.online(client, settings.googleKey), planModel = planModel)
    }

    private fun start(request: String, sample: Boolean) {
        this.sample = sample
        this.request = request
        val trip = Trip(request, language = language)
        this.trip = trip
        tab = Tab.PLAN
        run { planner().stream(trip, config) }
    }

    /**
     * Continues the saved run, with [update] applied to its state first. [trip] is then the state
     * the run continues with, which is saved only when its step is done: a pause before that keeps it.
     */
    private fun resume(update: (Trip) -> Trip = { it }) {
        run { planner().streamResume(config) { update(it).also { started -> trip = started } } }
    }

    /** Stops the run where it is. What its current step did is lost, and the step runs again when the run goes on. */
    private fun pause() {
        job?.cancel()
        phase = Phase.PAUSED
        render()
    }

    /** Continues a paused run from [from], with the traveller's [text] if they wrote any. */
    private fun goOn(from: Trip, text: String) {
        when {
            text.isEmpty() -> resume { from }
            from.planning -> resume { from.redirected(text) }
            // What the planner read from the trip is read again with the words: the run starts again from the interview.
            else -> {
                val told = from.toldMore(text)
                trip = told
                run { planner().stream(told, config) }
            }
        }
    }

    private fun run(events: () -> Flow<GraphEvent<Trip>>) {
        phase = Phase.RUNNING
        drafts = emptyMap()
        editing = null
        failure = ""
        writing = ""
        feed = emptyList()
        dishShown = 0
        dishDrawn = -1
        named = emptyList()
        steps = steps(trip)
        render()
        job =
            scope.launch {
                // A step that waits for a model shows how long it has waited, so that a slow model does not look like a stuck page.
                val clock = launch {
                    while (true) {
                        delay(1000)
                        if (steps.any { it.status == Status.RUNNING }) renderRun()
                    }
                }
                try {
                    events().collect(::on)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    failure = e.message ?: texts.somethingWentWrong
                    phase = Phase.FAILED
                    render()
                } finally {
                    clock.cancel()
                }
            }
    }

    private fun on(event: GraphEvent<Trip>) {
        when (event) {
            is GraphEvent.NodeStarted -> {
                step(event.node)?.let {
                    // The agent's two nodes are one step of the list: it keeps the time of its first round.
                    if (it.status != Status.RUNNING) it.started = Date.now()
                    it.heard = Date.now()
                    it.status = Status.RUNNING
                }
                when (event.node) {
                    Nodes.PLAN -> writing = ""
                    // The calls the model just asked for are the last message of the state the tools node starts with.
                    Nodes.TOOLS -> feed = (feed + event.state.messages.pendingToolCalls().flatMap { describe(it, event.state) }).takeLast(FEED_LINES)
                    Nodes.ORDER -> step(Nodes.PLAN)?.let {
                        it.status = Status.DONE
                        it.finished = Date.now()
                    }
                }
                renderRun()
            }
            is GraphEvent.NodeProgress -> {
                when (val value = event.value) {
                    is Activity ->
                        step(event.node)?.let {
                            it.activity = value.text
                            it.heard = Date.now()
                        }
                    is ChatEvent.TextDelta -> writing += value.text
                    is Named -> named = value.dishes
                }
                renderRun()
            }
            is GraphEvent.NodeRetry -> {
                step(event.node)?.activity = texts.retrying(event.cause.message.orEmpty())
                renderRun()
            }
            is GraphEvent.NodeCompleted -> {
                // The agent is done when the node after it starts, not when one of its rounds ends.
                val step = step(event.node)
                if (step != null && step.node != Nodes.PLAN) {
                    step.status = Status.DONE
                    step.finished = Date.now()
                }
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
                result.state.questions.isNotEmpty() -> Phase.QUESTION
                result.state.interview.stage == InterviewStage.CHECKING -> Phase.CHECKING
                else -> Phase.STOPPED
            }
        steps = steps(trip)
        render()
        // The planner's new words are under the conversation: the traveller is taken to them.
        if (tab == Tab.PLAN) (root.querySelector(".turn") as? HTMLElement)?.let { turn -> window.requestAnimationFrame { turn.asDynamic().scrollIntoView(js("({ block: 'start', behavior: 'smooth' })")) } }
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
            editing = null
            tab = Tab.PLAN
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
                Step(Nodes.INTERVIEW, texts.stepInterview),
                Step(Nodes.BRIEF, texts.stepBrief),
                Step(Nodes.GUIDE, texts.stepGuide),
                Step(Nodes.MAP, texts.stepMap),
                Step(Nodes.REVIEWS, texts.stepReviews),
                Step(Nodes.DISHES, texts.stepDishes),
                Step(Nodes.PLAN, texts.stepPlan),
                Step(Nodes.ORDER, texts.stepOrder),
            )
        // What a saved trip already has was done by a run before this one.
        if (trip != null) {
            val done =
                when {
                    trip.dishes.isNotEmpty() -> setOf(Nodes.INTERVIEW, Nodes.BRIEF, Nodes.GUIDE, Nodes.MAP, Nodes.REVIEWS, Nodes.DISHES)
                    trip.stops.isNotEmpty() -> setOf(Nodes.INTERVIEW, Nodes.BRIEF)
                    trip.interview.stage == InterviewStage.DONE -> setOf(Nodes.INTERVIEW)
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
        if (step.status == Status.SKIPPED) return texts.reviewsNotUsed
        // The agent writes the route step by step, so its count is shown while it works.
        if (trip == null || step.status == Status.WAITING || (!step.settled && step.node != Nodes.PLAN)) return ""
        fun from(source: Source) = texts.places(trip.restaurants.count { source in it.sources })
        return when (step.node) {
            Nodes.INTERVIEW -> profileRows(trip.profile).joinToString(" · ") { it.second }
            Nodes.BRIEF -> trip.stops.joinToString(" · ") { texts.daysIn(it.days, it.title) }
            Nodes.GUIDE -> from(Source.GUIDE)
            Nodes.MAP -> from(Source.MAP)
            Nodes.REVIEWS -> from(Source.REVIEWS)
            Nodes.DISHES -> trip.dishes.joinToString(", ") { it.name }
            Nodes.PLAN -> if (trip.route.isEmpty()) "" else texts.mealsOnTheRoute(trip.route.size)
            else -> ""
        }
    }

    /** A tool call of the agent, in words for the traveller: a line, or one for each meal of a route. */
    private fun describe(call: ToolCall, trip: Trip): List<String> {
        if (call.name == "set_route") return (call.input["meals"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { meal -> describe(ToolCall(call.id, "set_meal", meal), trip) } }.flatten()
        fun input(key: String) = (call.input[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        // The model names a meal in English; the traveller reads it in the page's language.
        fun meal() = MealTime.of(input("meal"))?.let { texts.meal(it).lowercase() } ?: input("meal")
        val line = when (call.name) {
            "set_meal" -> texts.didSetMeal(input("day"), meal(), trip.restaurant(input("restaurant"))?.name ?: input("restaurant"))
            "remove_meal" -> texts.didRemoveMeal(input("day"), meal())
            "search_map" -> texts.didSearchMap(input("stop"), input("name").ifBlank { input("cuisine") })
            "read_guide" -> texts.didReadGuide(input("page"))
            "search_reviews" -> texts.didSearchReviews(input("query"))
            "candidates" -> texts.didLookThrough(input("stop"), input("words"))
            else -> call.name
        }
        return listOf(line)
    }

    // The page

    private fun render() {
        stopSpeaking()
        document.documentElement?.setAttribute("lang", language.code)
        map?.remove()
        map = null
        runPanel = null
        root.clear()

        root.add("header", "top") {
            add("h1", text = "Foodie Route")
            add("div", "top-actions") {
                (add("select", "language") as HTMLSelectElement).apply {
                    setAttribute("aria-label", texts.languageLabel)
                    Language.entries.forEach { option ->
                        add("option", text = option.own) {
                            setAttribute("value", option.name)
                            if (option == language) setAttribute("selected", "selected")
                        }
                    }
                    onchange = {
                        change(settings.copy(language = Language.valueOf(value)))
                        render()
                    }
                }
                button(if (settingsOpen) texts.close else texts.settings, "ghost") {
                    settingsOpen = !settingsOpen
                    render()
                }
            }
        }
        // Below the buttons, across the page: next to them it has no room on a phone.
        root.add("p", "tagline", texts.tagline)
        if (settingsOpen) root.settingsPanel()

        val trip = trip
        // Before a trip there is nothing to keep apart from the conversation.
        if (phase != Phase.START) root.tabs()
        if (phase != Phase.START && tab == Tab.DETAILS) {
            root.detailsTab(trip)
        } else {
            plan(trip)
        }
        root.credits()
    }

    private fun HTMLElement.tabs() {
        add("nav", "tabs") {
            setAttribute("role", "tablist")
            // The planner waits for the traveller: the tab of the conversation says so.
            val waiting = phase in setOf(Phase.QUESTION, Phase.CHECKING, Phase.REVIEW, Phase.PAUSED)
            listOf(Tab.PLAN to texts.planTab + if (tab != Tab.PLAN && waiting) " •" else "", Tab.DETAILS to texts.detailsTab).forEach { (it, label) ->
                button(label, "tab" + if (it == tab) " current" else "") {
                    tab = it
                    detailsSaved = false
                    render()
                }.setAttribute("aria-selected", (it == tab).toString())
            }
        }
    }

    /** The tab of the conversation: what was said, and what the planner does or asks now. */
    private fun plan(trip: Trip?) {
        // The route is what the traveller came for: it stands alone, without the conversation above it.
        if (trip != null && phase !in setOf(Phase.START, Phase.REVIEW, Phase.DONE)) root.thread(trip)
        when (phase) {
            Phase.START -> root.startForm()
            Phase.RUNNING -> runPanel = root.add("section", "card run").also { fillRun(it) }
            Phase.QUESTION -> if (trip != null) root.questionTurn(trip)
            Phase.CHECKING -> if (trip != null) root.checkingTurn(trip)
            Phase.REVIEW, Phase.DONE -> if (trip != null) root.route(trip)
            Phase.PAUSED, Phase.STOPPED -> if (trip != null) root.pausedTurn(trip)
            Phase.FAILED ->
                root.add("section", "card failed") {
                    add("h2", text = texts.gotStuck)
                    add("p", "failure", failure)
                    // A provider that refuses a request often sends no header that lets the page read why: the browser then reports that it could not reach it.
                    if ("Fail to fetch" in failure || "Failed to fetch" in failure) add("p", "hint", texts.refusedLooksUnreachable)
                    add("p", text = texts.foundIsSaved)
                    add("div", "actions") {
                        button(texts.tryAgain, "primary") { tryAgain() }
                        button(texts.settings, "ghost") {
                            settingsOpen = true
                            render()
                        }
                        button(texts.startOver, "ghost") { startOver() }
                    }
                }
        }
    }

    private fun HTMLElement.credits() {
        add("footer", "credits") {
            add("p") {
                add("span", text = texts.restaurantsFrom)
                link("Wikivoyage", "https://en.wikivoyage.org/")
                add("span", text = texts.ccBySaAnd)
                link("OpenStreetMap", "https://www.openstreetmap.org/copyright")
                add("span", text = texts.contributorsCheckHours)
            }
            add("p") {
                add("span", text = texts.workflowIsAGraphOf)
                link("Telar", "https://github.com/deeptelar/telar")
                add("span", text = texts.kotlinLibrary)
            }
        }
    }

    private fun HTMLElement.startForm() {
        add("section", "card") {
            add("h2", text = texts.whereAreYouGoing)
            add("p", "hint", texts.startHint)
            val text =
                (add("textarea") as HTMLTextAreaElement).apply {
                    rows = 4
                    placeholder = texts.requestPlaceholder
                    value = request
                    oninput = { request = value }
                }
            speakRow(read = { request }) {
                request = it
                text.value = it
            }
            add("div", "chips") {
                texts.examples.forEach { example ->
                    button(example, "chip") {
                        request = example
                        text.value = example
                    }
                }
            }
            add("div", "actions") {
                button(texts.planMyRoute, "primary") {
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
                button(texts.trySample, "ghost") { start(texts.sampleRequest, sample = true) }
            }
            if (speechSupported) {
                add("p", "hint small", texts.speechPrivacy)
            }
            if (!settings.hasModel) {
                add("p", "hint", texts.needAKey)
            }
        }
        add("section", "how") {
            add("h2", text = texts.howItWorks)
            add("ol") { texts.howItWorksSteps.forEach { add("li", text = it) } }
        }
    }

    private fun HTMLElement.settingsPanel() {
        add("section", "card settings") {
            add("h2", text = texts.settings)
            add("p", "hint", texts.keysStayHere)
            add("label", "field") {
                add("span", "field-label", texts.model)
                (add("select") as HTMLSelectElement).apply {
                    Provider.entries.forEach { provider ->
                        add("option", text = if (provider == Provider.OPENAI) texts.openAiProvider else provider.label) {
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
                    field(texts.anthropicKey, settings.claudeKey, type = "password", placeholder = "sk-ant-...") { change(settings.copy(claudeKey = it)) }
                    add("label", "field") {
                        add("span", "field-label", texts.claudeModel)
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
                    field(texts.apiAddress, settings.openAiUrl, placeholder = "https://api.openai.com/v1") { change(settings.copy(openAiUrl = it)) }
                    field(texts.model, settings.openAiModel, placeholder = "gpt-5") { change(settings.copy(openAiModel = it)) }
                    field(texts.apiKey, settings.openAiKey, type = "password", placeholder = texts.emptyForLocalServer) { change(settings.copy(openAiKey = it)) }
                }
            }
            field(texts.googleKey, settings.googleKey, type = "password", placeholder = "AIza...") { change(settings.copy(googleKey = it)) }
            add("p", "hint", texts.googleBills)
            add("details", "how-to") {
                add("summary", text = texts.howToGetAGoogleKey)
                add("ol") {
                    // The browser tells Google only the site a request comes from, not the page: the key is restricted to the site.
                    texts.googleKeySteps(window.location.origin + "/*").forEach { step ->
                        add("li") {
                            add("span", text = step.text)
                            if (step.url.isNotEmpty()) {
                                add("br")
                                link(step.link, step.url)
                            }
                        }
                    }
                }
                add("p", "hint small") {
                    link(texts.googlePrices, GoogleConsole.PRICES)
                    add("span", text = " · ")
                    link(texts.googleGuide, GoogleConsole.GUIDE)
                }
            }
            add("div", "actions") {
                button(texts.done, "primary") {
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
        panel.add("h2", text = texts.planningYourRoute)
        panel.add("ul", "steps") {
            steps.forEach { step ->
                add("li", "step ${step.status.name.lowercase()}") {
                    add("span", "dot")
                    add("div", "step-text") {
                        add("div", "step-title", step.title)
                        val detail = step.activity.ifEmpty { result(step, trip) }
                        val running = step.status == Status.RUNNING
                        // A step that ran in this run says how long it took, and a running one how long it has run.
                        val took = ((if (running) Date.now() else step.finished) - step.started) / 1000
                        val time = if (running || step.finished > 0) texts.seconds(took.toInt()) else ""
                        if (detail.isNotEmpty() || time.isNotEmpty()) {
                            add("div", "step-detail", listOf(detail, time).filter { it.isNotEmpty() }.joinToString(" · "))
                        }
                        // A model that thinks before it writes says nothing for a while: say that this is expected.
                        if (running && step.node in WAITS_FOR_A_MODEL && Date.now() - step.heard > QUIET_MILLIS) add("div", "step-detail quiet", texts.modelThinking)
                        if (step.node == Nodes.PLAN && step.status != Status.WAITING) {
                            if (feed.isNotEmpty()) add("ul", "feed") { feed.forEach { add("li", text = it) } }
                            if (writing.isNotBlank()) add("p", "writing", writing)
                        }
                        // Meanwhile the food of the trip is shown, one dish at a time: under the dishes while the sources are still read, then under the choosing.
                        val dishes = trip?.let { if (it.dishes.isEmpty()) it.copy(dishes = named) else it }?.whileYouWait().orEmpty()
                        val planning = step(Nodes.PLAN)?.status
                        val here = if (step.node == Nodes.PLAN) running else step.node == Nodes.DISHES && step.status == Status.DONE && planning == Status.WAITING
                        if (here && dishes.isNotEmpty()) whileYouWait(dishes)
                    }
                }
            }
        }
        if (sample) panel.add("p", "hint", texts.sampleRunning)
        panel.add("div", "actions") {
            button(texts.pause, "ghost") { pause() }
            button(texts.cancel, "ghost") { startOver() }
        }
    }

    /** One of [dishes]: the next one comes after a while, or when the traveller asks for it. */
    private fun HTMLElement.whileYouWait(dishes: List<Dish>) {
        if (dishDrawn < 0) {
            dishShownAt = Date.now()
        } else if (Date.now() - dishShownAt > DISH_MILLIS) {
            nextDish()
        }
        val dish = dishes[dishShown % dishes.size]
        // The panel is drawn again every second: only a dish that was not on it yet fades in.
        val first = dishDrawn < 0
        val card = add("aside", "tidbit" + if (dishShown != dishDrawn) " fresh" else "") {
            add("p", "tidbit-label", texts.whileIPlan(trip?.stop(dish.stop)?.title ?: dish.stop))
            add("p", "tidbit-name", dish.name)
            add("p", text = dish.what)
            if (dish.story.isNotBlank()) add("p", "story", dish.story)
            if (dishes.size > 1) {
                button(texts.anotherDish, "link") {
                    nextDish()
                    renderRun()
                }
            }
        }
        dishDrawn = dishShown
        // The steps above fill a phone's screen: the first dish is brought into view once.
        if (first) window.requestAnimationFrame { card.asDynamic().scrollIntoView(js("({ block: 'nearest', behavior: 'smooth' })")) }
    }

    private fun nextDish() {
        dishShown++
        dishShownAt = Date.now()
    }

    /** What was said so far, as a conversation: the request, each question and its answer, and what the traveller added. */
    private fun HTMLElement.thread(trip: Trip) {
        add("div", "thread") {
            bubble("them", texts.whereAreYouGoing)
            bubble("me", trip.request.trim())
            trip.answers.forEach { answer ->
                when (answer.aside) {
                    Aside.EDITED -> add("p", "note", texts.youEditedDetails)
                    Aside.ERASED -> add("p", "note", texts.youErasedDetails)
                    Aside.CORRECTION, Aside.ADDED -> bubble("me", answer.text.trim())
                    null -> {
                        bubble("them", answer.question)
                        if (answer.text.isBlank()) bubble("me skipped", texts.skipped) else bubble("me", answer.text.trim())
                    }
                }
            }
        }
    }

    private fun Element.bubble(who: String, text: String? = null, build: HTMLElement.() -> Unit = {}): HTMLElement = add("div", "bubble $who", text, build)

    /** A run that does not run: the traveller paused it, or the page was closed while it ran. Words written here go with it when it goes on. */
    private fun HTMLElement.pausedTurn(trip: Trip) {
        add("section", "turn") {
            bubble("them") {
                if (phase == Phase.PAUSED) {
                    add("p", text = texts.paused)
                } else {
                    add("p", text = texts.stoppedHalfway)
                    add("p", text = texts.closedWhileWorking(request.trim()))
                }
                add("p", text = texts.pausedHint)
            }
            val aside =
                (add("textarea") as HTMLTextAreaElement).apply {
                    rows = 2
                    placeholder = texts.asidePlaceholder
                }
            speakRow(read = { aside.value }) { aside.value = it }
            add("div", "actions") {
                button(texts.continueRun, "primary") { goOn(trip, aside.value.trim()) }
                button(texts.startOver, "ghost") { startOver() }
            }
        }
    }

    /** The planner's questions, as its next words in the conversation. */
    private fun HTMLElement.questionTurn(trip: Trip) {
        val interviewing = trip.interview.stage != InterviewStage.DONE
        add("section", "turn") {
            val left = Topic.entries.count { it !in trip.interview.covered }
            // What the request already said, so the traveller sees that it was heard, and why these are the questions.
            val asked = trip.answers.mapNotNull { it.topic } + trip.questions.map { it.topic }
            val told = Topic.entries.filter { it in trip.interview.covered && it !in asked }
            if (interviewing && trip.interview.rounds == 1 && told.isNotEmpty()) bubble("them", texts.youAlreadyToldMe(told.map { texts.topic(it) }))
            trip.questions.forEach { question(it) }
            add(
                "p",
                "hint small",
                when {
                    !interviewing -> texts.oneMoreThing
                    left == 0 -> texts.lastQuestions
                    else -> texts.moreQuestions(left)
                },
            )
            fun answers() = trip.questions.map { Answer(it.topic, it.text, (drafts[it.text] ?: Draft()).text) }
            add("div", "actions") {
                // The answers join the trip, and the node that asked reads them. They are taken now: a run starts with no drafts.
                button(texts.continueAnswers, "primary") {
                    val answers = answers()
                    resume { it.answered(answers) }
                }
                // The questions of the brief come after the interview, and it cannot go on without them.
                if (interviewing) {
                    button(texts.planNow, "ghost") {
                        val answers = answers()
                        resume { it.planNow(answers) }
                    }
                }
            }
            quietActions()
            if (sample) add("p", "hint small", texts.sampleAsks)
        }
    }

    /** What the planner understood, in a few lines, and the question whether to plan: the traveller says yes, or what to change. */
    private fun HTMLElement.checkingTurn(trip: Trip) {
        add("section", "turn") {
            bubble("them") {
                add("p", text = texts.hereIsWhatIHave)
                add("ul", "understood") {
                    profileRows(trip.profile).forEach { (label, value) ->
                        add("li") {
                            add("strong", text = "$label: ")
                            add("span", text = value)
                        }
                    }
                }
                add("p", text = texts.shallIPlan)
            }
            val correction =
                (add("textarea") as HTMLTextAreaElement).apply {
                    rows = 2
                    placeholder = texts.correctionPlaceholder
                }
            speakRow(read = { correction.value }) { correction.value = it }
            add("div", "actions") {
                button(texts.yesPlanIt, "primary") { resume { it.confirmed() } }
                button(texts.send, "ghost") {
                    val text = correction.value.trim()
                    if (text.isEmpty()) correction.focus() else resume { it.corrected(text) }
                }
            }
            quietActions()
            if (sample) add("p", "hint small", texts.sampleDidNotRead)
        }
    }

    /** What the traveller may want now and then, but not at every turn: the details, and starting again. */
    private fun HTMLElement.quietActions() {
        add("div", "quiet-actions") {
            button(texts.seeYourDetails, "link") { openDetails() }
            button(texts.startOver, "link") { startOver() }
        }
    }

    private fun openDetails() {
        tab = Tab.DETAILS
        detailsSaved = false
        render()
        window.scrollTo(0.0, 0.0)
    }

    /**
     * The tab of what the planner knows about the traveller. It can be changed while the run waits,
     * and only read while the planner works with it or when the trip is planned.
     */
    private fun HTMLElement.detailsTab(trip: Trip?) {
        add("section", "card") {
            add("h2", text = texts.detailsTab)
            if (trip == null || trip.interview.read < 0) {
                add("p", "hint", texts.noDetailsYet)
                return@add
            }
            if (phase == Phase.RUNNING || phase == Phase.DONE) {
                add("p", "hint", if (phase == Phase.RUNNING) texts.detailsWhileWorking else texts.detailsReadOnly)
                add("dl", "profile") {
                    profileRows(trip.profile).forEach { (label, value) ->
                        add("dt", text = label)
                        add("dd", text = value)
                    }
                }
            } else {
                add("p", "hint", texts.profileHint)
                profileEditor(trip)
                add("div", "actions") { button(if (phase == Phase.REVIEW) texts.updateForTheseDetails else texts.save, "primary") { saveDetails() } }
                if (detailsSaved) add("p", "saved", texts.saved)
            }
            add("p", "hint small", texts.keptInThisBrowser)
        }
    }

    /** Puts the traveller's edit of the details into the trip, as fits where the run is. */
    private fun saveDetails() {
        val draft = editing ?: return
        val now = trip ?: return
        if (now.edited(draft.profile, draft.erase) == now) return
        when (phase) {
            // The route is planned: the agent changes it for the new details.
            Phase.REVIEW -> {
                tab = Tab.PLAN
                resume { it.replannedFor(draft.profile, draft.erase) }
            }
            // A paused run goes on from the trip the page holds, which can be newer than the saved one.
            Phase.PAUSED -> saved(now.edited(draft.profile, draft.erase))
            // The run waits, so its checkpoint takes the edit: nothing runs, and a reload keeps it.
            else ->
                scope.launch {
                    val edited =
                        try {
                            checkpointer.load(config.threadId)?.let { checkpoint ->
                                checkpoint.state.edited(draft.profile, draft.erase).also { checkpointer.save(config.threadId, checkpoint.copy(state = it)) }
                            }
                        } catch (_: Throwable) {
                            // The browser blocks its storage: the edit holds until the page is closed.
                            null
                        }
                    saved(edited ?: now.edited(draft.profile, draft.erase))
                }
        }
    }

    private fun saved(edited: Trip) {
        trip = edited
        editing = ProfileDraft(edited.profile)
        detailsSaved = true
        render()
    }

    /** One question: its answers to tap, and a line for the traveller's own words. */
    private fun HTMLElement.question(question: Question) {
        add("div", "ask bubble them") {
            add("p", "question", question.text)
            if (question.options.isNotEmpty()) add("div", "chips") { question.options.forEach { chip(question, it) } }
            when (question.topic) {
                Topic.ALLERGIES -> add("p", "hint small", texts.allergyNote)
                Topic.PREGNANCY -> add("p", "hint small", texts.pregnancyNote)
                else -> Unit
            }
            val own = drafts[question.text]?.own.orEmpty()
            fun write(text: String) {
                drafts = drafts + (question.text to (drafts[question.text] ?: Draft()).copy(own = text))
            }
            val field =
                if (question.options.isEmpty()) {
                    (add("textarea") as HTMLTextAreaElement).apply {
                        rows = 2
                        placeholder = texts.yourAnswer
                        value = own
                        oninput = { write(value) }
                    }
                } else {
                    (add("input") as HTMLInputElement).apply {
                        placeholder = texts.somethingElse
                        value = own
                        oninput = { write(value) }
                    }
                }
            speakRow(read = { drafts[question.text]?.own.orEmpty() }) {
                write(it)
                if (field is HTMLTextAreaElement) field.value = it else (field as HTMLInputElement).value = it
            }
        }
    }

    /** A microphone button under a text field, where the browser can turn speech into text. */
    private fun HTMLElement.speakRow(read: () -> String, write: (String) -> Unit) {
        // A trip that is under way keeps the language it was started in.
        if (speechSupported) add("div", "speak-row") { speakButton(trip?.language ?: language, texts, read, write) }
    }

    /**
     * An answer to tap. Several can be tapped together, except an answer that rules out the others,
     * such as "None": tapping it clears the rest, and tapping another clears it. A second tap takes an answer back.
     */
    private fun Element.chip(question: Question, option: String) {
        val draft = drafts[question.text] ?: Draft()
        button(option, "chip answer" + if (option in draft.picked) " picked" else "") {
            val now = drafts[question.text] ?: Draft()
            val picked =
                when {
                    option in now.picked -> now.picked - option
                    option in question.alone -> listOf(option)
                    else -> now.picked.filter { it !in question.alone } + option
                }
            drafts = drafts + (question.text to now.copy(picked = picked))
            // Only the chips of this question are drawn again: what the traveller is typing stays.
            clear()
            question.options.forEach { chip(question, it) }
        }
    }

    /**
     * The profile as fields the traveller can change or empty. A change goes into [editing], and
     * nothing is saved until the button under the fields is tapped.
     */
    private fun HTMLElement.profileEditor(trip: Trip) {
        if (editing == null) editing = ProfileDraft(trip.profile)
        val box = add("div", "editor")
        fun draw() {
            // Only the fields are drawn again, after a tap: what the traveller typed is in [editing] already.
            box.clear()
            box.fillEditor(::draw)
        }
        draw()
    }

    private fun HTMLElement.fillEditor(redraw: () -> Unit) {
        val profile = editing?.profile ?: return
        fun edit(change: (Profile) -> Profile) {
            editing = editing?.let { it.copy(profile = change(it.profile)) }
        }
        fun changed(change: (Profile) -> Profile) {
            edit(change)
            redraw()
        }
        fun List<Allergy>.at(index: Int, change: (Allergy) -> Allergy) = mapIndexed { i, allergy -> if (i == index) change(allergy) else allergy }

        editRow(texts.allergies) {
            profile.allergies.forEachIndexed { index, allergy ->
                add("div", "allergy") {
                    add("div", "edit-line") {
                        textInput(allergy.food, texts.allergyPlaceholder, texts.allergies, "food") { food -> edit { it.copy(allergies = it.allergies.at(index) { a -> a.copy(food = food) }) } }
                        eraseButton { changed { it.copy(allergies = it.allergies.filterIndexed { i, _ -> i != index }) } }
                    }
                    check(texts.evenTracesOption, allergy.severe) { on -> edit { it.copy(allergies = it.allergies.at(index) { a -> a.copy(severe = on) }) } }
                }
            }
            if (profile.allergies.isEmpty()) add("p", "hint small", texts.noAllergies)
            button("+ " + texts.addAllergy, "chip") {
                changed { it.copy(allergies = it.allergies + Allergy("")) }
                this@fillEditor.querySelectorAll("input.food").let { (it.item(it.length - 1) as? HTMLInputElement)?.focus() }
            }
        }
        editRow(texts.pregnancy) { check(texts.someoneIsPregnant, profile.pregnant) { on -> edit { it.copy(pregnant = on) } } }
        textRow(texts.diet, profile.diet, texts.eatsEverything, ::changed) { p, text -> p.copy(diet = text) }
        textRow(texts.whenLabel, profile.season, texts.notSaid, ::changed) { p, text -> p.copy(season = text) }
        textRow(texts.who, profile.party, texts.notSaid, ::changed) { p, text -> p.copy(party = text) }
        editRow(texts.meals) {
            add("div", "chips") {
                MealTime.entries.forEach { time ->
                    button(texts.meal(time), "chip answer" + if (time in profile.meals) " picked" else "") {
                        changed { it.copy(meals = if (time in it.meals) it.meals - time else (it.meals + time).sorted()) }
                    }
                }
            }
            if (profile.meals.isEmpty()) add("p", "hint small", texts.mealsIfNone)
        }
        editRow(texts.budget) {
            add("div", "chips") {
                BUDGETS.forEach { word ->
                    button(texts.budget(word).replaceFirstChar { it.uppercase() }, "chip answer" + if (profile.budget == word) " picked" else "") { changed { it.copy(budget = word) } }
                }
            }
            check(texts.oneSpecialMeal, profile.splurge) { on -> edit { it.copy(splurge = on) } }
        }
        textRow(texts.taste, profile.taste, texts.notSaid, ::changed) { p, text -> p.copy(taste = text) }
        textRow(texts.drinks, profile.drinks, texts.notSaid, ::changed) { p, text -> p.copy(drinks = text) }
        textRow(texts.wishes, profile.wishes, texts.notSaid, ::changed) { p, text -> p.copy(wishes = text) }
        add("div", "actions") {
            button(texts.eraseEverything, "ghost danger") {
                editing = ProfileDraft(Profile(), erase = true)
                redraw()
            }
        }
    }

    /** A thing of the profile: its name, and what changes it. */
    private fun Element.editRow(label: String, build: HTMLElement.() -> Unit) {
        add("div", "edit-row") {
            add("span", "field-label", label)
            build()
        }
    }

    /** A thing of the profile in words, with a button that empties it. */
    private fun Element.textRow(label: String, value: String, placeholder: String, changed: ((Profile) -> Profile) -> Unit, set: (Profile, String) -> Profile) {
        editRow(label) {
            add("div", "edit-line") {
                // Words such as a diet with its reasons need more than a line: the field grows with them.
                (add("textarea") as HTMLTextAreaElement).apply {
                    rows = 1
                    this.value = value
                    this.placeholder = placeholder
                    setAttribute("aria-label", label)
                    fun fit() {
                        style.height = "auto"
                        style.height = "${scrollHeight + 2}px"
                    }
                    oninput = {
                        fit()
                        editing = editing?.let { it.copy(profile = set(it.profile, this.value)) }
                    }
                    // The field is on the page only after this is drawn, and only then has a height.
                    window.requestAnimationFrame { fit() }
                }
                eraseButton { changed { set(it, "") } }
            }
        }
    }

    private fun Element.textInput(value: String, placeholder: String, label: String, classes: String = "", onChange: (String) -> Unit): HTMLInputElement =
        (add("input", classes) as HTMLInputElement).apply {
            type = "text"
            this.value = value
            this.placeholder = placeholder
            autocomplete = "off"
            setAttribute("aria-label", label)
            oninput = { onChange(this.value) }
        }

    private fun Element.check(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
        add("label", "check") {
            (add("input") as HTMLInputElement).apply {
                type = "checkbox"
                checked = on
                onchange = { onChange(checked) }
            }
            add("span", text = label)
        }
    }

    private fun Element.eraseButton(onClick: () -> Unit) {
        button("×", "erase", onClick).apply {
            setAttribute("aria-label", texts.erase)
            title = texts.erase
        }
    }

    /** The profile in words for the traveller: a label and a value for each thing that is known. */
    private fun profileRows(profile: Profile): List<Pair<String, String>> =
        listOfNotNull(
            texts.allergies to if (profile.allergies.isEmpty()) texts.noAllergies else profile.allergies.joinToString { if (it.severe) texts.evenTraces(it.food) else it.food },
            if (profile.pregnant) texts.pregnancy to texts.someoneIsPregnant else null,
            texts.diet to profile.diet.ifBlank { texts.eatsEverything },
            profile.season.takeIf { it.isNotBlank() }?.let { texts.whenLabel to it },
            profile.party.takeIf { it.isNotBlank() }?.let { texts.who to it },
            profile.meals.takeIf { it.isNotEmpty() }?.let { meals -> texts.meals to meals.sorted().joinToString { texts.meal(it).lowercase() } },
            texts.budget to texts.budget(profile.budget) + if (profile.splurge) texts.withASpecialMeal else "",
            profile.taste.takeIf { it.isNotBlank() }?.let { texts.taste to it },
            profile.drinks.takeIf { it.isNotBlank() }?.let { texts.drinks to it },
            profile.wishes.takeIf { it.isNotBlank() }?.let { texts.wishes to it },
        )

    private fun HTMLElement.route(trip: Trip) {
        add("section", "route") {
            add("h2", text = if (phase == Phase.DONE) texts.yourFoodieRoute else texts.hereIsYourRoute)
            add("p", "hint", trip.stops.joinToString(" · ") { texts.daysIn(it.days, it.title) })
            if (trip.summary.isNotBlank()) add("p", "summary", trip.summary)

            if (trip.route.isEmpty()) {
                add("p", "failure", texts.noMealPlanned)
            } else {
                val container = add("div", "map")
                val days = trip.route.map { it.day }.distinct()
                if (days.size > 1) {
                    add("div", "chips") {
                        button(texts.wholeTrip, "chip") { map?.show(null) }
                        days.forEach { day -> button(texts.day(day), "chip ${RouteMap.dayClass(day)}") { map?.show(day) } }
                    }
                }
                map = RouteMap(container, trip, texts)
            }

            var number = 0
            (1..trip.days).forEach { day ->
                val meals = trip.route.filter { it.day == day }
                add("h3", "day ${RouteMap.dayClass(day)}", texts.dayAt(day, trip.stopOn(day)?.title.orEmpty()))
                if (meals.isEmpty()) add("p", "hint", texts.nothingPlannedThisDay)
                meals.forEachIndexed { index, meal ->
                    number++
                    meal.walk?.let { add("p", "walk", texts.walk(texts.distance(it), fromStay = index == 0)) }
                    meal(trip, meal, number)
                }
            }

            trip.stops.forEach { stop ->
                val dishes = trip.dishes.filter { it.stop == stop.name }
                if (dishes.isNotEmpty()) {
                    add("details", "known") {
                        add("summary", text = texts.knownFor(stop.title))
                        add("ul") {
                            dishes.forEach { dish ->
                                add("li") {
                                    add("strong", text = dish.name)
                                    add("span", text = ": ${dish.what}")
                                    if (dish.story.isNotBlank()) add("span", "story", " ${dish.story}")
                                }
                            }
                        }
                    }
                }
            }
            add("details", "known") {
                add("summary", text = texts.plannedForYou)
                add("dl", "profile") {
                    profileRows(trip.profile).forEach { (label, value) ->
                        add("dt", text = label)
                        add("dd", text = value)
                    }
                }
                // While the route waits for the traveller, the details can still change, and the agent changes the route for them.
                if (phase == Phase.REVIEW) add("div", "quiet-actions") { button(texts.seeYourDetails, "link") { openDetails() } }
            }
            if (trip.notes.isNotEmpty()) {
                add("details", "known") {
                    add("summary", text = texts.couldNotBeRead)
                    add("ul") { trip.notes.forEach { add("li", text = it) } }
                }
            }
            // The sample asks no model, so it has no tokens to show.
            add("p", "hint small", texts.placesCompared(trip.restaurants.size) + if (trip.tokens.total > 0) " · " + texts.tokensUsed(trip.tokens.total) else "")
        }

        add("section", "card") {
            if (phase == Phase.DONE) {
                add("h2", text = texts.enjoyYourTrip)
                add("div", "actions") {
                    button(texts.printOrSave, "primary") { window.print() }
                    button(texts.planAnotherTrip, "ghost") { startOver() }
                }
            } else {
                add("h2", text = texts.wantSomethingDifferent)
                val wish =
                    (add("textarea") as HTMLTextAreaElement).apply {
                        rows = 2
                        placeholder = texts.changePlaceholder
                    }
                speakRow(read = { wish.value }) { wish.value = it }
                add("div", "actions") {
                    button(texts.updateTheRoute, "primary") {
                        val text = wish.value.trim()
                        // The wish goes to the agent as the traveller's next message, and the graph loops back to it.
                        if (text.isEmpty()) wish.focus() else resume { it.copy(messages = it.messages + ChatMessage.User(text)) }
                    }
                    button(texts.happyWithIt, "ghost") { resume { it.copy(approved = true) } }
                    button(texts.startOver, "ghost") { startOver() }
                }
            }
        }
    }

    private fun HTMLElement.meal(trip: Trip, meal: Meal, number: Int) {
        val restaurant = trip.restaurant(meal.restaurantId) ?: return
        add("article", "meal") {
            add("div", "pin ${RouteMap.dayClass(meal.day)}", "$number")
            add("div", "meal-text") {
                add("div", "meal-time", texts.meal(meal.time))
                add("h4", text = restaurant.name)
                restaurant.rating?.let { stars -> add("div", "rating", "★ $stars" + restaurant.ratings?.let { " · " + texts.ratings(it) }.orEmpty()) }
                add("p", "order") {
                    add("strong", text = texts.order)
                    add("span", text = meal.dish)
                    trip.dishes.firstOrNull { it.name.equals(meal.dish, ignoreCase = true) }?.let { add("span", "what", " · ${it.what}") }
                }
                add("p", "why", meal.why)
                if (meal.caution.isNotBlank()) add("p", "caution", "⚠ ${meal.caution}")
                add("div", "badges") { restaurant.sources.forEach { add("span", "badge", texts.source(it)) } }
                val facts = listOf(restaurant.cuisine, restaurant.address, restaurant.price, restaurant.hours).filter { it.isNotBlank() }
                if (facts.isNotEmpty()) add("p", "facts", facts.joinToString(" · "))
                add("div", "links") {
                    if (restaurant.website.isNotBlank()) link(texts.website, restaurant.website)
                    link(texts.openInGoogleMaps, "https://www.google.com/maps/search/?api=1&query=${encode("${restaurant.name}, ${restaurant.stop}")}")
                    link("OpenStreetMap", "https://www.openstreetmap.org/?mlat=${restaurant.lat}&mlon=${restaurant.lon}#map=18/${restaurant.lat}/${restaurant.lon}")
                }
            }
        }
    }

    private fun encode(text: String): String = js("encodeURIComponent")(text).unsafeCast<String>()

    private companion object {
        /** How many of the agent's last actions a run shows. */
        const val FEED_LINES = 8

        /** The steps whose node asks a model and waits for its whole answer. */
        val WAITS_FOR_A_MODEL = setOf(Nodes.INTERVIEW, Nodes.BRIEF, Nodes.DISHES, Nodes.PLAN)

        /** How long a step may say nothing before the page explains why. */
        const val QUIET_MILLIS = 12_000.0

        /** How long a dish is shown while the agent chooses: long enough to read three sentences. */
        const val DISH_MILLIS = 12_000.0

        /** The budgets a profile can have, as a model writes them. */
        val BUDGETS = listOf("low", "medium", "high")
    }
}

/** What the traveller tapped and wrote for one question, before the answers are sent. */
private data class Draft(
    val picked: List<String> = emptyList(),
    val own: String = "",
) {
    /** The answer: the tapped answers, then the traveller's own words. Empty for a skipped question. */
    val text: String get() = (picked + listOf(own.trim()).filter { it.isNotEmpty() }).joinToString(", ")
}

/** The profile as the traveller is changing it: what the fields hold, and whether they erased everything first. */
private data class ProfileDraft(
    val profile: Profile,
    val erase: Boolean = false,
)
