package dev.deeptelar.foodie

/*
 * Choosing the restaurants is the slowest part of a plan. While the agent chooses, the page shows
 * the food of the trip one dish at a time: the dishes were found in the step before, and the ones
 * the traveller cannot eat were left out then.
 */

/**
 * The dishes to show while the agent chooses, in the order they are shown: the stops take turns,
 * and at each stop the dishes with a story come first.
 */
fun Trip.whileYouWait(): List<Dish> {
    val byStop = dishes.groupBy { it.stop }.values.map { stop -> stop.sortedBy { it.story.isBlank() } }
    return (0 until (byStop.maxOfOrNull { it.size } ?: 0)).flatMap { index -> byStop.mapNotNull { it.getOrNull(index) } }
}
