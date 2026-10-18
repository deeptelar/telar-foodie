package dev.deeptelar.foodie

/**
 * Everything the traveller reads that the app writes itself, in one language. What a model writes
 * comes in the trip's language from the model (see `languageRule`); what a model reads stays in
 * English. A text with something in it is a function, so that each language can put the words in
 * its own order and count in its own way.
 *
 * Each language is one object: [English], [Spanish] and [Catalan].
 */
interface Texts {
    // The page around everything

    val tagline: String
    val languageLabel: String
    val settings: String
    val close: String
    val restaurantsFrom: String
    val ccBySaAnd: String
    val contributorsCheckHours: String
    val workflowIsAGraphOf: String
    val kotlinLibrary: String

    // The start

    val whereAreYouGoing: String
    val startHint: String
    val requestPlaceholder: String
    val examples: List<String>
    val planMyRoute: String
    val trySample: String
    val speechPrivacy: String
    val needAKey: String
    val howItWorks: String
    val howItWorksSteps: List<String>

    // The settings

    val keysStayHere: String
    val model: String
    val openAiProvider: String
    val anthropicKey: String
    val claudeModel: String
    val apiAddress: String
    val apiKey: String
    val emptyForLocalServer: String
    val googleKey: String
    val googleBills: String
    val howToGetAGoogleKey: String

    /** How to make a Google key that only [site] can use, one step at a time. */
    fun googleKeySteps(site: String): List<KeyStep>
    val googlePrices: String
    val googleGuide: String
    val done: String

    // A run

    val planningYourRoute: String
    val stepInterview: String
    val stepBrief: String
    val stepGuide: String
    val stepMap: String
    val stepReviews: String
    val stepDishes: String
    val stepPlan: String
    val stepOrder: String
    val reviewsNotUsed: String
    val sampleRunning: String
    val cancel: String

    fun places(count: Int): String

    fun daysIn(days: Int, place: String): String

    fun mealsOnTheRoute(count: Int): String

    fun retrying(problem: String): String

    // What the agent does, as the run shows it

    fun didSetMeal(day: String, meal: String, restaurant: String): String

    fun didRemoveMeal(day: String, meal: String): String

    fun didSearchMap(stop: String, what: String): String

    fun didReadGuide(page: String): String

    fun didSearchReviews(query: String): String

    fun didLookThrough(stop: String, words: String): String

    // A run that stopped

    val stoppedHalfway: String
    val pause: String
    val paused: String

    /** On a paused run: what the traveller can write before it goes on. */
    val pausedHint: String
    val asidePlaceholder: String
    val continueRun: String
    val startOver: String
    val gotStuck: String
    val foundIsSaved: String
    val tryAgain: String
    val somethingWentWrong: String

    /** Under a failure that says the browser could not reach a provider. */
    val refusedLooksUnreachable: String

    fun closedWhileWorking(request: String): String

    // The questions

    val oneQuestion: String
    val aFewQuestions: String
    val oneMoreThing: String
    val lastQuestions: String
    val continueAnswers: String
    val planNow: String
    val sampleAsks: String
    val allergyNote: String
    val pregnancyNote: String
    val yourAnswer: String
    val somethingElse: String

    fun youAlreadyToldMe(topics: List<String>): String

    fun moreQuestions(count: Int): String

    /** The name of a topic, as in "you already told me: where and how long". */
    fun topic(topic: Topic): String

    /** The plain question of a topic, for when the model wrote none. */
    fun question(topic: Topic): Question

    // The two tabs, and the conversation

    val planTab: String
    val detailsTab: String

    /** The answer of a question the traveller skipped, in the conversation. */
    val skipped: String
    val youEditedDetails: String
    val youErasedDetails: String
    val shallIPlan: String
    val yesPlanIt: String
    val send: String
    val seeYourDetails: String

    // The tab of the traveller's details

    val noDetailsYet: String
    val detailsWhileWorking: String
    val detailsReadOnly: String
    val save: String
    val saved: String

    // The profile

    val hereIsWhatIHave: String
    val profileHint: String
    val correctionPlaceholder: String
    val correctIt: String
    val sampleDidNotRead: String
    val allergies: String
    val noAllergies: String
    val pregnancy: String
    val someoneIsPregnant: String
    val diet: String
    val eatsEverything: String
    val whenLabel: String
    val who: String
    val meals: String
    val budget: String
    val withASpecialMeal: String
    val taste: String
    val drinks: String
    val wishes: String

    fun evenTraces(food: String): String

    // Editing the profile

    /** In an empty field of the profile. */
    val notSaid: String

    /** The label of the button that empties a field. */
    val erase: String
    val addAllergy: String
    val allergyPlaceholder: String
    val evenTracesOption: String
    val oneSpecialMeal: String

    /** Under the meals, when none is picked. */
    val mealsIfNone: String
    val eraseEverything: String

    /** Under the profile: where it is kept, and how it goes away. */
    val keptInThisBrowser: String

    /** On a planned route, after the profile was changed. */
    val updateForTheseDetails: String

    /** A budget as the model writes it: `low`, `medium` or `high`. Another word is shown as it is. */
    fun budget(word: String): String

    // The route

    val yourFoodieRoute: String
    val hereIsYourRoute: String
    val noMealPlanned: String
    val wholeTrip: String
    val nothingPlannedThisDay: String
    val plannedForYou: String
    val couldNotBeRead: String
    val enjoyYourTrip: String
    val printOrSave: String
    val planAnotherTrip: String
    val wantSomethingDifferent: String
    val changePlaceholder: String
    val updateTheRoute: String
    val happyWithIt: String
    val order: String
    val website: String
    val openInGoogleMaps: String

    fun day(day: Int): String

    fun dayAt(day: Int, stop: String): String

    fun walk(distance: String, fromStay: Boolean): String

    fun knownFor(stop: String): String

    fun placesCompared(count: Int): String

    fun tokensUsed(count: Int): String

    fun ratings(count: Int): String

    fun meal(time: MealTime): String

    fun source(source: Source): String

    fun distance(metres: Int): String

    // The microphone

    val speak: String
    val stopListening: String
    val micNotAllowed: String
    val micNoNetwork: String
    val micMissing: String

    fun micFailed(problem: String): String

    // What the planner says it is doing

    val readingYourRequest: String
    val readingYourAnswers: String
    val findingYourPlaces: String
    val whereQuestion: String
    val findingDishes: String

    /** The dishes wait for what the travel guide says about the places. */
    val readingTheGuideFirst: String
    val notingWhatYouSaid: String

    /** How long a step has been running, shown after what it does. */
    fun seconds(count: Int): String

    /** Under a step that has waited a while for a model that has not written anything yet. */
    val modelThinking: String

    /** Over the dishes that are shown one by one while the route is planned. */
    fun whileIPlan(stop: String): String
    val anotherDish: String

    fun asking(question: String): String

    fun foundPlaces(places: List<String>): String

    fun namingDishes(dishes: List<String>): String

    fun notOnTheMap(places: List<String>): String

    fun findingOnTheMap(place: String): String

    fun readingTheGuide(page: String): String

    fun lookingOnTheMap(cuisine: String, stop: String): String

    fun readingReviews(stop: String): String

    fun searchingTheMap(stop: String, what: String): String

    fun searchingReviews(stop: String, query: String): String

    // What could not be read

    val theGuide: String
    val theMap: String
    val theReviews: String

    fun tookTooLong(source: String, stop: String): String

    fun gaveNoAnswer(source: String, stop: String, problem: String): String

    // The sample, a script in place of a model

    val sampleRequest: String
    val sampleStop: String
    val sampleWishes: String
    val sampleQuestions: List<Question>

    /** What each of the sample's dishes is, in the order of `SampleModel`. */
    val sampleDishes: List<String>

    /** A story of each of the sample's dishes, in the same order. */
    val sampleStories: List<String>
    val sampleFirstSearch: String
    val sampleWhy: String
    val sampleWhyPastry: String
    val sampleSummary: String
    val sampleNoChanges: String

    // The places known for a dish

    val stepSpecialists: String
    fun lookingForSpecialists(stop: String): String

    /** On the card of a meal at a place known for a dish of its stop. */
    fun knownForDish(dish: String): String

    /** What the street map says about booking a table, by its `reservation` tag, or `null` for a value it does not have. */
    fun booking(reservation: String): String?

    // The route in Google Maps

    /** The link under a day that opens its meals as a walk. */
    val dayInGoogleMaps: String
    val saveForMyMaps: String

    /** How to import the file into Google My Maps: the words before and after the link to it. */
    val myMapsHintBefore: String
    val myMapsHintAfter: String

    // A plan in a file

    val saveToFile: String
    val openAFile: String

    /** What a saved file holds, and what it does not. */
    val savedFileHint: String
    val notAPlanFile: String
    val planFromANewerPage: String
}

/** A step of a how-to, with a [link] to where it is done when there is one. */
data class KeyStep(
    val text: String,
    val link: String = "",
    val url: String = "",
)

/** Where a Google key is made. */
internal object GoogleConsole {
    const val NEW_PROJECT = "https://console.cloud.google.com/projectcreate"
    const val PLACES_API = "https://console.cloud.google.com/apis/library/places.googleapis.com"
    const val CREDENTIALS = "https://console.cloud.google.com/apis/credentials"
    const val PRICES = "https://developers.google.com/maps/billing-and-pricing/pricing"
    const val GUIDE = "https://developers.google.com/maps/documentation/places/web-service/get-api-key"
}

/** The texts of this language. */
val Language.texts: Texts
    get() =
        when (this) {
            Language.ENGLISH -> English
            Language.SPANISH -> Spanish
            Language.CATALAN -> Catalan
        }

/** Joins words as a sentence does: "a", "a and b", "a, b and c", with the language's [and]. */
internal fun List<String>.joinedWith(and: String): String =
    when (size) {
        0 -> ""
        1 -> single()
        else -> dropLast(1).joinToString(", ") + " $and " + last()
    }

/** A distance in metres to the nearest 50, or in kilometres with one decimal and the language's [comma]. */
internal fun distanceIn(metres: Int, comma: Char): String =
    if (metres < 1000) "${(metres + 25) / 50 * 50} m" else "${(metres + 50) / 100 / 10}$comma${(metres + 50) / 100 % 10} km"
