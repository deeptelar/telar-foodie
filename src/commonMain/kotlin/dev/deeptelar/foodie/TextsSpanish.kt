package dev.deeptelar.foodie

/** The texts in Spanish, as spoken in Spain. */
object Spanish : Texts {
    override val tagline = "Come donde come la gente del lugar. Lo planifica un agente que funciona en tu navegador."
    override val languageLabel = "Idioma"
    override val settings = "Ajustes"
    override val close = "Cerrar"
    override val restaurantsFrom = "Restaurantes de "
    override val ccBySaAnd = " (CC BY-SA) y de los colaboradores de "
    override val contributorsCheckHours = ". Comprueba los horarios antes de ir: una fuente puede no estar al día."
    override val workflowIsAGraphOf = "El flujo de trabajo es un grafo de "
    override val kotlinLibrary = ", una biblioteca de Kotlin para agentes y flujos de IA."

    override val whereAreYouGoing = "¿Adónde vas?"
    override val startHint =
        "Cuéntamelo por escrito o en voz alta: adónde, cuánto tiempo, con quién, qué te encanta y qué no puedes comer. " +
            "Te pregunto lo que falte."
    override val requestPlaceholder = "3 días en Lisboa y 2 en Oporto. Nada de cerdo, y me encantan los dulces."
    override val examples =
        listOf(
            "3 días en Lisboa y 2 en Oporto. Nada de cerdo, y me encantan los dulces.",
            "Un fin de semana en Nápoles: pizza y comida callejera con poco presupuesto.",
            "4 días en Oaxaca, vegetariano.",
            "Dos días en Bolonia y uno en Módena. Quiero los clásicos.",
        )
    override val planMyRoute = "Planifica mi ruta"
    override val trySample = "Prueba el ejemplo, sin clave"
    override val speechPrivacy =
        "Al hablar, tu navegador convierte tu voz en texto. La mayoría lo hace en los servidores de su fabricante, así que la grabación va allí; esta página solo recibe el texto."
    override val needAKey = "Para planificar un viaje propio, añade la clave de un modelo en Ajustes. El ejemplo planifica dos días en Lisboa con un guion en lugar del modelo."
    override val howItWorks = "Cómo funciona"
    override val howItWorksSteps =
        listOf(
            "Un modelo lee tu petición y te pregunta lo que preguntaría un planificador gastronómico: alergias, presupuesto, quién viene, cuánto te atreves a probar.",
            "Encuentra tus paradas en el mapa. Si no puede, te pregunta.",
            "Se consultan a la vez una guía de viaje, un mapa de calles y, con una clave de Google, las reseñas.",
            "El modelo te dice los platos típicos de cada lugar.",
            "Un agente elige un restaurante para cada comida. Puede volver a buscar en las fuentes un plato concreto.",
            "Recibes la ruta en un mapa y puedes pedir cambios hasta que te guste.",
        )

    override val keysStayHere = "Las claves se guardan solo en este navegador y no se envían a nadie más que a su proveedor."
    override val model = "Modelo"
    override val openAiProvider = "OpenAI, Gemini, Ollama u otro servidor con la API de OpenAI"
    override val anthropicKey = "Clave de la API de Anthropic"
    override val claudeModel = "Modelo de Claude"
    override val apiAddress = "Dirección de la API"
    override val apiKey = "Clave de la API"
    override val emptyForLocalServer = "Déjala vacía para un servidor local"
    override val googleKey = "Clave de Google Places, para las valoraciones (opcional)"
    override val googleBills = "Google cobra cada búsqueda al titular de la clave. Sin ella, el planificador usa la guía de viaje y el mapa de calles."
    override val done = "Hecho"

    override val planningYourRoute = "Planificando tu ruta"
    override val stepInterview = "Conociendo tu viaje"
    override val stepBrief = "Buscando tus paradas en el mapa"
    override val stepGuide = "Guía de viaje · Wikivoyage"
    override val stepMap = "Mapa de calles · OpenStreetMap"
    override val stepReviews = "Reseñas · Google"
    override val stepDishes = "Los platos típicos de cada lugar"
    override val stepPlan = "Eligiendo dónde comer"
    override val stepOrder = "Ordenando los días"
    override val reviewsNotUsed = "No se usa. Añade una clave de Google en Ajustes para ver valoraciones."
    override val sampleRunning = "Esto es el ejemplo: un guion hace de modelo. Las fuentes que consulta son reales."
    override val cancel = "Cancelar"

    override fun places(count: Int) = if (count == 1) "1 lugar" else "$count lugares"

    override fun daysIn(days: Int, place: String) = "$days ${if (days == 1) "día" else "días"} en $place"

    override fun mealsOnTheRoute(count: Int) = "$count ${if (count == 1) "comida" else "comidas"} en la ruta"

    override fun retrying(problem: String) = "No ha funcionado ($problem). Lo intento otra vez."

    override fun didSetMeal(day: String, meal: String, restaurant: String) = "Día $day, $meal: $restaurant"

    override fun didRemoveMeal(day: String, meal: String) = "He quitado de la ruta: $meal del día $day"

    override fun didSearchMap(stop: String, what: String) = "He buscado $what en el mapa de $stop"

    override fun didReadGuide(page: String) = "He leído la guía de viaje de $page"

    override fun didSearchReviews(query: String) = "He buscado «$query» en las reseñas"

    override fun didLookThrough(stop: String, words: String) = "He repasado los lugares de $stop" + if (words.isBlank()) "" else " buscando $words"

    override val stoppedHalfway = "Tu plan se quedó a medias"
    override val continueRun = "Continuar"
    override val startOver = "Empezar de nuevo"
    override val gotStuck = "El planificador se ha atascado"
    override val foundIsSaved = "Lo que encontró antes está guardado. Puedes volver a intentar el paso."
    override val tryAgain = "Volver a intentarlo"
    override val somethingWentWrong = "Algo ha ido mal."

    override val refusedLooksUnreachable =
        "Si la dirección es correcta, lo más probable es que el proveedor haya rechazado la petición: los navegadores ocultan el motivo. Revisa en Ajustes la clave, el nombre del modelo y el saldo de la cuenta."

    override fun closedWhileWorking(request: String) = "La página se cerró mientras el planificador trabajaba en «$request». Todo lo que había encontrado está guardado."

    override val oneQuestion = "Una pregunta"
    override val aFewQuestions = "Unas preguntas"
    override val oneMoreThing = "Una cosa más antes de planificar."
    override val lastQuestions = "Son las últimas. Toca todo lo que encaje, escribe lo que no o salta una pregunta."
    override val continueAnswers = "Continuar"
    override val planNow = "Ya basta, planifícalo"
    override val sampleAsks = "Esto es el ejemplo: un guion hace las preguntas y no lee las respuestas."
    override val allergyNote =
        "El planificador deja fuera lo que nombres y te dice qué avisar al personal. No puede saber qué pone una cocina en un plato: avisa siempre al camarero."
    override val pregnancyNote = "Con un embarazo, el planificador sigue los consejos habituales: nada de pescado, carne o huevo crudos, quesos sin pasteurizar, embutidos ni alcohol. Lo que diga tu médico va primero."
    override val yourAnswer = "Tu respuesta"
    override val somethingElse = "Otra cosa, o más detalles"

    override fun youAlreadyToldMe(topics: List<String>) = "Ya me has dicho: ${topics.joinedWith("y")}."

    override fun moreQuestions(count: Int) = "Quedan unas $count después de estas. Toca todo lo que encaje, escribe lo que no o salta una pregunta."

    override fun topic(topic: Topic) =
        when (topic) {
            Topic.WHERE -> "adónde y cuánto tiempo"
            Topic.ALLERGIES -> "alergias"
            Topic.PREGNANCY -> "embarazo"
            Topic.DIET -> "dieta"
            Topic.DATES -> "cuándo"
            Topic.PARTY -> "quién viene"
            Topic.STAY -> "dónde te alojas"
            Topic.MEALS -> "qué comidas"
            Topic.BUDGET -> "presupuesto"
            Topic.TASTE -> "gustos"
            Topic.DRINKS -> "bebidas"
        }

    override fun question(topic: Topic) =
        when (topic) {
            Topic.WHERE -> Question(topic, "¿Adónde vas y cuántos días estarás en cada sitio?")
            Topic.ALLERGIES ->
                Question(topic, "¿Tienes alguna alergia alimentaria? Si la tienes, ¿tienes que evitar incluso las trazas?", listOf("Ninguna", "Frutos secos", "Marisco", "Gluten", "Lácteos"), alone = listOf("Ninguna"))
            Topic.PREGNANCY -> Question(topic, "¿Hay alguien embarazada en este viaje?", listOf("No", "Sí"), alone = listOf("No", "Sí"))
            Topic.DIET -> Question(topic, "¿Hay algo que no comas?", listOf("Como de todo", "Vegetariano", "Pescetariano", "Halal", "Sin cerdo"), alone = listOf("Como de todo"))
            Topic.DATES -> Question(topic, "¿Cuándo vas?", listOf("Primavera", "Verano", "Otoño", "Invierno"))
            Topic.PARTY -> Question(topic, "¿Con quién comes?", listOf("Solo yo", "Dos personas", "Familia con niños", "Grupo de amigos"), alone = listOf("Solo yo"))
            Topic.STAY -> Question(topic, "¿Dónde te alojas en cada sitio? Basta con el barrio o el nombre del hotel.")
            Topic.MEALS -> Question(topic, "¿Qué comidas tiene que planificar la ruta?", listOf("Desayuno", "Comida", "Merienda", "Cena"))
            Topic.BUDGET ->
                Question(topic, "¿Cuánto quieres gastar por comida?", listOf("Lo menos posible", "Precio medio", "Precio medio y una cena especial", "El dinero no importa"), alone = listOf("Lo menos posible", "Precio medio", "Precio medio y una cena especial", "El dinero no importa"))
            Topic.TASTE -> Question(topic, "¿Cuánto te atreves a probar, y hay algo que no te guste?", listOf("Los clásicos", "Me pica la curiosidad", "Lo que coma la gente del lugar"), alone = listOf("Los clásicos", "Me pica la curiosidad", "Lo que coma la gente del lugar"))
            Topic.DRINKS -> Question(topic, "¿Qué quieres beber en la ruta?", listOf("Vino", "Cerveza", "Café", "Cócteles", "Sin alcohol"))
        }

    override val hereIsWhatIHave = "Esto es lo que tengo"
    override val profileHint = "Para esto se planificará la ruta. Dime si algo no está bien."
    override val correctionPlaceholder = "¿Algo que corregir? Por ejemplo: somos tres, no dos."
    override val correctIt = "Corregir"
    override val sampleDidNotRead = "Esto es el ejemplo: el guion no ha leído tus respuestas."
    override val allergies = "Alergias"
    override val noAllergies = "Ninguna"
    override val pregnancy = "Embarazo"
    override val someoneIsPregnant = "Hay alguien embarazada"
    override val diet = "Dieta"
    override val eatsEverything = "Come de todo"
    override val whenLabel = "Cuándo"
    override val who = "Quién"
    override val meals = "Comidas"
    override val budget = "Presupuesto"
    override val withASpecialMeal = ", con una comida especial"
    override val taste = "Gustos"
    override val drinks = "Bebidas"
    override val wishes = "Deseos"

    override fun evenTraces(food: String) = "$food, incluso trazas"

    override fun budget(word: String) =
        when (word.trim().lowercase()) {
            "low" -> "bajo"
            "medium" -> "medio"
            "high" -> "alto"
            else -> word
        }

    override val yourFoodieRoute = "Tu ruta gastronómica"
    override val hereIsYourRoute = "Aquí tienes tu ruta"
    override val noMealPlanned = "El planificador no ha puesto ninguna comida en la ruta. Dile abajo qué buscas, o empieza de nuevo."
    override val wholeTrip = "Todo el viaje"
    override val nothingPlannedThisDay = "Nada planificado para este día."
    override val plannedForYou = "Planificado para ti"
    override val couldNotBeRead = "Lo que no se pudo leer"
    override val enjoyYourTrip = "Disfruta del viaje"
    override val printOrSave = "Imprimir o guardar como PDF"
    override val planAnotherTrip = "Planificar otro viaje"
    override val wantSomethingDifferent = "¿Quieres algo distinto?"
    override val changePlaceholder = "Una cena vegetariana el día 2, y algo más barato para la primera comida."
    override val updateTheRoute = "Actualizar la ruta"
    override val happyWithIt = "Me gusta así"
    override val order = "Pide: "
    override val website = "Web"
    override val openInGoogleMaps = "Abrir en Google Maps"

    override fun day(day: Int) = "Día $day"

    override fun dayAt(day: Int, stop: String) = "Día $day · $stop"

    override fun walk(distance: String, fromStay: Boolean) = "↓ unos $distance desde " + if (fromStay) "donde te alojas" else "allí"

    override fun knownFor(stop: String) = "Qué es típico de $stop"

    override fun placesCompared(count: Int) = "$count lugares comparados"

    override fun tokensUsed(count: Int) = "$count tokens usados"

    override fun ratings(count: Int) = "$count valoraciones"

    override fun meal(time: MealTime) =
        when (time) {
            MealTime.BREAKFAST -> "Desayuno"
            MealTime.LUNCH -> "Comida"
            MealTime.SNACK -> "Merienda"
            MealTime.DINNER -> "Cena"
        }

    override fun source(source: Source) =
        when (source) {
            Source.GUIDE -> "Guía de viaje"
            Source.MAP -> "Mapa de calles"
            Source.REVIEWS -> "Reseñas de Google"
        }

    override fun distance(metres: Int) = distanceIn(metres, ',')

    override val speak = "🎤 Hablar"
    override val stopListening = "■ Parar"
    override val micNotAllowed = "El navegador no puede usar el micrófono. Permítelo en los ajustes del sitio, o escribe."
    override val micNoNetwork = "El navegador no ha podido conectar con su servicio de voz. Escribe en su lugar."
    override val micMissing = "No se ha encontrado ningún micrófono."

    override fun micFailed(problem: String) = "El navegador no ha podido empezar a escuchar: $problem"

    override val readingYourRequest = "Leyendo tu petición"
    override val readingYourAnswers = "Leyendo tus respuestas"
    override val findingYourPlaces = "Buscando los lugares de tu viaje"
    override val whereQuestion = "¿Adónde vas y cuántos días?"
    override val findingDishes = "Buscando los platos típicos de cada lugar"

    override val notingWhatYouSaid = "Anotando lo que me has dicho"

    override fun seconds(count: Int) = "$count s"

    override val modelThinking = "El modelo piensa antes de escribir. Aquí puede tardar hasta un minuto."

    override fun asking(question: String) = "Escribiendo las preguntas: «$question»"

    override fun foundPlaces(places: List<String>) = "Encontrado: ${places.joinToString(" · ")}"

    override fun namingDishes(dishes: List<String>) = "Platos: ${dishes.joinToString(", ")}…"

    override fun notOnTheMap(places: List<String>) = "No he encontrado ${places.joinToString(" ni ")} en el mapa. ¿A qué lugar te refieres?"

    override fun findingOnTheMap(place: String) = "Buscando $place en el mapa"

    override fun readingTheGuide(page: String) = "Leyendo la guía de viaje de $page"

    override fun lookingOnTheMap(cuisine: String, stop: String) = "Buscando restaurantes de cocina local en el mapa de $stop"

    override fun readingReviews(stop: String) = "Leyendo reseñas de $stop"

    override fun searchingTheMap(stop: String, what: String) = "Buscando $what en el mapa de $stop"

    override fun searchingReviews(stop: String, query: String) = "Buscando $query en las reseñas de $stop"

    override val theGuide = "La guía de viaje"
    override val theMap = "El mapa de calles"
    override val theReviews = "Las reseñas de Google"

    override fun tookTooLong(source: String, stop: String) = "$source ha tardado demasiado para $stop."

    override fun gaveNoAnswer(source: String, stop: String, problem: String) = "$source no ha respondido para $stop: $problem"

    override val sampleRequest = "Dos días en Lisboa. Me encantan el marisco y los dulces."
    override val sampleStop = "Lisboa"
    override val sampleWishes = "marisco y dulces"
    override val sampleQuestions =
        listOf(
            Question(
                Topic.ALLERGIES,
                "En Lisboa se cocina mucho marisco, y muchos de sus dulces llevan almendra. ¿Tienes alguna alergia alimentaria?",
                listOf("Ninguna", "Marisco", "Frutos secos", "Gluten"),
                alone = listOf("Ninguna"),
            ),
            Question(Topic.DIET, "Lisboa quiere al cerdo tanto como al pescado: la bifana es un bocadillo de cerdo. ¿Hay algo que no comas?", listOf("Como de todo", "Sin cerdo", "Pescetariano", "Vegetariano"), alone = listOf("Como de todo")),
            Question(Topic.DATES, "¿Cuándo vas? En junio toda la ciudad asa sardinas en la calle.", listOf("En junio", "Otro mes de verano", "Otra estación")),
        )
    override val sampleDishes =
        listOf(
            "Bacalao desmigado con cebolla, patata frita muy fina y huevo.",
            "Un arroz meloso y caldoso con gambas, almejas y cangrejo.",
            "Sardinas a la brasa, con pan y patata cocida.",
            "Pulpo asado con ajo y mucho aceite de oliva.",
            "Un panecillo con cerdo en filetes finos cocido en ajo y vino blanco.",
            "Una tartaleta de crema con la superficie tostada, que se come caliente y con canela.",
            "Un licor de guinda que se toma en vasito, de pie en la barra.",
        )
    override val sampleFirstSearch = "Primero, un sitio para los pastéis de nata."
    override val sampleWhy = "Ejemplo: elegido por lo que dicen de él las fuentes, no por este plato."
    override val sampleWhyPastry = "Ejemplo: la primera pastelería que devolvió el mapa de calles."
    override val sampleSummary =
        "Este es el viaje de ejemplo. Lo ha escrito un guion, no un modelo: los restaurantes son reales y salen de la guía de viaje y del mapa de calles, " +
            "pero el guion ha cogido los que tienen más respaldo sin leer qué cocinan. " +
            "Añade la clave de un modelo en Ajustes y el planificador elegirá cada lugar por un plato, para tu propio viaje."
    override val sampleNoChanges = "El ejemplo es un guion y no puede cambiar la ruta. Añade la clave de un modelo en Ajustes y el planificador seguirá lo que le pidas."
}
