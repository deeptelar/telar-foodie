package dev.deeptelar.foodie

import dev.deeptelar.foodie.sources.GuidePage
import dev.deeptelar.foodie.sources.Sources
import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphEvent
import dev.deeptelar.telar.MemoryCheckpointer
import dev.deeptelar.telar.agent.ChatEvent
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.ChatModel
import dev.deeptelar.telar.agent.ChatRequest
import dev.deeptelar.telar.agent.ChatResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchedTest {
    @Test
    fun `only the strings whose closing quote has arrived count`() {
        val written = """{"profile":{},"questions":[{"topic":"ALLERGIES","text":"Any \"real\" allergy?"},{"topic":"DIET","text":"Anything you do no"""

        assertEquals(listOf("Any \"real\" allergy?"), finishedValues(written, "text"))
    }

    @Test
    fun `the interview says each question while the model writes it`() =
        runTest {
            val reading =
                Reading(
                    Profile(),
                    answered = listOf(Topic.WHERE),
                    questions = listOf(Question(Topic.ALLERGIES, "Naples fries a lot of seafood. Any allergy?"), Question(Topic.PREGNANCY, "Is anyone pregnant?")),
                )
            // A model that writes its answer in small pieces, as a streaming server does.
            val model = Pieces(Json.encodeToString(reading))
            val sources = Sources(geocoder = { null }, guide = { GuidePage() }, map = { _, _, _, _ -> emptyList() }, reviews = null)
            val config = GraphConfig<Trip>(threadId = "trip", checkpointer = MemoryCheckpointer())

            val events = planner(model, sources).stream(Trip("Two days in Naples"), config).toList()

            val said = events.filterIsInstance<GraphEvent.NodeProgress<Trip>>().mapNotNull { (it.value as? Activity)?.text }
            assertEquals(
                listOf(
                    "Reading your request",
                    "Noting what you told me",
                    "Writing the questions: “Naples fries a lot of seafood. Any allergy?”",
                    "Writing the questions: “Is anyone pregnant?”",
                ),
                said,
            )
        }

    private class Pieces(
        private val answer: String,
    ) : ChatModel {
        override suspend fun chat(request: ChatRequest): ChatResponse = ChatResponse(ChatMessage.Assistant(answer))

        override fun stream(request: ChatRequest): Flow<ChatEvent> =
            flow {
                answer.chunked(7).forEach { emit(ChatEvent.TextDelta(it)) }
                emit(ChatEvent.Completed(ChatResponse(ChatMessage.Assistant(answer))))
            }
    }
}
