package dev.deeptelar.foodie

import kotlin.test.Test
import kotlin.test.assertEquals

class WhileYouWaitTest {
    @Test
    fun `the stops take turns and the dishes with a story come first`() {
        val trip =
            Trip(
                "Porto and Lisbon",
                dishes =
                    listOf(
                        Dish("Porto", "tripas", "Tripe with beans."),
                        Dish("Porto", "francesinha", "A sandwich under cheese and sauce.", story = "Made after the croque-monsieur."),
                        Dish("Lisbon", "bifana", "A pork roll."),
                        Dish("Porto", "caldo verde", "A kale soup."),
                    ),
            )

        assertEquals(listOf("francesinha", "bifana", "tripas", "caldo verde"), trip.whileYouWait().map { it.name })
    }

    @Test
    fun `a trip without dishes has nothing to show`() {
        assertEquals(emptyList(), Trip("Lisbon").whileYouWait())
    }
}
