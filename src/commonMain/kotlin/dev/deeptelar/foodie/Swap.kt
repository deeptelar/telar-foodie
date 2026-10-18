package dev.deeptelar.foodie

import dev.deeptelar.telar.agent.ChatMessage
import kotlinx.serialization.Serializable

/*
 * A traveller who likes the route but not one of its places asks to change that place. The page
 * asks why, with answers to tap, as a planner would: the dish, the kind of food, where it is, the
 * price. The questions are fixed here, so the traveller has them at once, without waiting for a
 * model. What the traveller says goes to the agent as one message for all the places changed
 * together, and the places they leave are not put on the route again.
 */

/** Why a traveller wants another place for a meal. [label] is the reason for the model; the traveller reads it from the [Texts]. */
@Serializable
enum class SwapReason(
    val label: String,
) {
    DISH("does not fancy this dish"),
    FOOD("wants another kind of food at this meal"),
    CLOSER("wants it closer to somewhere"),
    CHEAPER("wants something cheaper"),
    SPECIAL("wants something special"),
    TOURISTY("finds it touristy"),
    KNOWN("knows the place already"),
    CLOSED("it is closed or full that day"),
}

/** The traveller's wish to change the place of one meal of the route, and why. */
@Serializable
data class MealSwap(
    val day: Int,
    val time: MealTime,
    val reasons: List<SwapReason> = emptyList(),
    /** What the traveller would like instead: a dish or a kind of food. */
    val instead: String = "",
    /** The place the traveller wants the meal closer to. */
    val near: String = "",
    /** Anything else, in the traveller's words. */
    val note: String = "",
) {
    /** Whether the traveller said anything: a wish with no reason and no words says nothing to change. */
    val said: Boolean get() = reasons.isNotEmpty() || instead.isNotBlank() || near.isNotBlank() || note.isNotBlank()
}

/**
 * The places the agent offered for one meal: the first is the one it recommends, and it is on the
 * route until the traveller chooses another.
 */
@Serializable
data class Offer(
    val day: Int,
    val time: MealTime,
    val options: List<Meal>,
    /** Why the agent recommends the first option over the others, in one sentence. */
    val recommendation: String = "",
) {
    val recommended: Meal get() = options.first()
}

/** The offer for the meal of [day] and [time], if the agent made one. */
fun Trip.offerFor(day: Int, time: MealTime): Offer? = offers.firstOrNull { it.day == day && it.time == time }

/**
 * The trip with [option], one of the places the agent offered, on the route. Nothing runs: the
 * agent hears of it with the traveller's next words, and the walks of the day are measured again.
 */
fun Trip.picked(option: Meal): Trip {
    val changed = copy(route = route.filterNot { it.day == option.day && it.time == option.time } + option)
    val slot = "Day ${option.day} ${option.time.label.lowercase()}"
    val name = restaurant(option.restaurantId)?.name ?: option.restaurantId
    return changed.copy(route = ordered(changed), picks = picks.filterNot { it.startsWith("$slot:") } + "$slot: $name [${option.restaurantId}]")
}

/**
 * The trip as the agent's next turn starts: what the traveller chose of the offers goes before
 * their words, so that the agent knows the route it changes.
 */
internal fun Trip.withPicksTold(): Trip {
    if (picks.isEmpty()) return this
    val last = messages.lastOrNull() as? ChatMessage.User ?: return copy(picks = emptyList())
    val told = "Of the places you offered, I chose:\n" + picks.joinToString("\n") { "- $it" } + "\n\n" + last.text
    return copy(messages = messages.dropLast(1) + ChatMessage.User(told), picks = emptyList())
}

/**
 * The trip with the traveller's [swaps] for the agent, as their next message, and the places they
 * leave among the [Trip.rejected], so that no change brings them back.
 */
fun Trip.swapped(swaps: List<MealSwap>): Trip {
    val leaving = swaps.mapNotNull { swap -> route.firstOrNull { it.day == swap.day && it.time == swap.time }?.restaurantId }
    return copy(rejected = (rejected + leaving).distinct(), messages = messages + ChatMessage.User(swapPrompt(this, swaps)))
}
