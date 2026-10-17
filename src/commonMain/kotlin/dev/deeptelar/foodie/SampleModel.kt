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
 * understands nothing: whatever the request says, it plans [REQUEST], two days in Lisbon. It asks
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
        val message =
            when {
                system.startsWith(INTERVIEW_SYSTEM) -> ChatMessage.Assistant(Json.encodeToString(interview(request.messages)))
                system.startsWith(BRIEF_SYSTEM) -> ChatMessage.Assistant(Json.encodeToString(BRIEF))
                system.startsWith(DISHES_SYSTEM) -> ChatMessage.Assistant(Json.encodeToString(Menu(DISHES)))
                else -> plan(request.messages)
            }
        return ChatResponse(message)
    }

    /** The questions of the first round, and after it a profile with every topic answered. */
    private fun interview(messages: List<ChatMessage>): Reading {
        val first = messages.none { "answers to the planner's questions" in it.text }
        return if (first) {
            Reading(PROFILE, answered = Topic.entries - QUESTIONS.map { it.topic }, questions = QUESTIONS)
        } else {
            Reading(PROFILE, answered = Topic.entries)
        }
    }

    private fun plan(messages: List<ChatMessage>): ChatMessage.Assistant {
        val results = messages.filterIsInstance<ChatMessage.ToolResult>()
        val asked = messages.count { it is ChatMessage.User }
        return when {
            // A second message of the traveller is a wish for a change, which a script cannot follow.
            asked > 1 -> ChatMessage.Assistant(NO_CHANGES)
            results.isEmpty() -> ChatMessage.Assistant("First, a place for pastéis de nata.", listOf(call(1, "search_map", MapLookup(STOP, cuisine = "pastry"))))
            results.none { it.toolName == "set_meal" } -> ChatMessage.Assistant(toolCalls = meals(ids(messages.first().text), ids(results.first().text).firstOrNull()))
            else -> ChatMessage.Assistant(SUMMARY)
        }
    }

    /** A lunch and a dinner for both days at the candidates listed first, and a snack at [pastry] when the map found one. */
    private fun meals(candidates: List<String>, pastry: String?): List<ToolCall> {
        val places = candidates.filter { it != pastry }
        val slots = listOf(1 to "lunch", 1 to "dinner", 2 to "lunch", 2 to "dinner")
        val meals =
            slots.zip(places).mapIndexed { index, (slot, id) ->
                MealChoice(slot.first, slot.second, id, DISHES[index].name, "Sample: taken for the evidence the sources have for it, not for this dish.")
            }
        val snack = pastry?.let { MealChoice(1, "snack", it, "pastel de nata", "Sample: the first pastry shop the street map returned.") }
        return (meals + listOfNotNull(snack)).mapIndexed { index, meal -> call(index + 2, "set_meal", meal) }
    }

    /** The ids in a list of candidates, in the order they are listed. */
    private fun ids(text: String): List<String> = Regex("^\\[(r\\d+)\\]", RegexOption.MULTILINE).findAll(text).map { it.groupValues[1] }.toList()

    private inline fun <reified Input> call(number: Int, tool: String, input: Input): ToolCall =
        ToolCall("sample-$number", tool, Json.encodeToJsonElement(input) as JsonObject)

    companion object {
        /** The trip the sample plans. */
        const val REQUEST = "Two days in Lisbon. I love seafood and pastries."

        private const val STOP = "Lisbon, Portugal"

        private val PROFILE = Profile(wishes = "seafood and pastries")

        private val QUESTIONS =
            listOf(
                Question(
                    Topic.ALLERGIES,
                    "Lisbon cooks a lot of shellfish, and many of its pastries have almonds. Do you have a food allergy?",
                    listOf("None", "Shellfish", "Nuts", "Gluten"),
                    several = true,
                ),
                Question(Topic.DATES, "When are you going? In June the whole city grills sardines in the street.", listOf("June", "Another summer month", "Another season")),
                Question(Topic.BUDGET, "Lunch at a tasca is cheap, a seafood dinner can cost five times as much. What suits you?", listOf("Tascas only", "A mix", "One big seafood dinner")),
            )

        private val BRIEF =
            Brief(
                stops = listOf(BriefStop(STOP, days = 2, cuisines = listOf("portuguese", "regional", "seafood"))),
            )

        private val DISHES =
            listOf(
                Dish(STOP, "bacalhau à brás", "Shredded salt cod with onion, thin fried potato and egg.", "lunch"),
                Dish(STOP, "arroz de marisco", "A loose, soupy rice with prawns, clams and crab.", "dinner"),
                Dish(STOP, "sardinhas assadas", "Sardines grilled over charcoal, eaten with bread and boiled potato.", "lunch"),
                Dish(STOP, "polvo à lagareiro", "Octopus roasted with garlic and a lot of olive oil.", "dinner"),
                Dish(STOP, "bifana", "A roll with thin pork that simmered in garlic and white wine.", "snack"),
                Dish(STOP, "pastel de nata", "A small custard tart with a burnt top, eaten warm with cinnamon.", "snack"),
                Dish(STOP, "ginjinha", "A sour cherry liqueur, drunk from a small glass at a counter.", "snack"),
            )

        private const val SUMMARY =
            "This is the sample trip. A script wrote it, not a model: the restaurants are real and come from the travel guide and the street map, " +
                "but the script took the ones with the most evidence and did not read what they cook. " +
                "Add the key of a model under Settings and the planner chooses each place for a dish, for your own trip."

        private const val NO_CHANGES = "The sample is a script and cannot change the route. Add the key of a model under Settings, and the planner follows what you ask for."
    }
}
