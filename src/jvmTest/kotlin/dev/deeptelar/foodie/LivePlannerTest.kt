package dev.deeptelar.foodie

import dev.deeptelar.foodie.sources.Sources
import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphEvent
import dev.deeptelar.telar.MemoryCheckpointer
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.pendingToolCalls
import dev.deeptelar.telar.openai.OpenAiChatModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
 * What the run does is written to `build/live-planner.txt`.
 */
class LivePlannerTest {
    @Test
    fun `a real model plans a trip and changes it`() {
        val url = System.getenv("FOODIE_MODEL_URL") ?: return
        val log = StringBuilder()
        fun say(line: String) {
            println(line)
            log.appendLine(line)
        }
        try {
            runBlocking {
                HttpClient(CIO).use { client ->
                    val model = OpenAiChatModel(client = client, apiKey = System.getenv("FOODIE_MODEL_KEY"), model = System.getenv("FOODIE_MODEL"), baseUrl = url)
                    val planner = planner(model, Sources.online(client))
                    val config = GraphConfig(threadId = "live", checkpointer = MemoryCheckpointer<Trip>(), interruptBefore = setOf(Nodes.REVIEW), maxIterations = 120)
                    val request = System.getenv("FOODIE_REQUEST") ?: "Two days in Lisbon and one in Porto. I love seafood and pastries, and I do not eat pork."

                    say("Request: $request")
                    var trip = follow(planner.stream(Trip(request), config), ::say)
                    // The interview: every question gets its first answer, and the profile is taken as it is.
                    while (trip.route.isEmpty() && (trip.questions.isNotEmpty() || trip.interview.stage == InterviewStage.CHECKING)) {
                        trip =
                            if (trip.questions.isNotEmpty()) {
                                trip.questions.forEach { say("  asks (${it.topic}): ${it.text} ${it.options}") }
                                follow(planner.streamResume(config) { it.answered(it.questions.map { q -> Answer(q.topic, q.text, q.options.firstOrNull().orEmpty()) }) }, ::say)
                            } else {
                                say("  profile: ${trip.profile}")
                                follow(planner.streamResume(config) { it.confirmed() }, ::say)
                            }
                    }
                    describe(trip, ::say)
                    assertTrue(trip.route.isNotEmpty(), "the route is empty")

                    val change = "Make the first dinner a place for grilled fish, and keep everything else."
                    say("\nChange: $change")
                    trip = follow(planner.streamResume(config) { it.copy(messages = it.messages + ChatMessage.User(change)) }, ::say)
                    describe(trip, ::say)

                    val done = follow(planner.streamResume(config) { it.copy(approved = true) }, ::say)
                    assertEquals(true, done.approved)
                }
            }
        } finally {
            java.io.File("build/live-planner.txt").writeText(log.toString())
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
        say("Stops: ${trip.stops.joinToString { "${it.name} (${it.days} days, ${it.cuisines})" }}")
        say("Candidates: ${trip.restaurants.size}, ${Source.entries.joinToString { s -> "${s.label} ${trip.restaurants.count { s in it.sources }}" }}")
        say("Dishes: ${trip.dishes.joinToString { "${it.name} (${it.stop})" }}")
        trip.route.forEach { meal ->
            val place = trip.restaurant(meal.restaurantId)
            say("  day ${meal.day} ${meal.time.label}: ${place?.name} · ${meal.dish} · ${meal.why}")
        }
        say("Summary: ${trip.summary}")
        say("Notes: ${trip.notes}")
        say("Tokens: ${trip.tokens.total}")
    }
}
