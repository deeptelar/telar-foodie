package dev.deeptelar.foodie

import dev.deeptelar.foodie.sources.Nominatim
import dev.deeptelar.foodie.sources.Overpass
import dev.deeptelar.foodie.sources.Recorder
import dev.deeptelar.foodie.sources.Recording
import dev.deeptelar.foodie.sources.Sources
import dev.deeptelar.foodie.sources.Wikivoyage
import dev.deeptelar.foodie.sources.replay
import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphEvent
import dev.deeptelar.telar.MemoryCheckpointer
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.pendingToolCalls
import dev.deeptelar.telar.anthropic.AnthropicChatModel
import dev.deeptelar.telar.openai.OpenAiChatModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Plans a trip with a real model and the real sources, asks for a change and says yes. It costs
 * tokens and needs a network, so it only runs when a model is given, through a server with the API
 * of OpenAI:
 *
 * ```
 * FOODIE_MODEL_URL=https://generativelanguage.googleapis.com/v1beta/openai \
 * FOODIE_MODEL=gemini-flash-latest FOODIE_MODEL_KEY=... ./gradlew jvmTest --tests '*LivePlannerTest*'
 * ```
 *
 * With `FOODIE_PROVIDER=claude`, Claude plans instead, with the key in `FOODIE_MODEL_KEY` and the
 * model in `FOODIE_MODEL`; `FOODIE_MODEL_URL` is not needed then.
 *
 * `FOODIE_REQUEST` sets the trip, and `FOODIE_LANGUAGE` its language (`ENGLISH`, `SPANISH` or `CATALAN`).
 * With `FOODIE_SOURCES` set to a file, the sources answer from what that file kept, and what they
 * are asked anew is kept in it: models compared on the same file plan from the same places.
 * What the run does is written to `build/live-planner.txt`, with the time each part took.
 */
class LivePlannerTest {
    @Test
    fun `a real model plans a trip and changes it`() {
        val claude = System.getenv("FOODIE_PROVIDER") == "claude"
        val url = System.getenv("FOODIE_MODEL_URL") ?: if (claude) "" else return
        val log = StringBuilder()
        fun say(line: String) {
            println(line)
            log.appendLine(line)
        }
        try {
            runBlocking {
                HttpClient(CIO).use { client ->
                    val model =
                        if (claude) {
                            AnthropicChatModel(client = client, apiKey = System.getenv("FOODIE_MODEL_KEY"), model = System.getenv("FOODIE_MODEL"))
                        } else {
                            OpenAiChatModel(client = client, apiKey = System.getenv("FOODIE_MODEL_KEY"), model = System.getenv("FOODIE_MODEL"), baseUrl = url)
                        }
                    val kept = System.getenv("FOODIE_SOURCES")?.let(::File)
                    // The street map is often busy: a comparison waits for it longer than a traveller would.
                    val online = Sources(Nominatim(client), Wikivoyage(client), Overpass(client, patience = 60.seconds, rest = Duration.ZERO))
                    // A question that fails is a note of the trip, as on the page: it must not fail the run with it.
                    val questions = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext.job))
                    val recorder = kept?.let { file -> Recorder(replay({ if (file.exists()) Recording.fromJson(file.readText()) else Recording() }, online), questions) }
                    val planner = planner(model, recorder?.sources ?: online)
                    val started = TimeSource.Monotonic.markNow()
                    fun took() = say("Took: ${started.elapsedNow().inWholeSeconds / 60} min ${started.elapsedNow().inWholeSeconds % 60} s")
                    val config = GraphConfig(threadId = "live", checkpointer = MemoryCheckpointer<Trip>(), interruptBefore = setOf(Nodes.REVIEW), maxIterations = 120)
                    val request = System.getenv("FOODIE_REQUEST") ?: "Two days in Lisbon and one in Porto. I love seafood and pastries, and I do not eat pork."

                    say("Request: $request")
                    val language = System.getenv("FOODIE_LANGUAGE")?.let { Language.valueOf(it) } ?: Language.ENGLISH
                    var trip = follow(planner.stream(Trip(request, language = language), config), ::say)
                    // The interview: a question with an answer that rules out the others gets that one, such as "None"; any other
                    // gets all its answers, so that the route plans every meal. The profile is taken as it is.
                    while (trip.route.isEmpty() && (trip.questions.isNotEmpty() || trip.interview.stage == InterviewStage.CHECKING)) {
                        trip =
                            if (trip.questions.isNotEmpty()) {
                                trip.questions.forEach { say("  asks (${it.topic}): ${it.text} ${it.options}") }
                                follow(planner.streamResume(config) { it.answered(it.questions.map { q -> Answer(q.topic, q.text, q.alone.firstOrNull() ?: q.options.joinToString(", ")) }) }, ::say)
                            } else {
                                say("  profile: ${trip.profile}")
                                follow(planner.streamResume(config) { it.confirmed() }, ::say)
                            }
                    }
                    describe(trip, ::say)
                    took()
                    recorder?.let { kept!!.writeText(it.recording.plus(kept).toJson() + "\n") }
                    assertTrue(trip.route.isNotEmpty(), "the route is empty")

                    val change = "Make the first dinner a place for grilled fish, and keep everything else."
                    say("\nChange: $change")
                    trip = follow(planner.streamResume(config) { it.copy(messages = it.messages + ChatMessage.User(change)) }, ::say)
                    describe(trip, ::say)
                    took()

                    // A change of place from the page, as a traveller taps it: the first lunch, or else the first meal, is touristy.
                    val swapped = trip.route.firstOrNull { it.time == MealTime.LUNCH } ?: trip.route.first()
                    say("\nSwap: day ${swapped.day} ${swapped.time.label}, touristy")
                    trip = follow(planner.streamResume(config) { it.swapped(listOf(MealSwap(swapped.day, swapped.time, listOf(SwapReason.TOURISTY)))) }, ::say)
                    describe(trip, ::say)
                    trip.offerFor(swapped.day, swapped.time)?.let { offer -> say("Offered: ${offer.options.map { trip.restaurant(it.restaurantId)?.name }} · ${offer.recommendation}") }
                    took()

                    val done = follow(planner.streamResume(config) { it.copy(approved = true) }, ::say)
                    assertEquals(true, done.approved)
                    questions.cancel()
                }
            }
        } finally {
            File("build/live-planner.txt").writeText(log.toString())
        }
    }

    private suspend fun follow(run: Flow<GraphEvent<Trip>>, say: (String) -> Unit): Trip {
        var last: Trip? = null
        run.collect { event ->
            when (event) {
                is GraphEvent.NodeStarted ->
                    if (event.node == Nodes.TOOLS) {
                        event.state.messages.pendingToolCalls().forEach { say("  tool ${it.name} ${it.input}") }
                    } else {
                        say("node ${event.node}")
                    }
                is GraphEvent.NodeRetry -> say("  retry of ${event.node}: ${event.cause}")
                is GraphEvent.StepCompleted -> {
                    last = event.state
                    val errors = event.state.messages.lastOrNull()?.let { it as? ChatMessage.ToolResult }
                    if (errors?.isError == true) say("  tool error: ${errors.text.take(300)}")
                }
                is GraphEvent.Interrupted -> {
                    last = event.state
                    say("paused before ${event.nextNodes}")
                }
                is GraphEvent.Completed -> {
                    last = event.state
                    say("completed")
                }
                else -> Unit
            }
        }
        return checkNotNull(last)
    }

    private fun describe(trip: Trip, say: (String) -> Unit) {
        say("Stops: ${trip.stops.joinToString { "${it.name} / ${it.title} (${it.days} days, ${it.cuisines}, stay ${it.stay})" }}")
        say("Candidates: ${trip.restaurants.size}, ${Source.entries.joinToString { s -> "${s.label} ${trip.restaurants.count { s in it.sources }}" }}")
        say("Dishes: ${trip.dishes.joinToString { "${it.name} (${it.stop})" }}")
        trip.route.forEach { meal ->
            val place = trip.restaurant(meal.restaurantId)
            say("  day ${meal.day} ${meal.time.label}: ${place?.name} · ${meal.dish} · ${meal.why}" + if (meal.caution.isBlank()) "" else " · caution: ${meal.caution}")
        }
        say("Summary: ${trip.summary}")
        say("Notes: ${trip.notes}")
        say("Tokens: ${trip.tokens.total}")
    }

    /** This recording with what [file] kept before: a later run keeps what the earlier ones found. */
    private fun Recording.plus(file: File): Recording {
        val before = runCatching { Recording.fromJson(file.readText()) }.getOrElse { Recording() }
        return Recording(before.places + places, before.guides + guides, before.restaurants + restaurants, before.sights + sights)
    }
}
