package dev.deeptelar.foodie

/** The texts in English. */
object English : Texts {
    override val tagline = "Eat where the locals eat. Planned by an agent that runs in your browser."
    override val languageLabel = "Language"
    override val settings = "Settings"
    override val close = "Close"
    override val restaurantsFrom = "Restaurants from "
    override val ccBySaAnd = " (CC BY-SA) and "
    override val contributorsCheckHours = " contributors. Check opening hours before you go: a source can be out of date."
    override val workflowIsAGraphOf = "The workflow is a graph of "
    override val kotlinLibrary = ", a Kotlin library for AI agents and workflows."

    override val whereAreYouGoing = "Where are you going?"
    override val startHint =
        "Tell me about it, in writing or out loud: where, for how long, who comes along, what you love and what you cannot eat. " +
            "I ask about whatever is missing."
    override val requestPlaceholder = "3 days in Lisbon and 2 in Porto. No pork, and I love pastries."
    override val examples =
        listOf(
            "3 days in Lisbon and 2 in Porto. No pork, and I love pastries.",
            "A weekend in Naples: pizza and street food on a low budget.",
            "4 days in Oaxaca, vegetarian.",
            "Two days in Bologna and one in Modena. I want the classics.",
        )
    override val planMyRoute = "Plan my route"
    override val trySample = "Try the sample, no key needed"
    override val speechPrivacy =
        "Speaking: your browser turns your voice into text. Most browsers do that on their maker's servers, so the recording goes there; this page only gets the text."
    override val needAKey = "To plan a trip of your own, add the key of a model under Settings. The sample plans two days in Lisbon with a script in place of the model."
    override val howItWorks = "How it works"
    override val howItWorksSteps =
        listOf(
            "A model reads your request and asks what a food planner would: allergies, budget, who is coming, how adventurous you are.",
            "It finds your stops on the map. If it cannot, it asks you.",
            "A travel guide, a street map and, with a Google key, reviews are searched at the same time.",
            "The model names what each place is known for.",
            "An agent chooses a restaurant for each meal. It can search the sources again for a dish.",
            "You get the route on a map, and can ask for changes until you like it.",
        )

    override val keysStayHere = "Keys are stored in this browser only and are sent to nobody but their provider."
    override val model = "Model"
    override val openAiProvider = "OpenAI, Gemini, Ollama or another server with the API of OpenAI"
    override val anthropicKey = "Anthropic API key"
    override val claudeModel = "Claude model"
    override val apiAddress = "Address of the API"
    override val apiKey = "API key"
    override val emptyForLocalServer = "Leave empty for a local server"
    override val googleKey = "Google Places key, for ratings (optional)"
    override val googleBills = "Google bills the owner of the key for each search. Without it, the planner works with the travel guide and the street map."
    override val done = "Done"

    override val planningYourRoute = "Planning your route"
    override val stepInterview = "Getting to know your trip"
    override val stepBrief = "Finding your stops on the map"
    override val stepGuide = "Travel guide · Wikivoyage"
    override val stepMap = "Street map · OpenStreetMap"
    override val stepReviews = "Reviews · Google"
    override val stepDishes = "What each place is known for"
    override val stepPlan = "Choosing where to eat"
    override val stepOrder = "Putting the days in order"
    override val reviewsNotUsed = "Not used. Add a Google key under Settings for ratings."
    override val sampleRunning = "This is the sample: a script stands in for the model. The sources it searches are real."
    override val cancel = "Cancel"

    override fun places(count: Int) = if (count == 1) "1 place" else "$count places"

    override fun daysIn(days: Int, place: String) = "$days ${if (days == 1) "day" else "days"} in $place"

    override fun mealsOnTheRoute(count: Int) = "$count ${if (count == 1) "meal" else "meals"} on the route"

    override fun retrying(problem: String) = "That did not work ($problem). Trying again."

    override fun didSetMeal(day: String, meal: String, restaurant: String) = "Day $day, $meal: $restaurant"

    override fun didRemoveMeal(day: String, meal: String) = "Took the $meal of day $day off the route"

    override fun didSearchMap(stop: String, what: String) = "Searched the map of $stop for $what"

    override fun didReadGuide(page: String) = "Read the travel guide of $page"

    override fun didSearchReviews(query: String) = "Searched the reviews for \"$query\""

    override fun didLookThrough(stop: String, words: String) = "Looked through the places in $stop" + if (words.isBlank()) "" else " for $words"

    override val stoppedHalfway = "Your plan stopped halfway"
    override val continueRun = "Continue"
    override val startOver = "Start over"
    override val gotStuck = "The planner got stuck"
    override val foundIsSaved = "What it found before that is saved. You can try the step again."
    override val tryAgain = "Try again"
    override val somethingWentWrong = "Something went wrong."

    override val refusedLooksUnreachable =
        "If the address is right, the provider probably refused the request: browsers hide why. Check the key, the name of the model and the credit of the account under Settings."

    override fun closedWhileWorking(request: String) = "The page was closed while the planner worked on \"$request\". Everything it had found is saved."

    override val oneQuestion = "One question"
    override val aFewQuestions = "A few questions"
    override val oneMoreThing = "One more thing before I plan."
    override val lastQuestions = "The last ones. Tap all that fit, write what does not, or skip a question."
    override val continueAnswers = "Continue"
    override val planNow = "That's enough, plan it"
    override val sampleAsks = "This is the sample: a script asks the questions, and it does not read the answers."
    override val allergyNote = "The planner leaves out what you name and says what to tell the staff. It cannot know what a kitchen puts in a dish: always tell the waiter."
    override val pregnancyNote = "With a pregnancy the planner follows the usual advice: no raw fish, meat or eggs, no unpasteurised cheese, cured meats or alcohol. Your doctor's advice comes first."
    override val yourAnswer = "Your answer"
    override val somethingElse = "Something else, or more about it"

    override fun youAlreadyToldMe(topics: List<String>) = "You already told me: ${topics.joinToString()}."

    override fun moreQuestions(count: Int) = "About $count more after these. Tap all that fit, write what does not, or skip a question."

    override fun topic(topic: Topic) =
        when (topic) {
            Topic.WHERE -> "where and how long"
            Topic.ALLERGIES -> "allergies"
            Topic.PREGNANCY -> "pregnancy"
            Topic.DIET -> "diet"
            Topic.DATES -> "when"
            Topic.PARTY -> "who is coming"
            Topic.STAY -> "where you stay"
            Topic.MEALS -> "which meals"
            Topic.BUDGET -> "budget"
            Topic.TASTE -> "taste"
            Topic.DRINKS -> "drinks"
        }

    override fun question(topic: Topic) =
        when (topic) {
            Topic.WHERE -> Question(topic, "Where are you going, and for how many days in each place?")
            Topic.ALLERGIES ->
                Question(topic, "Do you have a food allergy? If you do, do you need to avoid even traces?", listOf("None", "Nuts", "Shellfish", "Gluten", "Dairy"), alone = listOf("None"))
            Topic.PREGNANCY -> Question(topic, "Is anyone on this trip pregnant?", listOf("No", "Yes"), alone = listOf("No", "Yes"))
            Topic.DIET -> Question(topic, "Is there anything you do not eat?", listOf("I eat everything", "Vegetarian", "Pescatarian", "Halal", "No pork"), alone = listOf("I eat everything"))
            Topic.DATES -> Question(topic, "When are you going?", listOf("Spring", "Summer", "Autumn", "Winter"))
            Topic.PARTY -> Question(topic, "Who is eating with you?", listOf("Just me", "Two of us", "Family with children", "Group of friends"), alone = listOf("Just me"))
            Topic.STAY -> Question(topic, "Where are you staying in each place? A neighbourhood or the name of the hotel is enough.")
            Topic.MEALS -> Question(topic, "Which meals should the route plan?", listOf("Breakfast", "Lunch", "Snack", "Dinner"))
            Topic.BUDGET ->
                Question(topic, "What would you like to spend on a meal?", listOf("As little as possible", "Mid-range", "Mid-range, and one special dinner", "Money is no object"), alone = listOf("As little as possible", "Mid-range", "Mid-range, and one special dinner", "Money is no object"))
            Topic.TASTE -> Question(topic, "How adventurous are you with food, and is there anything you do not like?", listOf("The classics", "Curious", "Anything the locals eat"), alone = listOf("The classics", "Curious", "Anything the locals eat"))
            Topic.DRINKS -> Question(topic, "What should the route have to drink?", listOf("Wine", "Beer", "Coffee", "Cocktails", "No alcohol"))
        }

    override val hereIsWhatIHave = "Here is what I have"
    override val profileHint = "This is what the route will be planned for. Tell me if something is wrong."
    override val correctionPlaceholder = "Anything to correct? For example: we are three, not two."
    override val correctIt = "Correct it"
    override val sampleDidNotRead = "This is the sample: the script did not read your answers."
    override val allergies = "Allergies"
    override val noAllergies = "None"
    override val pregnancy = "Pregnancy"
    override val someoneIsPregnant = "Someone is pregnant"
    override val diet = "Diet"
    override val eatsEverything = "Eats everything"
    override val whenLabel = "When"
    override val who = "Who"
    override val meals = "Meals"
    override val budget = "Budget"
    override val withASpecialMeal = ", with one special meal"
    override val taste = "Taste"
    override val drinks = "Drinks"
    override val wishes = "Wishes"

    override fun evenTraces(food: String) = "$food, even traces"

    override fun budget(word: String) = word

    override val yourFoodieRoute = "Your foodie route"
    override val hereIsYourRoute = "Here is your route"
    override val noMealPlanned = "The planner put no meal on the route. Tell it below what you are looking for, or start over."
    override val wholeTrip = "Whole trip"
    override val nothingPlannedThisDay = "Nothing planned for this day."
    override val plannedForYou = "Planned for you"
    override val couldNotBeRead = "What could not be read"
    override val enjoyYourTrip = "Enjoy your trip"
    override val printOrSave = "Print or save as PDF"
    override val planAnotherTrip = "Plan another trip"
    override val wantSomethingDifferent = "Want something different?"
    override val changePlaceholder = "A vegetarian dinner on day 2, and somewhere cheaper for the first lunch."
    override val updateTheRoute = "Update the route"
    override val happyWithIt = "I'm happy with it"
    override val order = "Order: "
    override val website = "Website"
    override val openInGoogleMaps = "Open in Google Maps"

    override fun day(day: Int) = "Day $day"

    override fun dayAt(day: Int, stop: String) = "Day $day · $stop"

    override fun walk(distance: String, fromStay: Boolean) = "↓ about $distance from " + if (fromStay) "where you stay" else "there"

    override fun knownFor(stop: String) = "What $stop is known for"

    override fun placesCompared(count: Int) = "$count places compared"

    override fun tokensUsed(count: Int) = "$count tokens used"

    override fun ratings(count: Int) = "$count ratings"

    override fun meal(time: MealTime) = time.label

    override fun source(source: Source) = source.label

    override fun distance(metres: Int) = distanceIn(metres, '.')

    override val speak = "🎤 Speak"
    override val stopListening = "■ Stop"
    override val micNotAllowed = "The browser may not use the microphone. Allow it in the site settings, or type."
    override val micNoNetwork = "The browser could not reach its speech service. Type instead."
    override val micMissing = "No microphone was found."

    override fun micFailed(problem: String) = "The browser could not start listening: $problem"

    override val readingYourRequest = "Reading your request"
    override val readingYourAnswers = "Reading your answers"
    override val findingYourPlaces = "Finding the places of your trip"
    override val whereQuestion = "Where are you going, and for how many days?"
    override val findingDishes = "Finding out what each place is known for"

    override val notingWhatYouSaid = "Noting what you told me"

    override fun seconds(count: Int) = "$count s"

    override val modelThinking = "The model thinks before it writes. That can take up to a minute here."

    override fun asking(question: String) = "Writing the questions: \u201c$question\u201d"

    override fun foundPlaces(places: List<String>) = "Found: ${places.joinToString(" · ")}"

    override fun namingDishes(dishes: List<String>) = "Naming dishes: ${dishes.joinToString(", ")}…"

    override fun notOnTheMap(places: List<String>) = "I could not find ${places.joinToString(" or ")} on the map. Which place do you mean?"

    override fun findingOnTheMap(place: String) = "Finding $place on the map"

    override fun readingTheGuide(page: String) = "Reading the travel guide of $page"

    override fun lookingOnTheMap(cuisine: String, stop: String) = "Looking for $cuisine restaurants on the map of $stop"

    override fun readingReviews(stop: String) = "Reading reviews in $stop"

    override fun searchingTheMap(stop: String, what: String) = "Searching the map of $stop for $what"

    override fun searchingReviews(stop: String, query: String) = "Searching reviews in $stop for $query"

    override val theGuide = "The travel guide"
    override val theMap = "The street map"
    override val theReviews = "Google reviews"

    override fun tookTooLong(source: String, stop: String) = "$source took too long for $stop."

    override fun gaveNoAnswer(source: String, stop: String, problem: String) = "$source gave no answer for $stop: $problem"

    override val sampleRequest = "Two days in Lisbon. I love seafood and pastries."
    override val sampleStop = ""
    override val sampleWishes = "seafood and pastries"
    override val sampleQuestions =
        listOf(
            Question(
                Topic.ALLERGIES,
                "Lisbon cooks a lot of shellfish, and many of its pastries have almonds. Do you have a food allergy?",
                listOf("None", "Shellfish", "Nuts", "Gluten"),
                alone = listOf("None"),
            ),
            Question(Topic.DIET, "Lisbon loves pork as much as fish: the bifana is a pork roll. Is there anything you do not eat?", listOf("I eat everything", "No pork", "Pescatarian", "Vegetarian"), alone = listOf("I eat everything")),
            Question(Topic.DATES, "When are you going? In June the whole city grills sardines in the street.", listOf("June", "Another summer month", "Another season")),
        )
    override val sampleDishes =
        listOf(
            "Shredded salt cod with onion, thin fried potato and egg.",
            "A loose, soupy rice with prawns, clams and crab.",
            "Sardines grilled over charcoal, eaten with bread and boiled potato.",
            "Octopus roasted with garlic and a lot of olive oil.",
            "A roll with thin pork that simmered in garlic and white wine.",
            "A small custard tart with a burnt top, eaten warm with cinnamon.",
            "A sour cherry liqueur, drunk from a small glass at a counter.",
        )
    override val sampleFirstSearch = "First, a place for pastéis de nata."
    override val sampleWhy = "Sample: taken for the evidence the sources have for it, not for this dish."
    override val sampleWhyPastry = "Sample: the first pastry shop the street map returned."
    override val sampleSummary =
        "This is the sample trip. A script wrote it, not a model: the restaurants are real and come from the travel guide and the street map, " +
            "but the script took the ones with the most evidence and did not read what they cook. " +
            "Add the key of a model under Settings and the planner chooses each place for a dish, for your own trip."
    override val sampleNoChanges = "The sample is a script and cannot change the route. Add the key of a model under Settings, and the planner follows what you ask for."
}
