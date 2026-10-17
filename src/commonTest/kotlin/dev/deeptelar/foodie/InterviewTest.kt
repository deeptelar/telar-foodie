package dev.deeptelar.foodie

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InterviewTest {
    private val naples = Question(Topic.TASTE, "Naples has tripe and raw sea urchins at the port. Should the route have things like that?", listOf(" Yes ", "Yes", "Only the classics", ""))

    @Test
    fun `the next round asks the first open topics, in the model's words where it wrote some`() {
        val next = nextQuestions(covered = setOf(Topic.WHERE, Topic.ALLERGIES, Topic.DIET, Topic.DATES, Topic.PARTY, Topic.STAY, Topic.MEALS), wrote = listOf(naples), texts = English)

        assertEquals(listOf(Topic.PREGNANCY, Topic.BUDGET, Topic.TASTE), next.map { it.topic })
        assertEquals(English.question(Topic.BUDGET), next[1])
        // The model's answers are tidied: no blanks, no repeats.
        assertEquals(listOf("Yes", "Only the classics"), next[2].options)
    }

    @Test
    fun `a question the model wrote for a topic that is not next waits for its round`() {
        val next = nextQuestions(covered = emptySet(), wrote = listOf(naples), texts = English)

        assertEquals(listOf(Topic.WHERE, Topic.ALLERGIES, Topic.PREGNANCY), next.map { it.topic })
        assertEquals(listOf(English.question(Topic.WHERE), English.question(Topic.ALLERGIES), English.question(Topic.PREGNANCY)), next)
    }

    @Test
    fun `what the request answers is not asked, except allergies and pregnancy`() {
        val trip = Trip("Four days in Naples with my partner, who is pregnant. I'm allergic to peanuts.")

        val read =
            trip.read(Reading(Profile(allergies = listOf(Allergy("peanuts")), pregnant = true), answered = listOf(Topic.WHERE, Topic.PARTY, Topic.ALLERGIES, Topic.PREGNANCY)))

        assertEquals(listOf(Topic.ALLERGIES, Topic.PREGNANCY, Topic.DIET), read.questions.map { it.topic })
        assertEquals(setOf(Topic.WHERE, Topic.PARTY, Topic.ALLERGIES, Topic.PREGNANCY, Topic.DIET), read.interview.covered)
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
    fun `a traveller who wants to plan now gets no more questions, and checks what was read`() {
        val asked = Trip("Naples").read(Reading(Profile()))
        val now = asked.planNow(listOf(Answer(Topic.WHERE, "Where?", "Naples, four days")))

        val read = now.read(Reading(Profile(), questions = listOf(naples)))

        assertEquals(emptyList(), read.questions)
        assertEquals(InterviewStage.CHECKING, read.interview.stage)
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

    @Test
    fun `a profile the traveller edits needs no reading, and a model that reads later sees the edit`() {
        val checking = Trip("Naples", interview = Interview(covered = Topic.entries.toSet(), read = 1, stage = InterviewStage.CHECKING), profile = Profile(diet = "no pork"))
            .copy(answers = listOf(Answer(Topic.DIET, "Anything you do not eat?", "No pork")))

        val edited = checking.edited(Profile(allergies = listOf(Allergy("peanuts", severe = true)), meals = listOf(MealTime.DINNER)))

        assertEquals(listOf(Allergy("peanuts", severe = true)), edited.profile.allergies)
        assertEquals("", edited.profile.diet)
        assertTrue(edited.profileIsCurrent)
        assertEquals("Allergies: peanuts, even traces; Diet: none said; Budget: medium; Meals: dinner", edited.answers.last().text)
        // Nothing changed, nothing to keep.
        assertEquals(checking, checking.edited(checking.profile))
    }

    @Test
    fun `erasing leaves the request and nothing the traveller said about themselves`() {
        val told = Trip("Naples", profile = Profile(pregnant = true, diet = "vegetarian"), interview = Interview(read = 2, stage = InterviewStage.CHECKING))
            .copy(answers = listOf(Answer(Topic.PREGNANCY, "Is anyone pregnant?", "Yes"), Answer(Topic.DIET, "Anything you do not eat?", "Meat")))

        val erased = told.erased()

        assertEquals(Profile(), erased.profile)
        assertEquals("Naples", erased.request)
        assertEquals(listOf(null), erased.answers.map { it.topic })
        assertTrue(erased.profileIsCurrent)
        // An edit after erasing starts from nothing.
        assertEquals(Profile(drinks = "wine"), told.edited(Profile(drinks = "wine"), erase = true).profile)
        assertEquals(2, told.edited(Profile(drinks = "wine"), erase = true).answers.size)
    }

    @Test
    fun `an answer that rules out the others must be one of the answers`() {
        val wrote = Question(Topic.DIET, "Anything you do not eat?", listOf("I eat everything", "No pork"), alone = listOf(" I eat everything", "Nothing at all"))

        val next = nextQuestions(covered = setOf(Topic.WHERE, Topic.ALLERGIES, Topic.PREGNANCY), wrote = listOf(wrote), texts = English)

        assertEquals(listOf("I eat everything"), next.first().alone)
    }

    @Test
    fun `the meals and the budget are answered in plain words, whatever the model wrote`() {
        val wrote = Question(Topic.MEALS, "Which meals? In Naples breakfast is a coffee and a sfogliatella.", listOf("BREAKFAST", "LUNCH", "SNACK", "DINNER"))

        val next = nextQuestions(covered = Topic.entries.toSet() - Topic.MEALS, wrote = listOf(wrote), texts = Catalan)

        assertEquals("Which meals? In Naples breakfast is a coffee and a sfogliatella.", next.single().text)
        assertEquals(listOf("Esmorzar", "Dinar", "Berenar", "Sopar"), next.single().options)
    }
}
