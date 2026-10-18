package dev.deeptelar.foodie

import dev.deeptelar.telar.agent.TokenUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CostTest {
    @Test
    fun `a plan costs its tokens at the model's price and its Google searches`() {
        // The seven days in Sóller, Binissalem and Pollença, with two changes, planned by Claude Opus 5.5.
        val trip = Trip("", tokens = TokenUsage(inputTokens = 290_484, outputTokens = 16_164), googleSearches = 18)

        val cost = trip.cost("claude-opus-5-5")

        assertEquals("1.49", amount(cost.model!!, '.'))
        assertEquals("0.63", amount(cost.google, '.'))
        assertEquals("$2.12", English.money(cost.total!!))
        assertEquals("2,12 US$", Catalan.money(cost.total!!))
    }

    @Test
    fun `a model without a known price has only the cost of its searches`() {
        val cost = Trip("", tokens = TokenUsage(1000, 100), googleSearches = 2).cost("gemini-flash-latest")

        assertNull(cost.model)
        assertNull(cost.total)
        assertEquals("0.07", amount(cost.google, '.'))
    }

    @Test
    fun `an amount has two decimals`() {
        assertEquals("0.05", amount(0.049, '.'))
        assertEquals("12,30", amount(12.3, ','))
    }
}
