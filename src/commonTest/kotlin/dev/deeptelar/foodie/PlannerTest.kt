package dev.deeptelar.foodie

import dev.deeptelar.foodie.sources.GuidePage
import dev.deeptelar.foodie.sources.MapSearch
import dev.deeptelar.foodie.sources.PlaceReviews
import dev.deeptelar.foodie.sources.SourceException
import dev.deeptelar.foodie.sources.Sources
import dev.deeptelar.foodie.sources.StreetMap
import dev.deeptelar.foodie.sources.TravelGuide
import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphEvent
import dev.deeptelar.telar.GraphResult
import dev.deeptelar.telar.MemoryCheckpointer
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.TokenUsage
import dev.deeptelar.telar.test.ScriptedChatModel
import dev.deeptelar.telar.test.record
import dev.deeptelar.telar.test.test
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
        geocoder = { place ->
            when {
                place.startsWith("Lisbon") -> Location(38.7078, -9.1366)
                place.startsWith("Alfama") -> Location(38.7118, -9.1300)
                place.startsWith("Baixa") -> Location(38.7114, -9.1367)
                // A hotel of that name, but in another town.
                place.startsWith("Hotel Avenida") -> Location(41.1496, -8.6110)
                else -> null
            }
        },
        guide = guide,
        map = map,
        reviews = reviews,
    )

    private val brief = Brief(stops = listOf(BriefStop(lisbon, days = 1, cuisines = listOf("portuguese"))))
    private val menu = Menu(listOf(Dish(lisbon, "bacalhau à brás", "Salt cod with eggs and fried potato.", "dinner")))

    /** A trip whose interview is over, for the tests of what comes after it. */
    private fun interviewed(request: String, profile: Profile = Profile()) = Trip(request, interview = Interview(read = 0, stage = InterviewStage.DONE), profile = profile)

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

            val run = planner(model, sources()).stream(interviewed("One day in Lisbon, no pork"), config()).record()

            // The sources that exist here and the dishes ran in one step, and the run stopped before the review.
            assertEquals(
                listOf(setOf(Nodes.INTERVIEW), setOf(Nodes.BRIEF), setOf(Nodes.GUIDE, Nodes.MAP, Nodes.DISHES), setOf(Nodes.SPECIALISTS), setOf(Nodes.PLAN), setOf(Nodes.TOOLS), setOf(Nodes.PLAN), setOf(Nodes.ORDER)),
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
    fun `a whole route in one answer needs no round of the model after it`() =
        runTest {
            val model =
                ScriptedChatModel {
                    answers(brief)
                    answers(menu)
                    calls(
                        call(
                            "set_route",
                            RoutePlan(
                                listOf(
                                    MealChoice(1, "dinner", "r1", "bacalhau à brás", "The guide calls it authentic."),
                                    MealChoice(1, "lunch", "r2", "caldo verde", "The map lists it as Portuguese."),
                                ),
                                message = "A day of salt cod.",
                            ),
                        ),
                    )
                }

            val result = planner(model, sources()).invoke(interviewed("One day in Lisbon"), config())

            assertEquals(listOf(Nodes.REVIEW), assertIs<GraphResult.Interrupted<Trip>>(result).nextNodes)
            assertEquals(3, model.requests.size)
            assertEquals(listOf("r2", "r1"), result.state.route.map { it.restaurantId })
            assertEquals("A day of salt cod.", result.state.summary)
            val done = result.state.messages.last() as ChatMessage.ToolResult
            assertEquals("On the route: 2 of 2 meals. Every day has its lunch and dinner. The route is done, and the traveller reads your message.", done.text)
        }

    @Test
    fun `a route with a mistake keeps its good meals and the model is asked again`() =
        runTest {
            val model =
                ScriptedChatModel {
                    answers(brief)
                    answers(menu)
                    calls(
                        call(
                            "set_route",
                            RoutePlan(
                                listOf(MealChoice(1, "lunch", "r99", "bifana", "Made up."), MealChoice(1, "dinner", "r1", "bacalhau", "Fine.")),
                                message = "Too soon.",
                            ),
                        ),
                    )
                    calls("set_meal", MealChoice(1, "lunch", "r2", "caldo verde", "The map knows it."))
                    says("Fixed: lunch at Zé da Mouraria.")
                }

            val trip = planner(model, sources()).invoke(interviewed("One day in Lisbon"), config()).state

            val result = model.requests[3].messages.last() as ChatMessage.ToolResult
            assertEquals(
                "On the route: 1 of 2 meals.\nNot on the route:\n- Day 1 lunch: There is no candidate with the id \"r99\". Use an id from the list. " +
                    "Still open: day 1 lunch. Your message is not shown yet: fix this, then send the message again.",
                result.text,
            )
            assertEquals(listOf("r2", "r1"), trip.route.map { it.restaurantId })
            assertEquals("Fixed: lunch at Zé da Mouraria.", trip.summary)
            assertEquals(0, model.remaining)
        }

    @Test
    fun `the dishes are named while the map is still searched`() =
        runTest {
            val model =
                ScriptedChatModel {
                    answers(brief)
                    answers(menu)
                    says("Nothing to plan.")
                }
            val slowMap =
                StreetMap { _, _, _, _ ->
                    delay(30.seconds)
                    listOf(zeDaMouraria)
                }

            var guideReads = 0
            val guide =
                TravelGuide {
                    guideReads++
                    GuidePage("Most restaurants are small and family-run.", listOf(santaRitaInGuide))
                }

            val events = planner(model, sources(map = slowMap, guide = guide)).stream(interviewed("One day in Lisbon"), config()).toList()

            val named = events.indexOfFirst { it is GraphEvent.NodeProgress && it.value is Named }
            val mapDone = events.indexOfFirst { it is GraphEvent.NodeCompleted && it.node == Nodes.MAP }
            assertTrue(named in 0 until mapDone, "dishes at $named, map done at $mapDone")
            assertEquals(menu.dishes, (events[named] as GraphEvent.NodeProgress).value.let { (it as Named).dishes })
            // The guide was read once, for its node and for the dishes, and what it says reached the dishes.
            assertTrue("small and family-run" in model.requests[1].messages.first().text)
            assertEquals(1, guideReads)
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

            planner(model, sources()).invoke(interviewed("One day in Lisbon, no pork", Profile(diet = "no pork")), config())

            val first = model.requests[2].messages.first().text
            assertTrue("- Diet: no pork" in first, first)
            assertTrue("- Day 1: $lisbon" in first, first)
            assertTrue("- bacalhau à brás (dinner): Salt cod with eggs and fried potato." in first, first)
            assertTrue("[r1] Restaurante Santa Rita · portuguese · found in: Travel guide, Street map" in first, first)
            assertEquals(listOf("candidates", "search_map", "read_guide", "set_route", "set_meal", "offer_choices", "remove_meal", "set_days", "add_stop"), model.requests[2].tools.map { it.name })
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

            planner(model, sources()).test(interviewed("One day in Lisbon"), config()) {
                expectNodes(Nodes.INTERVIEW, Nodes.BRIEF)
                // The sources run in one step.
                expectStep(Nodes.GUIDE, Nodes.MAP, Nodes.DISHES)
                expectNodes(Nodes.SPECIALISTS, Nodes.PLAN, Nodes.TOOLS, Nodes.PLAN, Nodes.ORDER)
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

            val asked = planner.invoke(interviewed("I love seafood"), config)

            assertIs<GraphResult.Interrupted<Trip>>(asked)
            assertEquals(listOf(Question(Topic.WHERE, "Which city are you visiting?")), asked.state.questions)
            assertEquals(listOf(Nodes.BRIEF), asked.nextNodes)

            val answered = planner.resume(config) { it.answered(listOf(Answer(Topic.WHERE, "Which city are you visiting?", "Lisbon, one day"))) }

            assertEquals(listOf(lisbon), answered.state.stops.map { it.name })
            assertTrue("Lisbon, one day" in model.requests[1].messages.first().text)
        }

    @Test
    fun `a place the map does not know is asked for`() =
        runTest {
            val model = ScriptedChatModel { answers(Brief(stops = listOf(BriefStop("Atlantis")))) }

            val asked = planner(model, sources()).invoke(interviewed("Two days in Atlantis"), config())

            assertEquals("I could not find Atlantis on the map. Which place do you mean?", asked.state.questions.single().text)
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

            val trip = planner(model, sources(map = broken)).invoke(interviewed("One day in Lisbon"), config()).state

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

            val trip = planner(model, sources(map = unreachable)).invoke(interviewed("One day in Lisbon"), config()).state

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

            val trip = planner(model, sources(map = slow)).invoke(interviewed("One day in Lisbon"), config()).state

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

            val planner = planner(SampleModel(pause = Duration.ZERO), sources(map = map))
            val config = config()

            // The sample asks one round of questions, and then shows what it understood.
            val asked = planner.invoke(Trip("Anything"), config)
            assertEquals(listOf(Topic.ALLERGIES, Topic.PREGNANCY, Topic.DIET), asked.state.questions.map { it.topic })
            val checking = planner.resume(config) { trip -> trip.answered(trip.questions.map { Answer(it.topic, it.text, it.options.first()) }) }
            assertEquals(InterviewStage.CHECKING, checking.state.interview.stage)
            val result = planner.resume(config) { it.confirmed() }

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
    fun `the places known for a dish are searched once the dishes are named, and the agent reads them first`() =
        runTest {
            val searches = mutableListOf<MapSearch>()
            val bifanas = Restaurant(stop = lisbon, name = "Casa das Bifanas", lat = 38.7135, lon = -9.1390, cuisine = "sandwich", sources = setOf(Source.MAP))
            val castle = Sight(lisbon, "Castelo de São Jorge", 38.7139, -9.1334)
            val map =
                object : StreetMap {
                    override suspend fun restaurants(stop: String, near: Location, search: MapSearch, radius: Int): List<Restaurant> {
                        searches += search
                        return if (search is MapSearch.Dishes) listOf(bifanas) else listOf(santaRitaOnMap, zeDaMouraria)
                    }

                    override suspend fun sights(stop: String, near: Location, radius: Int): List<Sight> = listOf(castle)
                }
            val queries = mutableListOf<String>()
            val reviews = PlaceReviews { _, query, _ -> emptyList<Restaurant>().also { queries += query } }
            val dishes =
                Menu(
                    listOf(
                        Dish(lisbon, "Bifana", "A pork sandwich.", "lunch", words = listOf("bifana", "bifanas")),
                        Dish(lisbon, "Ginjinha", "A cherry liqueur.", "snack"),
                    ),
                )
            val model =
                ScriptedChatModel {
                    answers(brief)
                    answers(dishes)
                    says("Planned.")
                }

            val run = planner(model, sources(map = map, reviews = reviews)).stream(interviewed("One day in Lisbon"), config()).record()

            assertEquals(listOf(setOf(Nodes.SPECIALISTS)), run.steps.drop(3).take(1).map { it.toSet() })
            assertEquals(MapSearch.Dishes(listOf("bifana", "bifanas")), searches.last())
            // A drink that every bar pours has no words: only the sandwich is searched in the reviews.
            assertEquals("best Bifana in $lisbon", queries.last())
            // Google bills each search: the trip counts them.
            assertEquals(queries.size, run.state.googleSearches)
            assertEquals(listOf(castle), run.state.sights)
            val prompt = model.requests[2].messages.first().text
            assertTrue(Regex("""\[r\d+\] Casa das Bifanas · sandwich · known for: Bifana""").containsMatchIn(prompt), prompt)
        }

    @Test
    fun `when the map does not answer, the places known for a dish are still searched in the reviews`() =
        runTest {
            val cafe = Restaurant(stop = lisbon, name = "Café Bifana", lat = 38.7135, lon = -9.1390, rating = 4.7, ratings = 900, sources = setOf(Source.REVIEWS))
            val queries = mutableListOf<String>()
            val reviews = PlaceReviews { _, query, _ -> (if (query.startsWith("best")) listOf(cafe) else emptyList()).also { queries += query } }
            val dishes = Menu(listOf(Dish(lisbon, "Bifana", "A pork sandwich.", "lunch", words = listOf("bifana"))))
            val model =
                ScriptedChatModel {
                    answers(brief)
                    answers(dishes)
                    says("Planned.")
                }
            val down = StreetMap { _, _, _, _ -> throw SourceException("No server of the street map answered.") }

            val run = planner(model, sources(map = down, reviews = reviews)).stream(interviewed("One day in Lisbon"), config()).record()

            assertEquals("best Bifana in $lisbon", queries.last())
            assertTrue("Café Bifana · known for: Bifana" in model.requests[2].messages.first().text)
            // The map's own step says that it did not answer, once.
            assertEquals(1, run.state.notes.size, run.state.notes.toString())
        }

    @Test
    fun `the traveller's answers are kept when the step that reads them fails, so trying again goes on with them`() =
        runTest {
            val config = config()
            val model = ScriptedChatModel { answers(Reading(Profile())) }
            val asked = planner(model, sources()).invoke(Trip("One day in Lisbon"), config)
            val answers = asked.state.questions.map { Answer(it.topic, it.text, "Peanuts") }

            // The script has no answer for the reading of the answers: the step fails.
            assertFailsWith<AssertionError> { planner(model, sources()).resume(config) { it.answered(answers) } }

            assertEquals(answers, config.checkpointer!!.load(config.threadId)!!.state.answers)
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

            val run = planner(model, sources(reviews = reviews)).stream(interviewed("One day in Lisbon"), config()).record()

            assertEquals(setOf(Nodes.GUIDE, Nodes.MAP, Nodes.REVIEWS, Nodes.DISHES), run.steps[2].toSet())
            // The salt cod of the menu has no words to search for: the specialists step searches nothing.
            assertEquals(listOf("traditional portuguese restaurant where locals eat", "best bacalhau"), queries)
            // The node's search and the agent's both count.
            assertEquals(2, run.state.googleSearches)
            // The rating joined the entry of the map.
            val ze = run.state.restaurants.single { it.name == "Zé da Mouraria" }
            assertEquals(setOf(Source.MAP, Source.REVIEWS), ze.sources)
            assertEquals(4.6, ze.rating)
            val result = model.requests[3].messages.last() as ChatMessage.ToolResult
            assertTrue(result.text.startsWith("[r2] Zé da Mouraria · portuguese · found in: Street map, Google reviews · 4.6 stars (3200 ratings)"), result.text)
        }

    @Test
    fun `a traveller who stays longer gets the days added, and the agent plans them`() =
        runTest {
            val model =
                ScriptedChatModel {
                    answers(brief)
                    answers(menu)
                    calls("set_meal", MealChoice(1, "lunch", "r1", "bacalhau à brás", "The guide knows it."))
                    says("One day of salt cod.")
                    calls(
                        call("set_days", StopDays("Lisbon", 2)),
                        call("set_meal", MealChoice(2, "lunch", "r2", "caldo verde", "The map lists it as Portuguese.")),
                    )
                    says("A second day, with soup.")
                }

            planner(model, sources()).test(interviewed("One day in Lisbon"), config()) {
                expectNodes(Nodes.INTERVIEW, Nodes.BRIEF)
                expectStep(Nodes.GUIDE, Nodes.MAP, Nodes.DISHES)
                expectNodes(Nodes.SPECIALISTS, Nodes.PLAN, Nodes.TOOLS, Nodes.PLAN, Nodes.ORDER)
                expectInterruptBefore(Nodes.REVIEW) { trip -> assertEquals(1, trip.days) }

                resumeWith { it.copy(messages = it.messages + ChatMessage.User("We stay one more day.")) }
                expectNodes(Nodes.REVIEW, Nodes.PLAN, Nodes.TOOLS, Nodes.PLAN, Nodes.ORDER)
                expectInterruptBefore(Nodes.REVIEW) { trip ->
                    assertEquals(2, trip.days)
                    assertEquals(listOf(1 to "r1", 2 to "r2"), trip.route.sortedBy { it.day }.map { it.day to it.restaurantId })
                }
            }

            val results = model.requests[5].messages.filterIsInstance<ChatMessage.ToolResult>().takeLast(2)
            assertEquals(listOf(false, false), results.map { it.isError })
            assertEquals("The trip has 2 days now: days 1 to 2 in $lisbon. Still open: day 1 dinner, day 2 lunch, day 2 dinner.", results[0].text)
        }

    @Test
    fun `days added to a stop move the stops after it, and days taken away take their meals`() {
        val porto = Stop("Porto, Portugal", 2, 3, 41.1496, -8.6109)
        val trip =
            Trip(
                "Two days in Lisbon, then two in Porto",
                stops = listOf(Stop(lisbon, 2, 1, 38.7078, -9.1366), porto),
                route = listOf(Meal(1, MealTime.LUNCH, "r1", "", ""), Meal(2, MealTime.LUNCH, "r2", "", ""), Meal(3, MealTime.LUNCH, "r3", "", ""), Meal(4, MealTime.LUNCH, "r4", "", "")),
            )

        val longer = trip.withDays(trip.stops[0], 3)
        assertEquals(listOf(1 to 3, 4 to 2), longer.stops.map { it.firstDay to it.days })
        assertEquals(listOf(1, 2, 4, 5), longer.route.map { it.day })
        assertEquals(porto.name, longer.stopOn(5)?.name)

        val shorter = trip.withDays(trip.stops[0], 1)
        assertEquals(listOf(1 to 1, 2 to 2), shorter.stops.map { it.firstDay to it.days })
        assertEquals(listOf("r1" to 1, "r3" to 2, "r4" to 3), shorter.route.map { it.restaurantId to it.day })

        assertEquals(listOf(1, 2, 3, 4, 5), trip.withDays(porto, 3).route.map { it.day } + 5)

        val gone = trip.withDays(trip.stops[0], 0)
        assertEquals(listOf(porto.name to (1 to 2)), gone.stops.map { it.name to (it.firstDay to it.days) })
        assertEquals(listOf("r3" to 1, "r4" to 2), gone.route.map { it.restaurantId to it.day })

        val braga = trip.withStop(Stop("Braga, Portugal", 1, 0, 41.55, -8.42), after = trip.stops[0])
        assertEquals(listOf(1 to 2, 3 to 1, 4 to 2), braga.stops.map { it.firstDay to it.days })
        assertEquals(listOf(1, 2, 4, 5), braga.route.map { it.day })
        assertEquals(listOf(5 to 1), trip.withStop(Stop("Braga, Portugal", 1, 0, 41.55, -8.42), after = null).stops.drop(2).map { it.firstDay to it.days })
    }

    @Test
    fun `a place added to the trip gets its days and its candidates, and the agent plans it`() =
        runTest {
            val tasca = { stop: String -> Restaurant(stop = stop, name = "Tasca do Chico", lat = 38.7120, lon = -9.1301, cuisine = "portuguese", sources = setOf(Source.MAP)) }
            val map = StreetMap { stop, _, _, _ -> if (stop == lisbon) listOf(santaRitaOnMap, zeDaMouraria) else listOf(tasca(stop)) }
            val guide = TravelGuide { page -> if (page == lisbon) GuidePage("Most restaurants are small and family-run.", listOf(santaRitaInGuide)) else GuidePage("Fado houses and grilled sardines.", emptyList()) }
            val model =
                ScriptedChatModel {
                    answers(brief)
                    answers(menu)
                    calls("set_meal", MealChoice(1, "lunch", "r1", "bacalhau à brás", "The guide knows it."))
                    says("One day of salt cod.")
                    calls("add_stop", NewStop("Alfama, Portugal", days = 1, cuisines = listOf("portuguese")))
                    calls("set_meal", MealChoice(2, "lunch", "r3", "sardinhas assadas", "The map lists it as Portuguese."))
                    says("A day in Alfama too.")
                }

            val config = config()
            val planner = planner(model, sources(map = map, guide = guide))
            planner.invoke(interviewed("One day in Lisbon"), config)
            val changed = planner.resume(config) { it.copy(messages = it.messages + ChatMessage.User("Add a day in Alfama.")) }.state

            assertEquals(listOf(lisbon to 1, "Alfama, Portugal" to 2), changed.stops.map { it.name to it.firstDay })
            assertEquals(listOf(1 to "r1", 2 to "r3"), changed.route.sortedBy { it.day }.map { it.day to it.restaurantId })
            assertEquals("Fado houses and grilled sardines.", changed.guideNotes["Alfama, Portugal"])
            val added = model.requests[5].messages.filterIsInstance<ChatMessage.ToolResult>().last().text
            assertTrue(added.startsWith("Added Alfama, Portugal: day 2. The trip has 2 days now: day 1 in $lisbon, day 2 in Alfama, Portugal."), added)
            assertTrue("[r3] Tasca do Chico" in added, added)
            assertEquals(0, model.remaining)
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

            val trip = planner(model, sources()).invoke(interviewed("One day in Lisbon"), config()).state

            val results = model.requests[3].messages.filterIsInstance<ChatMessage.ToolResult>()
            assertEquals(listOf(true, true, true, false), results.map { it.isError })
            assertEquals("There is no candidate with the id \"r99\". Use an id from the list.", results[0].text)
            assertEquals("The trip has 1 days. There is no day 2. To make a stay longer, call set_days first.", results[1].text)
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

            val result = planner(model, sources(), tokenBudget = 1000).invoke(interviewed("One day in Lisbon"), config())

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

            val closed = launch { planner.stream(interviewed("One day in Lisbon"), config).collect {} }
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

    @Test
    fun `an agent that keeps searching is told to write the route`() =
        runTest {
            val model =
                ScriptedChatModel {
                    answers(brief)
                    answers(menu)
                    calls("candidates", CandidateSearch(lisbon, "a"))
                    repeat(7) { calls("candidates", CandidateSearch(lisbon, "portuguese")) }
                    says("Planned.")
                }

            planner(model, sources()).invoke(interviewed("One day in Lisbon"), config())

            val results = model.requests.last().messages.filterIsInstance<ChatMessage.ToolResult>()
            assertEquals("Search for a word of at least three letters, such as a dish or a cuisine.", results.first().text)
            assertTrue(results.dropLast(1).none { "Stop searching" in it.text })
            assertTrue(results.last().text.endsWith("You have searched 8 times. Stop searching and write the route now with set_route, from the candidates you have. Still open: day 1 lunch, day 1 dinner."), results.last().text)
        }

    @Test
    fun `a change has a token budget of its own`() =
        runTest {
            val model =
                ScriptedChatModel {
                    answers(brief)
                    answers(menu)
                    calls("set_meal", MealChoice(1, "lunch", "r1", "bacalhau", "Fine."), usage = TokenUsage(900, 100))
                    calls("set_meal", MealChoice(1, "dinner", "r2", "caldo verde", "Fine."), usage = TokenUsage(900, 100))
                    says("Changed.")
                }
            val planner = planner(model, sources(), tokenBudget = 1000)
            val config = config()

            planner.invoke(interviewed("One day in Lisbon"), config)
            val changed = planner.resume(config) { it.copy(messages = it.messages + ChatMessage.User("Add a dinner.")) }

            // The first turn used its budget; the change was still asked of the model, and stopped at its own.
            assertEquals(listOf("r1", "r2"), changed.state.route.map { it.restaurantId })
            assertEquals(1, model.remaining)
        }

    @Test
    fun `the interview asks in rounds, reads the answers, and plans once the traveller checked them`() =
        runTest {
            val model =
                ScriptedChatModel {
                    answers(
                        Reading(
                            Profile(),
                            answered = listOf(Topic.WHERE),
                            questions = listOf(Question(Topic.ALLERGIES, "Lisbon cooks a lot of shellfish. Any allergy?", listOf("None", "Shellfish"))),
                        ),
                    )
                    answers(Reading(Profile(allergies = listOf(Allergy("shellfish", severe = true)), budget = "low"), answered = Topic.entries))
                    answers(brief)
                    answers(menu)
                    says("Planned.")
                }
            val planner = planner(model, sources())
            val config = config()

            val asked = planner.invoke(Trip("One day in Lisbon"), config)

            // What the request said is not asked. The model's words are used where it wrote some.
            assertIs<GraphResult.Interrupted<Trip>>(asked)
            assertEquals(listOf(Nodes.INTERVIEW), asked.nextNodes)
            assertEquals(listOf("Lisbon cooks a lot of shellfish. Any allergy?", English.question(Topic.PREGNANCY).text, English.question(Topic.DIET).text), asked.state.questions.map { it.text })
            assertTrue("- WHERE: Where and how long\n" in model.requests[0].messages.first().text)

            val checking = planner.resume(config) { trip -> trip.answered(trip.questions.map { Answer(it.topic, it.text, if (it.topic == Topic.ALLERGIES) "Shellfish, even traces" else "") }) }

            // The second reading saw the answers, a skipped one too, and the planner shows what it understood.
            assertIs<GraphResult.Interrupted<Trip>>(checking)
            assertEquals(InterviewStage.CHECKING, checking.state.interview.stage)
            assertEquals(listOf(Allergy("shellfish", severe = true)), checking.state.profile.allergies)
            val second = model.requests[1].messages.first().text
            assertTrue("- Lisbon cooks a lot of shellfish. Any allergy? Shellfish, even traces" in second, second)
            assertTrue("- ${English.question(Topic.DIET).text} (no answer)" in second, second)
            assertTrue("- ALLERGIES: Allergies (done)" in second, second)

            val planned = planner.resume(config) { it.confirmed() }

            assertEquals(listOf(Nodes.REVIEW), (planned as GraphResult.Interrupted).nextNodes)
            // The brief reads the answers too.
            assertTrue("Shellfish, even traces" in model.requests[2].messages.first().text)
            assertEquals(0, model.remaining)
        }

    @Test
    fun `a trip planned again keeps the answers and the profile, and the traveller checks the profile first`() =
        runTest {
            val model =
                ScriptedChatModel {
                    answers(brief)
                    answers(menu)
                    calls("set_meal", MealChoice(1, "lunch", "r1", "bacalhau à brás", "The guide knows it."))
                    says("Planned.")
                    answers(brief)
                    answers(menu)
                    calls("set_meal", MealChoice(1, "lunch", "r2", "caldo verde", "The map lists it as Portuguese."))
                    says("Planned again.")
                }
            val planner = planner(model, sources())
            val answers = listOf(Answer(Topic.DIET, "Anything you do not eat?", "No pork"))
            val start = interviewed("One day in Lisbon", Profile(diet = "no pork")).copy(answers = answers, interview = Interview(read = 1, stage = InterviewStage.DONE))
            val first = planner.invoke(start, config()).state

            val again = first.plannedAgain()
            assertEquals(Trip("One day in Lisbon", answers = answers, profile = Profile(diet = "no pork"), interview = Interview(read = 1, stage = InterviewStage.CHECKING)), again)

            val config = config()
            // The profile is shown without asking the model to read it again.
            val checking = planner.invoke(again, config)
            assertEquals(listOf(Nodes.INTERVIEW), (checking as GraphResult.Interrupted).nextNodes)
            assertEquals(InterviewStage.CHECKING, checking.state.interview.stage)

            val planned = planner.resume(config) { it.confirmed() }
            assertEquals(listOf("r2"), planned.state.route.map { it.restaurantId })
            assertEquals("Planned again.", planned.state.summary)
            assertEquals(0, model.remaining)
        }

    @Test
    fun `what the traveller answered on the start page is not asked again`() =
        runTest {
            val model =
                ScriptedChatModel {
                    // The model would ask about allergies again: the topics answered on the start page are covered.
                    answers(Reading(Profile(allergies = listOf(Allergy("shellfish"))), answered = listOf(Topic.WHERE), questions = listOf(Question(Topic.ALLERGIES, "Any allergy?"), Question(Topic.DATES, "When?"))))
                }
            val answers =
                UP_FRONT.map { English.question(it) }.map { question ->
                    Answer(question.topic, question.text, mapOf(Topic.ALLERGIES to "Shellfish", Topic.PREGNANCY to "No")[question.topic].orEmpty())
                }
            val trip = newTrip("One day in Lisbon", Language.ENGLISH, answers)
            // A skipped question is no answer, and the interview asks it.
            assertEquals(listOf(Topic.ALLERGIES, Topic.PREGNANCY), trip.answers.map { it.topic })

            val asked = planner(model, sources()).invoke(trip, config())

            assertEquals(listOf(Topic.DIET, Topic.DATES, Topic.PARTY), asked.state.questions.map { it.topic })
            val prompt = model.requests.single().messages.first().text
            assertTrue("- ALLERGIES: Allergies (done)" in prompt, prompt)
            assertTrue("- ${English.question(Topic.ALLERGIES).text} Shellfish" in prompt, prompt)
        }

    @Test
    fun `a traveller who wants to plan right away skips the rest of the interview`() =
        runTest {
            val model =
                ScriptedChatModel {
                    answers(Reading(Profile()))
                    answers(Reading(Profile(diet = "vegetarian"), questions = listOf(Question(Topic.DATES, "When?"))))
                    answers(brief)
                    answers(menu)
                    says("Planned.")
                }
            val planner = planner(model, sources())
            val config = config()

            planner.invoke(Trip("One day in Lisbon"), config)
            val checking = planner.resume(config) { it.planNow(listOf(Answer(Topic.DIET, "Anything you do not eat?", "Vegetarian"))) }
            // No more questions, but the traveller still sees what the planner understood.
            assertEquals(emptyList(), checking.state.questions)
            assertEquals(InterviewStage.CHECKING, checking.state.interview.stage)
            val planned = planner.resume(config) { it.confirmed() }

            assertEquals(listOf(Nodes.REVIEW), (planned as GraphResult.Interrupted).nextNodes)
            assertEquals("vegetarian", planned.state.profile.diet)
            assertEquals(1, planned.state.interview.rounds)
        }

    @Test
    fun `a correction is read and the profile is shown again`() =
        runTest {
            val model =
                ScriptedChatModel {
                    // Even a request that says everything is asked about allergies.
                    answers(Reading(Profile(party = "two adults"), answered = Topic.entries))
                    answers(Reading(Profile(party = "two adults"), answered = Topic.entries))
                    answers(Reading(Profile(party = "three adults"), answered = Topic.entries))
                }
            val planner = planner(model, sources())
            val config = config()

            val asked = planner.invoke(Trip("One day in Lisbon, the two of us"), config)
            assertEquals(listOf(Topic.ALLERGIES, Topic.PREGNANCY), asked.state.questions.map { it.topic })
            val checking = planner.resume(config) { it.answered(listOf(Answer(Topic.ALLERGIES, "Any allergy?", "None"))) }
            assertEquals(InterviewStage.CHECKING, checking.state.interview.stage)
            val again = planner.resume(config) { it.corrected("We are three.") }

            assertEquals(InterviewStage.CHECKING, again.state.interview.stage)
            assertEquals("three adults", again.state.profile.party)
            assertTrue("- Is there anything to correct? We are three." in model.requests[2].messages.first().text)
        }

    @Test
    fun `a profile the traveller edited is planned for without asking the model to read it`() =
        runTest {
            val model =
                ScriptedChatModel {
                    answers(Reading(Profile(diet = "no pork"), answered = Topic.entries))
                    answers(Reading(Profile(diet = "no pork"), answered = Topic.entries))
                    answers(brief)
                    answers(menu)
                    says("Planned.")
                }
            val planner = planner(model, sources())
            val config = config()

            planner.invoke(Trip("One day in Lisbon"), config)
            planner.resume(config) { it.answered(listOf(Answer(Topic.ALLERGIES, "Any allergy?", "None"))) }
            val planned = planner.resume(config) { it.edited(Profile(allergies = listOf(Allergy("shellfish")))).confirmed() }

            assertEquals(listOf(Allergy("shellfish")), planned.state.profile.allergies)
            assertEquals("", planned.state.profile.diet)
            assertTrue("Allergies: shellfish" in model.requests[4].messages.first().text)
            assertEquals(0, model.remaining)
        }

    @Test
    fun `a profile changed on a planned route goes to the agent as the traveller's message`() =
        runTest {
            val model =
                ScriptedChatModel {
                    answers(brief)
                    answers(menu)
                    calls("set_meal", MealChoice(1, "dinner", "r2", "caldo verde", "The map lists it as Portuguese."))
                    says("Planned.")
                    calls("set_meal", MealChoice(1, "breakfast", "r1", "pastel de nata", "The guide calls it authentic."))
                    says("Added a breakfast.")
                }
            val planner = planner(model, sources())
            val config = config()

            planner.invoke(interviewed("One day in Lisbon", Profile(pregnant = true)), config)
            val changed = planner.resume(config) { it.replannedFor(Profile(pregnant = true, meals = listOf(MealTime.BREAKFAST, MealTime.DINNER))) }

            // The route is changed, not planned again.
            assertEquals(listOf("r1", "r2"), changed.state.route.map { it.restaurantId })
            val told = model.requests[4].messages.last { it is ChatMessage.User }.text
            assertTrue("I changed what you know about me" in told)
            assertTrue("Meals of every day: breakfast, dinner" in told)
        }

    @Test
    fun `an erased profile leaves nothing in the agent's conversation`() =
        runTest {
            val model =
                ScriptedChatModel {
                    answers(brief)
                    answers(menu)
                    calls("set_meal", MealChoice(1, "dinner", "r2", "caldo verde", "The map lists it as Portuguese.", caution = "Ask for it without the chouriço: it is cured."))
                    says("Planned.")
                    calls("set_meal", MealChoice(1, "dinner", "r1", "bacalhau à brás", "The guide calls it authentic."))
                    says("Planned again.")
                }
            val planner = planner(model, sources())
            val config = config()

            planner.invoke(interviewed("One day in Lisbon", Profile(pregnant = true)), config)
            val erased = planner.resume(config) { it.replannedFor(Profile(), erase = true) }

            assertEquals(Profile(), erased.state.profile)
            assertEquals(listOf("r1"), erased.state.route.map { it.restaurantId })
            // The agent starts again from a first message that is written for the empty profile.
            val again = model.requests[4].messages
            assertEquals(1, again.size)
            assertTrue("Pregnancy" !in again.single().text)
            assertTrue(erased.state.messages.none { "chouriço" in it.toString() || "Pregnancy" in it.toString() })
        }

    @Test
    fun `the dishes and the route are planned for the traveller's profile`() =
        runTest {
            val profile =
                Profile(
                    allergies = listOf(Allergy("shellfish", severe = true)),
                    pregnant = true,
                    meals = listOf(MealTime.LUNCH, MealTime.BREAKFAST),
                    budget = "high",
                    splurge = true,
                    taste = "anything the locals eat",
                    season = "June",
                )
            val model =
                ScriptedChatModel {
                    answers(brief)
                    answers(menu)
                    calls("set_meal", MealChoice(1, "lunch", "r1", "bacalhau à brás", "The guide knows it.", caution = "Ask if the oil was used for prawns."))
                    says("Planned.")
                }

            val trip = planner(model, sources()).invoke(interviewed("One day in Lisbon", profile), config()).state

            val dishes = model.requests[1].messages.first().text
            assertTrue("Leave out what the traveller cannot eat:\n- shellfish, even traces" in dishes, dishes)
            assertTrue("The trip is in June" in dishes, dishes)
            val plan = model.requests[2].messages.first().text
            assertTrue("- Allergies: shellfish, even traces" in plan, plan)
            assertTrue("- Budget: high, with one special meal" in plan, plan)
            // A pregnancy reaches both models, with what to leave out.
            assertTrue("- Pregnancy: someone on the trip is pregnant, so their dishes have no raw or undercooked fish" in plan, plan)
            assertTrue("- someone on the trip is pregnant" in dishes, dishes)
            assertTrue("- Meals of every day: breakfast, lunch\n" in plan, plan)
            // The meals the traveller asked for are the ones the agent is told are open.
            val result = model.requests[3].messages.last() as ChatMessage.ToolResult
            assertEquals("Day 1, lunch: Restaurante Santa Rita. Still open: day 1 breakfast.", result.text)
            assertEquals("Ask if the oil was used for prawns.", trip.route.single().caution)
        }

    @Test
    fun `the first walk of a day starts where the traveller sleeps`() =
        runTest {
            val model =
                ScriptedChatModel {
                    answers(Brief(stops = listOf(BriefStop(lisbon, days = 1, cuisines = listOf("portuguese"), stay = "Alfama"))))
                    answers(menu)
                    calls("set_meal", MealChoice(1, "lunch", "r1", "bacalhau à brás", "The guide knows it."))
                    says("Planned.")
                }

            val trip = planner(model, sources()).invoke(interviewed("One day in Lisbon, staying in Alfama", Profile(nearStay = listOf(MealTime.DINNER))), config()).state

            assertEquals(Location(38.7118, -9.1300), trip.stops.single().stay)
            assertTrue(trip.route.single().walk in 400..700, "walk was ${trip.route.single().walk}")
            assertTrue("- Near where they sleep, within 700 m: dinner\n" in model.requests[2].messages.first().text)
            assertEquals(emptyList(), trip.notes)
        }

    @Test
    fun `a traveller who changes hotels walks from the place of each night, and to breakfast from the night before`() =
        runTest {
            val moves = listOf(BriefStay("Baixa", fromDay = 3), BriefStay("Hotel Avenida", fromDay = 2))
            val model =
                ScriptedChatModel {
                    answers(Brief(stops = listOf(BriefStop(lisbon, days = 3, cuisines = listOf("portuguese"), stay = "Alfama", moves = moves))))
                    answers(menu)
                    says("Nothing to plan.")
                }

            val trip = planner(model, sources()).invoke(interviewed("Three days in Lisbon"), config()).state

            val stop = trip.stops.single()
            assertEquals("Alfama", stop.stayName)
            // The hotel that the map finds in another town is left out, and the one before it goes on.
            assertEquals(listOf(Stay("Baixa", 3, 38.7114, -9.1367)), stop.stays)
            assertEquals(Location(38.7118, -9.1300), trip.stayFor(2, MealTime.DINNER))
            assertEquals(Location(38.7118, -9.1300), trip.stayFor(3, MealTime.BREAKFAST))
            assertEquals(Location(38.7114, -9.1367), trip.stayFor(3, MealTime.DINNER))
            val first = model.requests[2].messages.first().text
            assertTrue("- Days 1 to 3: $lisbon, sleeping at Alfama, from the night of day 3 at Baixa\n" in first, first)
        }

    @Test
    fun `a meal the traveller wants near where they sleep is pointed out to the agent when it is far`() {
        val trip =
            Trip(
                "One day in Lisbon",
                profile = Profile(nearStay = listOf(MealTime.BREAKFAST)),
                stops = listOf(Stop(lisbon, 1, 1, 38.7078, -9.1366, stay = Location(38.7118, -9.1300), stayName = "Alfama")),
                restaurants = listOf(zeDaMouraria.copy(id = "r1", lat = 38.7125, lon = -9.1310), santaRitaOnMap.copy(id = "r2", lat = 38.7300, lon = -9.1500)),
                route = listOf(Meal(1, MealTime.BREAKFAST, "r1", "", ""), Meal(1, MealTime.LUNCH, "r2", "", "")),
            )
        // The breakfast is a short walk away, and the lunch need not be.
        assertEquals("", trip.farFromStay(trip.route))

        val far = trip.copy(route = listOf(Meal(1, MealTime.BREAKFAST, "r2", "", "")))
        val told = far.farFromStay(far.route)
        assertTrue(told.startsWith("\nDay 1 breakfast at Santa Rita is "), told)
        assertTrue(told.endsWith(" m from Alfama, but the traveller wants it near where they sleep: choose a place within 700 m, and search with near \"Alfama\" if no candidate is."), told)
    }

    @Test
    fun `a dish ordered twice on the route is pointed out to the agent, by the stop's dish it names`() {
        val trip =
            Trip(
                "Three days in Lisbon",
                dishes = listOf(Dish(lisbon, "Pastel de nata", "A custard tart.", "breakfast")),
                route =
                    listOf(
                        Meal(1, MealTime.BREAKFAST, "r1", "pastel de nata", ""),
                        Meal(1, MealTime.LUNCH, "r2", "bifana", ""),
                        Meal(3, MealTime.BREAKFAST, "r3", "Pastel de nata, warm, with a bica", ""),
                    ),
            )

        val told = trip.repeatedDishes(trip.route.takeLast(1))

        assertEquals(
            "\nThe same dish more than once: pastel de nata on day 1 breakfast, day 3 breakfast. " +
                "Give each meal its own dish unless the traveller asked for this one again: a stop has more to eat at that time of the day than its best-known dish.",
            told,
        )
        // A meal whose dish is on the route once is no repetition.
        assertEquals("", trip.repeatedDishes(trip.route.filter { it.time == MealTime.LUNCH }))
    }

    @Test
    fun `a dish people eat at breakfast or as a snack is pointed out to the agent at a lunch or a dinner`() {
        val trip =
            Trip(
                "Two days in Lisbon",
                dishes = listOf(Dish(lisbon, "Pastel de nata", "A custard tart.", "breakfast"), Dish(lisbon, "bifana", "A pork sandwich.", "snack")),
                route =
                    listOf(
                        Meal(1, MealTime.BREAKFAST, "r1", "pastel de nata", ""),
                        Meal(1, MealTime.LUNCH, "r2", "bacalhau à brás", ""),
                        Meal(1, MealTime.DINNER, "r3", "A pastel de nata, warm", ""),
                        Meal(2, MealTime.SNACK, "r4", "bifana", ""),
                    ),
            )

        assertEquals(
            "\nDay 1 dinner orders Pastel de nata, which people there eat at breakfast, not as a dinner. " +
                "Unless the place makes a meal of it, as a stall of street food does, order a dish people eat at dinner.",
            trip.lightMeals(trip.route),
        )
        // A dish at the meal people eat it at, or one the stop's dishes do not name, is no such meal.
        assertEquals("", trip.lightMeals(trip.route.filter { it.time != MealTime.DINNER }))
    }

    @Test
    fun `a search near a place looks around it, and says how far each place is from it`() =
        runTest {
            var searched: Pair<Location, Int>? = null
            val map =
                StreetMap { _, near, _, radius ->
                    if (radius != MAP_RADIUS) searched = near to radius
                    listOf(santaRitaOnMap, zeDaMouraria)
                }
            val model =
                ScriptedChatModel {
                    answers(brief)
                    answers(menu)
                    calls("search_map", MapLookup(lisbon, cuisine = "portuguese", near = "Alfama"))
                    calls("set_meal", MealChoice(1, "lunch", "r2", "caldo verde", "Near Alfama."))
                    says("Planned.")
                }

            planner(model, sources(map = map)).invoke(interviewed("One day in Lisbon"), config())

            assertEquals(Location(38.7118, -9.1300) to 1000, searched)
            val listed = model.requests[3].messages.filterIsInstance<ChatMessage.ToolResult>().last().text
            assertTrue(Regex("""\[r2\] Zé da Mouraria .* · \d+ m from Alfama""").containsMatchIn(listed), listed)
        }

    @Test
    fun `a place the traveller wants changed is replaced for their reasons, and not chosen again`() =
        runTest {
            val model =
                ScriptedChatModel {
                    answers(brief)
                    answers(menu)
                    calls("set_meal", MealChoice(1, "lunch", "r1", "bacalhau à brás", "The guide knows it."))
                    says("Salt cod for lunch.")
                    calls("set_meal", MealChoice(1, "lunch", "r1", "bacalhau à brás", "Still the best."))
                    calls("set_meal", MealChoice(1, "lunch", "r2", "caldo verde", "The map lists it as Portuguese."))
                    says("Soup instead.")
                }
            val config = config()
            val planner = planner(model, sources())
            planner.invoke(interviewed("One day in Lisbon"), config)

            val swap = MealSwap(1, MealTime.LUNCH, listOf(SwapReason.DISH, SwapReason.CLOSER), instead = "caldo verde", near = "Alfama")
            val changed = planner.resume(config) { it.swapped(listOf(swap)) }.state

            assertEquals(listOf("r2"), changed.route.map { it.restaurantId })
            assertEquals(listOf("r1"), changed.rejected)
            val asked = model.requests[4].messages.last { it is ChatMessage.User }.text
            assertTrue("- Day 1 lunch, now Restaurante Santa Rita [r1] with bacalhau à brás.\n" in asked, asked)
            assertTrue("Why not: the traveller does not fancy this dish; wants it closer to somewhere." in asked, asked)
            assertTrue("Instead, the traveller would like: caldo verde." in asked, asked)
            assertTrue("Closer to: Alfama. Search with near set to it." in asked, asked)
            val refused = model.requests[5].messages.filterIsInstance<ChatMessage.ToolResult>().last()
            assertTrue(refused.isError)
            assertEquals("The traveller asked to change Restaurante Santa Rita: choose another place.", refused.text)
            assertEquals(0, model.remaining)
        }

    @Test
    fun `the agent offers places for a meal, the recommended one first, and hears which one the traveller chose`() =
        runTest {
            val tasca = Restaurant(stop = lisbon, name = "Tasca do Chico", lat = 38.7120, lon = -9.1301, cuisine = "portuguese", sources = setOf(Source.MAP))
            val model =
                ScriptedChatModel {
                    answers(brief)
                    answers(menu)
                    calls("set_meal", MealChoice(1, "lunch", "r1", "bacalhau à brás", "The guide knows it."))
                    says("Salt cod for lunch.")
                    calls(
                        "offer_choices",
                        MealOffer(
                            1,
                            "lunch",
                            listOf(MealOption("r2", "caldo verde", "The map lists it as Portuguese."), MealOption("r3", "sardinhas assadas", "A tasca of the old town.")),
                            recommendation = "Zé da Mouraria is the one locals queue for.",
                        ),
                    )
                    says("Two places to choose from.")
                    says("Enjoy the sardines.")
                }
            val config = config()
            val planner = planner(model, sources(map = StreetMap { _, _, _, _ -> listOf(santaRitaOnMap, zeDaMouraria, tasca) }))
            planner.invoke(interviewed("One day in Lisbon"), config)

            val offered = planner.resume(config) { it.swapped(listOf(MealSwap(1, MealTime.LUNCH, listOf(SwapReason.DISH)))) }.state

            // The recommended place is on the route until the traveller chooses.
            assertEquals(listOf("r2"), offered.route.map { it.restaurantId })
            val offer = assertNotNull(offered.offerFor(1, MealTime.LUNCH))
            assertEquals(listOf("r2", "r3"), offer.options.map { it.restaurantId })
            assertEquals("r2", offer.recommended.restaurantId)
            assertEquals("Zé da Mouraria is the one locals queue for.", offer.recommendation)
            assertTrue("offer two or three new places with `offer_choices`" in model.requests[4].messages.last { it is ChatMessage.User }.text)

            // The traveller taps the other one: nothing runs, and the agent hears of it with their next words.
            val picked = offered.picked(offer.options[1])
            assertEquals(listOf("r3"), picked.route.map { it.restaurantId })
            assertEquals(listOf("Day 1 lunch: Tasca do Chico [r3]"), picked.picks)
            val after = planner.resume(config) { picked.copy(messages = picked.messages + ChatMessage.User("Thanks!")) }.state

            assertEquals("Of the places you offered, I chose:\n- Day 1 lunch: Tasca do Chico [r3]\n\nThanks!", model.requests.last().messages.last().text)
            assertEquals(emptyList(), after.picks)
            assertEquals(listOf("r3"), after.route.map { it.restaurantId })
            assertEquals(0, model.remaining)
        }

    @Test
    fun `an offer of one place, or of a place the traveller left, is refused`() =
        runTest {
            val model =
                ScriptedChatModel {
                    answers(brief)
                    answers(menu)
                    calls(
                        call("offer_choices", MealOffer(1, "lunch", listOf(MealOption("r1", "bacalhau", "Fine.")), "Only one.")),
                        call("offer_choices", MealOffer(1, "lunch", listOf(MealOption("r1", "bacalhau", "Fine."), MealOption("r2", "bifana", "Fine.")), "Two.")),
                    )
                    says("Planned.")
                }

            val trip = planner(model, sources()).invoke(interviewed("One day in Lisbon").copy(rejected = listOf("r2")), config()).state

            val results = model.requests[3].messages.filterIsInstance<ChatMessage.ToolResult>()
            assertEquals("Offer two or three places.", results[0].text)
            assertEquals("The traveller asked to change Zé da Mouraria: choose another place.", results[1].text)
            assertEquals(emptyList(), trip.offers)
        }

    @Test
    fun `a place to sleep that the map finds in another town is left out, and the traveller hears of it`() =
        runTest {
            val model =
                ScriptedChatModel {
                    answers(Brief(stops = listOf(BriefStop(lisbon, days = 1, stay = "Hotel Avenida"))))
                    answers(menu)
                    calls("set_meal", MealChoice(1, "lunch", "r1", "bacalhau à brás", "The guide knows it."))
                    says("Planned.")
                }

            val trip = planner(model, sources()).invoke(interviewed("One day in Lisbon", Profile(nearStay = listOf(MealTime.DINNER))), config()).state

            assertEquals(null, trip.stops.single().stay)
            assertEquals(null, trip.route.single().walk)
            assertEquals(listOf(Language.ENGLISH.texts.stayNotOnTheMap("Hotel Avenida", lisbon)), trip.notes)
            // Nothing can be measured from where they sleep, so the agent is not asked for a dinner near it.
            val first = model.requests[2].messages.first().text
            assertFalse("Near where they sleep" in first, first)
        }

    @Test
    fun `a traveller who wants a meal near where they sleep, and does not say where, hears of it`() =
        runTest {
            val model =
                ScriptedChatModel {
                    answers(brief)
                    answers(menu)
                    says("Nothing to plan.")
                }

            val trip = planner(model, sources()).invoke(interviewed("One day in Lisbon", Profile(nearStay = listOf(MealTime.DINNER))), config()).state

            assertEquals(listOf(Language.ENGLISH.texts.staySaidNowhere), trip.notes)
            assertFalse("Near where they sleep" in model.requests[2].messages.first().text)
        }

    @Test
    fun `every model is told to write in the traveller's language`() =
        runTest {
            val model =
                ScriptedChatModel {
                    // Allergies are asked even when the request answers everything: the interview reads twice.
                    answers(Reading(Profile(), answered = Topic.entries))
                    answers(Reading(Profile(), answered = Topic.entries))
                    answers(brief)
                    answers(menu)
                    says("Planificat.")
                }
            val planner = planner(model, sources())
            val config = config()

            planner.invoke(Trip("Un dia a Lisboa", language = Language.CATALAN), config)
            planner.resume(config) { it.answered(listOf(Answer(Topic.ALLERGIES, "Al·lèrgies?", "Cap"))) }
            planner.resume(config) { it.confirmed() }

            // The interview twice, the brief, the dishes and the agent.
            assertEquals(5, model.requests.size)
            model.requests.forEach { request ->
                val text = request.messages.first().text
                assertTrue("Write everything the traveller reads in Catalan" in text, text)
            }
        }

    @Test
    fun `words added in a pause before the route are read with the answers, and the trip is read again`() =
        runTest {
            val model =
                ScriptedChatModel {
                    answers(Reading(Profile(), answered = Topic.entries))
                    answers(Reading(Profile(), answered = Topic.entries))
                    // After the pause: the interview reads again, and everything after it is found again.
                    answers(Reading(Profile(drinks = "natural wine"), answered = Topic.entries))
                    answers(brief)
                    answers(menu)
                    says("Planned with wine.")
                }
            val planner = planner(model, sources())
            val config = config()
            planner.invoke(Trip("One day in Lisbon"), config)
            val checking = planner.resume(config) { it.answered(listOf(Answer(Topic.ALLERGIES, "Any allergy?", "None"))) }.state

            // The traveller confirmed, and paused while the brief was read: the page has the trip the run started from.
            val paused = checking.confirmed()
            assertTrue(!paused.planning)
            val planned = planner.invoke(paused.toldMore("We love natural wine."), config)

            assertTrue("- The traveller added, while the planner worked: We love natural wine." in model.requests[2].messages.first().text)
            assertEquals(listOf(Nodes.REVIEW), (planned as GraphResult.Interrupted).nextNodes)
            assertEquals("natural wine", planned.state.profile.drinks)
            assertEquals(listOf(lisbon), planned.state.stops.map { it.name })
            assertEquals(0, model.remaining)
        }

    @Test
    fun `words added in a pause of the agent are its next message, and calls it did not make are dropped`() =
        runTest {
            val model =
                ScriptedChatModel {
                    answers(brief)
                    answers(menu)
                    calls("set_meal", MealChoice(1, "dinner", "r2", "caldo verde", "The map lists it as Portuguese."))
                    calls("set_meal", MealChoice(1, "dinner", "r1", "bacalhau à brás", "Seafood, as asked."))
                    says("Planned with seafood.")
                }
            val planner = planner(model, sources())
            // The run stops where a pause would find it: the agent asked for a call that has not run.
            val config = config().copy(interruptAfter = setOf(Nodes.PLAN))
            val paused = planner.invoke(interviewed("One day in Lisbon"), config)
            assertEquals(listOf(Nodes.TOOLS), (paused as GraphResult.Interrupted).nextNodes)
            assertTrue(paused.state.planning)

            val redirected = planner.resume(config.copy(interruptAfter = emptySet())) { it.redirected("More seafood, please.") }

            assertEquals(listOf("r1"), redirected.state.route.map { it.restaurantId })
            val told = model.requests[3].messages
            assertTrue(told.none { it is ChatMessage.Assistant && it.toolCalls.isNotEmpty() })
            assertTrue("More seafood, please." in told.last().text)
        }

    @Test
    fun `words added before the agent wrote anything come after the trip in its first message`() {
        val trip = interviewed("One day in Lisbon").copy(stops = listOf(Stop(lisbon, 1, 1, 38.7, -9.1)), dishes = menu.dishes)

        val redirected = trip.redirected("Only vegetarian places.")

        val first = redirected.messages.single().text
        assertTrue(first.startsWith("Plan the meals of this trip."))
        assertTrue(first.endsWith("Plan the route with it in mind: change what it changes, keep what still fits, and go on."))
        assertTrue("Only vegetarian places." in first)
    }
}
