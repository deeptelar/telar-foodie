package dev.deeptelar.foodie

import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.ChatModel
import dev.deeptelar.telar.agent.ChatRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsTest {
    private val settings = Settings(claudeKey = "sk-ant-own")

    private suspend fun sent(model: (HttpClient) -> ChatModel?): String {
        var body = ""
        val client =
            HttpClient(
                MockEngine { request ->
                    body = (request.body as TextContent).text
                    respond(
                        """{"content":[{"type":"text","text":"Fine."}],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            )
        model(client)!!.chat(ChatRequest(listOf(ChatMessage.User("A question.")), "A system."))
        return body
    }

    @Test
    fun `Claude keeps what it read for the rounds of the agent`() =
        runTest { assertEquals(true, "cache_control" in sent(settings::planModel)) }

    @Test
    fun `Claude keeps nothing of a single question`() =
        runTest { assertEquals(false, "cache_control" in sent(settings::model)) }
}
