package dev.deeptelar.foodie

import dev.deeptelar.foodie.sources.GooglePlaces
import dev.deeptelar.foodie.sources.MapSearch
import dev.deeptelar.foodie.sources.Nominatim
import dev.deeptelar.foodie.sources.Overpass
import dev.deeptelar.foodie.sources.SourceException
import dev.deeptelar.foodie.sources.Wikivoyage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class SourcesTest {
    private val lisbon = Location(38.7078, -9.1366)

    /**
     * A client whose requests are answered by [handler]. Its engine runs on the scheduler of the
     * test, so that the time limit a source puts around a request counts the time of the test.
     */
    private fun TestScope.client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { handler(it) }
            }
        }

    // Nominatim

    @Test
    fun `the place search returns the position of the first result`() =
        runTest {
            val client =
                client { request ->
                    assertEquals("Lisbon, Portugal", request.url.parameters["q"])
                    respond("""[{"place_id":1,"lat":"38.7077507","lon":"-9.1365919","name":"Lisbon"}]""")
                }

            assertEquals(Location(38.7077507, -9.1365919), Nominatim(client).locate("Lisbon, Portugal"))
        }

    @Test
    fun `a place nobody knows has no position`() =
        runTest {
            assertNull(Nominatim(client { respond("[]") }).locate("Atlantis"))
        }

    // Wikivoyage

    private val alfama =
        """
        ==See==
        * {{see | name=Castle | lat=38.7139 | long=-9.1335 | content=Old.}}

        ==Eat==
        Alfama is the place for [[Fado|fado]] houses.<!-- keep this short -->

        ===Budget===
        All these are around the ''Castelo de S. Jorge'':

        * {{eat
        | name=Restaurante Santa Rita | alt= | url=https://example.org/santa-rita | email=
        | address=Rua de São Mamede 24c | lat=38.711364 | long=-9.134798 | directions=
        | hours=12:00-22:00 | price=Mains €5-9 (May 2017)
        | lastedit=2022-05-01
        | content=Authentic, cheap [[Portugal|Portuguese]] dishes in a very busy, shared-table restaurant.
        }}
        * {{eat
        | name=No Position | address=Somewhere | lat= | long= | content=Cannot be on a route.}}
        * {{listing | type=eat | name=Café Parreirinha | lat=38.7159988 | long= -9.1340794 | content=}}

        ==Drink==
        * {{drink | name=A Bar | lat=38.71 | long=-9.13 | content=Not food.}}
        """.trimIndent()

    @Test
    fun `the listings under Eat become restaurants, with the words of the guide`() {
        val found = Wikivoyage.eatListings("Lisbon", alfama)

        assertEquals(listOf("Restaurante Santa Rita", "Café Parreirinha"), found.map { it.name })
        val santaRita = found.first()
        assertEquals(Location(38.711364, -9.134798), santaRita.location)
        assertEquals("Rua de São Mamede 24c", santaRita.address)
        assertEquals("https://example.org/santa-rita", santaRita.website)
        assertEquals("Mains €5-9 (May 2017)", santaRita.price)
        assertEquals("Authentic, cheap Portuguese dishes in a very busy, shared-table restaurant.", santaRita.note)
        assertEquals(setOf(Source.GUIDE), santaRita.sources)
        assertEquals(-9.1340794, found[1].lon)
    }

    @Test
    fun `the text under Eat is plain, without the listings`() {
        assertEquals(
            "Alfama is the place for fado houses.\nBudget:\nAll these are around the Castelo de S. Jorge:",
            Wikivoyage.eatText(alfama),
        )
    }

    @Test
    fun `a page without the section has nothing to eat`() {
        assertEquals("", Wikivoyage.eatText("==See==\nA castle."))
        assertEquals(emptyList(), Wikivoyage.eatListings("Lisbon", "==See==\nA castle."))
    }

    @Test
    fun `the guide reads the page of a place and the pages of its districts`() =
        runTest {
            val asked = mutableListOf<String>()
            val client =
                client { request ->
                    val parameters = request.url.parameters
                    assertEquals("*", parameters["origin"])
                    when {
                        parameters["action"] == "parse" -> {
                            asked += parameters["page"]!!
                            when (parameters["page"]) {
                                "Lisbon" -> respond(wikitext("==Eat==\nMost restaurants are small and family-run.\n{{SeeDistricts}}"))
                                "Lisbon/Alfama" -> respond(wikitext(alfama))
                                else -> respond("""{"error":{"code":"missingtitle","info":"The page you specified doesn't exist."}}""")
                            }
                        }
                        parameters["list"] == "prefixsearch" -> {
                            assertEquals("Lisbon/", parameters["pssearch"])
                            respond("""{"query":{"prefixsearch":[{"title":"Lisbon/Alfama"},{"title":"Lisbon (Ohio)"},{"title":"Lisbon/Gone"}]}}""")
                        }
                        else -> error("Unexpected request: ${request.url}")
                    }
                }

            val page = Wikivoyage(client).read("Lisbon, Portugal")

            assertEquals("Most restaurants are small and family-run.", page.text)
            assertEquals(listOf("Restaurante Santa Rita", "Café Parreirinha"), page.restaurants.map { it.name })
            assertEquals("Lisbon, Portugal", page.restaurants.first().stop)
            // The page of the place is asked for twice: to find its title, and to read it.
            assertEquals(setOf("Lisbon", "Lisbon/Alfama", "Lisbon/Gone"), asked.toSet())
        }

    @Test
    fun `a place whose page has another title is found by a search`() =
        runTest {
            val client =
                client { request ->
                    val parameters = request.url.parameters
                    when {
                        parameters["page"] == "Oporto" -> respond("""{"error":{"code":"missingtitle"}}""")
                        parameters["list"] == "search" -> respond("""{"query":{"search":[{"title":"Porto"}]}}""")
                        parameters["page"] == "Porto" -> respond(wikitext("==Eat==\nTry a francesinha."))
                        else -> respond("""{"query":{"prefixsearch":[]}}""")
                    }
                }

            assertEquals("Try a francesinha.", Wikivoyage(client).read("Oporto, Portugal").text)
        }

    private fun wikitext(text: String): String =
        buildJsonObject { putJsonObject("parse") { putJsonObject("wikitext") { put("*", text) } } }.toString()

    // Overpass

    private val mapAnswer =
        """
        {"elements":[
          {"type":"node","id":1,"lat":38.7111,"lon":-9.1401,"tags":{"amenity":"restaurant","cuisine":"regional;fish_and_chips","name":"O Sacramento",
            "addr:street":"Calçada do Sacramento","addr:housenumber":"46","website":"https://example.org","opening_hours":"Mo-Su 12:00-23:00"}},
          {"type":"way","id":2,"center":{"lat":38.7135,"lon":-9.1338},"tags":{"amenity":"restaurant","cuisine":"portuguese","name":"Casa do Leão"}},
          {"type":"node","id":3,"lat":38.71,"lon":-9.13,"tags":{"amenity":"restaurant","cuisine":"regional","name":"Chão do Loureiro","operational_status":"closed"}}
        ]}
        """.trimIndent()

    @Test
    fun `the places of the map become restaurants, and a closed one is left out`() {
        val found = Overpass.restaurants("Lisbon", Json.parseToJsonElement(mapAnswer))

        assertEquals(listOf("O Sacramento", "Casa do Leão"), found.map { it.name })
        assertEquals("regional, fish and chips", found[0].cuisine)
        assertEquals("Calçada do Sacramento 46", found[0].address)
        assertEquals("Mo-Su 12:00-23:00", found[0].hours)
        // A building has no position of its own: the middle of its outline is used.
        assertEquals(Location(38.7135, -9.1338), found[1].location)
        assertEquals(setOf(Source.MAP), found[1].sources)
    }

    @Test
    fun `the question to the map asks for a cuisine or a name, without chains and without what a model must not write into it`() {
        assertEquals(
            "[out:json][timeout:25];nwr[\"amenity\"~\"^(restaurant|cafe|fast_food|pub|bar|ice_cream)$\"][\"name\"]" +
                "[\"cuisine\"~\"portuguese|regional\",i][!\"brand\"](around:3000,38.7078,-9.1366);out tags center 120;",
            Overpass.query(lisbon, MapSearch.Cuisine(listOf("portuguese", " regional ")), 3000),
        )
        assertTrue("[\"name\"~\"tasca do chico\",i]" in Overpass.query(lisbon, MapSearch.Name("tasca \"];(do) chico"), 3000))
    }

    @Test
    fun `when a server of the map fails, the next one is asked`() =
        runTest {
            val asked = mutableListOf<String>()
            val client =
                client { request ->
                    asked += request.url.host
                    assertTrue("data=%5Bout%3Ajson%5D" in request.body.toByteArray().decodeToString())
                    if (request.url.host == "first.example") respond("Too busy", HttpStatusCode.TooManyRequests) else respond(mapAnswer)
                }
            val map = Overpass(client, servers = listOf("https://first.example/api", "https://second.example/api"))

            val found = map.restaurants("Lisbon", lisbon, MapSearch.Cuisine(listOf("regional")), 3000)

            assertEquals(listOf("first.example", "second.example"), asked)
            assertEquals(2, found.size)
        }

    @Test
    fun `a server of the map that a browser cannot reach is skipped too`() =
        runTest {
            val asked = mutableListOf<String>()
            val client =
                client { request ->
                    asked += request.url.host
                    // What Ktor throws in a browser for a request that got no response.
                    if (request.url.host == "first.example") throw Error("Fail to fetch") else respond(mapAnswer)
                }
            val map = Overpass(client, servers = listOf("https://first.example/api", "https://second.example/api"))

            val found = map.restaurants("Lisbon", lisbon, MapSearch.Cuisine(listOf("regional")), 3000)

            assertEquals(listOf("first.example", "second.example"), asked)
            assertEquals(2, found.size)
        }

    @Test
    fun `when no server of the map answers, the search fails with what the last one said`() =
        runTest {
            val map = Overpass(client { respond("Too busy", HttpStatusCode.TooManyRequests) }, servers = listOf("https://first.example/api"))

            val failure = assertFailsWith<SourceException> { map.restaurants("Lisbon", lisbon, MapSearch.Name("tasca"), 3000) }

            assertEquals("No server of the street map answered: The street map answered with 429: Too busy", failure.message)
        }

    @Test
    fun `a server of the map that takes too long is given up, and the one that answered is asked first the next time`() =
        runTest {
            val asked = mutableListOf<String>()
            val client =
                client { request ->
                    asked += request.url.host
                    if (request.url.host == "slow.example") delay(1.minutes)
                    respond(mapAnswer)
                }
            val map = Overpass(client, servers = listOf("https://slow.example/api", "https://fast.example/api"), patience = 10.seconds)

            map.restaurants("Lisbon", lisbon, MapSearch.Cuisine(listOf("regional")), 3000)
            map.restaurants("Lisbon", lisbon, MapSearch.Name("tasca"), 3000)

            assertEquals(listOf("slow.example", "fast.example", "fast.example"), asked)
        }

    @Test
    fun `after no server answered, the map is not asked again for a while`() =
        runTest {
            var asked = 0
            val client =
                client {
                    asked++
                    respond("Too busy", HttpStatusCode.TooManyRequests)
                }
            val map = Overpass(client, servers = listOf("https://first.example/api", "https://second.example/api"))

            assertFailsWith<SourceException> { map.restaurants("Lisbon", lisbon, MapSearch.Name("tasca"), 3000) }
            val second = assertFailsWith<SourceException> { map.restaurants("Lisbon", lisbon, MapSearch.Name("tasca"), 3000) }

            assertEquals(2, asked)
            assertEquals("The street map does not answer at the moment. Go on without it.", second.message)
        }

    // Google Places

    @Test
    fun `a search of the reviews sends the key and returns restaurants with their ratings`() =
        runTest {
            val client =
                client { request ->
                    assertEquals("https://places.googleapis.com/v1/places:searchText", request.url.toString())
                    assertEquals("my-key", request.headers["X-Goog-Api-Key"])
                    assertTrue("places.rating" in request.headers["X-Goog-FieldMask"]!!)
                    val body = request.body.toByteArray().decodeToString()
                    assertTrue("\"textQuery\":\"best bacalhau\"" in body, body)
                    assertTrue("\"latitude\":38.7078" in body, body)
                    respond(
                        """
                        {"places":[
                          {"displayName":{"text":"Zé da Mouraria"},"formattedAddress":"R. João do Outeiro 24, Lisboa","location":{"latitude":38.7155,"longitude":-9.1362},
                           "rating":4.6,"userRatingCount":3200,"priceLevel":"PRICE_LEVEL_MODERATE","websiteUri":"https://example.org/ze",
                           "primaryTypeDisplayName":{"text":"Portuguese restaurant"}},
                          {"displayName":{"text":"No position"}}
                        ]}
                        """.trimIndent(),
                    )
                }

            val found = GooglePlaces(client, "my-key").search("Lisbon", "best bacalhau", lisbon)

            assertEquals(
                listOf(
                    Restaurant(
                        stop = "Lisbon", name = "Zé da Mouraria", lat = 38.7155, lon = -9.1362, cuisine = "Portuguese restaurant",
                        address = "R. João do Outeiro 24, Lisboa", website = "https://example.org/ze", price = "medium",
                        rating = 4.6, ratings = 3200, sources = setOf(Source.REVIEWS),
                    ),
                ),
                found,
            )
        }

    @Test
    fun `a search of the reviews that finds nothing is an empty list, and a refused key is a failure`() =
        runTest {
            assertEquals(emptyList(), GooglePlaces(client { respond("{}") }, "my-key").search("Lisbon", "nothing", lisbon))

            val refused = GooglePlaces(client { respond("""{"error":{"message":"API key not valid."}}""", HttpStatusCode.BadRequest) }, "bad")
            val failure = assertFailsWith<SourceException> { refused.search("Lisbon", "anything", lisbon) }
            assertTrue("Google Places answered with 400" in failure.message!!, failure.message)
        }

    // Candidates

    @Test
    fun `the same restaurant from two sources is one candidate that knows what both know`() {
        val guide = Restaurant(stop = "Lisbon", name = "Restaurante Santa Rita", lat = 38.711364, lon = -9.134798, note = "Authentic.", sources = setOf(Source.GUIDE))
        val map = Restaurant(stop = "Lisbon", name = "santa rita", lat = 38.71140, lon = -9.13485, cuisine = "portuguese", sources = setOf(Source.MAP))
        val other = Restaurant(stop = "Lisbon", name = "Santa Rita", lat = 38.75, lon = -9.20, sources = setOf(Source.MAP))

        val all = emptyList<Restaurant>().merged(listOf(guide)).merged(listOf(map, other))

        assertEquals(listOf("r1", "r2"), all.map { it.id })
        assertEquals(guide.copy(id = "r1", cuisine = "portuguese", sources = setOf(Source.GUIDE, Source.MAP)), all[0])
        // The same name five kilometres away is another restaurant.
        assertEquals(other.copy(id = "r2"), all[1])
        assertTrue(all[0].evidence() > all[1].evidence())
    }

    @Test
    fun `names are compared without accents, case and the word for restaurant`() {
        assertEquals("ze da mouraria", "Restaurante Zé da Mouraria".simplified())
        assertEquals("sacramento", "O Sacramento".simplified())
        assertEquals("cafe", "Café".simplified())
    }

    @Test
    fun `the distance between two points is measured in metres`() {
        // One hundredth of a degree of latitude is about 1112 metres everywhere.
        assertEquals(1112, metresBetween(Location(38.70, -9.13), Location(38.71, -9.13)))
        assertEquals(0, metresBetween(lisbon, lisbon))
    }
}
