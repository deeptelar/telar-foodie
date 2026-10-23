package dev.deeptelar.foodie

/** The texts in German, as spoken in Germany. */
object German : Texts {
    override val tagline = "Iss dort, wo die Einheimischen essen. Ein Agent plant es, und er läuft in deinem Browser."
    override val languageLabel = "Sprache"
    override val settings = "Einstellungen"
    override val close = "Schließen"
    override val restaurantsFrom = "Restaurants aus "
    override val ccBySaAnd = " (CC BY-SA) und von den Mitwirkenden von "
    override val contributorsCheckHours = ". Prüfe die Öffnungszeiten, bevor du hingehst: Eine Quelle kann veraltet sein."
    override val workflowIsAGraphOf = "Der Ablauf ist ein Graph von "
    override val kotlinLibrary = ", einer Kotlin-Bibliothek für KI-Agenten und -Abläufe."

    override val whereAreYouGoing = "Wohin geht die Reise?"
    override val startHint =
        "Schreib es oder sag es laut: wohin, wie lange, mit wem, was du liebst und was du nicht essen kannst. " +
            "Was fehlt, frage ich dich."
    override val upFrontTitle = "Sag es mir jetzt, dann frage ich nicht danach"
    override val upFrontHint = "Alles ist freiwillig: Was du überspringst, frage ich später, wenn die Reise es braucht."
    override val requestPlaceholder = "3 Tage in Lissabon und 2 in Porto. Kein Schweinefleisch, und ich liebe Süßes."
    override val examples =
        listOf(
            "3 Tage in Lissabon und 2 in Porto. Kein Schweinefleisch, und ich liebe Süßes.",
            "Ein Wochenende in Neapel: Pizza und Streetfood mit kleinem Budget.",
            "4 Tage in Oaxaca, vegetarisch.",
            "Zwei Tage in Bologna und einer in Modena. Ich will die Klassiker.",
        )
    override val planMyRoute = "Plane meine Route"
    override val trySample = "Probier das Beispiel, ohne Schlüssel"
    override val speechPrivacy =
        "Wenn du sprichst, macht dein Browser aus deiner Stimme Text. Die meisten tun das auf den Servern ihres Herstellers, die Aufnahme geht also dorthin; diese Seite bekommt nur den Text."
    override val needAKey = "Um deine eigene Reise zu planen, füge in den Einstellungen den Schlüssel eines Modells hinzu. Das Beispiel plant zwei Tage in Lissabon mit einem Skript statt eines Modells."
    override val howItWorks = "So funktioniert es"
    override val howItWorksSteps =
        listOf(
            "Ein Modell liest deine Anfrage und fragt, was ein Food-Planer fragen würde: Allergien, Budget, wer mitkommt, wie experimentierfreudig du bist.",
            "Es findet deine Stationen auf der Karte. Wenn es das nicht kann, fragt es dich.",
            "Ein Reiseführer, ein Stadtplan und, mit einem Google-Schlüssel, Bewertungen werden gleichzeitig befragt.",
            "Das Modell nennt dir die typischen Gerichte jedes Ortes.",
            "Ein Agent wählt für jede Mahlzeit ein Restaurant. Er kann die Quellen noch einmal nach einem bestimmten Gericht durchsuchen.",
            "Du bekommst die Route auf einer Karte und kannst Änderungen wünschen, bis sie dir gefällt.",
        )

    override val keysStayHere = "Die Schlüssel werden nur in diesem Browser gespeichert und an niemanden außer ihren Anbieter geschickt."
    override val visitsCounted =
        "Die Seite zählt Besuche und einige Schritte, etwa einen begonnenen Plan oder eine fertige Route, mit GoatCounter: ohne Cookies und ohne etwas von der Reise."
    override val model = "Modell"
    override val openAiProvider = "OpenAI, Gemini, Ollama oder ein anderer Server mit der API von OpenAI"
    override val anthropicKey = "Anthropic-API-Schlüssel"
    override val claudeModel = "Claude-Modell"
    override val apiAddress = "API-Adresse"
    override val apiKey = "API-Schlüssel"
    override val emptyForLocalServer = "Leer lassen für einen lokalen Server"
    override val localServerHint =
        "Ein Server auf diesem Computer, etwa Ollama, muss die Adresse dieser Seite erlauben, sonst hält der Browser seine Antworten zurück. Der Browser fragt vielleicht auch, ob die Seite auf dein lokales Netzwerk zugreifen darf. Starte Ollama mit:"
    override val googleKey = "Google-Places-Schlüssel, für Bewertungen (optional)"
    override val googleBills =
        "Google gibt jeden Monat eine Anzahl Suchen gratis und berechnet den Rest dem Inhaber des Schlüssels. " +
            "Eine Reise macht eine Suche pro Station und ein paar mehr, wenn der Planer nach einem Ort sucht. " +
            "Ohne Schlüssel nutzt der Planer den Reiseführer und den Stadtplan."
    override val howToGetAGoogleKey = "So bekommst du einen Google-Schlüssel"

    override fun googleKeySteps(site: String) =
        listOf(
            KeyStep("Erstelle ein Projekt in der Google Cloud Console. Google verlangt ein Rechnungskonto mit einer Karte.", "Projekt erstellen", GoogleConsole.NEW_PROJECT),
            KeyStep("Öffne Places API (New) und klicke auf Aktivieren.", "Places API (New)", GoogleConsole.PLACES_API),
            KeyStep("Klicke unter Anmeldedaten auf Anmeldedaten erstellen und dann auf API-Schlüssel.", "Anmeldedaten", GoogleConsole.CREDENTIALS),
            KeyStep(
                "Schränke den Schlüssel ein, damit nur diese Seite ihn nutzen kann: Wähle unter Anwendungseinschränkungen Websites und füge $site hinzu, " +
                    "und wähle unter API-Einschränkungen Places API (New).",
            ),
            KeyStep("Kopiere den Schlüssel und füge ihn oben ein."),
        )
    override val googlePrices = "Die Preise von Google"
    override val googleGuide = "Die Anleitung von Google"
    override val done = "Fertig"

    override val planningYourRoute = "Deine Route wird geplant"
    override val stepInterview = "Deine Reise kennenlernen"
    override val stepBrief = "Deine Stationen auf der Karte finden"
    override val stepGuide = "Reiseführer · Wikivoyage"
    override val stepMap = "Stadtplan · OpenStreetMap"
    override val stepReviews = "Bewertungen · Google"
    override val stepDishes = "Die typischen Gerichte jedes Ortes"
    override val stepPlan = "Auswählen, wo du isst"
    override val stepOrder = "Die Tage ordnen"
    override val reviewsNotUsed = "Nicht genutzt. Füge in den Einstellungen einen Google-Schlüssel hinzu, um Bewertungen zu sehen."
    override val sampleRunning = "Das ist das Beispiel: Ein Skript spielt das Modell. Die Antworten der Quellen sind echt: Reiseführer und Karte, einmal befragt und gespeichert."
    override val cancel = "Abbrechen"

    override fun places(count: Int) = if (count == 1) "1 Ort" else "$count Orte"

    override fun daysIn(days: Int, place: String) = "$days ${if (days == 1) "Tag" else "Tage"} in $place"

    override fun mealsOnTheRoute(count: Int) = "$count ${if (count == 1) "Mahlzeit" else "Mahlzeiten"} auf der Route"

    override fun retrying(problem: String) = "Das hat nicht geklappt ($problem). Ich versuche es noch einmal."

    override fun didSetMeal(day: String, meal: String, restaurant: String) = "Tag $day, $meal: $restaurant"

    override fun didRemoveMeal(day: String, meal: String) = "Von der Route genommen: $meal an Tag $day"

    override fun didSearchMap(stop: String, what: String) = "Auf der Karte von $stop nach $what gesucht"

    override fun didReadGuide(page: String) = "Den Reiseführer zu $page gelesen"

    override fun didSearchReviews(query: String) = "In den Bewertungen nach „$query“ gesucht"

    override fun didLookThrough(stop: String, words: String) = "Die Orte in $stop durchgesehen" + if (words.isBlank()) "" else ", nach $words"

    override val stoppedHalfway = "Dein Plan wurde nicht fertig"
    override val pause = "Pause"
    override val paused = "Pausiert"
    override val pausedHint = "Füge hinzu, was der Planer wissen sollte, oder sag ihm, er soll etwas anders machen. Er berücksichtigt es weiterhin."
    override val asidePlaceholder = "Zum Beispiel: Wir haben ein Auto. Oder: mehr Meeresfrüchte und weniger Pasta."
    override val continueRun = "Weiter"
    override val startOver = "Neu anfangen"
    override val gotStuck = "Der Planer kommt nicht weiter"
    override val foundIsSaved = "Was er bisher gefunden hat, ist gespeichert. Du kannst den Schritt noch einmal versuchen."
    override val tryAgain = "Noch einmal versuchen"
    override val somethingWentWrong = "Etwas ist schiefgegangen."

    override val refusedLooksUnreachable =
        "Wenn die Adresse stimmt, hat der Anbieter die Anfrage höchstwahrscheinlich abgelehnt: Browser verbergen den Grund. Prüfe in den Einstellungen den Schlüssel, den Namen des Modells und das Guthaben des Kontos."

    override fun closedWhileWorking(request: String) = "Die Seite wurde geschlossen, während der Planer an „$request“ arbeitete. Alles, was er gefunden hatte, ist gespeichert."

    override val oneQuestion = "Eine Frage"
    override val aFewQuestions = "Ein paar Fragen"
    override val oneMoreThing = "Noch eine Sache, bevor ich plane."
    override val lastQuestions = "Das sind die letzten. Tippe alles an, was passt, schreib, was nicht passt, oder überspring eine Frage."
    override val continueAnswers = "Weiter"
    override val planNow = "Genug gefragt"
    override val sampleAsks = "Das ist das Beispiel: Ein Skript stellt die Fragen und liest die Antworten nicht."
    override val allergyNote =
        "Der Planer lässt weg, was du nennst, und sagt dir, worauf du das Personal hinweisen solltest. Er kann nicht wissen, was eine Küche in ein Gericht gibt: Sag es immer der Bedienung."
    override val pregnancyNote = "Bei einer Schwangerschaft folgt der Planer den üblichen Empfehlungen: kein roher Fisch, kein rohes Fleisch, keine rohen Eier, kein Rohmilchkäse, keine Wurstwaren und kein Alkohol. Was deine Ärztin oder dein Arzt sagt, geht vor."
    override val yourAnswer = "Deine Antwort"
    override val somethingElse = "Etwas anderes, oder mehr dazu"

    override fun youAlreadyToldMe(topics: List<String>) = "Das hast du mir schon gesagt: ${topics.joinedWith("und")}."

    override fun moreQuestions(count: Int) = "Danach kommen noch etwa $count. Tippe alles an, was passt, schreib, was nicht passt, oder überspring eine Frage."

    override fun topic(topic: Topic) =
        when (topic) {
            Topic.WHERE -> "wohin und wie lange"
            Topic.ALLERGIES -> "Allergien"
            Topic.PREGNANCY -> "Schwangerschaft"
            Topic.DIET -> "Ernährung"
            Topic.DATES -> "wann"
            Topic.PARTY -> "wer mitkommt"
            Topic.STAY -> "wo du übernachtest"
            Topic.MEALS -> "welche Mahlzeiten"
            Topic.BUDGET -> "Budget"
            Topic.TASTE -> "Geschmack"
            Topic.DRINKS -> "Getränke"
        }

    override fun question(topic: Topic) =
        when (topic) {
            Topic.WHERE -> Question(topic, "Wohin geht die Reise, und wie viele Tage bleibst du an jedem Ort?")
            Topic.ALLERGIES ->
                Question(topic, "Hast du eine Lebensmittelallergie? Wenn ja, musst du auch Spuren meiden?", listOf("Keine", "Nüsse", "Meeresfrüchte", "Gluten", "Milchprodukte"), alone = listOf("Keine"))
            Topic.PREGNANCY -> Question(topic, "Ist auf dieser Reise jemand schwanger?", listOf("Nein", "Ja"), alone = listOf("Nein", "Ja"))
            Topic.DIET -> Question(topic, "Gibt es etwas, das du nicht isst?", listOf("Ich esse alles", "Vegetarisch", "Pescetarisch", "Halal", "Kein Schweinefleisch"), alone = listOf("Ich esse alles"))
            Topic.DATES -> Question(topic, "Wann fährst du?", listOf("Frühling", "Sommer", "Herbst", "Winter"))
            Topic.PARTY -> Question(topic, "Mit wem isst du?", listOf("Nur ich", "Zu zweit", "Familie mit Kindern", "Gruppe von Freunden"), alone = listOf("Nur ich"))
            Topic.STAY -> Question(topic, "Wo übernachtest du an jedem Ort, und ab welchem Tag, wenn du das Hotel wechselst? Das Viertel oder der Name des Hotels genügt. Sag mir auch, ob du eine Mahlzeit in der Nähe möchtest, etwa das Frühstück.")
            Topic.MEALS -> Question(topic, "Welche Mahlzeiten soll die Route planen?", listOf("Frühstück", "Mittagessen", "Snack", "Abendessen"))
            Topic.BUDGET ->
                Question(topic, "Wie viel möchtest du pro Mahlzeit ausgeben?", listOf("So wenig wie möglich", "Mittlere Preise", "Mittlere Preise und ein besonderes Abendessen", "Geld spielt keine Rolle"), alone = listOf("So wenig wie möglich", "Mittlere Preise", "Mittlere Preise und ein besonderes Abendessen", "Geld spielt keine Rolle"))
            Topic.TASTE -> Question(topic, "Wie experimentierfreudig bist du, und gibt es etwas, das du nicht magst?", listOf("Die Klassiker", "Ich bin neugierig", "Was die Einheimischen essen"), alone = listOf("Die Klassiker", "Ich bin neugierig", "Was die Einheimischen essen"))
            Topic.DRINKS -> Question(topic, "Was möchtest du unterwegs trinken?", listOf("Wein", "Bier", "Kaffee", "Cocktails", "Alkoholfrei"))
        }

    override val planTab = "Plan"
    override val detailsTab = "Deine Angaben"
    override val skipped = "Keine Antwort"
    override val youEditedDetails = "Du hast deine Angaben geändert."
    override val youErasedDetails = "Du hast deine Angaben gelöscht."
    override val shallIPlan = "Soll ich die Route planen?"
    override val yesPlanIt = "Ja, plane sie"
    override val send = "Senden"
    override val seeYourDetails = "Deine Angaben ansehen oder ändern"
    override val noDetailsYet = "Noch nichts. Hier erscheint, was du dem Planer über dich erzählst."
    override val detailsWhileWorking = "Der Planer arbeitet gerade damit. Pausiere ihn, um es zu ändern."
    override val detailsReadOnly = "Die Route wurde dafür geplant. „Neu anfangen“ löscht es."
    override val save = "Speichern"
    override val saved = "Gespeichert."

    override val hereIsWhatIHave = "Das weiß ich bisher"
    override val profileHint = "Dafür wird die Route geplant. Ändere oder lösche, was du willst, und speichere es."
    override val correctionPlaceholder = "Etwas zu korrigieren? Zum Beispiel: Wir sind zu dritt, nicht zu zweit."
    override val correctIt = "Korrigieren"
    override val sampleDidNotRead = "Das ist das Beispiel: Das Skript hat deine Antworten nicht gelesen."
    override val allergies = "Allergien"
    override val noAllergies = "Keine"
    override val pregnancy = "Schwangerschaft"
    override val someoneIsPregnant = "Jemand ist schwanger"
    override val diet = "Ernährung"
    override val eatsEverything = "Isst alles"
    override val whenLabel = "Wann"
    override val who = "Wer"
    override val meals = "Mahlzeiten"
    override val budget = "Budget"
    override val withASpecialMeal = ", mit einer besonderen Mahlzeit"
    override val taste = "Geschmack"
    override val drinks = "Getränke"
    override val wishes = "Wünsche"

    override fun evenTraces(food: String) = "$food, auch Spuren"

    override val notSaid = "Nicht angegeben"
    override val erase = "Löschen"
    override val addAllergy = "Eine Allergie hinzufügen"
    override val allergyPlaceholder = "Zum Beispiel Erdnüsse"
    override val evenTracesOption = "Auch Spuren"
    override val oneSpecialMeal = "Eine besondere Mahlzeit"
    override val mealsIfNone = "Wenn du keine wählst: Mittag- und Abendessen."
    override val eraseEverything = "Alles löschen"
    override val keptInThisBrowser = "Das wird nur in diesem Browser gespeichert, mit dieser Reise. „Neu anfangen“ löscht alles."
    override val updateForTheseDetails = "Die Route für diese Angaben aktualisieren"

    override fun budget(word: String) =
        when (word.trim().lowercase()) {
            "low" -> "niedrig"
            "medium" -> "mittel"
            "high" -> "hoch"
            else -> word
        }

    override val noMealPlanned = "Der Planer hat keine Mahlzeit auf die Route gesetzt. Sag ihm unten, was du suchst, oder fang neu an."
    override val wholeTrip = "Die ganze Reise"
    override val nothingPlannedThisDay = "Für diesen Tag ist nichts geplant."
    override val plannedForYou = "Für dich geplant"
    override val couldNotBeRead = "Was nicht gelesen werden konnte"
    override val enjoyYourTrip = "Gute Reise"
    override val printOrSave = "Drucken oder als PDF speichern"
    override val planAnotherTrip = "Eine andere Reise planen"
    override val planAgain = "Mit meinen Antworten neu planen"
    override val wantSomethingDifferent = "Möchtest du etwas anderes?"
    override val changePlaceholder = "Ein vegetarisches Abendessen an Tag 2, und etwas Günstigeres für das erste Mittagessen."
    override val updateTheRoute = "Route aktualisieren"
    override val happyWithIt = "Gefällt mir so"
    override val order = "Bestell: "
    override val website = "Website"
    override val openInGoogleMaps = "In Google Maps öffnen"

    override fun day(day: Int) = "Tag $day"

    override fun dayAt(day: Int, stop: String) = "Tag $day · $stop"

    override fun walk(distance: String, fromStay: Boolean) = "↓ etwa $distance von " + if (fromStay) "deiner Unterkunft" else "dort"

    override fun knownFor(stop: String) = "Wofür $stop bekannt ist"

    override fun placesCompared(count: Int) = "$count Orte verglichen"

    override fun tokensUsed(count: Int) = "$count Tokens verbraucht"

    override val whatItCost = "Was dieser Plan gekostet hat"

    override fun costAbout(amount: String) = "Dieser Plan hat etwa $amount gekostet"

    override fun modelCost(model: String, read: Int, written: Int, amount: String?) = "$model: $read Tokens gelesen und $written geschrieben" + (amount?.let { " · $it" } ?: "")

    override fun googleCost(searches: Int, amount: String) = "Google Places: $searches " + (if (searches == 1) "Suche" else "Suchen") + " · $amount"

    override val atListPrices =
        "Zu den Listenpreisen der Anbieter vom Oktober 2026. Google berechnet die ersten 1.000 Suchen jedes Monats nicht, und der Cache eines Anbieters kann gelesene Tokens günstiger machen."

    override fun money(dollars: Double) = amount(dollars, ',') + " US$"

    override val tracing = "OpenTelemetry-Traces (für Entwickler)"

    override val tracingHint =
        "Jeder Lauf wird als Trace gesendet: ein Span pro Schritt, mit seiner Dauer und dem, was fehlgeschlagen ist, und nichts von der Reise. Der Collector muss den Ursprung dieser Seite erlauben (CORS)."

    override val tracingAddress = "Adresse für die Traces (OTLP/HTTP)"

    override val tracingHeader = "Header für den Schlüssel des Dienstes (Name: Wert, optional)"

    override fun ratings(count: Int) = "$count Bewertungen"

    override fun meal(time: MealTime) =
        when (time) {
            MealTime.BREAKFAST -> "Frühstück"
            MealTime.LUNCH -> "Mittagessen"
            MealTime.SNACK -> "Snack"
            MealTime.DINNER -> "Abendessen"
        }

    // German writes its nouns with a capital, inside a sentence too.
    override fun mealInSentence(time: MealTime) = meal(time)

    override fun source(source: Source) =
        when (source) {
            Source.GUIDE -> "Reiseführer"
            Source.MAP -> "Stadtplan"
            Source.REVIEWS -> "Google-Bewertungen"
        }

    override fun distance(metres: Int) = distanceIn(metres, ',')

    override val speak = "🎤 Sprechen"
    override val stopListening = "■ Stopp"
    override val micNotAllowed = "Der Browser darf das Mikrofon nicht nutzen. Erlaube es in den Einstellungen der Website, oder schreib."
    override val micNoNetwork = "Der Browser konnte seinen Sprachdienst nicht erreichen. Schreib stattdessen."
    override val micMissing = "Kein Mikrofon gefunden."

    override fun micFailed(problem: String) = "Der Browser konnte nicht anfangen zuzuhören: $problem"

    override val readingYourRequest = "Deine Anfrage wird gelesen"
    override val readingYourAnswers = "Deine Antworten werden gelesen"
    override val findingYourPlaces = "Die Orte deiner Reise werden gesucht"
    override val whereQuestion = "Wohin geht die Reise, und wie viele Tage?"
    override val findingDishes = "Die typischen Gerichte jedes Ortes werden gesucht"
    override val readingTheGuideFirst = "Zuerst, was der Reiseführer über jeden Ort sagt"

    override val notingWhatYouSaid = "Ich notiere, was du mir gesagt hast"

    override fun seconds(count: Int) = "$count s"

    override val modelThinking = "Das Modell denkt nach, bevor es schreibt. Das kann hier bis zu einer Minute dauern."

    override fun whileIPlan(stop: String) = "Während ich deine Route plane · $stop"
    override val anotherDish = "Noch eins"

    override fun asking(question: String) = "Die Fragen werden geschrieben: „$question“"

    override fun foundPlaces(places: List<String>) = "Gefunden: ${places.joinToString(" · ")}"

    override fun namingDishes(dishes: List<String>) = "Gerichte: ${dishes.joinToString(", ")}…"

    override fun notOnTheMap(places: List<String>) = "Ich habe ${places.joinToString(" und ")} nicht auf der Karte gefunden. Welchen Ort meinst du?"

    override fun findingOnTheMap(place: String) = "$place wird auf der Karte gesucht"

    override fun readingTheGuide(page: String) = "Der Reiseführer zu $page wird gelesen"

    override fun lookingOnTheMap(cuisine: String, stop: String) = "Restaurants mit regionaler Küche werden auf der Karte von $stop gesucht"

    override fun readingReviews(stop: String) = "Bewertungen aus $stop werden gelesen"

    override fun searchingTheMap(stop: String, what: String) = "Auf der Karte von $stop wird nach $what gesucht"

    override fun searchingReviews(stop: String, query: String) = "In den Bewertungen aus $stop wird nach $query gesucht"

    override val theGuide = "Der Reiseführer"
    override val theMap = "Der Stadtplan"
    override val theReviews = "Die Google-Bewertungen"

    override fun tookTooLong(source: String, stop: String) = "$source hat für $stop zu lange gebraucht."

    override fun gaveNoAnswer(source: String, stop: String, problem: String) = "$source hat für $stop nicht geantwortet: $problem"

    override fun stayNotOnTheMap(place: String, stop: String) =
        "Ich habe „$place“ in $stop nicht auf der Karte gefunden, also wurde keine Mahlzeit danach gewählt, dass sie nahe deiner Unterkunft liegt. Wünsch dir eine Änderung mit einer Straße oder einem Platz in der Nähe."

    override fun nothingToEat(places: List<String>, withReviews: Boolean) =
        "Ich habe in ${places.joinToString(" und ")} keinen Ort zum Essen gefunden, also gibt es noch keine Route. Versuch es später noch einmal, wenn die Quellen antworten" +
            (if (withReviews) "" else ", oder füge in den Einstellungen einen Google-Schlüssel hinzu") + ". Du kannst dir auch eine Änderung wünschen, etwa einen anderen Ort in der Nähe."

    override fun noNewPlace(meals: List<String>) =
        (if (meals.size == 1) "Ich habe keinen anderen Ort für diese Mahlzeit gefunden: " else "Ich habe keinen anderen Ort für diese Mahlzeiten gefunden: ") +
            meals.joinToString("; ") + ". Wünsch dir die Änderung noch einmal, oder sag, worauf du dort Lust hast."

    override fun placesOffered(meals: List<String>) =
        "Ich schlage dir ein paar Orte vor für " + meals.joinToString("; ") + ". Der erste steht auf der Route, bis du den antippst, den du willst."

    override val routeReady = "Deine Route ist fertig. Wenn etwas nicht passt, wünsch dir eine Änderung."

    override val staySaidNowhere = "Du möchtest einige Mahlzeiten nahe deiner Unterkunft, aber ich weiß nicht, wo sie ist. Wünsch dir eine Änderung mit einer Straße oder einem Platz in der Nähe."

    override val sampleRequest = "Zwei Tage in Lissabon. Ich liebe Meeresfrüchte und Süßes."
    override val sampleStop = "Lissabon"
    override val sampleWishes = "Meeresfrüchte und Süßes"
    override val sampleQuestions =
        listOf(
            Question(
                Topic.ALLERGIES,
                "In Lissabon wird viel mit Meeresfrüchten gekocht, und in vielen Süßspeisen stecken Mandeln. Hast du eine Lebensmittelallergie?",
                listOf("Keine", "Meeresfrüchte", "Nüsse", "Gluten"),
                alone = listOf("Keine"),
            ),
            Question(Topic.DIET, "Lissabon liebt Schweinefleisch so sehr wie Fisch: Die Bifana ist ein Brötchen mit Schweinefleisch. Gibt es etwas, das du nicht isst?", listOf("Ich esse alles", "Kein Schweinefleisch", "Pescetarisch", "Vegetarisch"), alone = listOf("Ich esse alles")),
            Question(Topic.DATES, "Wann fährst du? Im Juni grillt die ganze Stadt Sardinen auf der Straße.", listOf("Im Juni", "In einem anderen Sommermonat", "In einer anderen Jahreszeit")),
        )
    override val sampleDishes =
        listOf(
            "Zerpflückter Stockfisch mit Zwiebeln, hauchdünnen Pommes und Ei.",
            "Ein saftiger, suppiger Reis mit Garnelen, Venusmuscheln und Krabben.",
            "Gegrillte Sardinen mit Brot und gekochten Kartoffeln.",
            "Gebratener Oktopus mit Knoblauch und viel Olivenöl.",
            "Ein Brötchen mit dünnen Scheiben Schweinefleisch, in Knoblauch und Weißwein geschmort.",
            "Ein Puddingtörtchen mit gebräunter Oberfläche, warm gegessen und mit Zimt.",
            "Ein Sauerkirschlikör im kleinen Glas, im Stehen an der Theke getrunken.",
        )
    override val sampleStories =
        listOf(
            "Angeblich ist er nach Brás benannt, der eine Taverne im Bairro Alto hatte.",
            "Er kommt im Topf an den Tisch, zum Teilen für zwei oder mehr, und wird mit dem Löffel gegessen.",
            "Im Juni, zu den Festen des heiligen Antonius, grillt die ganze Stadt sie auf der Straße.",
            "Der Lagareiro arbeitete in der Ölmühle: Darum schwimmt der Oktopus in Olivenöl.",
            "In Lissabon kommt Senf darauf, und man isst sie im Stehen an der Theke, mit einem kleinen Bier.",
            "Die ersten backten die Mönche des Hieronymusklosters in Belém. Als die Klöster 1834 geschlossen wurden, kaufte eine Konditorei nebenan das Rezept.",
            "Bestell ihn com elas, mit den Kirschen im Glas, oder sem elas, ohne sie.",
        )
    override val sampleFirstSearch = "Zuerst ein Ort für die Pastéis de Nata."
    override val sampleWhy = "Beispiel: gewählt nach dem, was die Quellen über den Ort sagen, nicht nach diesem Gericht."
    override val sampleWhyPastry = "Beispiel: die erste Konditorei, die der Stadtplan geliefert hat."
    override val sampleSummary =
        "Das ist die Beispielreise. Ein Skript hat sie geschrieben, kein Modell: Die Restaurants sind echt und stammen aus dem Reiseführer und dem Stadtplan, " +
            "aber das Skript hat die mit den meisten Belegen genommen, ohne zu lesen, was sie kochen. " +
            "Füge in den Einstellungen den Schlüssel eines Modells hinzu, und der Planer wählt jeden Ort nach einem Gericht, für deine eigene Reise."
    override val sampleNoChanges = "Das Beispiel ist ein Skript und kann die Route nicht ändern. Füge in den Einstellungen den Schlüssel eines Modells hinzu, und der Planer folgt deinen Wünschen."

    override val stepSpecialists = "Die Orte, die für jedes Gericht bekannt sind"
    override fun lookingForSpecialists(stop: String) = "Die Orte werden gesucht, die für die Gerichte von $stop bekannt sind"
    override fun knownForDish(dish: String) = "Bekannt für: $dish"
    override fun booking(reservation: String) =
        when (reservation) {
            "required" -> "Reservierung nötig"
            "recommended" -> "Besser reservieren"
            "yes" -> "Nimmt Reservierungen an"
            "no" -> "Keine Reservierung: einfach hingehen"
            else -> null
        }
    override val thisDayInGoogleMaps = "Dieser Tag in Google Maps"
    override val moreActions = "Mehr"
    override val readMore = "Weiterlesen"
    override val readLess = "Weniger anzeigen"
    override val onTheMap = "Auf der Karte zeigen"
    override val saveForMyMaps = "Die Orte für Google Maps speichern"
    override val myMapsHintBefore = "Um alle in Google Maps zu haben: Erstelle in "
    override val myMapsHintAfter =
        " eine neue Karte, wähle Importieren und dann die Datei. Danach erscheint die Karte in der Google-Maps-App unter Gespeichert, Karten. " +
            "Am einfachsten importierst du sie an einem Computer."
    override val saveOneByOne = "Speichere die Orte in Google Maps, einen nach dem anderen"
    override val oneByOneHint =
        "Jeder Button öffnet den Ort in Google Maps. Tippe dort auf Speichern, wähle eine Liste, etwa eine neue für diese Reise, und komm für den nächsten zurück. " +
            "Die Seite merkt sich, welche du geöffnet hast."
    override val saveInGoogleMaps = "In Google Maps speichern"
    override fun openedOfAll(opened: Int, all: Int) = "$opened von $all geöffnet"
    override val sendTheFile = "Schick dir die Datei"
    override val sentFileText =
        "Die Orte meiner Foodie Route. Öffne an einem Computer Google My Maps, erstelle eine neue Karte und importiere diese Datei. " +
            "Wenn gefragt wird, welche Spalten genutzt werden sollen, wähle Latitude und Longitude für den Ort und Name für den Titel."
    override val saveToFile = "Den Plan als Datei speichern"
    override val openAFile = "Einen gespeicherten Plan öffnen"
    override val savedFileHint =
        "Die Datei enthält deine Reise, was du dem Planer gesagt hast, und alles, was er gefunden hat, aber keinen deiner Schlüssel. " +
            "Du kannst sie später hier wieder öffnen oder teilen, um zu zeigen, wie der Planer gewählt hat."
    override val notAPlanFile = "Diese Datei ist kein Plan von Foodie Route."
    override val planFromANewerPage = "Eine neuere Version der Seite hat diesen Plan gespeichert. Lade die Seite neu und öffne ihn noch einmal."

    override val changeThisPlace = "Diesen Ort ändern"
    override val whyNotThisOne = "Warum nicht dieser?"

    override fun swapReason(reason: SwapReason) =
        when (reason) {
            SwapReason.DISH -> "Auf dieses Gericht habe ich keine Lust"
            SwapReason.FOOD -> "Eine andere Küche"
            SwapReason.CLOSER -> "Näher an einem Ort"
            SwapReason.CHEAPER -> "Etwas Günstigeres"
            SwapReason.SPECIAL -> "Etwas Besonderes"
            SwapReason.TOURISTY -> "Wirkt touristisch"
            SwapReason.KNOWN -> "Kenne ich schon"
            SwapReason.CLOSED -> "An dem Tag geschlossen oder voll"
        }
    override val whatInstead = "Worauf hast du stattdessen Lust?"
    override val insteadPlaceholder = "Ein Gericht oder eine Küche"
    override val closerToWhat = "Näher woran?"
    override val closerPlaceholder = "Dein Hotel, ein Strand, ein Museum…"
    override val swapNotePlaceholder = "Sonst noch etwas? (optional)"
    override val findAnotherPlace = "Einen anderen Ort suchen"

    override fun changeThesePlaces(count: Int) = "Diese $count Orte ändern"
    override val alsoChangeAnother = "Noch einen Ort ändern"
    override val markedToChange = "Zum Ändern markiert"
    override val editSwap = "Bearbeiten"
    override val keepThisPlace = "Behalten"
    override val changingTheRoute = "Die Route wird geändert"
    override val lookingForOthers = "Andere Orte werden gesucht…"
    override val stopChanging = "Stopp"
    override val nearStay = "Nahe deiner Unterkunft"
    override val nearStayHint = "Tippe die Mahlzeiten an, die du nahe deiner Unterkunft möchtest."

    override fun sleepingAt(place: String) = "Du übernachtest in $place"

    override val recommended = "Empfohlen"
    override val yourChoice = "Deine Wahl"
    override val otherOptions = "Andere Möglichkeiten"
    override val details = "Details"

    override fun otherOptionsCount(count: Int) = if (count == 1) "1 weitere Möglichkeit" else "$count weitere Möglichkeiten"
    override val chooseThisOne = "Diesen wählen"
}
