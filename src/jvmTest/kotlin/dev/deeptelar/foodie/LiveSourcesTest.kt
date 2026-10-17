package dev.deeptelar.foodie

import dev.deeptelar.foodie.sources.MapSearch
import dev.deeptelar.foodie.sources.Sources
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Asks the real services, to see that they still answer in the form the sources read. It needs a
 * network and is slow, so it only runs when asked for: `FOODIE_LIVE=1 ./gradlew jvmTest`.
 */
class LiveSourcesTest {
    @Test
    fun `the free sources know Lisbon`() {
        if (System.getenv("FOODIE_LIVE") != "1") return
        runBlocking {
            HttpClient(CIO).use { client ->
                val sources = Sources.online(client)

                val lisbon = assertNotNull(sources.geocoder.locate("Lisbon, Portugal"))
                println("Lisbon is at $lisbon")

                val page = sources.guide.read("Lisbon, Portugal")
                println("The guide: ${page.text.length} characters, ${page.restaurants.size} restaurants")
                println(page.text.take(400))
                page.restaurants.take(5).forEach { println("  " + Trip("").line(it.copy(id = "-"))) }
                assertTrue(page.text.isNotBlank())
                assertTrue(page.restaurants.size > 10, "the guide listed ${page.restaurants.size}")

                val onMap = sources.map.restaurants("Lisbon", lisbon, MapSearch.Cuisine(listOf("portuguese", "regional")), MAP_RADIUS)
                println("The map: ${onMap.size} restaurants")
                onMap.take(5).forEach { println("  " + Trip("").line(it.copy(id = "-"))) }
                assertTrue(onMap.size > 10, "the map listed ${onMap.size}")

                val both = emptyList<Restaurant>().merged(page.restaurants).merged(onMap)
                println("Together ${both.size}, in both sources: ${both.filter { it.sources.size > 1 }.map { it.name }}")
            }
        }
    }
}
