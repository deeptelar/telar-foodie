package dev.deeptelar.foodie

/** The texts in Catalan. */
object Catalan : Texts {
    override val tagline = "Menja on menja la gent del lloc. Ho planifica un agent que funciona al teu navegador."
    override val languageLabel = "Llengua"
    override val settings = "Configuració"
    override val close = "Tanca"
    override val restaurantsFrom = "Restaurants de "
    override val ccBySaAnd = " (CC BY-SA) i dels col·laboradors d'"
    override val contributorsCheckHours = ". Comprova els horaris abans d'anar-hi: una font pot estar desactualitzada."
    override val workflowIsAGraphOf = "El flux de treball és un graf de "
    override val kotlinLibrary = ", una biblioteca de Kotlin per a agents i fluxos d'IA."

    override val whereAreYouGoing = "On vas?"
    override val startHint =
        "Explica-m'ho per escrit o en veu alta: on, quant de temps, amb qui, què t'encanta i què no pots menjar. " +
            "Et pregunto el que falti."
    override val requestPlaceholder = "3 dies a Lisboa i 2 a Porto. Res de porc, i m'encanten els dolços."
    override val examples =
        listOf(
            "3 dies a Lisboa i 2 a Porto. Res de porc, i m'encanten els dolços.",
            "Un cap de setmana a Nàpols: pizza i menjar de carrer amb poc pressupost.",
            "4 dies a Oaxaca, vegetarià.",
            "Dos dies a Bolonya i un a Mòdena. Vull els clàssics.",
        )
    override val planMyRoute = "Planifica la meva ruta"
    override val trySample = "Prova l'exemple, sense clau"
    override val speechPrivacy =
        "Quan parles, el navegador converteix la teva veu en text. La majoria ho fan als servidors del seu fabricant, així que la gravació hi va; aquesta pàgina només rep el text."
    override val needAKey = "Per planificar un viatge teu, afegeix la clau d'un model a Configuració. L'exemple planifica dos dies a Lisboa amb un guió en lloc del model."
    override val howItWorks = "Com funciona"
    override val howItWorksSteps =
        listOf(
            "Un model llegeix la teva petició i et pregunta el que et preguntaria un planificador gastronòmic: al·lèrgies, pressupost, qui ve, fins on t'atreveixes a tastar.",
            "Troba les teves parades al mapa. Si no pot, t'ho pregunta.",
            "Es consulten alhora una guia de viatge, un mapa de carrers i, amb una clau de Google, les ressenyes.",
            "El model et diu els plats típics de cada lloc.",
            "Un agent tria un restaurant per a cada àpat. Pot tornar a buscar a les fonts un plat concret.",
            "Reps la ruta en un mapa i pots demanar canvis fins que t'agradi.",
        )

    override val keysStayHere = "Les claus es desen només en aquest navegador i no s'envien a ningú més que al seu proveïdor."
    override val model = "Model"
    override val openAiProvider = "OpenAI, Gemini, Ollama o un altre servidor amb l'API d'OpenAI"
    override val anthropicKey = "Clau de l'API d'Anthropic"
    override val claudeModel = "Model de Claude"
    override val apiAddress = "Adreça de l'API"
    override val apiKey = "Clau de l'API"
    override val emptyForLocalServer = "Deixa-la buida per a un servidor local"
    override val googleKey = "Clau de Google Places, per a les valoracions (opcional)"
    override val googleBills = "Google cobra cada cerca al titular de la clau. Sense ella, el planificador fa servir la guia de viatge i el mapa de carrers."
    override val done = "Fet"

    override val planningYourRoute = "Planificant la teva ruta"
    override val stepInterview = "Coneixent el teu viatge"
    override val stepBrief = "Buscant les teves parades al mapa"
    override val stepGuide = "Guia de viatge · Wikivoyage"
    override val stepMap = "Mapa de carrers · OpenStreetMap"
    override val stepReviews = "Ressenyes · Google"
    override val stepDishes = "Els plats típics de cada lloc"
    override val stepPlan = "Triant on menjar"
    override val stepOrder = "Posant els dies en ordre"
    override val reviewsNotUsed = "No s'utilitza. Afegeix una clau de Google a Configuració per veure valoracions."
    override val sampleRunning = "Això és l'exemple: un guió fa de model. Les fonts que consulta són reals."
    override val cancel = "Cancel·la"

    override fun places(count: Int) = if (count == 1) "1 lloc" else "$count llocs"

    override fun daysIn(days: Int, place: String) = "$days ${if (days == 1) "dia" else "dies"} a $place"

    override fun mealsOnTheRoute(count: Int) = "$count ${if (count == 1) "àpat" else "àpats"} a la ruta"

    override fun retrying(problem: String) = "No ha funcionat ($problem). Ho torno a provar."

    override fun didSetMeal(day: String, meal: String, restaurant: String) = "Dia $day, $meal: $restaurant"

    override fun didRemoveMeal(day: String, meal: String) = "He tret de la ruta: $meal del dia $day"

    override fun didSearchMap(stop: String, what: String) = "He buscat $what al mapa de $stop"

    override fun didReadGuide(page: String) = "He llegit la guia de viatge de $page"

    override fun didSearchReviews(query: String) = "He buscat «$query» a les ressenyes"

    override fun didLookThrough(stop: String, words: String) = "He repassat els llocs de $stop" + if (words.isBlank()) "" else " buscant $words"

    override val stoppedHalfway = "El teu pla s'ha quedat a mitges"
    override val continueRun = "Continua"
    override val startOver = "Torna a començar"
    override val gotStuck = "El planificador s'ha encallat"
    override val foundIsSaved = "El que havia trobat abans està desat. Pots tornar a provar el pas."
    override val tryAgain = "Torna-ho a provar"
    override val somethingWentWrong = "Alguna cosa ha anat malament."

    override val refusedLooksUnreachable =
        "Si l'adreça és correcta, el més probable és que el proveïdor hagi rebutjat la petició: els navegadors n'amaguen el motiu. Revisa a Configuració la clau, el nom del model i el saldo del compte."

    override fun closedWhileWorking(request: String) = "La pàgina es va tancar mentre el planificador treballava en «$request». Tot el que havia trobat està desat."

    override val oneQuestion = "Una pregunta"
    override val aFewQuestions = "Unes quantes preguntes"
    override val oneMoreThing = "Una cosa més abans de planificar."
    override val lastQuestions = "Són les últimes. Toca tot el que encaixi, escriu el que no o salta una pregunta."
    override val continueAnswers = "Continua"
    override val planNow = "Prou preguntes"
    override val sampleAsks = "Això és l'exemple: un guió fa les preguntes i no llegeix les respostes."
    override val allergyNote =
        "El planificador deixa fora el que diguis i t'indica què has de dir al personal. No pot saber què posa una cuina en un plat: digues-ho sempre al cambrer."
    override val pregnancyNote = "Amb un embaràs, el planificador segueix els consells habituals: res de peix, carn o ou crus, formatges sense pasteuritzar, embotits ni alcohol. El que digui el teu metge va primer."
    override val yourAnswer = "La teva resposta"
    override val somethingElse = "Una altra cosa, o més detalls"

    override fun youAlreadyToldMe(topics: List<String>) = "Ja m'has dit: ${topics.joinedWith("i")}."

    override fun moreQuestions(count: Int) = "En queden unes $count després d'aquestes. Toca tot el que encaixi, escriu el que no o salta una pregunta."

    override fun topic(topic: Topic) =
        when (topic) {
            Topic.WHERE -> "on i quant de temps"
            Topic.ALLERGIES -> "al·lèrgies"
            Topic.PREGNANCY -> "embaràs"
            Topic.DIET -> "dieta"
            Topic.DATES -> "quan"
            Topic.PARTY -> "qui ve"
            Topic.STAY -> "on t'allotges"
            Topic.MEALS -> "quins àpats"
            Topic.BUDGET -> "pressupost"
            Topic.TASTE -> "gustos"
            Topic.DRINKS -> "begudes"
        }

    override fun question(topic: Topic) =
        when (topic) {
            Topic.WHERE -> Question(topic, "On vas i quants dies estaràs a cada lloc?")
            Topic.ALLERGIES ->
                Question(topic, "Tens alguna al·lèrgia alimentària? Si en tens, has d'evitar fins i tot les traces?", listOf("Cap", "Fruits secs", "Marisc", "Gluten", "Lactis"), alone = listOf("Cap"))
            Topic.PREGNANCY -> Question(topic, "Hi ha algú embarassada en aquest viatge?", listOf("No", "Sí"), alone = listOf("No", "Sí"))
            Topic.DIET -> Question(topic, "Hi ha res que no mengis?", listOf("Menjo de tot", "Vegetarià", "Piscivegetarià", "Halal", "Sense porc"), alone = listOf("Menjo de tot"))
            Topic.DATES -> Question(topic, "Quan hi vas?", listOf("Primavera", "Estiu", "Tardor", "Hivern"))
            Topic.PARTY -> Question(topic, "Amb qui menges?", listOf("Només jo", "Dues persones", "Família amb nens", "Grup d'amics"), alone = listOf("Només jo"))
            Topic.STAY -> Question(topic, "On t'allotges a cada lloc? N'hi ha prou amb el barri o el nom de l'hotel.")
            Topic.MEALS -> Question(topic, "Quins àpats ha de planificar la ruta?", listOf("Esmorzar", "Dinar", "Berenar", "Sopar"))
            Topic.BUDGET ->
                Question(topic, "Quant vols gastar per àpat?", listOf("Com menys millor", "Preu mitjà", "Preu mitjà i un sopar especial", "Els diners no importen"), alone = listOf("Com menys millor", "Preu mitjà", "Preu mitjà i un sopar especial", "Els diners no importen"))
            Topic.TASTE -> Question(topic, "Fins on t'atreveixes a tastar, i hi ha res que no t'agradi?", listOf("Els clàssics", "Tinc curiositat", "El que mengi la gent del lloc"), alone = listOf("Els clàssics", "Tinc curiositat", "El que mengi la gent del lloc"))
            Topic.DRINKS -> Question(topic, "Què vols beure a la ruta?", listOf("Vi", "Cervesa", "Cafè", "Còctels", "Sense alcohol"))
        }

    override val hereIsWhatIHave = "Això és el que tinc"
    override val profileHint = "Per a això es planificarà la ruta. Canvia o esborra el que vulguis, o explica-m'ho a sota amb les teves paraules."
    override val correctionPlaceholder = "Alguna cosa a corregir? Per exemple: som tres, no dos."
    override val correctIt = "Corregeix"
    override val sampleDidNotRead = "Això és l'exemple: el guió no ha llegit les teves respostes."
    override val allergies = "Al·lèrgies"
    override val noAllergies = "Cap"
    override val pregnancy = "Embaràs"
    override val someoneIsPregnant = "Hi ha algú embarassada"
    override val diet = "Dieta"
    override val eatsEverything = "Menja de tot"
    override val whenLabel = "Quan"
    override val who = "Qui"
    override val meals = "Àpats"
    override val budget = "Pressupost"
    override val withASpecialMeal = ", amb un àpat especial"
    override val taste = "Gustos"
    override val drinks = "Begudes"
    override val wishes = "Desitjos"

    override fun evenTraces(food: String) = "$food, fins i tot traces"

    override val notSaid = "No ho has dit"
    override val erase = "Esborra"
    override val addAllergy = "Afegeix una al·lèrgia"
    override val allergyPlaceholder = "Per exemple, cacauets"
    override val evenTracesOption = "Fins i tot traces"
    override val oneSpecialMeal = "Un àpat especial"
    override val mealsIfNone = "Si no en tries cap: dinar i sopar."
    override val eraseEverything = "Esborra-ho tot"
    override val keptInThisBrowser = "Només es desa en aquest navegador, amb aquest viatge. «Torna a començar» ho esborra tot."
    override val updateForTheseDetails = "Actualitza la ruta amb aquestes dades"

    override fun budget(word: String) =
        when (word.trim().lowercase()) {
            "low" -> "baix"
            "medium" -> "mitjà"
            "high" -> "alt"
            else -> word
        }

    override val yourFoodieRoute = "La teva ruta gastronòmica"
    override val hereIsYourRoute = "Aquí tens la teva ruta"
    override val noMealPlanned = "El planificador no ha posat cap àpat a la ruta. Digues-li a sota què busques, o torna a començar."
    override val wholeTrip = "Tot el viatge"
    override val nothingPlannedThisDay = "No hi ha res planificat per a aquest dia."
    override val plannedForYou = "Planificat per a tu"
    override val couldNotBeRead = "El que no s'ha pogut llegir"
    override val enjoyYourTrip = "Gaudeix del viatge"
    override val printOrSave = "Imprimeix o desa com a PDF"
    override val planAnotherTrip = "Planifica un altre viatge"
    override val wantSomethingDifferent = "Vols alguna cosa diferent?"
    override val changePlaceholder = "Un sopar vegetarià el dia 2, i un lloc més barat per al primer dinar."
    override val updateTheRoute = "Actualitza la ruta"
    override val happyWithIt = "M'agrada així"
    override val order = "Demana: "
    override val website = "Web"
    override val openInGoogleMaps = "Obre a Google Maps"

    override fun day(day: Int) = "Dia $day"

    override fun dayAt(day: Int, stop: String) = "Dia $day · $stop"

    override fun walk(distance: String, fromStay: Boolean) = "↓ uns $distance " + if (fromStay) "des d'on t'allotges" else "des d'allà"

    override fun knownFor(stop: String) = "Què és típic de $stop"

    override fun placesCompared(count: Int) = "$count llocs comparats"

    override fun tokensUsed(count: Int) = "$count tokens utilitzats"

    override fun ratings(count: Int) = "$count valoracions"

    override fun meal(time: MealTime) =
        when (time) {
            MealTime.BREAKFAST -> "Esmorzar"
            MealTime.LUNCH -> "Dinar"
            MealTime.SNACK -> "Berenar"
            MealTime.DINNER -> "Sopar"
        }

    override fun source(source: Source) =
        when (source) {
            Source.GUIDE -> "Guia de viatge"
            Source.MAP -> "Mapa de carrers"
            Source.REVIEWS -> "Ressenyes de Google"
        }

    override fun distance(metres: Int) = distanceIn(metres, ',')

    override val speak = "🎤 Parla"
    override val stopListening = "■ Atura"
    override val micNotAllowed = "El navegador no pot fer servir el micròfon. Permet-ho a la configuració del lloc web, o escriu."
    override val micNoNetwork = "El navegador no ha pogut connectar amb el seu servei de veu. Escriu-ho."
    override val micMissing = "No s'ha trobat cap micròfon."

    override fun micFailed(problem: String) = "El navegador no ha pogut començar a escoltar: $problem"

    override val readingYourRequest = "Llegint la teva petició"
    override val readingYourAnswers = "Llegint les teves respostes"
    override val findingYourPlaces = "Buscant els llocs del teu viatge"
    override val whereQuestion = "On vas, i quants dies?"
    override val findingDishes = "Buscant els plats típics de cada lloc"

    override val notingWhatYouSaid = "Anotant el que m'has dit"

    override fun seconds(count: Int) = "$count s"

    override val modelThinking = "El model pensa abans d'escriure. Aquí pot trigar fins a un minut."

    override fun asking(question: String) = "Escrivint les preguntes: «$question»"

    override fun foundPlaces(places: List<String>) = "Trobat: ${places.joinToString(" · ")}"

    override fun namingDishes(dishes: List<String>) = "Plats: ${dishes.joinToString(", ")}…"

    override fun notOnTheMap(places: List<String>) = "No he trobat ${places.joinToString(" ni ")} al mapa. Quin lloc vols dir?"

    override fun findingOnTheMap(place: String) = "Buscant $place al mapa"

    override fun readingTheGuide(page: String) = "Llegint la guia de viatge de $page"

    override fun lookingOnTheMap(cuisine: String, stop: String) = "Buscant restaurants de cuina local al mapa de $stop"

    override fun readingReviews(stop: String) = "Llegint ressenyes de $stop"

    override fun searchingTheMap(stop: String, what: String) = "Buscant $what al mapa de $stop"

    override fun searchingReviews(stop: String, query: String) = "Buscant $query a les ressenyes de $stop"

    override val theGuide = "La guia de viatge"
    override val theMap = "El mapa de carrers"
    override val theReviews = "Les ressenyes de Google"

    override fun tookTooLong(source: String, stop: String) = "$source ha trigat massa per a $stop."

    override fun gaveNoAnswer(source: String, stop: String, problem: String) = "$source no ha respost per a $stop: $problem"

    override val sampleRequest = "Dos dies a Lisboa. M'encanten el marisc i els dolços."
    override val sampleStop = "Lisboa"
    override val sampleWishes = "marisc i dolços"
    override val sampleQuestions =
        listOf(
            Question(
                Topic.ALLERGIES,
                "A Lisboa es cuina molt de marisc, i molts dels seus dolços porten ametlla. Tens alguna al·lèrgia alimentària?",
                listOf("Cap", "Marisc", "Fruits secs", "Gluten"),
                alone = listOf("Cap"),
            ),
            Question(Topic.DIET, "Lisboa estima el porc tant com el peix: la bifana és un entrepà de porc. Hi ha res que no mengis?", listOf("Menjo de tot", "Sense porc", "Piscivegetarià", "Vegetarià"), alone = listOf("Menjo de tot")),
            Question(Topic.DATES, "Quan hi vas? Al juny tota la ciutat fa sardines a la brasa al carrer.", listOf("Al juny", "Un altre mes d'estiu", "Una altra època de l'any")),
        )
    override val sampleDishes =
        listOf(
            "Bacallà esmicolat amb ceba, patata fregida molt fina i ou.",
            "Un arròs melós i caldós amb gambes, cloïsses i cranc.",
            "Sardines a la brasa, amb pa i patata bullida.",
            "Pop rostit amb all i molt d'oli d'oliva.",
            "Un panet amb porc a làmines fines cuit amb all i vi blanc.",
            "Una tarteleta de crema amb la superfície torrada, que es menja calenta i amb canyella.",
            "Un licor de guinda que es pren en un gotet, dret a la barra.",
        )
    override val sampleFirstSearch = "Primer, un lloc per als pastéis de nata."
    override val sampleWhy = "Exemple: triat pel que en diuen les fonts, no per aquest plat."
    override val sampleWhyPastry = "Exemple: la primera pastisseria que ha tornat el mapa de carrers."
    override val sampleSummary =
        "Aquest és el viatge d'exemple. L'ha escrit un guió, no un model: els restaurants són reals i surten de la guia de viatge i del mapa de carrers, " +
            "però el guió ha agafat els que tenen més suport sense llegir què cuinen. " +
            "Afegeix la clau d'un model a Configuració i el planificador triarà cada lloc per un plat, per al teu propi viatge."
    override val sampleNoChanges = "L'exemple és un guió i no pot canviar la ruta. Afegeix la clau d'un model a Configuració i el planificador seguirà el que li demanis."
}
