package dev.deeptelar.foodie

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextsTest {
    @Test
    fun `every language asks the same plain questions, with answers where English has them`() {
        Language.entries.map { it.texts }.forEach { texts ->
            Topic.entries.forEach { topic ->
                val english = English.question(topic)
                val question = texts.question(topic)
                assertEquals(topic, question.topic)
                assertEquals(english.alone.size, question.alone.size, "$texts $topic")
                assertTrue(question.options.containsAll(question.alone), "$texts $topic")
                assertEquals(english.options.isEmpty(), question.options.isEmpty(), "$texts $topic")
            }
        }
    }

    @Test
    fun `every language has the whole sample`() {
        Language.entries.map { it.texts }.forEach { texts ->
            assertEquals(English.sampleQuestions.map { it.topic }, texts.sampleQuestions.map { it.topic }, "$texts")
            assertEquals(English.sampleDishes.size, texts.sampleDishes.size, "$texts")
            assertEquals(English.howItWorksSteps.size, texts.howItWorksSteps.size, "$texts")
            assertTrue(texts.examples.isNotEmpty())
        }
    }

    @Test
    fun `a list is joined with the language's and, and a distance with its comma`() {
        assertEquals("on i quant de temps i gustos", listOf("on i quant de temps", "gustos").joinedWith("i"))
        assertEquals("a, b y c", listOf("a", "b", "c").joinedWith("y"))
        assertEquals("1,2 km", Catalan.distance(1240))
        assertEquals("1.2 km", English.distance(1240))
        assertEquals("450 m", Spanish.distance(440))
    }

    @Test
    fun `a Catalan trip gets plain questions in Catalan`() {
        val read = Trip("Lisboa", language = Language.CATALAN).read(Reading(Profile(), answered = listOf(Topic.WHERE)))

        assertEquals(listOf(Catalan.question(Topic.ALLERGIES), Catalan.question(Topic.PREGNANCY), Catalan.question(Topic.DIET)), read.questions)
    }
}
