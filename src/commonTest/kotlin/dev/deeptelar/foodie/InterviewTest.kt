package dev.deeptelar.foodie

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InterviewTest {
    private val naples = Question(Topic.TASTE, "Naples has tripe and raw sea urchins at the port. Should the route have things like that?", listOf(" Yes ", "Yes", "Only the classics", ""))

    @Test
    fun `the next round asks the first open topics, in the model's words where it wrote some`() {
        val next = nextQuestions(covered = setOf(Topic.WHERE, Topic.ALLERGIES, Topic.DIET, Topic.DATES, Topic.PARTY, Topic.STAY, Topic.MEALS), wrote = listOf(naples))

        assertEquals(listOf(Topic.BUDGET, Topic.TASTE, Topic.DRINKS), next.map { it.topic })
        assertEquals(Topic.BUDGET.question, next[0])
        // The model's answers are tidied: no blanks, no repeats.
        assertEquals(listOf("Yes", "Only the classics"), next[1].options)
    }

    @Test
    fun `a question the model wrote for a topic that is not next waits for its round`() {
        val next = nextQuestions(covered = emptySet(), wrote = listOf(naples))

        assertEquals(listOf(Topic.WHERE, Topic.ALLERGIES, Topic.DIET), next.map { it.topic })
        assertEquals(listOf(Topic.WHERE.question, Topic.ALLERGIES.question, Topic.DIET.question), next)
    }

    @Test
    fun `what the request answers is not asked, except allergies`() {
        val trip = Trip("Four days in Naples with my partner. I'm allergic to peanuts.")

        val read = trip.read(Reading(Profile(allergies = listOf(Allergy("peanuts"))), answered = listOf(Topic.WHERE, Topic.PARTY, Topic.ALLERGIES)))

        assertEquals(listOf(Topic.ALLERGIES, Topic.DIET, Topic.DATES), read.questions.map { it.topic })
        assertEquals(setOf(Topic.WHERE, Topic.PARTY, Topic.ALLERGIES, Topic.DIET, Topic.DATES), read.interview.covered)
        assertEquals(1, read.interview.rounds)
        assertEquals(0, read.interview.read)
        assertEquals(listOf(Allergy("peanuts")), read.profile.allergies)
    }

    @Test
    fun `answers make the profile out of date until the model read them`() {
        val asked = Trip("Naples").read(Reading(Profile()))
        assertTrue(asked.profileIsCurrent)

        val answered = asked.answered(asked.questions.map { Answer(it.topic, it.text, "") })

        assertEquals(emptyList(), answered.questions)
        assertEquals(3, answered.answers.size)
        assertTrue(!answered.profileIsCurrent)
    }

    @Test
    fun `with every topic covered the traveller checks the profile`() {
        val trip = Trip("Naples", interview = Interview(covered = Topic.entries.toSet(), rounds = 3))

        val read = trip.read(Reading(Profile(diet = "vegetarian")))

        assertEquals(emptyList(), read.questions)
        assertEquals(InterviewStage.CHECKING, read.interview.stage)
    }

    @Test
    fun `the interview stops asking after its last round`() {
        val trip = Trip("Naples", interview = Interview(rounds = MAX_ROUNDS))

        assertEquals(InterviewStage.CHECKING, trip.read(Reading(Profile())).interview.stage)
    }

    @Test
    fun `a traveller who wants to plan now gets no more questions, and the answers are still read`() {
        val asked = Trip("Naples").read(Reading(Profile()))
        val now = asked.planNow(listOf(Answer(Topic.WHERE, "Where?", "Naples, four days")))

        val read = now.read(Reading(Profile(), questions = listOf(naples)))

        assertEquals(emptyList(), read.questions)
        assertEquals(InterviewStage.DONE, read.interview.stage)
        assertTrue(read.profileIsCurrent)
    }

    @Test
    fun `a correction is read again and the profile is shown again`() {
        val checking = Trip("Naples", interview = Interview(covered = Topic.entries.toSet(), read = 0, stage = InterviewStage.CHECKING))

        val corrected = checking.corrected("We are three, not two.")

        assertEquals(Answer(null, "Is there anything to correct?", "We are three, not two."), corrected.answers.last())
        assertEquals(InterviewStage.ASKING, corrected.interview.stage)
        assertEquals(InterviewStage.CHECKING, corrected.read(Reading(Profile(party = "three adults"))).interview.stage)
        assertEquals(InterviewStage.DONE, checking.confirmed().interview.stage)
    }
}
