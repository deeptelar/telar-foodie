package dev.deeptelar.foodie

import dev.deeptelar.telar.Checkpoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/*
 * A plan in a file, so that a traveller can keep it, open it again in another browser, or share it
 * to show how the planner chose. The file is the run's checkpoint with the trip as readable JSON:
 * opening it puts the checkpoint back, and the run goes on from where it was saved.
 * The keys are in the settings, never in the trip, so a file has none of them.
 */

/** What a file of a plan holds. */
@Serializable
data class SavedPlan(
    val app: String = APP,
    val format: Int = FORMAT,
    /** When the file was written, as the browser's ISO time. */
    val saved: String = "",
    /** The model that planned, such as "claude-sonnet-5-5": for whoever studies the file. */
    val model: String = "",
    val trip: Trip,
    /** Where the run goes on: the nodes of the next step, and the step it is. */
    val nextNodes: List<String>,
    val step: Int = 0,
    val interruptedBefore: Boolean = false,
    /** What the run's steps saved as effects, so that opening the file does not ask the sources and the model again. */
    val effects: Map<String, String> = emptyMap(),
) {
    val checkpoint: Checkpoint<Trip> get() = Checkpoint(trip, nextNodes, step, interruptedBefore, effects = effects)

    companion object {
        const val APP: String = "Foodie Route"

        /** Raised when the file changes in a way an older page cannot read. */
        const val FORMAT: Int = 1
    }
}

/** This checkpoint as the text of a file. */
fun Checkpoint<Trip>.toFile(model: String, saved: String): String =
    FILE.encodeToString(SavedPlan(saved = saved, model = model, trip = state, nextNodes = nextNodes, step = step, interruptedBefore = interruptedBefore, effects = effects))

/** A file that the page cannot open as a plan. [newer] when a newer version of the page wrote it. */
class NotAPlanException(
    val newer: Boolean = false,
    cause: Throwable? = null,
) : IllegalArgumentException(if (newer) "The plan was saved by a newer version of the page." else "The file is no plan of ${SavedPlan.APP}.", cause)

/**
 * The checkpoint in the text of a file.
 *
 * @throws NotAPlanException when the text is no plan, or one of a newer page.
 */
fun planFromFile(text: String): Checkpoint<Trip> {
    val plan =
        try {
            FILE.decodeFromString<SavedPlan>(text)
        } catch (e: SerializationException) {
            throw NotAPlanException(cause = e)
        }
    if (plan.app != SavedPlan.APP) throw NotAPlanException()
    if (plan.format > SavedPlan.FORMAT) throw NotAPlanException(newer = true)
    return plan.checkpoint
}

/** The name of a file for [trip]: its places and the day it was saved. */
fun fileName(trip: Trip, day: String): String {
    val places = trip.stops.joinToString("-") { it.name.substringBefore(',').simplified().replace(' ', '-') }.ifBlank { "trip" }
    return "foodie-route-$places-$day.json"
}

private val FILE =
    Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
