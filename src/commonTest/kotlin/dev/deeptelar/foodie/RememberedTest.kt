package dev.deeptelar.foodie

import dev.deeptelar.telar.MemoryStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RememberedTest {
    private val nuts = Answer(Topic.ALLERGIES, "Any allergy?", "Nuts")

    @Test
    fun `a trip tells what the traveller cannot eat and what they like`() {
        val trip =
            Trip(
                "Three days in Palma",
                answers = listOf(nuts),
                profile = Profile(allergies = listOf(Allergy("nuts", severe = true)), pregnant = true, diet = " piscivegetarian ", taste = "curious", party = "two adults", budget = "high"),
            )

        assertEquals(Remembered(allergies = listOf(Allergy("nuts", severe = true)), diet = "piscivegetarian", taste = "curious"), trip.remembered())
    }

    @Test
    fun `an empty profile is a no only when the traveller answered the question`() {
        val answered = Trip("A day in Porto", answers = listOf(Answer(Topic.ALLERGIES, "Any allergy?", "None"), Answer(Topic.DIET, "Anything you do not eat?", "I eat everything")))
        val silent = Trip("A day in Porto", answers = listOf(Answer(Topic.DIET, "Anything you do not eat?", "")))

        assertEquals(Remembered(allergies = emptyList(), diet = ""), answered.remembered())
        assertTrue(silent.remembered().isEmpty)
    }

    @Test
    fun `a topic the trip says nothing about keeps what an earlier trip told`() =
        runTest {
            val store = MemoryStore()

            store.remember(Remembered(allergies = listOf(Allergy("shellfish")), diet = "vegetarian", taste = "the classics"))
            store.remember(Remembered(diet = "vegan"))

            assertEquals(Remembered(allergies = listOf(Allergy("shellfish")), diet = "vegan", taste = "the classics"), store.remembered())
        }

    @Test
    fun `forgetting leaves nothing remembered`() =
        runTest {
            val store = MemoryStore()
            store.remember(Remembered(allergies = emptyList(), diet = "halal", taste = "curious"))

            store.forgetTraveller()

            assertTrue(store.remembered().isEmpty)
            assertTrue(store.list(REMEMBERED).isEmpty())
        }

    @Test
    fun `the start page gets the answers in its own language`() {
        val remembered = Remembered(allergies = emptyList(), diet = "", taste = "curious, no offal")

        val catalan = remembered.prefilled(Catalan)

        assertEquals(listOf(Topic.ALLERGIES, Topic.DIET, Topic.TASTE), catalan.map { it.question.topic })
        assertEquals(listOf(listOf("Cap"), listOf("Menjo de tot"), emptyList()), catalan.map { it.picked })
        assertEquals("curious, no offal", catalan[2].own)
    }

    @Test
    fun `an allergy that is an answer to tap is tapped, and one to avoid even as traces is in words`() {
        val remembered = Remembered(allergies = listOf(Allergy("peanuts", severe = true), Allergy("shellfish"), Allergy("kiwi")))

        assertEquals(listOf(Prefilled(English.question(Topic.ALLERGIES), picked = listOf("Shellfish"), own = "peanuts, even traces; kiwi")), remembered.prefilled(English))
    }

    @Test
    fun `the parts of a diet or a taste that are answers to tap are tapped`() {
        val remembered = Remembered(diet = "vegetarian, no pork, no mushrooms", taste = "curious, but nothing very spicy")

        val (diet, taste) = remembered.prefilled(English)

        assertEquals(listOf("Vegetarian", "No pork") to "no mushrooms", diet.picked to diet.own)
        assertEquals(listOf("Curious") to "but nothing very spicy", taste.picked to taste.own)
    }
}
