package dev.deeptelar.foodie

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleMapsTest {
    private val palma = "Palma de Mallorca, Spain"
    private val pages = Restaurant(id = "r1", stop = palma, name = "Celler Pagès", lat = 39.5696, lon = 2.6457, address = "Carrer de Felip Bauzà, 2, 07012 Palma", googleId = "ChIJpages", phone = "+34 971 72 17 81", reservation = "recommended")
    private val joan = Restaurant(id = "r2", stop = palma, name = "Ca'n Joan de s'Aigo", lat = 39.5699, lon = 2.6526, googleId = "ChIJjoan")
    private val julio = Restaurant(id = "r3", stop = palma, name = "Casa Julio", lat = 39.5698, lon = 2.6509, address = "C de la Previsió 4")
    private val trip =
        Trip(
            "Two days in Palma",
            stops = listOf(Stop(palma, 2, 1, 39.5696, 2.6501, stay = Location(39.5662, 2.6534), shown = "Palma")),
            restaurants = listOf(pages, joan, julio),
            route =
                listOf(
                    Meal(1, MealTime.DINNER, "r1", "escaldums", "A cellar of the old town.", caution = "No wine <for> her & no sobrassada."),
                    Meal(1, MealTime.BREAKFAST, "r2", "ensaïmada", "The famous one."),
                    Meal(1, MealTime.LUNCH, "r3", "sopes mallorquines", "Home cooking."),
                    Meal(2, MealTime.LUNCH, "r1", "frit", "Again."),
                ),
        )

    @Test
    fun `a place Google found opens as that place, and another as a search with its town`() {
        assertEquals(
            "https://www.google.com/maps/search/?api=1&query=Celler%20Pag%C3%A8s%2C%20Carrer%20de%20Felip%20Bauz%C3%A0%2C%202%2C%2007012%20Palma&query_place_id=ChIJpages",
            pages.googleMapsLink(),
        )
        assertEquals("https://www.google.com/maps/search/?api=1&query=Casa%20Julio%2C%20C%20de%20la%20Previsi%C3%B3%204%2C%20Palma%20de%20Mallorca", julio.googleMapsLink())
    }

    @Test
    fun `a day opens as a walk from where the traveller sleeps, through the meals in their order`() {
        val link = trip.dayInGoogleMaps(1)!!
        assertTrue(link.startsWith("https://www.google.com/maps/dir/?api=1&travelmode=walking&origin=39.5662%2C2.6534"), link)
        assertTrue("&destination=Celler%20Pag%C3%A8s&destination_place_id=ChIJpages" in link, link)
        // Casa Julio has no id of Google, so the stops between go by where they are.
        assertTrue("&waypoints=39.5699%2C2.6526%7C39.5698%2C2.6509" in link, link)
        assertTrue("waypoint_place_ids" !in link, link)
        assertNull(trip.dayInGoogleMaps(3))
    }

    @Test
    fun `a day with a place across town is no walk, and Google Maps offers the ways to get there`() {
        val far = trip.copy(restaurants = trip.restaurants.map { if (it.id == "r1") it.copy(lat = 39.5544, lon = 2.6797) else it })
        val link = far.dayInGoogleMaps(1)!!
        assertTrue(link.startsWith("https://www.google.com/maps/dir/?api=1&origin="), link)
        assertTrue("travelmode" !in link, link)
    }

    @Test
    fun `without where the traveller sleeps, the walk starts at the first meal`() {
        val link = trip.copy(stops = trip.stops.map { it.copy(stay = null) }).dayInGoogleMaps(1)!!
        assertTrue("&origin=Ca'n%20Joan%20de%20s'Aigo&origin_place_id=ChIJjoan" in link || "&origin=Ca%27n%20Joan%20de%20s%27Aigo&origin_place_id=ChIJjoan" in link, link)
        assertTrue("&waypoints=39.5698%2C2.6509&" in link || link.endsWith("&waypoints=39.5698%2C2.6509"), link)
    }

    @Test
    fun `the file for My Maps has a folder for each day and a pin for each meal, with what to order`() {
        val kml = trip.toKml()
        assertEquals(2, Regex("<Folder>").findAll(kml).count())
        assertEquals(4, Regex("<Placemark>").findAll(kml).count())
        assertTrue("<name>1. Breakfast · Ca'n Joan de s'Aigo</name>" in kml, kml)
        assertTrue("Order: escaldums" in kml, kml)
        assertTrue("⚠ No wine &lt;for&gt; her &amp; no sobrassada." in kml, kml)
        assertTrue("<coordinates>2.6457,39.5696</coordinates>" in kml, kml)
        assertTrue("Booking recommended · +34 971 72 17 81" in kml, kml)
        assertEquals("foodie-route-palma-de-mallorca-2026-10-18.kml", kmlName(trip, "2026-10-18"))
    }

    @Test
    fun `the file a phone can share is a CSV of the same pins, with the columns My Maps asks for`() {
        val rows = trip.toCsv().split("\r\n")
        assertEquals("Name,Latitude,Longitude,Description", rows.first())
        assertTrue(rows[1].startsWith("\"1. Breakfast · Ca'n Joan de s'Aigo\",39.5699,2.6526,\"Order: ensaïmada\nThe famous one.\""), rows[1])
        // A quote in a field is doubled, and the line breaks of a description stay inside its quotes.
        val quoted = trip.copy(route = listOf(Meal(1, MealTime.LUNCH, "r3", "sopes \"de la casa\"", "Home cooking."))).toCsv()
        assertTrue("\"Order: sopes \"\"de la casa\"\"\nHome cooking.\nC de la Previsió 4\"" in quoted, quoted)
        assertEquals(6, rows.size, "a header, four meals and the end of the last line")
        assertEquals("foodie-route-palma-de-mallorca-2026-10-18.csv", csvName(trip, "2026-10-18"))
    }
}
