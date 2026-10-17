package dev.deeptelar.foodie

import dev.deeptelar.telar.agent.ChatEvent
import dev.deeptelar.telar.agent.ChatModel
import dev.deeptelar.telar.agent.ChatModelException
import dev.deeptelar.telar.agent.ChatRequest
import dev.deeptelar.telar.agent.ChatResponse
import dev.deeptelar.telar.reportProgress
import kotlinx.serialization.json.Json

/*
 * A typed answer (`ask`) arrives whole, and a model that thinks before it writes can take a minute
 * for it. So that the traveller sees what happens, the nodes ask through a model that streams the
 * answer and says what it has written so far: the questions of the interview one by one, the places
 * of the brief, the dishes. The answer itself is the same.
 */

/** A model that asks [model] with `stream` and calls [watch] with all the text written so far, after each piece. */
private class Watched(
    private val model: ChatModel,
    private val watch: suspend (String) -> Unit,
) : ChatModel {
    override suspend fun chat(request: ChatRequest): ChatResponse {
        val written = StringBuilder()
        var response: ChatResponse? = null
        model.stream(request).collect { event ->
            when (event) {
                is ChatEvent.TextDelta -> {
                    written.append(event.text)
                    watch(written.toString())
                }
                is ChatEvent.Completed -> response = event.response
            }
        }
        return response ?: throw ChatModelException("The model's stream ended before the answer was complete.")
    }
}

/**
 * This model, reporting as the node's progress what [describe] makes of the text it has written so
 * far, each time that changes. [describe] returns `null` when there is nothing to say yet.
 */
internal fun ChatModel.watched(describe: (String) -> String?): ChatModel {
    var last: String? = null
    return Watched(this) { written ->
        val now = describe(written)
        if (now != null && now != last) {
            last = now
            reportProgress(Activity(now))
        }
    }
}

/** The values of [key] in JSON that may still be being written: the strings whose closing quote has arrived. */
internal fun finishedValues(json: String, key: String): List<String> =
    Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(json).map { unescaped(it.groupValues[1]) }.filter { it.isNotBlank() }.toList()

private fun unescaped(raw: String): String =
    try {
        Json.decodeFromString<String>("\"$raw\"")
    } catch (_: IllegalArgumentException) {
        raw
    }
