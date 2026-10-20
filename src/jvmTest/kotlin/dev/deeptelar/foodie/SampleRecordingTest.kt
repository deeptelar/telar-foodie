package dev.deeptelar.foodie

import dev.deeptelar.foodie.sources.MapSearch
import dev.deeptelar.foodie.sources.Nominatim
import dev.deeptelar.foodie.sources.Overpass
import dev.deeptelar.foodie.sources.Recorder
import dev.deeptelar.foodie.sources.Recording
import dev.deeptelar.foodie.sources.Sources
import dev.deeptelar.foodie.sources.StreetMap
import dev.deeptelar.foodie.sources.Wikivoyage
import dev.deeptelar.foodie.sources.replay
import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.MemoryCheckpointer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.UserAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The sample plans from what the sources answered once, kept in [FILE], and asks none of them. To
 * ask the real guide and map again and keep their answers:
 *
 * ```
 * FOODIE_RECORD=1 ./gradlew jvmTest --tests '*SampleRecordingTest*'
 * ```
 */
class SampleRecordingTest {
    @Test
    fun `the sample plans from its recording alone, in every language`() =
        runTest {
            val recording = Recording.fromJson(File(FILE).readText())
            val sources = replay({ recording }, unasked)
            Language.entries.forEach { language ->
                val trip = sample(sources, language)
                assertEquals(emptyList(), trip.notes, "$language")
                assertEquals(5, trip.route.size, "$language")
            }
        }

    @Test
    fun `the real sources are asked again and their answers kept`() {
        if (System.getenv("FOODIE_RECORD") != "1") return
        runBlocking {
            // Wikimedia and Nominatim ask a program to say what it is; a browser says it for the page.
            // A recording can wait for a busy server longer than a traveller would: CIO stops a request after 15 s on its own.
            HttpClient(CIO) {
                engine { requestTimeout = 0 }
                install(UserAgent) { agent = "telar-foodie (https://github.com/deeptelar/telar-foodie)" }
            }.use { client ->
                // A question that fails reaches the run that asked it, and not the test.
                val questions = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext.job))
                val recorder = Recorder(Sources(Nominatim(client), Wikivoyage(client), Overpass(client, patience = 60.seconds, rest = Duration.ZERO)), questions)
                // A source that answers after the planner stopped waiting is kept, and the next run has it. The last run has every answer.
                var trip = sample(recorder.sources, Language.ENGLISH)
                repeat(RUNS - 1) {
                    if (trip.notes.isEmpty() && recorder.recording.sights.isNotEmpty()) return@repeat
                    questions.coroutineContext.job.children.toList().joinAll()
                    trip = sample(recorder.sources, Language.ENGLISH)
                }
                assertEquals(emptyList(), trip.notes)
                // An empty answer for a city is a server that failed without saying so.
                val recording = recorder.recording
                assertTrue(recording.sights.isNotEmpty() && recording.sights.values.all { it.isNotEmpty() }, "no sights were found")
                assertTrue(recording.restaurants.values.all { it.isNotEmpty() }, "a search of the map found nothing")
                File(FILE).writeText(recording.toJson() + "\n")
                questions.cancel()
            }
        }
    }

    /** Plans the sample to its route: one round of questions, each with its first answer, and the profile taken as it is. */
    private suspend fun sample(sources: Sources, language: Language): Trip {
        val planner = planner(SampleModel(pause = Duration.ZERO), sources)
        val config = GraphConfig(threadId = "sample", checkpointer = MemoryCheckpointer<Trip>(), interruptBefore = setOf(Nodes.REVIEW))
        planner.invoke(Trip(language.texts.sampleRequest, language = language), config)
        planner.resume(config) { trip -> trip.answered(trip.questions.map { Answer(it.topic, it.text, it.options.first()) }) }
        return planner.resume(config) { it.confirmed() }.state
    }

    /** Sources that fail the test when they are asked: whatever the sample asks has to be in the recording. */
    private val unasked =
        Sources(
            geocoder = { fail("The recording does not know where $it is") },
            guide = { fail("The recording has no guide for $it") },
            map =
                object : StreetMap {
                    override suspend fun restaurants(stop: String, near: Location, search: MapSearch, radius: Int) =
                        fail("The recording has no search of the map for $search at $stop")

                    override suspend fun sights(stop: String, near: Location, radius: Int) = fail("The recording has no sights of $stop")
                },
        )

    private companion object {
        const val FILE = "src/jsMain/resources/sample-sources.json"

        /** How many times the recording runs the sample, at most, for the answers that came late. */
        const val RUNS = 4
    }
}
