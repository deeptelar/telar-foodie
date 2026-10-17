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
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
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

    /** The language of the page, and of the next trip. */
    private val language: Language get() = settings.language ?: Language.of(window.navigator.language)

    /** The texts of the page, in its language. */
    private val texts: Texts get() = language.texts
    private var settingsOpen = false
    private var phase = Phase.START
    private var trip: Trip? = null
    private var request = ""
    private var sample = false
    private var failure = ""
    private var steps = emptyList<Step>()
    private var feed = emptyList<String>()
    private var writing = ""

    /** What the traveller tapped and wrote for each open question, by its text, so that drawing the page again keeps it. */
    private var drafts = emptyMap<String, Draft>()
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
        return planner(model, Sources.online(client, settings.googleKey))
    }

    private fun start(request: String, sample: Boolean) {
        this.sample = sample
        this.request = request
        trip = null
        run { planner().stream(Trip(request, language = language), config) }
    }

    /** Continues the saved run, with [update] applied to its state first. */
    private fun resume(update: (Trip) -> Trip = { it }) {
        run { planner().streamResume(config) { update(it) } }
    }

    private fun run(events: () -> Flow<GraphEvent<Trip>>) {
        phase = Phase.RUNNING
        drafts = emptyMap()
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
                    failure = e.message ?: texts.somethingWentWrong
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
                step(event.node)?.activity = texts.retrying(event.cause.message.orEmpty())
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
                result.state.questions.isNotEmpty() -> Phase.QUESTION
                result.state.interview.stage == InterviewStage.CHECKING -> Phase.CHECKING
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

    /** A tool call of the agent, in words for the traveller. */
    private fun describe(call: ToolCall, trip: Trip): String {
        fun input(key: String) = (call.input[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        // The model names a meal in English; the traveller reads it in the page's language.
        fun meal() = MealTime.of(input("meal"))?.let { texts.meal(it).lowercase() } ?: input("meal")
        return when (call.name) {
            "set_meal" -> texts.didSetMeal(input("day"), meal(), trip.restaurant(input("restaurant"))?.name ?: input("restaurant"))
            "remove_meal" -> texts.didRemoveMeal(input("day"), meal())
            "search_map" -> texts.didSearchMap(input("stop"), input("name").ifBlank { input("cuisine") })
            "read_guide" -> texts.didReadGuide(input("page"))
            "search_reviews" -> texts.didSearchReviews(input("query"))
            "candidates" -> texts.didLookThrough(input("stop"), input("words"))
            else -> call.name
        }
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
        when (phase) {
            Phase.START -> root.startForm()
            Phase.RUNNING -> runPanel = root.add("section", "card run").also { fillRun(it) }
            Phase.QUESTION -> if (trip != null) root.questionCard(trip)
            Phase.CHECKING -> if (trip != null) root.profileCard(trip)
            Phase.REVIEW, Phase.DONE -> if (trip != null) root.route(trip)
            Phase.STOPPED ->
                root.add("section", "card") {
                    add("h2", text = texts.stoppedHalfway)
                    add("p", text = texts.closedWhileWorking(request.trim()))
                    add("div", "actions") {
                        button(texts.continueRun, "primary") { resume() }
                        button(texts.startOver, "ghost") { startOver() }
                    }
                }
            Phase.FAILED ->
                root.add("section", "card failed") {
                    add("h2", text = texts.gotStuck)
                    add("p", "failure", failure)
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

        root.add("footer", "credits") {
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
        if (sample) panel.add("p", "hint", texts.sampleRunning)
        panel.add("div", "actions") { button(texts.cancel, "ghost") { startOver() } }
    }

    private fun HTMLElement.questionCard(trip: Trip) {
        val interviewing = trip.interview.stage != InterviewStage.DONE
        add("section", "card") {
            add("h2", text = if (trip.questions.size == 1) texts.oneQuestion else texts.aFewQuestions)
            val left = Topic.entries.count { it !in trip.interview.covered }
            // What the request already said, so the traveller sees that it was heard, and why these are the questions.
            val asked = trip.answers.mapNotNull { it.topic } + trip.questions.map { it.topic }
            val told = Topic.entries.filter { it in trip.interview.covered && it !in asked }
            if (interviewing && trip.interview.rounds == 1 && told.isNotEmpty()) {
                add("p", "told", texts.youAlreadyToldMe(told.map { texts.topic(it) }))
            }
            add(
                "p",
                "hint",
                when {
                    !interviewing -> texts.oneMoreThing
                    left == 0 -> texts.lastQuestions
                    else -> texts.moreQuestions(left)
                },
            )
            trip.questions.forEach { question(it) }
            fun answers() = trip.questions.map { Answer(it.topic, it.text, (drafts[it.text] ?: Draft()).text) }
            add("div", "actions") {
                // The answers join the trip, and the node that asked reads them.
                button(texts.continueAnswers, "primary") { resume { it.answered(answers()) } }
                // The questions of the brief come after the interview, and it cannot go on without them.
                if (interviewing) button(texts.planNow, "ghost") { resume { it.planNow(answers()) } }
                button(texts.startOver, "ghost") { startOver() }
            }
            if (sample) add("p", "hint small", texts.sampleAsks)
        }
    }

    /** One question: its answers to tap, and a line for the traveller's own words. */
    private fun HTMLElement.question(question: Question) {
        add("div", "ask") {
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

    private fun HTMLElement.profileCard(trip: Trip) {
        add("section", "card") {
            add("h2", text = texts.hereIsWhatIHave)
            add("p", "hint", texts.profileHint)
            add("dl", "profile") {
                profileRows(trip.profile).forEach { (label, value) ->
                    add("dt", text = label)
                    add("dd", text = value)
                }
            }
            val correction =
                (add("textarea") as HTMLTextAreaElement).apply {
                    rows = 2
                    placeholder = texts.correctionPlaceholder
                }
            speakRow(read = { correction.value }) { correction.value = it }
            add("div", "actions") {
                button(texts.planMyRoute, "primary") { resume { it.confirmed() } }
                button(texts.correctIt, "ghost") {
                    val text = correction.value.trim()
                    if (text.isEmpty()) correction.focus() else resume { it.corrected(text) }
                }
                button(texts.startOver, "ghost") { startOver() }
            }
            if (sample) add("p", "hint small", texts.sampleDidNotRead)
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
                        add("ul") { dishes.forEach { dish -> add("li") { add("strong", text = dish.name); add("span", text = ": ${dish.what}") } } }
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
