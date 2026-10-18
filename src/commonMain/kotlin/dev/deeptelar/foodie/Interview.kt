package dev.deeptelar.foodie

import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.Description
import kotlinx.serialization.Serializable

/*
 * The interview before a trip is planned. A planner who knows food does not hand out a form: they
 * ask what the trip needs, skip what the traveller already said, and ask what is risky first. The
 * topics and their order are fixed here, so no model can leave out the questions about allergies
 * and pregnancy.
 * The model reads the traveller's words and writes the questions for the places of the trip.
 */

/**
 * What the planner asks about, in the order it asks. [label] is the topic's name for a model; the
 * traveller reads it, and its plain question, from the [Texts] of the trip's language.
 */
@Serializable
enum class Topic(
    val label: String,
) {
    WHERE("Where and how long"),
    ALLERGIES("Allergies"),
    PREGNANCY("Pregnancy"),
    DIET("Diet"),
    DATES("When"),
    PARTY("Who is coming"),
    STAY("Where you stay"),
    MEALS("Which meals"),
    BUDGET("Budget"),
    TASTE("Taste"),
    DRINKS("Drinks"),
}

/** A question to the traveller. The descriptions are what the model sees. */
@Serializable
data class Question(
    @Description("The topic the question is about.")
    val topic: Topic,
    @Description(
        "The question, in one or two short sentences, as a planner who knows the food of these places asks it. " +
            "Name a dish or a habit of the place when it helps the traveller answer.",
    )
    val text: String,
    @Description(
        "Two to five short answers the traveller can tap, each a few words. The traveller can tap several of them together. " +
            "Empty when only the traveller's own words can answer, such as the name of a hotel.",
    )
    val options: List<String> = emptyList(),
    @Description(
        "The answers among the options that rule out all the others, such as \"None\" or \"I eat everything\", " +
            "or each step of a scale such as a budget. Empty when any answers can be true together.",
    )
    val alone: List<String> = emptyList(),
)

/** What the traveller answered to a question. An empty [text] is a question the traveller skipped. A correction of the profile has no [topic]. */
@Serializable
data class Answer(
    val topic: Topic?,
    val question: String,
    val text: String,
)

/** What the planner knows about the traveller, read from the request and the answers. The descriptions are what the model sees. */
@Serializable
data class Profile(
    @Description("Each food the traveller is allergic to. Empty if the traveller said none or did not say.")
    val allergies: List<Allergy> = emptyList(),
    @Description("Whether someone on the trip is pregnant. False if the traveller said no or did not say.")
    val pregnant: Boolean = false,
    @Description("What the traveller does not eat, for example \"vegetarian\", \"halal\" or \"no pork\". Empty if the traveller eats everything or did not say.")
    val diet: String = "",
    @Description("When the trip is: dates, a month or a season. Empty if not said.")
    val season: String = "",
    @Description("Who eats with the traveller, for example \"two adults\" or \"a family with children of 4 and 9\". Empty if not said.")
    val party: String = "",
    @Description("The meals the traveller wants planned. Empty if not said.")
    val meals: List<MealTime> = emptyList(),
    @Description("What the traveller wants to spend: \"low\", \"medium\" or \"high\". \"medium\" if not said.")
    val budget: String = "medium",
    @Description("Whether the traveller wants one special, more expensive meal on the trip.")
    val splurge: Boolean = false,
    @Description("How adventurous the traveller is with food, and what they do not like, for example \"curious, but no offal and nothing very spicy\". Empty if not said.")
    val taste: String = "",
    @Description("What the traveller likes to drink, for example \"natural wine\" or \"no alcohol\". Empty if not said.")
    val drinks: String = "",
    @Description(
        "Anything else the traveller wishes for, for example \"markets\" or \"no long walks\". " +
            "Not the places, the days or where the traveller stays: the planner has those already. Empty if nothing else was said.",
    )
    val wishes: String = "",
)

@Serializable
data class Allergy(
    @Description("The food, for example \"peanuts\" or \"shellfish\".")
    val food: String,
    @Description("Whether the traveller must avoid even traces of it, as with a severe allergy, rather than only the food itself.")
    val severe: Boolean = false,
)

/** The model's typed answer in the interview: what it read, and the questions it would ask next. */
@Serializable
data class Reading(
    @Description("Everything the traveller said so far, in the request and in all the answers.")
    val profile: Profile,
    @Description("The topics that the traveller's words already answer, so that nobody needs to ask about them.")
    val answered: List<Topic> = emptyList(),
    @Description("A question for each open topic that the traveller's words do not answer yet, in the order of the list, at most three.")
    val questions: List<Question> = emptyList(),
)

/** Where the interview of a trip is. */
@Serializable
data class Interview(
    /** The topics that were asked about, or that the traveller's words answered before anybody asked. */
    val covered: Set<Topic> = emptySet(),
    /** How many rounds of questions the traveller was shown. */
    val rounds: Int = 0,
    /** How many of the trip's answers the profile was read from. -1 before the first reading. */
    val read: Int = -1,
    /** Whether the traveller wanted no more questions. */
    val enough: Boolean = false,
    val stage: InterviewStage = InterviewStage.ASKING,
)

@Serializable
enum class InterviewStage {
    /** The planner asks: a round of questions is open, or the next one is to be written. */
    ASKING,

    /** No topic is open. The traveller reads what the planner understood, and confirms or corrects it. */
    CHECKING,

    /** The traveller confirmed, or wanted to plan right away. */
    DONE,
}

/** The topics that are asked about even when the request mentions them: a planner makes sure of what is a matter of health. */
internal val ALWAYS_ASKED: Set<Topic> = setOf(Topic.ALLERGIES, Topic.PREGNANCY)

/** How many questions a round asks at most: few enough for one screen of a phone. */
internal const val PER_ROUND = 3

/** How many rounds of questions the interview asks at most. */
internal const val MAX_ROUNDS = 4

/**
 * The questions of the next round: the first topics in [Topic] order that are not [covered], each
 * in the words the model [wrote] for it, or in its plain words of [texts] when the model wrote none.
 */
internal fun nextQuestions(covered: Set<Topic>, wrote: List<Question>, texts: Texts): List<Question> =
    Topic.entries.filter { it !in covered }.take(PER_ROUND).map { topic ->
        val plain = texts.question(topic)
        val written = wrote.firstOrNull { it.topic == topic && it.text.isNotBlank() }?.tidied() ?: return@map plain
        // A model writes the answers of these as the values of the profile, such as LUNCH or low: they keep the plain ones.
        if (topic in PLAIN_ANSWERS) written.copy(options = plain.options, alone = plain.alone) else written
    }

/** The topics whose answers are always the plain ones of [Texts], in the model's question. */
private val PLAIN_ANSWERS = setOf(Topic.PREGNANCY, Topic.MEALS, Topic.BUDGET)

private fun Question.tidied(): Question {
    val options = options.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(6)
    return copy(text = text.trim(), options = options, alone = alone.map { it.trim() }.filter { it in options })
}

/** Whether the profile was read from every answer the traveller gave. */
internal val Trip.profileIsCurrent: Boolean get() = interview.read == answers.size

/**
 * The trip after the model read the traveller's words into [reading]: the profile is the new one,
 * what the words answer is covered, and the next round of questions is open, if a topic is left.
 * Allergies and pregnancy are asked about even when the request mentions them: a planner makes sure of them.
 * With no topic left, the interview waits for the traveller to check the profile.
 */
internal fun Trip.read(reading: Reading): Trip {
    val covered = interview.covered + reading.answered.filter { it !in ALWAYS_ASKED }
    val read = copy(profile = reading.profile, interview = interview.copy(covered = covered, read = answers.size))
    if (interview.stage == InterviewStage.DONE) return read
    val next = if (interview.rounds < MAX_ROUNDS && !interview.enough) nextQuestions(covered, reading.questions, language.texts) else emptyList()
    return if (next.isEmpty()) {
        read.copy(interview = read.interview.copy(stage = InterviewStage.CHECKING))
    } else {
        read.copy(questions = next, interview = read.interview.copy(covered = covered + next.map { it.topic }, rounds = interview.rounds + 1))
    }
}

/** The trip with the traveller's [answers] to the open questions. The planner reads them next. */
fun Trip.answered(answers: List<Answer>): Trip = copy(questions = emptyList(), answers = this.answers + answers)

/** The trip with the [answers] the traveller gave so far, and no more questions: the planner reads them and shows the profile to check. */
fun Trip.planNow(answers: List<Answer>): Trip = answered(answers).copy(interview = interview.copy(enough = true))

/** The profile as the traveller confirmed it. */
fun Trip.confirmed(): Trip = copy(interview = interview.copy(stage = InterviewStage.DONE))

/** The trip with a [correction] of the profile in the traveller's words. The planner reads it and shows the profile again. */
fun Trip.corrected(correction: String): Trip =
    copy(answers = answers + Answer(null, CORRECTION, correction), interview = interview.copy(stage = InterviewStage.ASKING))

/**
 * The trip with the [profile] the traveller edited themselves, after erasing everything first when
 * [erase]. The edit is kept as an answer too, in the words a model reads, so that a correction in
 * words later starts from it. The profile needs no reading: it is what the traveller wrote.
 */
fun Trip.edited(profile: Profile, erase: Boolean = false): Trip {
    val start = if (erase) erased() else this
    val profile = profile.tidied()
    if (profile == start.profile) return start
    val answers = start.answers + Answer(null, EDITED, profileWords(profile))
    return start.copy(profile = profile, answers = answers, interview = start.interview.copy(read = answers.size))
}

/**
 * The trip without what the traveller told about themselves: the profile is empty, and the answers
 * are gone. One answer says so, because the request stays: the places and days are in it.
 */
fun Trip.erased(): Trip {
    val answers = listOf(Answer(null, ERASED, "Take nothing about the traveller from the words above, only the places and the days."))
    return copy(profile = Profile(), answers = answers, interview = interview.copy(read = answers.size))
}

/**
 * The trip with a [profile] the traveller changed when the route was already planned. The agent is
 * told as the traveller's next message, and the graph loops back to it to change the route. After
 * [erase], the agent plans the route again from the start: its conversation and the route hold
 * what was erased.
 */
fun Trip.replannedFor(profile: Profile, erase: Boolean = false): Trip {
    val changed = edited(profile, erase)
    if (erase) return changed.copy(messages = emptyList(), route = emptyList())
    return changed.copy(messages = messages + ChatMessage.User(profileChangePrompt(changed)))
}

/**
 * The trip to be planned again from the start, for another route: the request, the answers and the
 * profile stay, and the traveller checks the profile first, where one or two things can change.
 * Everything the planner found and chose is gone, so the sources are asked again.
 */
fun Trip.plannedAgain(): Trip =
    Trip(request, language, answers = answers, profile = profile, interview = interview.copy(stage = InterviewStage.CHECKING))

/** The profile without the empty lines of an editor: an allergy with no food, and spaces around words. */
private fun Profile.tidied(): Profile =
    copy(
        allergies = allergies.map { it.copy(food = it.food.trim()) }.filter { it.food.isNotEmpty() },
        diet = diet.trim(),
        season = season.trim(),
        party = party.trim(),
        meals = meals.distinct().sorted(),
        taste = taste.trim(),
        drinks = drinks.trim(),
        wishes = wishes.trim(),
    )

/** What an answer without a topic is, by its question: the page shows each in the traveller's own words. */
internal enum class Aside { CORRECTION, EDITED, ERASED, ADDED }

internal val Answer.aside: Aside?
    get() =
        when {
            topic != null -> null
            question == CORRECTION -> Aside.CORRECTION
            question == EDITED -> Aside.EDITED
            question == ERASED -> Aside.ERASED
            question == ADDED -> Aside.ADDED
            else -> null
        }

private const val CORRECTION = "Is there anything to correct?"
private const val EDITED = "The traveller corrected the profile by hand. It is now:"
private const val ERASED = "The traveller erased what the planner knew about them."
