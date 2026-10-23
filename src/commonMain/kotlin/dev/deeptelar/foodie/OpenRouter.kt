package dev.deeptelar.foodie

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** OpenRouter: one key for the models of many providers, with the API of OpenAI. */
object OpenRouter {
    const val ADDRESS = "https://openrouter.ai/api/v1"

    /** Its list of models, which needs no key and which a page may read. */
    const val MODELS = "$ADDRESS/models"

    /** The models the README rates best for the planner, at the top of the list when OpenRouter has them. */
    val RECOMMENDED = listOf("google/gemma-4-31b-it")

    /** Whether [address] is OpenRouter's. */
    fun isAt(address: String): Boolean = "openrouter.ai" in address
}

/**
 * A model of OpenRouter, with what a million tokens of it cost, read and written; [price] is
 * `null` when OpenRouter does not say.
 */
data class OpenRouterModel(
    val id: String,
    val name: String,
    val price: Price?,
)

/**
 * The models of OpenRouter's list that can plan, by name: the agent calls tools, and writes text.
 * A batch model answers hours later, and is left out.
 */
fun openRouterModels(json: String): List<OpenRouterModel> =
    Json.parseToJsonElement(json).jsonObject["data"]?.jsonArray.orEmpty()
        .mapNotNull { it as? JsonObject }
        .filter { model ->
            val id = model.text("id")
            val output = (model["architecture"] as? JsonObject)?.get("output_modalities") as? JsonArray
            id.isNotEmpty() && !id.endsWith(":batch") && model.list("supported_parameters").contains("tools") && output?.map { (it as? JsonPrimitive)?.contentOrNull } == listOf("text")
        }
        .map { model ->
            val pricing = model["pricing"] as? JsonObject
            val input = pricing?.number("prompt")
            val output = pricing?.number("completion")
            OpenRouterModel(model.text("id"), model.text("name").ifEmpty { model.text("id") }, if (input != null && output != null) Price(input * 1_000_000, output * 1_000_000) else null)
        }
        .sortedBy { it.name.lowercase() }

private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject.list(key: String): List<String> = (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

// OpenRouter writes its prices as text, in dollars a token.
private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.contentOrNull?.toDoubleOrNull() ?: it.doubleOrNull }
