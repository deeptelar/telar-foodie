package dev.deeptelar.foodie

import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.ChatModel
import dev.deeptelar.telar.agent.ChatRequest
import dev.deeptelar.telar.agent.ChatResponse
import dev.deeptelar.telar.agent.ToolCall
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Stands in for a model, so that the page can be tried without a key. It is a script and
 * understands nothing: whatever the request says, it plans [Texts.sampleRequest], two days in Lisbon. It asks
 * one round of questions, as a model would, but does not read the answers.
 *
 * The graph, the sources and the tools around it are the real ones. The script searches the map
 * for a pastry shop, as a model would, and then puts the candidates with the most evidence on the
 * route. A model chooses each place for a dish; the script cannot, and says so in its reasons.
 *
 * @param pause how long an answer takes, so that a run can be watched.
 */
class SampleModel(
    private val pause: Duration = 700.milliseconds,
) : ChatModel {
    override suspend fun chat(request: ChatRequest): ChatResponse {
        delay(pause)
        val system = request.system.orEmpty()
        // The script writes in the language the prompts ask for, as a model does.
        val texts = languageOf(request.messages).texts
        val message =
            when {
                system.startsWith(INTERVIEW_SYSTEM) -> ChatMessage.Assistant(Json.encodeToString(interview(request.messages, texts)))
                system.startsWith(BRIEF_SYSTEM) -> ChatMessage.Assistant(Json.encodeToString(brief(texts)))
                system.startsWith(DISHES_SYSTEM) -> ChatMessage.Assistant(Json.encodeToString(Menu(dishes(texts))))
                else -> plan(request.messages, texts)
            }
        return ChatResponse(message)
    }

    /** The language that the first message names in its `languageRule`. */
    private fun languageOf(messages: List<ChatMessage>): Language {
        val text = messages.firstOrNull()?.text.orEmpty()
        return Language.entries.firstOrNull { "reads in ${it.english}:" in text } ?: Language.ENGLISH
    }

    /** The questions of the first round, and after it a profile with every topic answered. */
    private fun interview(messages: List<ChatMessage>, texts: Texts): Reading {
        val first = messages.none { "answers to the planner's questions" in it.text }
        val profile = Profile(wishes = texts.sampleWishes)
        return if (first) {
            // The sample's request says where it goes and what the traveller likes. The rest is asked.
            Reading(profile, answered = listOf(Topic.WHERE, Topic.TASTE), questions = texts.sampleQuestions)
        } else {
            Reading(profile, answered = Topic.entries)
        }
    }

    private fun plan(messages: List<ChatMessage>, texts: Texts): ChatMessage.Assistant {
        val results = messages.filterIsInstance<ChatMessage.ToolResult>()
        val asked = messages.count { it is ChatMessage.User }
        return when {
            // A second message of the traveller is a wish for a change, which a script cannot follow.
            asked > 1 -> ChatMessage.Assistant(texts.sampleNoChanges)
            results.isEmpty() -> ChatMessage.Assistant(texts.sampleFirstSearch, listOf(call(1, "search_map", MapLookup(STOP, cuisine = "pastry"))))
            results.none { it.toolName == "set_meal" } -> ChatMessage.Assistant(toolCalls = meals(ids(messages.first().text), ids(results.first().text).firstOrNull(), texts))
            else -> ChatMessage.Assistant(texts.sampleSummary)
        }
    }

    /** A lunch and a dinner for both days at the candidates listed first, and a snack at [pastry] when the map found one. */
    private fun meals(candidates: List<String>, pastry: String?, texts: Texts): List<ToolCall> {
        val places = candidates.filter { it != pastry }
        val slots = listOf(1 to "lunch", 1 to "dinner", 2 to "lunch", 2 to "dinner")
        val meals =
            slots.zip(places).mapIndexed { index, (slot, id) ->
                MealChoice(slot.first, slot.second, id, DISHES[index].first, texts.sampleWhy)
            }
        val snack = pastry?.let { MealChoice(1, "snack", it, "pastel de nata", texts.sampleWhyPastry) }
        return (meals + listOfNotNull(snack)).mapIndexed { index, meal -> call(index + 2, "set_meal", meal) }
    }

    /** The ids in a list of candidates, in the order they are listed. */
    private fun ids(text: String): List<String> = Regex("^\\[(r\\d+)\\]", RegexOption.MULTILINE).findAll(text).map { it.groupValues[1] }.toList()

    private inline fun <reified Input> call(number: Int, tool: String, input: Input): ToolCall =
        ToolCall("sample-$number", tool, Json.encodeToJsonElement(input) as JsonObject)

    private fun brief(texts: Texts): Brief = Brief(listOf(BriefStop(STOP, shown = texts.sampleStop, days = 2, cuisines = listOf("portuguese", "regional", "seafood"))))

    private fun dishes(texts: Texts): List<Dish> =
        DISHES.zip(texts.sampleDishes.zip(texts.sampleStories)) { (name, meal), (what, story) -> Dish(STOP, name, what, meal, story) }

    companion object {
        private const val STOP = "Lisbon, Portugal"

        /** The sample's dishes by their local names, with when they are eaten. What each is comes from [Texts.sampleDishes], and its story from [Texts.sampleStories]. */
        private val DISHES =
            listOf(
                "bacalhau à brás" to "lunch",
                "arroz de marisco" to "dinner",
                "sardinhas assadas" to "lunch",
                "polvo à lagareiro" to "dinner",
                "bifana" to "snack",
                "pastel de nata" to "snack",
                "ginjinha" to "snack",
            )
    }
}
