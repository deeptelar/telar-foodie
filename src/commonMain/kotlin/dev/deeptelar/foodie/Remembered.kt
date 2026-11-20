package dev.deeptelar.foodie

import dev.deeptelar.telar.Store
import dev.deeptelar.telar.serialization.get
import dev.deeptelar.telar.serialization.put
import kotlinx.serialization.Serializable

/*
 * What the planner remembers of the traveller from one trip to the next: what they cannot eat and
 * what they like. Somebody with a nut allergy has it on every trip, and should not have to say it
 * every time. It is kept in a Store of Telar, which the page gives the run in GraphConfig.store,
 * and the planner writes it there when the traveller confirms the profile, never the model. What
 * belongs to one trip is not kept: the dates, who comes, the meals, the budget, where they sleep.
 * Neither is a pregnancy, which changes from one trip to the next.
 *
 * The next trip does not take any of it for granted: the start page shows it as the answers of its
 * questions, where the traveller sees it, changes it or forgets it, before anything is planned.
 */

/**
 * What the planner remembers of the traveller. `null` is a topic it knows nothing about; an empty
 * list of [allergies] is a traveller who said they have none, and an empty [diet] one who eats everything.
 */
@Serializable
data class Remembered(
    val allergies: List<Allergy>? = null,
    val diet: String? = null,
    val taste: String? = null,
) {
    /** Whether nothing is remembered. */
    val isEmpty: Boolean get() = allergies == null && diet == null && taste == null
}

/** Where in the store of the run the planner keeps what it remembers. */
internal val REMEMBERED: List<String> = listOf("food")

private const val ALLERGIES = "allergies"
private const val DIET = "diet"
private const val TASTE = "taste"

/**
 * What this trip tells about the traveller, from the profile they confirmed. A topic counts only
 * when the traveller said something about it: an empty profile says nothing, unless the traveller
 * answered the question, which is then "none" or "everything".
 */
fun Trip.remembered(): Remembered {
    fun saidAbout(topic: Topic) = answers.any { it.topic == topic && it.text.isNotBlank() }
    return Remembered(
        allergies = profile.allergies.takeIf { it.isNotEmpty() || saidAbout(Topic.ALLERGIES) },
        diet = profile.diet.trim().takeIf { it.isNotEmpty() || saidAbout(Topic.DIET) },
        taste = profile.taste.trim().takeIf { it.isNotEmpty() },
    )
}

/** What the planner remembers of the traveller, in this store. */
suspend fun Store.remembered(): Remembered =
    Remembered(
        allergies = get<List<Allergy>>(REMEMBERED, ALLERGIES),
        diet = get<String>(REMEMBERED, DIET),
        taste = get<String>(REMEMBERED, TASTE),
    )

/** Keeps what [remembered] knows in this store. A topic it knows nothing about keeps what an earlier trip told. */
suspend fun Store.remember(remembered: Remembered) {
    remembered.allergies?.let { put(REMEMBERED, ALLERGIES, it) }
    remembered.diet?.let { put(REMEMBERED, DIET, it) }
    remembered.taste?.let { put(REMEMBERED, TASTE, it) }
}

/** Forgets everything the planner remembers of the traveller. */
suspend fun Store.forgetTraveller() {
    listOf(ALLERGIES, DIET, TASTE).forEach { delete(REMEMBERED, it) }
}

/** An answer of the start page, before the traveller changes it: the answers tapped, and the traveller's own words. */
data class Prefilled(
    val question: Question,
    val picked: List<String> = emptyList(),
    val own: String = "",
)

/**
 * The answers of the start page's questions that what is remembered gives, in the words of
 * [texts]: "None" and "I eat everything" for a traveller who said so, and each part that is one of
 * the answers to tap, in any case, as that answer. The rest stays in the traveller's own words,
 * which the model reads. An allergy to avoid even as traces is always in words: no answer to tap says it.
 */
fun Remembered.prefilled(texts: Texts): List<Prefilled> =
    listOfNotNull(
        allergies?.let { allergies ->
            val question = texts.question(Topic.ALLERGIES)
            if (allergies.isEmpty()) {
                Prefilled(question, picked = question.alone)
            } else {
                val (tapped, said) = allergies.partition { !it.severe && question.option(it.food) != null }
                Prefilled(question, tapped.mapNotNull { question.option(it.food) }, said.joinToString("; ") { if (it.severe) texts.evenTraces(it.food) else it.food })
            }
        },
        diet?.let { diet ->
            val question = texts.question(Topic.DIET)
            if (diet.isEmpty()) Prefilled(question, picked = question.alone) else question.prefilled(diet)
        },
        taste?.let { texts.question(Topic.TASTE).prefilled(it) },
    )

/** The answer to tap that [words] are, in any case, or `null`. */
private fun Question.option(words: String): String? = options.firstOrNull { it.equals(words.trim(), ignoreCase = true) }

/** [text] as answers to this question: each part between commas that is an answer to tap, and the rest in words. */
private fun Question.prefilled(text: String): Prefilled {
    val parts = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val (tapped, said) = parts.partition { option(it) != null }
    // Of the answers that rule out the others, one is enough.
    val picked = tapped.mapNotNull { option(it) }.distinct().let { all -> all.firstOrNull { it in alone }?.let(::listOf) ?: all }
    return Prefilled(this, picked, said.joinToString(", "))
}
