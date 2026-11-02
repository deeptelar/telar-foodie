package dev.deeptelar.foodie

import dev.deeptelar.telar.agent.TokenUsage
import kotlin.math.roundToLong

/*
 * What a plan cost, at the list prices of the providers: the tokens of the model and the searches
 * of Google Places. It is what an app that pays for its travellers' plans would pay, and the
 * traveller with their own keys sees what their keys were billed.
 *
 * The prices are those of 2026-11-02. Tokens that a provider reused or kept for later have prices of
 * their own: the agent's model keeps what it read, and OpenAI does that by itself.
 */

/**
 * What a million tokens of a model cost, read and written, in US dollars. [cacheRead] is the price of
 * a token the provider reused, and [cacheWrite] of one it kept for later: both cost as much as any
 * other token read when the provider has no such prices.
 */
data class Price(
    val input: Double,
    val output: Double,
    val cacheRead: Double = input,
    val cacheWrite: Double = input,
)

/**
 * The list prices of the models of the settings, by the model's name. A model that is not here,
 * such as one of Ollama or Gemini, has no price, and only its tokens are shown.
 */
val MODEL_PRICES: Map<String, Price> =
    mapOf(
        // What Claude keeps costs its price for five minutes, the time the agent's model asks for.
        "claude-opus-5-5" to Price(4.0, 20.0, cacheRead = 0.20, cacheWrite = 5.0),
        "claude-sonnet-5-5" to Price(2.0, 10.0, cacheRead = 0.10, cacheWrite = 2.5),
        // Up to 100,000 tokens a request, which every request of a plan stays under.
        "claude-haiku-5-5" to Price(0.10, 0.50, cacheRead = 0.01, cacheWrite = 0.125),
        "gpt-5" to Price(1.25, 10.0, cacheRead = 0.125),
        "gpt-5-mini" to Price(0.25, 2.0, cacheRead = 0.025),
        "gpt-5.5" to Price(5.0, 30.0, cacheRead = 0.50),
        "gpt-5.4" to Price(2.5, 15.0, cacheRead = 0.25),
        "gpt-5.4-mini" to Price(0.75, 4.5, cacheRead = 0.075),
    )

/**
 * What one search of Google Places costs, in US dollars: Text Search Enterprise, since the search
 * asks for ratings, websites and phone numbers. Google does not bill the first 1,000 of a month.
 */
const val GOOGLE_SEARCH_PRICE: Double = 35.0 / 1000

/** How many searches of Google Places a month Google does not bill. */
const val GOOGLE_FREE_SEARCHES: Int = 1000

/** What a plan cost: [model] is `null` when the price of the model is not known. */
data class Cost(
    val model: Double?,
    val google: Double,
) {
    /** The whole cost, or `null` when the price of the model is not known. */
    val total: Double? get() = model?.let { it + google }
}

/** What [tokens] of the model [modelName] cost, or `null` when its price is not known. */
fun TokenUsage.costWith(modelName: String): Double? =
    MODEL_PRICES[modelName.trim()]?.let { price ->
        // `inputTokens` holds every token read, those reused and kept among them.
        val fullPrice = inputTokens - cacheReadTokens - cacheWriteTokens
        (fullPrice * price.input + cacheReadTokens * price.cacheRead + cacheWriteTokens * price.cacheWrite + outputTokens * price.output) / 1_000_000
    }

/** What this trip cost so far, when the model [modelName] planned it. */
fun Trip.cost(modelName: String): Cost = Cost(tokens.costWith(modelName), googleSearches * GOOGLE_SEARCH_PRICE)

/** [dollars] with two decimals after [separator], such as "0.42", for a text to put its currency around. */
fun amount(dollars: Double, separator: Char): String {
    val cents = (dollars * 100).roundToLong()
    return "${cents / 100}$separator" + (cents % 100).toString().padStart(2, '0')
}
