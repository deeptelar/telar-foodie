package dev.deeptelar.foodie

import dev.deeptelar.telar.Checkpoint
import dev.deeptelar.telar.agent.ChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SavedPlanTest {
    private val trip =
        Trip(
            "Two days in Porto",
            language = Language.CATALAN,
            profile = Profile(allergies = listOf(Allergy("peanuts", severe = true))),
            stops = listOf(Stop("Porto, Portugal", 2, 1, 41.1496, -8.6109)),
            restaurants = listOf(Restaurant(id = "r1", stop = "Porto, Portugal", name = "Café Santiago", lat = 41.146, lon = -8.606, sources = setOf(Source.MAP))),
            route = listOf(Meal(1, MealTime.LUNCH, "r1", "francesinha", "Known for it.")),
            messages = listOf(ChatMessage.User("Plan the meals of this trip."), ChatMessage.Assistant("Done.")),
        )

    @Test
    fun `a plan in a file is the checkpoint it was saved from, with the trip readable in it`() {
        val checkpoint = Checkpoint(trip, listOf(Nodes.REVIEW), step = 9, interruptedBefore = true, effects = mapOf("dishes 1" to "{}"))

        val file = checkpoint.toFile(model = "claude-sonnet-5-5", saved = "2026-10-17T18:00:00.000Z")

        assertEquals(checkpoint, planFromFile(file))
        assertTrue("\"model\": \"claude-sonnet-5-5\"" in file, file)
        assertTrue("\"name\": \"Café Santiago\"" in file, file)
    }

    @Test
    fun `a file that is not a plan, or one of a newer page, is refused with the reason`() {
        assertFalse(assertFailsWith<NotAPlanException> { planFromFile("not json") }.newer)
        assertFalse(assertFailsWith<NotAPlanException> { planFromFile("{}") }.newer)
        val other = Checkpoint(trip, emptyList()).toFile("", "").replace("\"Foodie Route\"", "\"Another\"")
        assertFalse(assertFailsWith<NotAPlanException> { planFromFile(other) }.newer)
        val newer = Checkpoint(trip, emptyList()).toFile("", "").replace("\"format\": 1", "\"format\": 2")
        assertTrue(assertFailsWith<NotAPlanException> { planFromFile(newer) }.newer)
    }

    @Test
    fun `a file is named after the places of the trip and the day`() {
        assertEquals("foodie-route-porto-2026-10-17.json", fileName(trip, "2026-10-17"))
        assertEquals("foodie-route-trip-2026-10-17.json", fileName(Trip(""), "2026-10-17"))
    }
}
