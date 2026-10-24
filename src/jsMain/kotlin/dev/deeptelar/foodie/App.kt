package dev.deeptelar.foodie

import dev.deeptelar.foodie.sources.Recording
import dev.deeptelar.foodie.sources.Sources
import dev.deeptelar.foodie.sources.replay
import dev.deeptelar.telar.Checkpoint
import dev.deeptelar.telar.CompiledGraph
import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphEvent
import dev.deeptelar.telar.GraphResult
import dev.deeptelar.telar.agent.ChatEvent
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.ToolCall
import dev.deeptelar.telar.agent.pendingToolCalls
import dev.deeptelar.telar.checkpoint.browser.LocalStorageCheckpointer
import dev.deeptelar.telar.otel.OtlpObserver
import dev.deeptelar.telar.serialization.KotlinxStateSerializer
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlin.js.Date
import kotlin.js.Promise
import kotlin.js.dateLocaleOptions
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.url.URL

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
    private val config: GraphConfig<Trip>
        get() =
            GraphConfig(
                threadId = "trip",
                checkpointer = checkpointer,
                // The run stops when the route is ready and waits for the traveller.
                interruptBefore = setOf(Nodes.REVIEW),
                // Every round of the agent is two steps, and a trip of several days takes many rounds.
                maxIterations = 120,
                observers = listOfNotNull(tracer()),
            )

    /** The observer that sends the runs as traces, with the settings it was made for, and the coroutines that send them. */
    private var tracing: Triple<Pair<String, String>, OtlpObserver, Job>? = null

    /**
     * The observer that sends the runs to the collector of the settings, or `null` when they name
     * none. A change of the settings makes a new one. The requests of the models and sources carry
     * no `traceparent`: their servers would refuse a header they do not allow from a page.
     */
    private fun tracer(): OtlpObserver? {
        val url = settings.traceUrl.trim()
        val made = url to settings.traceHeader
        tracing?.let { (madeFor, observer, job) -> if (madeFor == made) return observer else job.cancel() }
        tracing = null
        if (url.isEmpty()) return null
        val job = Job(scope.coroutineContext[Job])
        val observer =
            OtlpObserver(
                client,
                CoroutineScope(scope.coroutineContext + job),
                endpoint = url,
                serviceName = "foodie-route",
                resource = mapOf("deployment.environment" to window.location.hostname),
                headers = settings.traceHeaders,
                onExportFailed = { console.warn("Foodie Route could not send its traces: ${it.message}") },
            )
        tracing = Triple(made, observer, job)
        return observer
    }

    private var settings = Settings.load()

    /** The language of the page, and of the next trip. */
    private val language: Language get() = settings.language ?: Language.of(window.navigator.language)

    /** The texts of the page, in its language. */
    private val texts: Texts get() = language.texts
    private var settingsOpen = false

    /** The code of the try-out link the page was opened with, until its secret dish opens it, and the dish typed so far. */
    private var tryCode: String? = null
    private var tryDish = ""
    private var tryOpening = false
    private var tryFailure = ""

    /** OpenRouter's models that can plan, once loaded: `null` before, and while they load. */
    private var openRouterModels: List<OpenRouterModel>? = null
    private var loadingOpenRouter = false

    /** Whether the page just forgot a friend's key, for a word on the start. */
    private var tryEnded = false
    private var tab = Tab.PLAN

    /** Whether the details were just saved, for a word under them. */
    private var detailsSaved = false
    private var phase = Phase.START
    private var trip: Trip? = null
    private var request = ""
    private var sample = false
    private var failure = ""

    /** Why the file the traveller opened is no plan, for a word under the start. */
    private var fileFailure = ""
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

    /** The meals the traveller opened to change, by their day and time, with what they said so far. */
    private var swaps = emptyMap<Pair<Int, MealTime>, MealSwap>()

    /**
     * Whether the run changes a route the traveller is looking at: the route then stays on the
     * screen while it runs, with the meals in [changing] marked, and [beforeChange] is the saved run
     * that stopping the change goes back to.
     */
    private var revising = false
    private var changing = emptySet<Pair<Int, MealTime>>()
    private var beforeChange: Checkpoint<Trip>? = null

    /** The meals whose cards are open, by their day and time. */
    private var opened = emptySet<Pair<Int, MealTime>>()

    /** Whether the traveller opened the agent's words above the route. */
    private var summaryOpen = false
    private var job: Job? = null
    private var map: RouteMap? = null

    /** The day the route shows, `null` for the whole trip, and the list of its meals. */
    private var day: Int? = ShownDay.load()
    private var dayList: HTMLElement? = null
    private var runPanel: HTMLElement? = null

    /** Shows the page, with the saved plan when there is one. */
    fun open() {
        window.addEventListener("beforeprint", { printing(all = true) })
        window.addEventListener("afterprint", { printing(all = false) })
        // A page that is hidden may be closed: what was traced so far is sent now.
        document.addEventListener("visibilitychange", { if (document.asDynamic().visibilityState == "hidden") tracing?.let { scope.launch { it.second.flush() } } })
        readTryOut()
        endTryOut()
        // A friend may tap another link while the page is open.
        window.addEventListener("hashchange", {
            readTryOut()
            render()
        })
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

    /** Takes the code of a try-out link out of the address, so that the history and a screenshot do not keep it. */
    private fun readTryOut() {
        val code = window.location.hash.removePrefix("#").takeIf { it.startsWith(TryOut.PREFIX) }?.removePrefix(TryOut.PREFIX) ?: return
        window.history.replaceState(null, "", window.location.pathname + window.location.search)
        tryCode = code
        tryFailure = ""
    }

    /** Forgets a friend's key whose try-out has ended. */
    private fun endTryOut() {
        val ended = settings.ended(Date.now())
        if (ended == settings) return
        change(ended)
        tryEnded = true
    }

    /** Opens the try-out link with the dish the traveller typed: the friend's model plans from then on. */
    private fun openTryOut(code: String) {
        tryOpening = true
        tryFailure = ""
        render()
        scope.launch {
            val tryOut = TryOut.unlock(code, tryDish)
            tryOpening = false
            if (tryOut == null) {
                tryFailure = texts.wrongDish
            } else {
                change(settings.trying(tryOut))
                tryCode = null
                tryDish = ""
                tryEnded = false
                count("try-out-opened")
            }
            render()
        }
    }

    /** A moment as the page's language writes it, such as "26 Oct, 18:40". */
    private fun dateTime(millis: Double): String =
        Date(millis).toLocaleString(
            language.code,
            dateLocaleOptions {
                day = "numeric"
                month = "short"
                hour = "2-digit"
                minute = "2-digit"
            },
        )

    // The run

    /** The graph for the current settings. Without a key for a model, the sample script plans. */
    private fun planner(): CompiledGraph<Trip> {
        val model = if (sample) SampleModel() else settings.model(client) ?: SampleModel()
        val planModel = if (sample) model else settings.planModel(client) ?: model
        // The script plans from what the sources answered once: a visitor's try asks none of the free services.
        val sources = if (model is SampleModel) replay(::sampleRecording, Sources.online(client)) else Sources.online(client, settings.googleKey, settings.placesUrl)
        return planner(model, sources, planModel = planModel)
    }

    /** What the sources answered for the sample, kept in a file next to the page. */
    private suspend fun sampleRecording(): Recording {
        val response = client.get(URL("sample-sources.json", document.baseURI).href)
        check(response.status.isSuccess()) { "The sample's recording answered with ${response.status.value}" }
        return Recording.fromJson(response.bodyAsText())
    }

    private fun start(request: String, sample: Boolean, answers: List<Answer> = emptyList()) {
        this.sample = sample
        this.request = request
        val trip = newTrip(request, language, answers)
        this.trip = trip
        firstDay()
        tab = Tab.PLAN
        count(if (scripted) "sample-start" else "trip-start-${settings.provider.name.lowercase()}")
        run { planner().stream(trip, config) }
    }

    /** Whether the sample script plans, and not a model: the sample was asked for, or no model has a key. */
    private val scripted: Boolean get() = sample || !settings.hasModel

    /** Counts [event] of a visit, apart for the sample, which shows the page and not a trip. */
    private fun counted(event: String) = count(if (scripted) "sample-$event" else event)

    /**
     * Continues the saved run, with [update] applied to its state first. [trip] is then the state
     * the run continues with. `resume` saves it before the first step runs, so a step that fails
     * or a pause keeps it, such as the traveller's answers, and "Try again" goes on with it.
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

    /**
     * Continues the run from the route with [update], as [resume] does, while the route stays on
     * the screen. What was saved before is kept, for [stopChange].
     */
    private fun revise(event: String, update: (Trip) -> Trip) {
        counted(event)
        scope.launch {
            beforeChange =
                try {
                    checkpointer.load(config.threadId)
                } catch (_: Throwable) {
                    null
                }
            // Named: in a coroutine, a bare `run` is the scope function of the standard library, which runs nothing.
            this@App.run(revision = true) { planner().streamResume(config) { update(it).also { started -> trip = started } } }
        }
    }

    /** Stops a change of the route and puts the route back as it was; without what was saved before, the run is paused. */
    private fun stopChange() {
        val running = job
        scope.launch {
            running?.cancelAndJoin()
            val before = beforeChange
            changing = emptySet()
            if (before == null) {
                phase = Phase.PAUSED
                render()
                return@launch
            }
            try {
                checkpointer.save(config.threadId, before)
            } catch (_: Throwable) {
                // The browser blocks its storage: the route is back on the page only.
            }
            show(GraphResult.Interrupted(before.state, before.nextNodes))
        }
    }

    private fun run(revision: Boolean = false, events: () -> Flow<GraphEvent<Trip>>) {
        phase = Phase.RUNNING
        revising = revision
        summaryOpen = false
        drafts = emptyMap()
        swaps = emptyMap()
        editing = null
        failure = ""
        writing = ""
        feed = emptyList()
        dishShown = 0
        dishDrawn = -1
        named = emptyList()
        steps = steps(trip)
        render()
        // The place being changed stays in view while the planner works on it.
        changing.minWithOrNull(compareBy({ it.first }, { it.second }))?.let { (day, time) ->
            (root.querySelector("article.meal[data-meal='${mealKey(day, time)}']") as? HTMLElement)?.asDynamic()?.scrollIntoView(js("({ block: 'center' })"))
        }
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
            is GraphEvent.Interrupted -> {
                if (Nodes.REVIEW in event.nextNodes) counted(if (revising) "change-ready" else "route-ready")
                show(GraphResult.Interrupted(event.state, event.nextNodes))
            }
            is GraphEvent.Completed -> show(GraphResult.Completed(event.state))
            // No node of the planner runs for each item of a list, and no subgraph is part of it.
            is GraphEvent.SubgraphEvent, is GraphEvent.ItemCompleted -> Unit
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
        val changed = changing.minWithOrNull(compareBy({ it.first }, { it.second }))
        val revised = revising
        // The changed places open, with the places offered for them.
        if (result.state.offers.isNotEmpty()) opened = opened + changing.filter { result.state.offerFor(it.first, it.second) != null }
        changing = emptySet()
        revising = false
        render()
        when {
            // A changed place is shown where it was, with the places offered for it.
            changed != null -> (root.querySelector("article.meal[data-meal='${mealKey(changed.first, changed.second)}']") as? HTMLElement)?.let { card ->
                markCard(card)
                map?.mark(changed.first, changed.second)
                window.requestAnimationFrame { card.asDynamic().scrollIntoView(js("({ block: 'start', behavior: 'smooth' })")) }
            }
            // A route changed by the traveller's words is shown from the day's start.
            revised -> (root.querySelector(".map") as? HTMLElement)?.let { shown -> window.requestAnimationFrame { shown.asDynamic().scrollIntoView(js("({ block: 'start', behavior: 'smooth' })")) } }
            // The planner's new words are under the conversation: the traveller is taken to them.
            tab == Tab.PLAN -> (root.querySelector(".turn") as? HTMLElement)?.let { turn -> window.requestAnimationFrame { turn.asDynamic().scrollIntoView(js("({ block: 'start', behavior: 'smooth' })")) } }
        }
    }

    /** A new trip, or a trip planned anew, is shown from its first day. */
    private fun firstDay() {
        day = 1
        ShownDay.save(1)
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

    /**
     * Plans [from] again from the start, for another route: the page shows the profile to check, where
     * the traveller can change one or two things, and the planner then finds and chooses anew.
     */
    private fun planAgain(from: Trip) {
        job?.cancel()
        scope.launch {
            try {
                checkpointer.delete(config.threadId)
            } catch (_: Throwable) {
                // The browser blocks its storage: then nothing was saved either.
            }
            val again = from.plannedAgain()
            trip = again
            firstDay()
            tab = Tab.PLAN
            // Named: in a coroutine, a bare `run` is the scope function of the standard library, which runs nothing.
            this@App.run { planner().stream(again, config) }
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
            if (saved == null) start(request, sample, trip?.answers.orEmpty()) else resume()
        }
    }

    // A plan in a file

    /** Gives the traveller the saved run as a file: the checkpoint, so that opening the file goes on from where it is. */
    private fun saveFile() {
        scope.launch {
            val saved =
                try {
                    checkpointer.load(config.threadId)
                } catch (_: Throwable) {
                    null
                }
            // The browser blocks its storage: the trip on the page is saved, as a run that is over.
            val checkpoint = saved ?: trip?.let { Checkpoint(it, emptyList()) } ?: return@launch
            val now = Date().toISOString()
            counted("plan-saved")
            download(fileName(checkpoint.state, now.take(10)), checkpoint.toFile(if (sample) "sample" else settings.modelName, now))
        }
    }

    /** Opens a plan the traveller saved as a file: its checkpoint becomes the saved run, and the page shows it. */
    private fun openFile() {
        pickFile(".json,application/json") { file ->
            scope.launch {
                try {
                    val checkpoint = planFromFile(file.asDynamic().text().unsafeCast<Promise<String>>().await())
                    job?.cancel()
                    // A run saved before would keep its earlier steps in the history of the thread.
                    checkpointer.delete(config.threadId)
                    checkpointer.save(config.threadId, checkpoint)
                    sample = false
                    fileFailure = ""
                    firstDay()
                    tab = Tab.PLAN
                    show(if (checkpoint.isComplete) GraphResult.Completed(checkpoint.state) else GraphResult.Interrupted(checkpoint.state, checkpoint.nextNodes))
                } catch (e: NotAPlanException) {
                    fileFailure = if (e.newer) texts.planFromANewerPage else texts.notAPlanFile
                    render()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // The plan was read, but the browser blocks its storage.
                    fileFailure = e.message ?: texts.somethingWentWrong
                    render()
                }
            }
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
                Step(Nodes.SPECIALISTS, texts.stepSpecialists),
                Step(Nodes.PLAN, texts.stepPlan),
                Step(Nodes.ORDER, texts.stepOrder),
            )
        // What a saved trip already has was done by a run before this one.
        if (trip != null) {
            val done =
                when {
                    trip.messages.isNotEmpty() -> setOf(Nodes.INTERVIEW, Nodes.BRIEF, Nodes.GUIDE, Nodes.MAP, Nodes.REVIEWS, Nodes.DISHES, Nodes.SPECIALISTS)
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
            Nodes.SPECIALISTS -> texts.places(trip.restaurants.count { trip.speciality(it) != null })
            Nodes.PLAN -> if (trip.route.isEmpty()) "" else texts.mealsOnTheRoute(trip.route.size)
            else -> ""
        }
    }

    /** A tool call of the agent, in words for the traveller: a line, or one for each meal of a route. */
    private fun describe(call: ToolCall, trip: Trip): List<String> {
        if (call.name == "set_route") return (call.input["meals"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { meal -> describe(ToolCall(call.id, "set_meal", meal), trip) } }.flatten()
        fun input(key: String) = (call.input[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        // The model names a meal in English; the traveller reads it in the page's language.
        fun meal() = MealTime.of(input("meal"))?.let { texts.mealInSentence(it) } ?: input("meal")
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
        dayList = null
        runPanel = null
        root.clear()

        root.add("header", "top") {
            add("h1", text = "Foodie Route")
            add("div", "top-actions") {
                (add("select", "language") as HTMLSelectElement).apply {
                    setAttribute("aria-label", texts.languageLabel)
                    // The language by its code, so that the title and both buttons fit in one row on a phone.
                    Language.entries.forEach { option ->
                        add("option", text = option.code.uppercase()) {
                            setAttribute("value", option.name)
                            setAttribute("title", option.own)
                            if (option == language) setAttribute("selected", "selected")
                        }
                    }
                    onchange = {
                        change(settings.copy(language = Language.valueOf(value)))
                        render()
                    }
                }
                button(if (settingsOpen) "✕" else "⚙", "ghost settings-button" + if (settingsOpen) " current" else "") {
                    settingsOpen = !settingsOpen
                    render()
                }.apply {
                    setAttribute("aria-label", if (settingsOpen) texts.close else texts.settings)
                    setAttribute("title", texts.settings)
                }
            }
        }
        // What the page is, before a trip: then the trip takes the room.
        if (phase == Phase.START) root.add("p", "tagline", texts.tagline)
        tryCode?.let { root.tryOutCard(it) }
        if (settingsOpen) root.settingsPanel()

        val trip = trip
        // Before a trip there is nothing to keep apart from the conversation.
        // On the route, the details are in its menu, and the route starts under the header; the tabs lead back from the details.
        if (phase != Phase.START && (tab == Tab.DETAILS || phase !in setOf(Phase.REVIEW, Phase.DONE))) root.tabs()
        if (phase != Phase.START && tab == Tab.DETAILS) {
            root.detailsTab(trip)
        } else {
            plan(trip)
        }
        root.credits()
        drawBottomBar()
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
        if (trip != null && phase !in setOf(Phase.START, Phase.REVIEW, Phase.DONE) && !revising) root.thread(trip)
        when (phase) {
            Phase.START -> root.startForm()
            Phase.RUNNING ->
                if (revising && trip != null && trip.route.isNotEmpty()) {
                    root.route(trip)
                    runPanel = root.add("div", "working").also { fillWorking(it) }
                } else {
                    runPanel = root.add("section", "card run").also { fillRun(it) }
                }
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
                        button(texts.saveToFile, "ghost") { saveFile() }
                        button(texts.startOver, "ghost") { startOver() }
                    }
                }
        }
    }

    /** What a try-out link opens with: a field for the secret dish. */
    private fun HTMLElement.tryOutCard(code: String) {
        add("section", "card try-out") {
            add("h2", text = texts.friendShared)
            add("p", "hint", texts.enterSecretDish)
            fun open(input: HTMLInputElement) {
                if (TryOut.secret(tryDish).isEmpty()) input.focus() else if (!tryOpening) openTryOut(code)
            }
            val input = field(texts.secretDish, tryDish) { tryDish = it }
            input.onkeydown = { if (it.key == "Enter") open(input) }
            add("div", "actions") { button(if (tryOpening) texts.opening else texts.openTryOut, "primary") { open(input) }.disabled = tryOpening }
            if (tryFailure.isNotEmpty()) add("p", "failure", tryFailure)
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
            // The examples are for a traveller who has not started writing: then they make room for the questions below.
            lateinit var examples: HTMLElement
            fun written(words: String) {
                request = words
                examples.hidden = words.isNotBlank()
            }
            val text =
                (add("textarea") as HTMLTextAreaElement).apply {
                    rows = 4
                    placeholder = texts.requestPlaceholder
                    value = request
                    oninput = { written(value) }
                }
            speakRow(read = { request }) {
                text.value = it
                written(it)
            }
            examples =
                add("div", "chips") {
                    texts.examples.forEach { example ->
                        button(example, "chip") {
                            text.value = example
                            written(example)
                        }
                    }
                }
            examples.hidden = request.isNotBlank()
            upFront()
            add("div", "actions") {
                button(texts.planMyRoute, "primary") {
                    endTryOut()
                    when {
                        request.isBlank() -> text.focus()
                        // Without a key there is no model to ask: the settings say where to put one.
                        !settings.hasModel -> {
                            settingsOpen = true
                            render()
                        }
                        else -> start(request.trim(), sample = false, upFrontAnswers())
                    }
                }
                button(texts.trySample, "ghost") { start(texts.sampleRequest, sample = true) }
                button(texts.openAFile, "ghost") { openFile() }
            }
            if (fileFailure.isNotEmpty()) add("p", "failure", fileFailure)
            if (speechSupported) {
                add("p", "hint small", texts.speechPrivacy)
            }
            settings.borrowed?.let { add("p", "hint", texts.friendsModelUntil(dateTime(it.until))) }
            if (tryEnded) {
                add("p", "hint", texts.tryOutEnded)
            } else if (!settings.hasModel) {
                add("p", "hint", texts.needAKey)
            }
        }
        add("section", "how") {
            add("h2", text = texts.howItWorks)
            add("ol") { texts.howItWorksSteps.forEach { add("li", text = it) } }
        }
    }

    /**
     * The questions every trip needs, with answers to tap next to the request, so the interview
     * does not ask them again. All of them can be skipped.
     */
    private fun HTMLElement.upFront() {
        add("details", "up-front") {
            setAttribute("open", "")
            add("summary", text = texts.upFrontTitle)
            add("p", "hint small", texts.upFrontHint)
            UP_FRONT.map { texts.question(it) }.forEach { question ->
                add("div", "up-front-question") {
                    add("p", "field-label", question.text)
                    add("div", "chips") { question.options.forEach { chip(question, it) } }
                    if (question.topic == Topic.ALLERGIES) add("p", "hint small", texts.allergyNote)
                    textInput(drafts[question.text]?.own.orEmpty(), texts.somethingElse, question.text) { text ->
                        drafts = drafts + (question.text to (drafts[question.text] ?: Draft()).copy(own = text))
                    }
                }
            }
        }
    }

    /** What the traveller answered on the start page: a skipped question has no answer, and the interview asks it. */
    private fun upFrontAnswers(): List<Answer> = UP_FRONT.map { texts.question(it) }.map { Answer(it.topic, it.text, drafts[it.text]?.text.orEmpty()) }

    private fun HTMLElement.settingsPanel() {
        add("section", "card settings") {
            add("h2", text = texts.settings)
            add("p", "hint", texts.keysStayHere)
            add("p", "hint small", texts.visitsCounted)
            settings.borrowed?.let { add("p", "hint", texts.friendsModelUntil(dateTime(it.until))) }
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
                    field(texts.anthropicKey, settings.claudeKey, type = "password", placeholder = "sk-ant-...") { change(settings.copy(claudeKey = it).own(Provider.CLAUDE)) }
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
                    if (OpenRouter.isAt(settings.openAiUrl)) openRouterPicker()
                    field(texts.model, settings.openAiModel, placeholder = "gpt-5") { change(settings.copy(openAiModel = it)) }
                    field(texts.apiKey, settings.openAiKey, type = "password", placeholder = texts.emptyForLocalServer) { change(settings.copy(openAiKey = it).own(Provider.OPENAI)) }
                    // Ollama allows only pages on the same computer; this one comes from another site.
                    add("p", "hint small") {
                        add("span", text = texts.localServerHint + " ")
                        add("code", text = "OLLAMA_ORIGINS=${window.location.origin} ollama serve")
                    }
                }
            }
            // A friend's key is theirs to share, not the traveller's.
            if (settings.borrowed == null && settings.tryOut(0.0) != null) shareTryOut()
            field(texts.googleKey, settings.googleKey, type = "password", placeholder = "AIza...") { change(settings.copy(googleKey = it).ownGoogle()) }
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
            // For whoever runs the page: the traces show how long each step took, and which failed.
            add("details", "how-to") {
                add("summary", text = texts.tracing)
                add("p", "hint small", texts.tracingHint)
                field(texts.tracingAddress, settings.traceUrl, placeholder = "http://localhost:4318/v1/traces") { change(settings.copy(traceUrl = it)) }
                field(texts.tracingHeader, settings.traceHeader, type = "password", placeholder = "x-honeycomb-team: ...") { change(settings.copy(traceHeader = it)) }
            }
            add("div", "actions") {
                button(texts.done, "primary") {
                    settingsOpen = false
                    render()
                }
            }
        }
    }

    /** A link that plans with the model of these settings, locked with a secret dish, for friends to try the page. */
    private fun HTMLElement.shareTryOut() {
        add("details", "how-to") {
            add("summary", text = texts.shareTryOut)
            add("p", "hint small", texts.shareTryOutHint)
            var dish = ""
            var google = false
            val dishInput = field(texts.secretDish, "", placeholder = texts.secretDishPlaceholder) { dish = it }
            lateinit var days: HTMLSelectElement
            add("label", "field") {
                add("span", "field-label", texts.tryOutLasts)
                days =
                    (add("select") as HTMLSelectElement).apply {
                        TRY_OUT_DAYS.forEach { count ->
                            add("option", text = texts.days(count)) {
                                setAttribute("value", count.toString())
                                if (count == TRY_OUT_DAYS[1]) setAttribute("selected", "selected")
                            }
                        }
                    }
            }
            if (settings.googleKey.isNotBlank()) {
                check(texts.shareGoogleKey, google) { google = it }
                add("p", "hint small", texts.shareGoogleKeyHint)
            }
            lateinit var made: HTMLElement
            lateinit var make: HTMLButtonElement
            add("div", "actions") {
                make =
                    button(texts.makeLink, "primary") {
                        val tryOut = settings.tryOut(Date.now() + days.value.toInt() * DAY_MILLIS, google)
                        if (tryOut == null || TryOut.secret(dish).isEmpty()) {
                            dishInput.focus()
                        } else {
                            make.disabled = true
                            scope.launch {
                                val link = window.location.origin + window.location.pathname + "#" + TryOut.PREFIX + tryOut.lock(dish)
                                make.disabled = false
                                made.clear()
                                made.linkMade(link)
                                count("try-out-made")
                            }
                        }
                    }
            }
            made = add("div", "try-out-link")
        }
    }

    /** A list of OpenRouter's models that can plan, with their prices: picking one puts it in the field of the model. */
    private fun HTMLElement.openRouterPicker() {
        val models = openRouterModels
        if (models == null) {
            add("p", "hint small", texts.loadingModels)
            if (!loadingOpenRouter) {
                loadingOpenRouter = true
                scope.launch {
                    openRouterModels =
                        try {
                            val response = client.get(OpenRouter.MODELS)
                            if (response.status.isSuccess()) openRouterModels(response.bodyAsText()) else emptyList()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Throwable) {
                            // No list: the model is typed, as for any other server.
                            emptyList()
                        }
                    loadingOpenRouter = false
                    if (settingsOpen) render()
                }
            }
            return
        }
        if (models.isEmpty()) return
        add("label", "field") {
            add("span", "field-label", texts.openRouterModels)
            (add("select") as HTMLSelectElement).apply {
                add("option", text = texts.chooseAModel) { setAttribute("value", "") }
                fun option(parent: Element, model: OpenRouterModel) =
                    parent.add("option", text = model.name + (model.price?.takeIf { it.input > 0 || it.output > 0 }?.let { " · ${dollars(it.input)} / ${dollars(it.output)}" } ?: "")) {
                        setAttribute("value", model.id)
                        if (model.id == settings.openAiModel.trim()) setAttribute("selected", "selected")
                    }
                val recommended = OpenRouter.RECOMMENDED.mapNotNull { id -> models.firstOrNull { it.id == id } }
                if (recommended.isNotEmpty()) {
                    add("optgroup") {
                        setAttribute("label", texts.recommended)
                        recommended.forEach { option(this, it) }
                    }
                }
                add("optgroup") {
                    setAttribute("label", "OpenRouter")
                    models.forEach { option(this, it) }
                }
                onchange = {
                    if (value.isNotEmpty()) {
                        change(settings.copy(openAiModel = value))
                        render()
                    }
                }
            }
        }
    }

    /** A price in dollars, with as many decimals as it needs to not read as nothing. */
    private fun dollars(amount: Double): String = "$" + if (amount >= 1 || amount == 0.0) amount.asDynamic().toFixed(2) else amount.asDynamic().toPrecision(2)

    /** The try-out [link], to copy or to hand to the share sheet of a phone. */
    private fun HTMLElement.linkMade(link: String) {
        (add("input") as HTMLInputElement).apply {
            type = "text"
            readOnly = true
            value = link
            setAttribute("aria-label", texts.shareTryOut)
            onfocus = { select() }
        }
        add("div", "actions") {
            lateinit var copy: HTMLButtonElement
            copy =
                button(texts.copyLink, "ghost") {
                    try {
                        (window.navigator.asDynamic().clipboard.writeText(link) as Promise<Any?>).then { copy.textContent = texts.linkCopied }.catch { }
                    } catch (_: Throwable) {
                        // A browser without the clipboard: the link is in the field above, to copy by hand.
                    }
                }
            val navigator = window.navigator.asDynamic()
            if (jsTypeOf(navigator.share) == "function") {
                button(texts.shareLink, "ghost") {
                    val data: dynamic = js("({})")
                    data.title = "Foodie Route"
                    data.text = texts.linkMessage
                    data.url = link
                    // A traveller who closes the share sheet rejects the promise: nothing more to do.
                    (navigator.share(data) as Promise<Any?>).catch { }
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
            if (revising) fillWorking(panel) else fillRun(panel)
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

    /** The bar under a route that the planner changes: what it does now, and the button that stops it. */
    private fun fillWorking(panel: HTMLElement) {
        panel.add("span", "spinner")
        panel.add("div", "working-text") {
            add("div", "working-title", texts.changingTheRoute)
            val running = steps.firstOrNull { it.status == Status.RUNNING }
            val doing = feed.lastOrNull() ?: running?.activity?.ifEmpty { null } ?: running?.title
            val took = running?.let { texts.seconds(((Date.now() - it.started) / 1000).toInt()) }
            add("div", "working-detail", listOfNotNull(doing, took).joinToString(" · "))
        }
        panel.button(texts.stopChanging, "ghost") { stopChange() }
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
            button(texts.saveToFile, "link") { saveFile() }
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
        editRow(texts.nearStay) {
            add("div", "chips") {
                MealTime.entries.forEach { time ->
                    button(texts.meal(time), "chip answer" + if (time in profile.nearStay) " picked" else "") {
                        changed { it.copy(nearStay = if (time in it.nearStay) it.nearStay - time else (it.nearStay + time).sorted()) }
                    }
                }
            }
            if (profile.nearStay.isEmpty()) add("p", "hint small", texts.nearStayHint)
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
            profile.meals.takeIf { it.isNotEmpty() }?.let { meals -> texts.meals to meals.sorted().joinToString { texts.mealInSentence(it) } },
            profile.nearStay.takeIf { it.isNotEmpty() }?.let { meals -> texts.nearStay to meals.sorted().joinToString { texts.mealInSentence(it) } },
            texts.budget to texts.budget(profile.budget) + if (profile.splurge) texts.withASpecialMeal else "",
            profile.taste.takeIf { it.isNotBlank() }?.let { texts.taste to it },
            profile.drinks.takeIf { it.isNotBlank() }?.let { texts.drinks to it },
            profile.wishes.takeIf { it.isNotBlank() }?.let { texts.wishes to it },
        )

    private fun HTMLElement.route(trip: Trip) {
        add("section", "route") {
            if (trip.summary.isNotBlank()) summary(trip.summary)

            if (trip.route.isEmpty()) {
                add("p", "failure", texts.noMealPlanned)
            } else {
                // One day at a time: the strip stays at the top of the screen, and a day fits on a phone.
                if (trip.days > 1) dayStrip(trip)
                map = RouteMap(add("div", "map"), trip, texts, ::pinTapped).also { it.show(shownDay(trip)) }
            }
            dayList = add("div", "day-list").also { it.fillDay(trip) }

            saveOneByOne(trip)
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
            // The sample asks no model, so it has no tokens to show, and searches no Google.
            if (!sample && (trip.tokens.total > 0 || trip.googleSearches > 0)) costOf(trip)
            add("p", "hint small", texts.placesCompared(trip.restaurants.size) + if (trip.tokens.total > 0) " · " + texts.tokensUsed(trip.tokens.total) else "")
        }
    }

    /** What [trip] cost at the providers' list prices, for the model of the settings: what an app that paid for it would pay. */
    private fun HTMLElement.costOf(trip: Trip) {
        val cost = trip.cost(settings.modelName)
        val model = Settings.CLAUDE_MODELS.firstOrNull { it.first == settings.modelName }?.second ?: settings.modelName
        add("details", "known") {
            add("summary", text = cost.total?.let { texts.costAbout(texts.money(it)) } ?: texts.whatItCost)
            add("ul") {
                if (trip.tokens.total > 0) add("li", text = texts.modelCost(model, trip.tokens.inputTokens, trip.tokens.outputTokens, cost.model?.let(texts::money)))
                if (trip.googleSearches > 0) add("li", text = texts.googleCost(trip.googleSearches, texts.money(cost.google)))
            }
            add("p", "hint small", texts.atListPrices)
        }
    }

    /**
     * What the traveller does with the route now and then, in a sheet from the bar at the bottom:
     * asking for a change in words or saying it is good, planning again, and taking the route
     * along as a file, to Google My Maps or on paper.
     */
    private fun openMenu(trip: Trip) {
        closeSheet()
        val backdrop = root.add("div", "sheet-backdrop")
        val sheet = root.add("div", "sheet menu") {
            setAttribute("role", "dialog")
            setAttribute("aria-modal", "true")
        }
        document.body?.classList?.add("sheet-open")
        backdrop.onclick = { closeSheet() }
        sheet.add("div", "sheet-head") {
            add("h3", text = if (phase == Phase.DONE) texts.enjoyYourTrip else texts.wantSomethingDifferent)
            button("×", "ghost sheet-close") { closeSheet() }.setAttribute("aria-label", texts.close)
        }
        if (phase == Phase.REVIEW) {
            val wish =
                (sheet.add("textarea") as HTMLTextAreaElement).apply {
                    rows = 2
                    placeholder = texts.changePlaceholder
                }
            sheet.speakRow(read = { wish.value }) { wish.value = it }
            sheet.add("div", "actions") {
                button(texts.updateTheRoute, "primary") {
                    val text = wish.value.trim()
                    // The wish goes to the agent as the traveller's next message, and the graph loops back to it.
                    if (text.isEmpty()) {
                        wish.focus()
                    } else {
                        closeSheet()
                        revise("change-sent") { it.copy(messages = it.messages + ChatMessage.User(text)) }
                    }
                }
                button(texts.happyWithIt, "ghost") {
                    closeSheet()
                    revise("plan-approved") { it.copy(approved = true) }
                }
            }
        }
        sheet.add("div", "menu-items") {
            if (phase == Phase.DONE) {
                button(texts.printOrSave, "ghost") {
                    closeSheet()
                    window.print()
                }
            }
            button(texts.seeYourDetails, "ghost") {
                closeSheet()
                openDetails()
            }
            button(texts.planAgain, "ghost") {
                closeSheet()
                planAgain(trip)
            }
            button(texts.saveForMyMaps, "ghost") {
                val day = Date().toISOString().take(10)
                counted("maps-saved")
                download(kmlName(trip, day), trip.toKml(), KML)
            }
            // A phone imports nothing into My Maps: the file goes to a computer, by mail or a chat.
            if (canShareFiles()) {
                button(texts.sendTheFile, "ghost") {
                    val day = Date().toISOString().take(10)
                    if (!shareFile(kmlName(trip, day), trip.toKml(), KML, trip.mapTitle(), texts.sentFileText)) {
                        shareFile(csvName(trip, day), trip.toCsv(), "text/csv", trip.mapTitle(), texts.sentFileText)
                    }
                }
            }
            add("p", "hint small") {
                add("span", text = texts.myMapsHintBefore)
                link("Google My Maps", MY_MAPS)
                add("span", text = texts.myMapsHintAfter)
            }
            button(texts.saveToFile, "ghost") { saveFile() }
            add("p", "hint small", texts.savedFileHint)
            button(if (phase == Phase.DONE) texts.planAnotherTrip else texts.startOver, "ghost") {
                closeSheet()
                startOver()
            }
        }
    }

    /**
     * The agent's last words, in two lines that open with a tap, so that the map is near the top
     * of the screen. It opens only while the traveller reads it: another day, or a reload, closes it.
     */
    private fun HTMLElement.summary(text: String) {
        add("div", "summary" + if (summaryOpen) " open" else "") {
            val box = this
            add("p", "summary-text") { words(text) }
            val toggle = button(if (summaryOpen) texts.readLess else texts.readMore, "link summary-toggle") {}
            toggle.setAttribute("aria-expanded", summaryOpen.toString())
            toggle.onclick = {
                summaryOpen = !summaryOpen
                box.classList.toggle("open", summaryOpen)
                toggle.textContent = if (summaryOpen) texts.readLess else texts.readMore
                toggle.setAttribute("aria-expanded", summaryOpen.toString())
            }
            // Words that fit in two lines need no button.
            window.requestAnimationFrame {
                val words = box.querySelector(".summary-text") as? HTMLElement
                if (!summaryOpen && words != null && words.scrollHeight <= words.clientHeight + 1) toggle.remove()
            }
        }
    }

    /** The day shown, `null` for the whole trip; a trip of one day has only that day. */
    private fun shownDay(trip: Trip): Int? = if (trip.days <= 1) 1 else day?.takeIf { it in 1..trip.days }

    /** The days of the trip as tabs: the whole trip, then each day with the town of its stop. */
    private fun HTMLElement.dayStrip(trip: Trip) {
        val shown = shownDay(trip)
        add("nav", "days") {
            setAttribute("aria-label", texts.wholeTrip)
            fun tab(label: String, place: String, opens: Int?, classes: String) =
                add("button", "day-tab $classes" + if (opens == shown) " current" else "") {
                    setAttribute("type", "button")
                    setAttribute("data-day", opens?.toString() ?: "")
                    setAttribute("aria-pressed", (opens == shown).toString())
                    add("span", "day-tab-label", label)
                    if (place.isNotEmpty()) add("span", "day-tab-place", place)
                    onclick = { showDay(opens) }
                }
            tab(texts.wholeTrip, "", null, "")
            (1..trip.days).forEach { tab(texts.day(it), trip.stopOn(it)?.title.orEmpty().substringBefore(',').trim(), it, RouteMap.dayClass(it)) }
        }
    }

    /**
     * Shows [it], a day or the whole trip for `null`, on the map and in the list, and remembers it.
     * Only the list is drawn again, so the map and the strip stay where they are.
     */
    private fun showDay(it: Int?) {
        val trip = trip ?: return
        day = it
        ShownDay.save(it)
        closeSummary()
        val shown = shownDay(trip)
        root.querySelectorAll(".day-tab").let { tabs ->
            (0 until tabs.length).mapNotNull { tabs.item(it) as? HTMLElement }.forEach { tab ->
                val current = tab.getAttribute("data-day") == (shown?.toString() ?: "")
                tab.classList.toggle("current", current)
                tab.setAttribute("aria-pressed", current.toString())
                if (current) tab.asDynamic().scrollIntoView(js("({ block: 'nearest', inline: 'center', behavior: 'smooth' })"))
            }
        }
        map?.show(shown)
        drawBottomBar()
        dayList?.let { list ->
            list.clear()
            list.fillDay(trip)
            // A traveller who scrolled down a long day sees the next one from its start, with its map.
            (root.querySelector(".map") as? HTMLElement)?.let { if (list.getBoundingClientRect().top < 0) it.scrollIntoView(js("({ block: 'start' })")) }
        }
    }

    /** A tap on a pin brings its card to the middle of the screen, on its day, and marks it there. */
    private fun pinTapped(day: Int, time: MealTime) {
        val trip = trip ?: return
        if (shownDay(trip) != day) showDay(day)
        map?.mark(day, time)
        val card = root.querySelector("article.meal[data-meal='${mealKey(day, time)}']") as? HTMLElement ?: return
        markCard(card)
        window.requestAnimationFrame { card.asDynamic().scrollIntoView(js("({ block: 'center', behavior: 'smooth' })")) }
    }

    /** Marks [card] as the meal on the map, and no other. */
    private fun markCard(card: HTMLElement) {
        root.querySelectorAll("article.meal.marked").let { cards -> (0 until cards.length).forEach { (cards.item(it) as? HTMLElement)?.classList?.remove("marked") } }
        card.classList.add("marked")
    }

    private fun mealKey(day: Int, time: MealTime): String = "$day-${time.name}"

    private fun closeSummary() {
        if (!summaryOpen) return
        summaryOpen = false
        (root.querySelector(".summary") as? HTMLElement)?.classList?.remove("open")
        (root.querySelector(".summary-toggle") as? HTMLElement)?.let {
            it.textContent = texts.readMore
            it.setAttribute("aria-expanded", "false")
        }
    }

    /** The meals of the day shown, or for the whole trip a line for each day, to open it. */
    private fun HTMLElement.fillDay(trip: Trip) {
        val shown = shownDay(trip)
        if (shown == null) {
            add("p", "hint", trip.stops.joinToString(" · ") { texts.daysIn(it.days, it.title) })
            add("ol", "overview") {
                (1..trip.days).forEach { day ->
                    add("li", "overview-day ${RouteMap.dayClass(day)}") {
                        onclick = { showDay(day) }
                        add("div", "overview-title", texts.dayAt(day, trip.stopOn(day)?.title.orEmpty()))
                        val meals = trip.route.filter { it.day == day }.sortedBy { it.time }
                        add("div", "overview-places", meals.mapNotNull { meal -> trip.restaurant(meal.restaurantId)?.let { "${texts.meal(meal.time)}: ${it.name}" } }.joinToString(" · ").ifEmpty { texts.nothingPlannedThisDay })
                    }
                }
            }
            return
        }
        daySection(trip, shown)
        // The next day is a tap away at the end of this one, without going back up.
        if (trip.days > 1) {
            add("div", "day-steps") {
                if (shown > 1) button("← " + texts.day(shown - 1), "ghost") { showDay(shown - 1) }
                if (shown < trip.days) button(texts.day(shown + 1) + " →", "ghost next") { showDay(shown + 1) }
            }
        }
    }

    /** The heading of [shown], where the traveller sleeps, and its meals, numbered as on the map. */
    private fun HTMLElement.daySection(trip: Trip, shown: Int) {
        val meals = trip.route.filter { it.day == shown }.sortedBy { it.time }
        add("h3", "day ${RouteMap.dayClass(shown)}", texts.dayAt(shown, trip.stopOn(shown)?.title.orEmpty()))
        trip.sleepingPlace(shown, MealTime.DINNER)?.first?.takeIf { it.isNotBlank() }?.let { add("p", "hint small sleeping", texts.sleepingAt(it)) }
        if (meals.isEmpty()) add("p", "hint", texts.nothingPlannedThisDay)
        meals.forEachIndexed { index, meal ->
            meal.walk?.let { add("p", "walk", texts.walk(texts.distance(it), fromStay = index == 0)) }
            meal(trip, meal, index + 1)
        }
    }

    /** A printed route has every day, whichever day the screen shows. */
    private fun printing(all: Boolean) {
        val trip = trip ?: return
        val list = dayList ?: return
        list.clear()
        if (all) (1..trip.days).forEach { list.daySection(trip, it) } else list.fillDay(trip)
    }

    /**
     * The route's places, each with a button that opens it in Google Maps to be saved there: on a
     * phone, the app opens. The page ticks off and remembers the ones that were opened.
     */
    private fun HTMLElement.saveOneByOne(trip: Trip) {
        val places = trip.route.sortedWith(compareBy({ it.day }, { it.time })).mapNotNull { meal -> trip.restaurant(meal.restaurantId)?.let { meal to it } }
        if (places.isEmpty()) return
        val route = places.joinToString(",") { it.second.id }
        var opened = OpenedInMaps.load(route)
        add("details", "known one-by-one") {
            add("summary", text = texts.saveOneByOne)
            add("p", "hint small", texts.oneByOneHint)
            val count = add("p", "count", texts.openedOfAll(opened.size, places.size))
            add("ol", "save-list") {
                places.forEach { (meal, place) ->
                    // Drawn again in place: drawing the page again would close the list.
                    val item = add("li", if (place.id in opened) "opened" else "")
                    item.add("span", "save-name", "${texts.day(meal.day)} · ${texts.meal(meal.time)} · ${place.name}")
                    item.link(texts.saveInGoogleMaps, place.googleMapsLink(), "save-link").onclick = {
                        opened = opened + place.id
                        OpenedInMaps.save(route, opened)
                        item.className = "opened"
                        count.textContent = texts.openedOfAll(opened.size, places.size)
                        Unit
                    }
                }
            }
        }
    }

    private fun HTMLElement.meal(trip: Trip, meal: Meal, number: Int) {
        val restaurant = trip.restaurant(meal.restaurantId) ?: return
        val beingChanged = phase == Phase.RUNNING && (meal.day to meal.time) in changing
        add("article", "meal ${RouteMap.dayClass(meal.day)}" + if (beingChanged) " changing" else "") {
            setAttribute("data-meal", mealKey(meal.day, meal.time))
            // The number is the pin's: a tap shows the place on the map.
            val card = this
            button("$number", "pin ${RouteMap.dayClass(meal.day)}") {
                markCard(card)
                map?.mark(meal.day, meal.time)
                (root.querySelector(".map") as? HTMLElement)?.scrollIntoView(js("({ block: 'start', behavior: 'smooth' })"))
            }.setAttribute("aria-label", texts.onTheMap)
            add("div", "meal-text") {
                if (beingChanged) add("p", "changing-note", texts.lookingForOthers)
                // Closed, a card says where, what to order and what to watch out for: the rest opens with a tap.
                val more = add("div", "meal-summary") {
                    add("div", "meal-time", texts.meal(meal.time))
                    add("h4", text = restaurant.name)
                    restaurant.rating?.let { stars -> add("div", "rating", "★ $stars" + restaurant.ratings?.let { " · " + texts.ratings(it) }.orEmpty()) }
                    add("p", "order") {
                        add("strong", text = texts.order)
                        add("span", text = meal.dish)
                    }
                }
                if (meal.caution.isNotBlank()) add("p", "caution", "⚠ ${meal.caution}")
                val offer = trip.offerFor(meal.day, meal.time)
                // Of the places the agent offered, the one it recommends is always marked.
                if (offer != null) {
                    add("div", "badges") {
                        if (offer.recommended.restaurantId == meal.restaurantId) add("span", "badge recommended", "★ " + texts.recommended) else add("span", "badge chosen", texts.yourChoice)
                    }
                }
                val others = offer?.options.orEmpty().count { it.restaurantId != meal.restaurantId && trip.restaurant(it.restaurantId) != null }
                val toggle = add("div", "meal-actions") {
                    button(texts.details + if (others > 0 && phase == Phase.REVIEW) " · " + texts.otherOptionsCount(others) else "", "link meal-toggle") { toggleMeal(card, meal) }
                    // The agent changes the route only while it waits for the traveller.
                    if (phase == Phase.REVIEW) swapControl(trip, meal)
                }
                more.onclick = { toggleMeal(card, meal) }
                add("div", "meal-more") {
                    trip.dishes.firstOrNull { it.name.equals(meal.dish, ignoreCase = true) }?.let { add("p", "what", it.what) }
                    add("p", "why", meal.why)
                    add("div", "badges") {
                        trip.speciality(restaurant)?.let { add("span", "badge speciality", texts.knownForDish(it.name)) }
                        restaurant.sources.forEach { add("span", "badge", texts.source(it)) }
                    }
                    val facts = listOf(restaurant.cuisine, restaurant.address, restaurant.price, restaurant.hours).filter { it.isNotBlank() }
                    if (facts.isNotEmpty()) add("p", "facts", facts.joinToString(" · "))
                    val booking = texts.booking(restaurant.reservation)
                    if (booking != null || restaurant.phone.isNotBlank()) {
                        add("p", "booking") {
                            booking?.let { add("span", text = it) }
                            if (booking != null && restaurant.phone.isNotBlank()) add("span", text = " · ")
                            if (restaurant.phone.isNotBlank()) phoneLink(restaurant.phone)
                        }
                    }
                    add("div", "links") {
                        if (restaurant.website.isNotBlank()) link(texts.website, restaurant.website)
                        link(texts.openInGoogleMaps, restaurant.googleMapsLink())
                        link("OpenStreetMap", "https://www.openstreetmap.org/?mlat=${restaurant.lat}&mlon=${restaurant.lon}#map=18/${restaurant.lat}/${restaurant.lon}")
                    }
                    if (offer != null && offer.recommendation.isNotBlank() && offer.recommended.restaurantId == meal.restaurantId) add("p", "recommendation", offer.recommendation)
                    if (phase == Phase.REVIEW) offer?.let { options(trip, meal, it) }
                }
                // The actions come after what they open, at the end of an open card.
                if ((meal.day to meal.time) in opened) {
                    card.classList.add("open")
                    appendChild(toggle)
                }
                toggle.querySelector(".meal-toggle")?.setAttribute("aria-expanded", ((meal.day to meal.time) in opened).toString())
            }
        }
    }

    /** Opens or closes the card of [meal], in place. */
    private fun toggleMeal(card: HTMLElement, meal: Meal) {
        val key = meal.day to meal.time
        opened = if (key in opened) opened - key else opened + key
        val open = key in opened
        card.classList.toggle("open", open)
        val actions = card.querySelector(".meal-actions") as? HTMLElement ?: return
        val text = card.querySelector(".meal-text") as? HTMLElement ?: return
        // Open, the actions go under the details; closed, under the summary again.
        if (open) text.appendChild(actions) else text.insertBefore(actions, card.querySelector(".meal-more"))
        actions.querySelector(".meal-toggle")?.setAttribute("aria-expanded", open.toString())
        markCard(card)
        map?.mark(meal.day, meal.time)
    }

    /** The other places the agent offered for [meal], each with a button that puts it on the route; the recommended one says so, and why. */
    private fun HTMLElement.options(trip: Trip, meal: Meal, offer: Offer) {
        val others = offer.options.filter { it.restaurantId != meal.restaurantId }
        if (others.isEmpty()) return
        add("div", "options") {
            add("p", "field-label", texts.otherOptions)
            others.forEach { option ->
                val place = trip.restaurant(option.restaurantId) ?: return@forEach
                add("div", "option") {
                    add("div", "option-head") {
                        add("strong", text = place.name)
                        if (option == offer.recommended) add("span", "badge recommended", "★ " + texts.recommended)
                    }
                    place.rating?.let { stars -> add("div", "rating", "★ $stars" + place.ratings?.let { " · " + texts.ratings(it) }.orEmpty()) }
                    add("p", "order") {
                        add("strong", text = texts.order)
                        add("span", text = option.dish)
                    }
                    add("p", "why", option.why)
                    if (option == offer.recommended && offer.recommendation.isNotBlank()) add("p", "recommendation", offer.recommendation)
                    if (option.caution.isNotBlank()) add("p", "caution", "⚠ ${option.caution}")
                    add("div", "links") {
                        button(texts.chooseThisOne, "chip") { pick(option) }
                        link(texts.openInGoogleMaps, place.googleMapsLink())
                    }
                }
            }
        }
    }

    /**
     * Puts [option], a place the agent offered, on the route. Nothing runs: the run waits, so its
     * checkpoint takes the choice, and the agent hears of it with the traveller's next words.
     */
    private fun pick(option: Meal) {
        val now = trip ?: return
        val picked = now.picked(option)
        trip = picked
        render()
        scope.launch {
            try {
                checkpointer.load(config.threadId)?.let { checkpointer.save(config.threadId, it.copy(state = picked)) }
            } catch (_: Throwable) {
                // The browser blocks its storage: the choice holds until the page is closed.
            }
        }
    }

    /**
     * The button that opens the questions about changing the place of [meal], or, when the
     * traveller marked it, what they said and the buttons to edit that or keep the place.
     */
    private fun HTMLElement.swapControl(trip: Trip, meal: Meal) {
        val key = meal.day to meal.time
        val swap = swaps[key]
        if (swap == null) {
            button(texts.changeThisPlace, "link") {
                swaps = swaps + (key to MealSwap(meal.day, meal.time))
                openSheet(trip, meal)
            }
            return
        }
        add("div", "marked-swap") {
            add("p", "field-label", texts.markedToChange)
            val said = swap.reasons.map { texts.swapReason(it) } + listOf(swap.instead, swap.near, swap.note).filter { it.isNotBlank() }
            if (said.isNotEmpty()) add("p", "hint small", said.joinToString(" · "))
            add("div", "quiet-actions") {
                button(texts.editSwap, "link") { openSheet(trip, meal) }
                button(texts.keepThisPlace, "link") {
                    swaps = swaps - key
                    swapsChanged()
                }
            }
        }
    }

    /**
     * The questions about changing the place of [meal], in a sheet over the route: why not this
     * one, and what instead or near what, when the answer needs it. The route stays where it is
     * under the sheet, and only the sheet is drawn again after a tap.
     */
    private fun openSheet(trip: Trip, meal: Meal) {
        val key = meal.day to meal.time
        closeSheet()
        val backdrop = root.add("div", "sheet-backdrop")
        val sheet = root.add("div", "sheet") {
            setAttribute("role", "dialog")
            setAttribute("aria-modal", "true")
        }
        document.body?.classList?.add("sheet-open")
        // Closing keeps what the traveller said, to send with the other places; a sheet where nothing was said is no change.
        fun close() {
            swaps[key]?.let { if (!it.said) swaps = swaps - key }
            closeSheet()
            swapsChanged()
        }
        backdrop.onclick = { close() }
        fun draw() {
            sheet.clear()
            val swap = swaps[key] ?: return
            fun changed(change: (MealSwap) -> MealSwap, redraw: Boolean) {
                swaps[key]?.let { swaps = swaps + (key to change(it)) }
                if (redraw) draw() else relabelSwaps()
            }
            sheet.add("div", "sheet-head") {
                add("div") {
                    add("div", "meal-time", texts.day(meal.day) + " · " + texts.meal(meal.time))
                    add("h3", text = trip.restaurant(meal.restaurantId)?.name ?: meal.dish)
                }
                button("×", "ghost sheet-close") { close() }.setAttribute("aria-label", texts.close)
            }
            sheet.add("p", "field-label", texts.whyNotThisOne)
            sheet.add("div", "chips") {
                SwapReason.entries.forEach { reason ->
                    button(texts.swapReason(reason), "chip answer" + if (reason in swap.reasons) " picked" else "") {
                        changed({ it.copy(reasons = if (reason in it.reasons) it.reasons - reason else it.reasons + reason) }, redraw = true)
                    }
                }
            }
            if (SwapReason.DISH in swap.reasons || SwapReason.FOOD in swap.reasons) {
                sheet.add("p", "field-label", texts.whatInstead)
                // The dishes the stop is known for at this meal, as answers to tap.
                val stop = trip.stopOn(meal.day)?.name
                val dishes = trip.dishes.filter { it.stop == stop && MealTime.of(it.meal) == meal.time && !it.name.equals(meal.dish, ignoreCase = true) }
                if (dishes.isNotEmpty()) {
                    sheet.add("div", "chips") {
                        dishes.forEach { dish ->
                            button(dish.name, "chip answer" + if (swap.instead == dish.name) " picked" else "") {
                                changed({ it.copy(instead = if (it.instead == dish.name) "" else dish.name) }, redraw = true)
                            }
                        }
                    }
                }
                sheet.textInput(swap.instead, texts.insteadPlaceholder, texts.whatInstead) { text -> changed({ it.copy(instead = text) }, redraw = false) }
            }
            if (SwapReason.CLOSER in swap.reasons) {
                sheet.add("p", "field-label", texts.closerToWhat)
                val sleeping = trip.stopOn(meal.day)?.let { listOf(it.stayName) + it.stays.map { stay -> stay.name } }.orEmpty().filter { it.isNotBlank() }
                if (sleeping.isNotEmpty()) {
                    sheet.add("div", "chips") {
                        sleeping.forEach { place ->
                            button(place, "chip answer" + if (swap.near == place) " picked" else "") { changed({ it.copy(near = if (it.near == place) "" else place) }, redraw = true) }
                        }
                    }
                }
                sheet.textInput(swap.near, texts.closerPlaceholder, texts.closerToWhat) { text -> changed({ it.copy(near = text) }, redraw = false) }
            }
            sheet.textInput(swap.note, texts.swapNotePlaceholder, texts.swapNotePlaceholder, "swap-note") { text -> changed({ it.copy(note = text) }, redraw = false) }
            sheet.add("div", "actions") {
                button(swapLabel(), "primary swap-send") { sendSwaps() }
                button(texts.alsoChangeAnother, "ghost") { close() }
            }
        }
        draw()
    }

    private fun closeSheet() {
        root.querySelector(".sheet")?.remove()
        root.querySelector(".sheet-backdrop")?.remove()
        document.body?.classList?.remove("sheet-open")
    }

    /** Sends the places the traveller marked, with what they said: the route stays on the screen while the agent finds others. */
    private fun sendSwaps() {
        val ready = swaps.values.filter { it.said }.sortedWith(compareBy({ it.day }, { it.time }))
        if (ready.isEmpty()) return
        closeSheet()
        changing = ready.map { it.day to it.time }.toSet()
        day = ready.first().day
        ShownDay.save(day)
        revise("places-changed") { it.swapped(ready) }
    }

    private fun swapLabel(): String = swaps.values.count { it.said }.let { if (it > 1) texts.changeThesePlaces(it) else texts.findAnotherPlace }

    /** The send buttons say how many places they change: all the places where the traveller said something. */
    private fun relabelSwaps() {
        val label = swapLabel()
        root.querySelectorAll(".swap-send").let { buttons -> (0 until buttons.length).forEach { (buttons.item(it) as? HTMLElement)?.textContent = label } }
    }

    /** The marked places changed: their cards and the bar that sends them are drawn again. */
    private fun swapsChanged() {
        val trip = trip ?: return
        dayList?.let {
            it.clear()
            it.fillDay(trip)
        }
        drawBottomBar()
    }

    /**
     * The bar at the bottom of the route: the day shown in Google Maps, or the button that sends the
     * marked places while there are any, and the menu of everything else.
     */
    private fun drawBottomBar() {
        root.querySelector(".bottom-bar")?.remove()
        val trip = trip ?: return
        if (phase != Phase.REVIEW && phase != Phase.DONE || trip.route.isEmpty() || tab != Tab.PLAN) return
        root.add("div", "bottom-bar") {
            val shown = shownDay(trip)
            when {
                phase == Phase.REVIEW && swaps.values.any { it.said } -> button(swapLabel(), "primary swap-send") { sendSwaps() }
                shown != null -> trip.dayInGoogleMaps(shown)?.let { link(texts.thisDayInGoogleMaps, it, "bar-link") }
            }
            button("☰ " + texts.moreActions, if (phase == Phase.REVIEW && swaps.values.none { it.said } && shown == null) "primary menu-button" else "ghost menu-button") { openMenu(trip) }
        }
    }

    private companion object {
        /** The media type of a KML file. */
        const val KML = "application/vnd.google-earth.kml+xml"

        /** How many of the agent's last actions a run shows. */
        const val FEED_LINES = 8

        /** The steps whose node asks a model and waits for its whole answer. */
        val WAITS_FOR_A_MODEL = setOf(Nodes.INTERVIEW, Nodes.BRIEF, Nodes.DISHES, Nodes.PLAN)

        /** How long a step may say nothing before the page explains why. */
        const val QUIET_MILLIS = 12_000.0

        /** How long a dish is shown while the agent chooses: long enough to read three sentences. */
        const val DISH_MILLIS = 12_000.0

        /** The days a try-out link can last, the second the first offered. */
        val TRY_OUT_DAYS = listOf(1, 3, 7, 30)

        const val DAY_MILLIS = 24 * 60 * 60 * 1000.0

        /** The budgets a profile can have, as a model writes them. */
        val BUDGETS = listOf("low", "medium", "high")
    }
}

/**
 * The day of the route the traveller looked at last, so that a reload or a change shows that day
 * again: `null` for the whole trip. A new trip starts at its first day. A browser that blocks its
 * storage starts at the first day each time.
 */
private object ShownDay {
    private const val KEY = "foodie.day"

    fun load(): Int? =
        try {
            localStorage.getItem(KEY).let { if (it == "all") null else it?.toIntOrNull() ?: 1 }
        } catch (_: Throwable) {
            1
        }

    fun save(day: Int?) {
        try {
            localStorage.setItem(KEY, day?.toString() ?: "all")
        } catch (_: Throwable) {
            // The browser blocks its storage: the day holds until the page is closed.
        }
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
