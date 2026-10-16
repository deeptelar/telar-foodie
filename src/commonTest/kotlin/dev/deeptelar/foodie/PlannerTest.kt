package dev.deeptelar.foodie

import dev.deeptelar.foodie.sources.GuidePage
import dev.deeptelar.foodie.sources.MapSearch
import dev.deeptelar.foodie.sources.PlaceReviews
import dev.deeptelar.foodie.sources.SourceException
import dev.deeptelar.foodie.sources.Sources
import dev.deeptelar.foodie.sources.StreetMap
import dev.deeptelar.foodie.sources.TravelGuide
import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphResult
import dev.deeptelar.telar.MemoryCheckpointer
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.TokenUsage
import dev.deeptelar.telar.test.ScriptedChatModel
import dev.deeptelar.telar.test.record
import dev.deeptelar.telar.test.test
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class PlannerTest {
    private val lisbon = "Lisbon, Portugal"

    private val santaRitaInGuide =
        Restaurant(stop = lisbon, name = "Restaurante Santa Rita", lat = 38.711364, lon = -9.134798, note = "Authentic, cheap Portuguese dishes.", sources = setOf(Source.GUIDE))
    private val santaRitaOnMap = Restaurant(stop = lisbon, name = "Santa Rita", lat = 38.71137, lon = -9.13481, cuisine = "portuguese", sources = setOf(Source.MAP))
    private val zeDaMouraria = Restaurant(stop = lisbon, name = "Zé da Mouraria", lat = 38.7155, lon = -9.1362, cuisine = "portuguese", sources = setOf(Source.MAP))

    private fun sources(
        map: StreetMap = StreetMap { _, _, _, _ -> listOf(santaRitaOnMap, zeDaMouraria) },
        reviews: PlaceReviews? = null,
        guide: TravelGuide = TravelGuide { GuidePage("Most restaurants are small and family-run.", listOf(santaRitaInGuide)) },
    ) = Sources(
        geocoder = { place -> if (place.startsWith("Lisbon")) Location(38.7078, -9.1366) else null },
        guide = guide,
        map = map,
        reviews = reviews,
    )

    private val brief = Brief(stops = listOf(BriefStop(lisbon, days = 1, cuisines = listOf("portuguese"))), diet = "no pork")
    private val menu = Menu(listOf(Dish(lisbon, "bacalhau à brás", "Salt cod with eggs and fried potato.", "dinner")))

    private fun config() = GraphConfig(threadId = "trip", checkpointer = MemoryCheckpointer<Trip>(), interruptBefore = setOf(Nodes.REVIEW))

    @Test
    fun `a request becomes a route that waits for the traveller`() =
        runTest {
            val model =
                ScriptedChatModel {
                    answers(brief)
                    answers(menu)
                    calls(
                        call("set_meal", MealChoice(1, "dinner", "r1", "bacalhau à brás", "The guide calls it authentic, and the map knows it too.")),
                        call("set_meal", MealChoice(1, "lunch", "r2", "caldo verde", "The map lists it as Portuguese.")),
                    )
                    says("A day of salt cod.")
                }

            val run = planner(model, sources()).stream(Trip("One day in Lisbon, no pork"), config()).record()

            // The three sources that exist here ran in one step, and the run stopped before the review.
            assertEquals(
                listOf(setOf(Nodes.BRIEF), setOf(Nodes.GUIDE, Nodes.MAP), setOf(Nodes.DISHES), setOf(Nodes.PLAN), setOf(Nodes.TOOLS), setOf(Nodes.PLAN), setOf(Nodes.ORDER)),
                run.steps.map { it.toSet() },
            )
            val paused = assertIs<GraphResult.Interrupted<Trip>>(run.result)
            assertEquals(listOf(Nodes.REVIEW), paused.nextNodes)

            val trip = paused.state
            assertEquals(listOf(Stop(lisbon, days = 1, firstDay = 1, lat = 38.7078, lon = -9.1366, cuisines = listOf("portuguese"))), trip.stops)
            // The guide and the map both know Santa Rita: it is one candidate with two sources.
            assertEquals(listOf("r1", "r2"), trip.restaurants.map { it.id })
            assertEquals(setOf(Source.GUIDE, Source.MAP), trip.restaurant("r1")?.sources)
            assertEquals("portuguese", trip.restaurant("r1")?.cuisine)
            assertEquals(menu.dishes, trip.dishes)
            // The meals are in the order of the day, and the dinner knows how far it is from the lunch.
            assertEquals(listOf(MealTime.LUNCH to "r2", MealTime.DINNER to "r1"), trip.route.map { it.time to it.restaurantId })
            assertEquals(null, trip.route[0].walk)
            assertTrue(trip.route[1].walk in 400..600, "walk was ${trip.route[1].walk}")
            assertEquals("A day of salt cod.", trip.summary)
            assertEquals(0, model.remaining)
        }

    @Test
    fun `the agent reads the request, the dishes and the candidates`() =
        runTest {
            val model =
                ScriptedChatModel {
                    answers(brief)
                    answers(menu)
                    says("Nothing to plan.")
                }

            planner(model, sources()).invoke(Trip("One day in Lisbon, no pork"), config())

            val first = model.requests[2].messages.first().text
            assertTrue("Diet: no pork" in first, first)
            assertTrue("- Day 1: $lisbon" in first, first)
            assertTrue("- bacalhau à brás (dinner): Salt cod with eggs and fried potato." in first, first)
            assertTrue("[r1] Restaurante Santa Rita · portuguese · found in: Travel guide, Street map" in first, first)
            assertEquals(listOf("candidates", "search_map", "read_guide", "set_meal", "remove_meal"), model.requests[2].tools.map { it.name })
            // What the guide says about the food goes to the model that names the dishes.
            assertTrue("small and family-run" in model.requests[1].messages.first().text)
        }

    @Test
    fun `a change from the traveller goes back to the agent, and a yes ends the run`() =
        runTest {
            val model =
                ScriptedChatModel {
                    answers(brief)
                    answers(menu)
                    calls("set_meal", MealChoice(1, "lunch", "r2", "bifana", "The map lists it as Portuguese."))
                    says("A pork sandwich for lunch.")
                    calls("set_meal", MealChoice(1, "lunch", "r1", "bacalhau à brás", "No pork here."))
                    says("Changed the lunch.")
                }

            planner(model, sources()).test(Trip("One day in Lisbon"), config()) {
                // A step, not a node: in Telar 0.1.0-alpha10, expectStep after expectNode stops at the end of the node's step.
                expectStep(Nodes.BRIEF)
                expectStep(Nodes.GUIDE, Nodes.MAP)
                expectNodes(Nodes.DISHES, Nodes.PLAN, Nodes.TOOLS, Nodes.PLAN, Nodes.ORDER)
                expectInterruptBefore(Nodes.REVIEW) { trip -> assertEquals(listOf("r2"), trip.route.map { it.restaurantId }) }

                resumeWith { it.copy(messages = it.messages + ChatMessage.User("I do not eat pork.")) }
                // The graph loops back to the agent, which goes over the route again.
                expectNodes(Nodes.REVIEW, Nodes.PLAN, Nodes.TOOLS, Nodes.PLAN, Nodes.ORDER)
                expectInterruptBefore(Nodes.REVIEW) { trip ->
                    assertEquals(listOf("r1"), trip.route.map { it.restaurantId })
                    assertEquals("Changed the lunch.", trip.summary)
                }

                resumeWith { it.copy(approved = true) }
                expectNode(Nodes.REVIEW)
                expectFinished()
            }

            // The agent got the traveller's words after its own summary.
            assertEquals("I do not eat pork.", model.requests[4].messages.last { it is ChatMessage.User }.text)
            assertEquals(0, model.remaining)
        }

    @Test
    fun `a request without a place pauses with a question, and the answer continues it`() =
        runTest {
            val model =
                ScriptedChatModel {
                    answers(Brief(question = "Which city are you visiting?"))
                    answers(brief)
                    answers(menu)
                    says("Planned.")
                }
            val planner = planner(model, sources())
            val config = config()

            val asked = planner.invoke(Trip("I love seafood"), config)

            assertIs<GraphResult.Interrupted<Trip>>(asked)
            assertEquals("Which city are you visiting?", asked.state.question)
            assertEquals(listOf(Nodes.BRIEF), asked.nextNodes)

            val answered = planner.resume(config) { it.copy(request = it.request + "\nLisbon, one day", question = "") }

            assertEquals(listOf(lisbon), answered.state.stops.map { it.name })
            assertTrue("Lisbon, one day" in model.requests[1].messages.first().text)
        }

    @Test
    fun `a place the map does not know is asked for`() =
        runTest {
            val model = ScriptedChatModel { answers(Brief(stops = listOf(BriefStop("Atlantis")))) }

            val asked = planner(model, sources()).invoke(Trip("Two days in Atlantis"), config())

            assertEquals("I could not find Atlantis on the map. Which place do you mean?", asked.state.question)
            assertNotNull(asked.state.brief)
        }

    @Test
    fun `a source that fails leaves a note and the others still count`() =
        runTest {
            val model =
                ScriptedChatModel {
                    answers(brief)
                    answers(menu)
                    says("Planned with the guide only.")
                }
            val broken = StreetMap { _, _, _, _ -> throw SourceException("No server of the street map answered.") }

            val trip = planner(model, sources(map = broken)).invoke(Trip("One day in Lisbon"), config()).state

            assertEquals(listOf("The street map gave no answer for $lisbon: No server of the street map answered."), trip.notes)
            assertEquals(listOf("Restaurante Santa Rita"), trip.restaurants.map { it.name })
            assertTrue("What could not be read:" in model.requests[2].messages.first().text)
        }

    @Test
    fun `a source that a browser cannot reach leaves a note too`() =
        runTest {
            val model =
                ScriptedChatModel {
                    answers(brief)
                    answers(menu)
                    says("Planned with the guide only.")
                }
            // What Ktor throws in a browser for a request that got no response.
            val unreachable = StreetMap { _, _, _, _ -> throw Error("Fail to fetch") }

            val trip = planner(model, sources(map = unreachable)).invoke(Trip("One day in Lisbon"), config()).state

            assertEquals(listOf("The street map gave no answer for $lisbon: Fail to fetch"), trip.notes)
        }

    @Test
    fun `a source that takes too long is left out`() =
        runTest {
            val model =
                ScriptedChatModel {
                    answers(brief)
                    answers(menu)
                    says("Planned with the guide only.")
                }
            val slow =
                StreetMap { _, _, _, _ ->
                    delay(10.minutes)
                    listOf(zeDaMouraria)
                }

            val trip = planner(model, sources(map = slow)).invoke(Trip("One day in Lisbon"), config()).state

            assertEquals(listOf("The street map took too long for $lisbon."), trip.notes)
            assertEquals(listOf("Restaurante Santa Rita"), trip.restaurants.map { it.name })
        }

    @Test
    fun `the sample plans two days from the candidates and a search of the map`() =
        runTest {
            val searches = mutableListOf<MapSearch>()
            val bakery = Restaurant(stop = lisbon, name = "Manteigaria", lat = 38.7107, lon = -9.1437, cuisine = "pastry", sources = setOf(Source.MAP))
            val map =
                StreetMap { stop, _, search, _ ->
                    searches += search
                    if (search == MapSearch.Cuisine(listOf("pastry"))) {
                        listOf(bakery)
                    } else {
                        (1..5).map { Restaurant(stop = stop, name = "Tasca $it", lat = 38.71 + it / 1000.0, lon = -9.13, cuisine = "portuguese", sources = setOf(Source.MAP)) }
                    }
                }

            val result = planner(SampleModel(pause = Duration.ZERO), sources(map = map)).invoke(Trip("Anything"), config())

            assertIs<GraphResult.Interrupted<Trip>>(result)
            val trip = result.state
            assertEquals<List<MapSearch>>(listOf(MapSearch.Cuisine(listOf("portuguese", "regional", "seafood")), MapSearch.Cuisine(listOf("pastry"))), searches)
            assertEquals(
                listOf("1 LUNCH", "1 SNACK", "1 DINNER", "2 LUNCH", "2 DINNER"),
                trip.route.map { "${it.day} ${it.time}" },
            )
            assertEquals("Manteigaria", trip.restaurant(trip.route[1].restaurantId)?.name)
            assertEquals(5, trip.route.map { it.restaurantId }.distinct().size)
            assertTrue(trip.summary.startsWith("This is the sample trip."))
            assertEquals(7, trip.dishes.size)
        }

    @Test
    fun `with a source of reviews the graph has a node and a tool for it`() =
        runTest {
            val rated = Restaurant(stop = lisbon, name = "Zé da Mouraria", lat = 38.71551, lon = -9.13621, rating = 4.6, ratings = 3200, sources = setOf(Source.REVIEWS))
            val queries = mutableListOf<String>()
            val reviews =
                PlaceReviews { _, query, _ ->
                    queries += query
                    listOf(rated)
                }
            val model =
                ScriptedChatModel {
                    answers(brief)
                    answers(menu)
                    calls("search_reviews", ReviewSearch(lisbon, "best bacalhau"))
                    says("Planned.")
                }

            val run = planner(model, sources(reviews = reviews)).stream(Trip("One day in Lisbon"), config()).record()

            assertEquals(setOf(Nodes.GUIDE, Nodes.MAP, Nodes.REVIEWS), run.steps[1].toSet())
            assertEquals(listOf("traditional portuguese restaurant where locals eat", "best bacalhau"), queries)
            // The rating joined the entry of the map.
            val ze = run.state.restaurants.single { it.name == "Zé da Mouraria" }
            assertEquals(setOf(Source.MAP, Source.REVIEWS), ze.sources)
            assertEquals(4.6, ze.rating)
            val result = model.requests[3].messages.last() as ChatMessage.ToolResult
            assertTrue(result.text.startsWith("[r2] Zé da Mouraria · portuguese · found in: Street map, Google reviews · 4.6 stars (3200 ratings)"), result.text)
        }

    @Test
    fun `a meal at a restaurant that is no candidate is refused, and the model is told why`() =
        runTest {
            val model =
                ScriptedChatModel {
                    answers(brief)
                    answers(menu)
                    calls(
                        call("set_meal", MealChoice(1, "lunch", "r99", "bifana", "Made up.")),
                        call("set_meal", MealChoice(2, "lunch", "r1", "bifana", "Wrong day.")),
                        call("set_meal", MealChoice(1, "brunch", "r1", "bifana", "Wrong meal.")),
                        call("set_meal", MealChoice(1, "lunch", "r1", "bacalhau", "Fine.")),
                    )
                    says("Planned.")
                }

            val trip = planner(model, sources()).invoke(Trip("One day in Lisbon"), config()).state

            val results = model.requests[3].messages.filterIsInstance<ChatMessage.ToolResult>()
            assertEquals(listOf(true, true, true, false), results.map { it.isError })
            assertEquals("There is no candidate with the id \"r99\". Use an id from the list.", results[0].text)
            assertEquals("The trip has 1 days. There is no day 2.", results[1].text)
            assertEquals("Day 1, lunch: Restaurante Santa Rita. Still open: day 1 dinner.", results[3].text)
            assertEquals(listOf("r1"), trip.route.map { it.restaurantId })
        }

    @Test
    fun `the agent stops at the token budget with the route it has`() =
        runTest {
            val model =
                ScriptedChatModel {
                    answers(brief, usage = TokenUsage(100, 50))
                    answers(menu, usage = TokenUsage(200, 50))
                    calls("set_meal", MealChoice(1, "lunch", "r1", "bacalhau", "Fine."), usage = TokenUsage(900, 100))
                    says("Never said.")
                }

            val result = planner(model, sources(), tokenBudget = 1000).invoke(Trip("One day in Lisbon"), config())

            assertIs<GraphResult.Interrupted<Trip>>(result)
            assertEquals(1400, result.state.tokens.total)
            assertEquals(listOf("r1"), result.state.route.map { it.restaurantId })
            assertEquals(1, model.remaining)
        }

    @Test
    fun `a source that already answered is not asked again when its step runs again`() =
        runTest {
            val model =
                ScriptedChatModel {
                    answers(brief)
                    answers(menu)
                    says("Planned.")
                }
            var guideReads = 0
            var mapSearches = 0
            val guide =
                TravelGuide {
                    guideReads++
                    GuidePage(restaurants = listOf(santaRitaInGuide))
                }
            // The first search of the map is still going when the page is closed.
            val map =
                StreetMap { _, _, _, _ ->
                    if (++mapSearches == 1) delay(30.seconds)
                    listOf(zeDaMouraria)
                }
            val planner = planner(model, sources(map = map, guide = guide))
            val config = config()

            val closed = launch { planner.stream(Trip("One day in Lisbon"), config).collect {} }
            advanceTimeBy(1.seconds)
            closed.cancel()
            val reopened = planner.resume(config)

            assertIs<GraphResult.Interrupted<Trip>>(reopened)
            assertEquals(listOf(Nodes.REVIEW), reopened.nextNodes)
            // The guide had answered and its answer was saved; the map is asked again.
            assertEquals(1, guideReads)
            assertEquals(2, mapSearches)
            assertEquals(listOf("Restaurante Santa Rita", "Zé da Mouraria"), reopened.state.restaurants.map { it.name })
        }
}
