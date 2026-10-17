package dev.deeptelar.foodie

import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.pendingToolCalls

/*
 * The traveller can pause a run to add something the planner should know, or to send it another
 * way. Until the agent plans the route, what the planner read from the trip is read again with it:
 * the interview reads it with the answers, and the places, the sources and the dishes are found
 * again for it. Once the agent plans, it is told as the traveller's message, and goes on from the
 * route it has.
 */

/** Whether the agent plans the route, or is about to: what was read from the trip is done. */
val Trip.planning: Boolean get() = messages.isNotEmpty() || dishes.isNotEmpty()

/**
 * The trip with [text] the traveller added before the route is planned, as a run starts it again:
 * the words join the answers, and everything read after the interview is gone, to be read again.
 */
fun Trip.toldMore(text: String): Trip =
    copy(
        answers = answers + Answer(null, ADDED, text),
        questions = emptyList(),
        brief = null,
        stops = emptyList(),
        guideNotes = emptyMap(),
        dishes = emptyList(),
        restaurants = emptyList(),
        notes = emptyList(),
    )

/**
 * The trip with [text] for the agent that plans the route, as the traveller's next message. Tool
 * calls that the agent asked for and that did not run are dropped: the traveller wants something
 * else, and a message cannot come between the calls and their results. The agent gets a new budget
 * for what it was told.
 */
fun Trip.redirected(text: String): Trip {
    val kept = if (messages.pendingToolCalls().isEmpty()) messages else messages.take(messages.indexOfLast { it is ChatMessage.Assistant })
    // With nothing kept, the agent has not seen the trip yet: the first message has the trip, and then the words.
    val message = if (kept.isEmpty()) planPrompt(this) + "\n" + asidePrompt(text) else asidePrompt(text)
    return copy(messages = kept + ChatMessage.User(message), turnStart = tokens.total)
}

internal const val ADDED = "The traveller added, while the planner worked:"
