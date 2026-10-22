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
    override val upFrontTitle = "Dímelo ahora y no te lo preguntaré"
    override val upFrontHint = "Todo es opcional: lo que te saltes, te lo pregunto después si el viaje lo necesita."
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
    override val visitsCounted =
        "La página cuenta las visitas y algunos pasos, como un plan empezado o una ruta lista, con GoatCounter: sin cookies y sin nada del viaje."
    override val model = "Modelo"
    override val openAiProvider = "OpenAI, Gemini, Ollama u otro servidor con la API de OpenAI"
    override val anthropicKey = "Clave de la API de Anthropic"
    override val claudeModel = "Modelo de Claude"
    override val apiAddress = "Dirección de la API"
    override val apiKey = "Clave de la API"
    override val emptyForLocalServer = "Déjala vacía para un servidor local"
    override val localServerHint =
        "Un servidor de este ordenador, como Ollama, tiene que permitir el sitio de esta página, o el navegador detiene sus respuestas. El navegador también puede preguntar si la página puede acceder a tu red local. Inicia Ollama con:"
    override val googleKey = "Clave de Google Places, para las valoraciones (opcional)"
    override val googleBills =
        "Google da una cantidad de búsquedas gratis al mes y cobra el resto al titular de la clave. " +
            "Un viaje hace una búsqueda por parada, y unas pocas más cuando el planificador busca un sitio. " +
            "Sin clave, el planificador usa la guía de viaje y el mapa de calles."
    override val howToGetAGoogleKey = "Cómo conseguir una clave de Google"

    override fun googleKeySteps(site: String) =
        listOf(
            KeyStep("Crea un proyecto en la consola de Google Cloud. Google pide una cuenta de facturación, con una tarjeta.", "Crear un proyecto", GoogleConsole.NEW_PROJECT),
            KeyStep("Abre Places API (New) y pulsa Habilitar.", "Places API (New)", GoogleConsole.PLACES_API),
            KeyStep("En Credenciales, pulsa Crear credenciales y luego Clave de API.", "Credenciales", GoogleConsole.CREDENTIALS),
            KeyStep(
                "Restringe la clave, para que solo esta página pueda usarla: en Restricciones de aplicaciones elige Sitios web y añade $site, " +
                    "y en Restricciones de API elige Places API (New).",
            ),
            KeyStep("Copia la clave y pégala arriba."),
        )
    override val googlePrices = "Los precios de Google"
    override val googleGuide = "La guía de Google"
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
    override val sampleRunning = "Esto es el ejemplo: un guion hace de modelo. Lo que responden las fuentes es real: la guía de viaje y el mapa, consultados una vez y guardados."
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
    override val pause = "Pausar"
    override val paused = "En pausa"
    override val pausedHint = "Añade lo que el planificador debería saber, o dile que haga algo de otra manera. Seguirá teniéndolo en cuenta."
    override val asidePlaceholder = "Por ejemplo: tendremos coche. O: más marisco y menos pasta."
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
    override val planNow = "Basta de preguntas"
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
            Topic.STAY -> Question(topic, "¿Dónde te alojas en cada sitio, y desde qué día si cambias de hotel? Basta con el barrio o el nombre del hotel. Dime también si quieres alguna comida cerca, como el desayuno.")
            Topic.MEALS -> Question(topic, "¿Qué comidas tiene que planificar la ruta?", listOf("Desayuno", "Comida", "Merienda", "Cena"))
            Topic.BUDGET ->
                Question(topic, "¿Cuánto quieres gastar por comida?", listOf("Lo menos posible", "Precio medio", "Precio medio y una cena especial", "El dinero no importa"), alone = listOf("Lo menos posible", "Precio medio", "Precio medio y una cena especial", "El dinero no importa"))
            Topic.TASTE -> Question(topic, "¿Cuánto te atreves a probar, y hay algo que no te guste?", listOf("Los clásicos", "Me pica la curiosidad", "Lo que coma la gente del lugar"), alone = listOf("Los clásicos", "Me pica la curiosidad", "Lo que coma la gente del lugar"))
            Topic.DRINKS -> Question(topic, "¿Qué quieres beber en la ruta?", listOf("Vino", "Cerveza", "Café", "Cócteles", "Sin alcohol"))
        }

    override val planTab = "Plan"
    override val detailsTab = "Tus datos"
    override val skipped = "Sin respuesta"
    override val youEditedDetails = "Has cambiado tus datos."
    override val youErasedDetails = "Has borrado tus datos."
    override val shallIPlan = "¿Planifico la ruta?"
    override val yesPlanIt = "Sí, planifícala"
    override val send = "Enviar"
    override val seeYourDetails = "Ver o cambiar tus datos"
    override val noDetailsYet = "Aún nada. Aquí aparece lo que le cuentes al planificador sobre ti."
    override val detailsWhileWorking = "El planificador está trabajando con esto. Ponlo en pausa para cambiarlo."
    override val detailsReadOnly = "La ruta se ha planificado para esto. «Empezar de nuevo» lo borra."
    override val save = "Guardar"
    override val saved = "Guardado."

    override val hereIsWhatIHave = "Esto es lo que tengo"
    override val profileHint = "Para esto se planifica la ruta. Cambia o borra lo que quieras y guárdalo."
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

    override val notSaid = "No lo has dicho"
    override val erase = "Borrar"
    override val addAllergy = "Añadir una alergia"
    override val allergyPlaceholder = "Por ejemplo, cacahuetes"
    override val evenTracesOption = "Incluso trazas"
    override val oneSpecialMeal = "Una comida especial"
    override val mealsIfNone = "Si no eliges ninguna: comida y cena."
    override val eraseEverything = "Borrarlo todo"
    override val keptInThisBrowser = "Solo se guarda en este navegador, con este viaje. «Empezar de nuevo» lo borra todo."
    override val updateForTheseDetails = "Actualizar la ruta con estos datos"

    override fun budget(word: String) =
        when (word.trim().lowercase()) {
            "low" -> "bajo"
            "medium" -> "medio"
            "high" -> "alto"
            else -> word
        }

    override val noMealPlanned = "El planificador no ha puesto ninguna comida en la ruta. Dile abajo qué buscas, o empieza de nuevo."
    override val wholeTrip = "Todo el viaje"
    override val nothingPlannedThisDay = "Nada planificado para este día."
    override val plannedForYou = "Planificado para ti"
    override val couldNotBeRead = "Lo que no se pudo leer"
    override val enjoyYourTrip = "Disfruta del viaje"
    override val printOrSave = "Imprimir o guardar como PDF"
    override val planAnotherTrip = "Planificar otro viaje"
    override val planAgain = "Planificar de nuevo con mis respuestas"
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

    override val whatItCost = "Lo que ha costado este plan"

    override fun costAbout(amount: String) = "Este plan ha costado unos $amount"

    override fun modelCost(model: String, read: Int, written: Int, amount: String?) = "$model: $read tokens leídos y $written escritos" + (amount?.let { " · $it" } ?: "")

    override fun googleCost(searches: Int, amount: String) = "Google Places: $searches " + (if (searches == 1) "búsqueda" else "búsquedas") + " · $amount"

    override val atListPrices =
        "A los precios de lista de los proveedores de octubre de 2026. Google no cobra las primeras 1.000 búsquedas de cada mes, y la caché de un proveedor puede abaratar los tokens leídos."

    override fun money(dollars: Double) = amount(dollars, ',') + " US$"

    override val tracing = "Trazas de OpenTelemetry (para desarrolladores)"

    override val tracingHint =
        "Cada ejecución se envía como una traza: un span por paso, con lo que ha tardado y lo que ha fallado, y nada del viaje. El colector debe permitir el origen de esta página (CORS)."

    override val tracingAddress = "Dirección de las trazas (OTLP/HTTP)"

    override val tracingHeader = "Cabecera para la clave del servicio (Nombre: valor, opcional)"

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
    override val readingTheGuideFirst = "Primero, lo que dice la guía de viaje de cada lugar"

    override val notingWhatYouSaid = "Anotando lo que me has dicho"

    override fun seconds(count: Int) = "$count s"

    override val modelThinking = "El modelo piensa antes de escribir. Aquí puede tardar hasta un minuto."

    override fun whileIPlan(stop: String) = "Mientras planifico tu ruta · $stop"
    override val anotherDish = "Otro"

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

    override fun stayNotOnTheMap(place: String, stop: String) =
        "No he encontrado «$place» de $stop en el mapa, así que ninguna comida se ha elegido por estar cerca de donde duermes. Pide un cambio con una calle o una plaza cercana."

    override val staySaidNowhere = "Quieres algunas comidas cerca de donde duermes, pero no sé dónde es. Pide un cambio con una calle o una plaza cercana."

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
    override val sampleStories =
        listOf(
            "Dicen que lleva el nombre de Brás, que tenía una taberna en el Bairro Alto.",
            "Llega a la mesa en su olla, para compartir entre dos o más, y se come con cuchara.",
            "En junio, por las fiestas de San Antonio, toda la ciudad las asa en la calle.",
            "El lagareiro trabajaba en la almazara: por eso el pulpo nada en aceite de oliva.",
            "En Lisboa le ponen mostaza y se toma de pie en la barra, con una caña pequeña.",
            "Los primeros los hicieron los monjes del Monasterio de los Jerónimos, en Belém. Cuando cerraron los monasterios, en 1834, una pastelería de al lado compró la receta.",
            "Pídela com elas, con las guindas en el vaso, o sem elas, sin ellas.",
        )
    override val sampleFirstSearch = "Primero, un sitio para los pastéis de nata."
    override val sampleWhy = "Ejemplo: elegido por lo que dicen de él las fuentes, no por este plato."
    override val sampleWhyPastry = "Ejemplo: la primera pastelería que devolvió el mapa de calles."
    override val sampleSummary =
        "Este es el viaje de ejemplo. Lo ha escrito un guion, no un modelo: los restaurantes son reales y salen de la guía de viaje y del mapa de calles, " +
            "pero el guion ha cogido los que tienen más respaldo sin leer qué cocinan. " +
            "Añade la clave de un modelo en Ajustes y el planificador elegirá cada lugar por un plato, para tu propio viaje."
    override val sampleNoChanges = "El ejemplo es un guion y no puede cambiar la ruta. Añade la clave de un modelo en Ajustes y el planificador seguirá lo que le pidas."

    override val stepSpecialists = "Los sitios conocidos por cada plato"
    override fun lookingForSpecialists(stop: String) = "Buscando los sitios conocidos por los platos de $stop"
    override fun knownForDish(dish: String) = "Conocido por: $dish"
    override fun booking(reservation: String) =
        when (reservation) {
            "required" -> "Hay que reservar"
            "recommended" -> "Mejor reservar"
            "yes" -> "Acepta reservas"
            "no" -> "Sin reservas: ve directamente"
            else -> null
        }
    override val thisDayInGoogleMaps = "Este día en Google Maps"
    override val moreActions = "Más"
    override val readMore = "Leer más"
    override val readLess = "Mostrar menos"
    override val onTheMap = "Ver en el mapa"
    override val saveForMyMaps = "Guardar los sitios para Google Maps"
    override val myMapsHintBefore = "Para tenerlos todos en Google Maps: en "
    override val myMapsHintAfter =
        ", crea un mapa nuevo, elige Importar y escoge el archivo. Luego el mapa aparece en la app de Google Maps, en Guardados, Mapas. " +
            "Es más fácil importarlo desde un ordenador."
    override val saveOneByOne = "Guarda los sitios en Google Maps, uno a uno"
    override val oneByOneHint =
        "Cada botón abre el sitio en Google Maps. Allí toca Guardar, elige una lista, por ejemplo una nueva para este viaje, y vuelve para el siguiente. " +
            "La página recuerda cuáles has abierto."
    override val saveInGoogleMaps = "Guardar en Google Maps"
    override fun openedOfAll(opened: Int, all: Int) = "$opened de $all abiertos"
    override val sendTheFile = "Envíate el archivo"
    override val sentFileText =
        "Los sitios de mi Foodie Route. En un ordenador, abre Google My Maps, crea un mapa nuevo e importa este archivo. " +
            "Si pregunta qué columnas usar, elige Latitude y Longitude para el sitio, y Name para el título."
    override val saveToFile = "Guardar el plan en un archivo"
    override val openAFile = "Abrir un plan guardado"
    override val savedFileHint =
        "El archivo tiene tu viaje, lo que le dijiste al planificador y todo lo que encontró, pero ninguna de tus claves. " +
            "Puedes volver a abrirlo aquí más tarde, o compartirlo para mostrar cómo eligió el planificador."
    override val notAPlanFile = "Este archivo no es un plan de Foodie Route."
    override val planFromANewerPage = "Una versión más nueva de la página guardó este plan. Recarga la página y vuelve a abrirlo."

    override val changeThisPlace = "Cambiar este sitio"
    override val whyNotThisOne = "¿Por qué no este?"

    override fun swapReason(reason: SwapReason) =
        when (reason) {
            SwapReason.DISH -> "No me apetece este plato"
            SwapReason.FOOD -> "Otro tipo de comida"
            SwapReason.CLOSER -> "Más cerca de algún sitio"
            SwapReason.CHEAPER -> "Algo más barato"
            SwapReason.SPECIAL -> "Algo especial"
            SwapReason.TOURISTY -> "Parece turístico"
            SwapReason.KNOWN -> "Ya lo conozco"
            SwapReason.CLOSED -> "Cerrado o lleno ese día"
        }
    override val whatInstead = "¿Qué te apetece en su lugar?"
    override val insteadPlaceholder = "Un plato o un tipo de comida"
    override val closerToWhat = "¿Más cerca de qué?"
    override val closerPlaceholder = "Tu hotel, una playa, un museo…"
    override val swapNotePlaceholder = "¿Algo más? (opcional)"
    override val findAnotherPlace = "Buscar otro sitio"

    override fun changeThesePlaces(count: Int) = "Cambiar estos $count sitios"
    override val alsoChangeAnother = "Cambiar también otro sitio"
    override val markedToChange = "Marcado para cambiar"
    override val editSwap = "Editar"
    override val keepThisPlace = "Mantenerlo"
    override val changingTheRoute = "Cambiando la ruta"
    override val lookingForOthers = "Buscando otros sitios…"
    override val stopChanging = "Detener"
    override val nearStay = "Cerca de donde duermes"
    override val nearStayHint = "Toca las comidas que quieres cerca de donde duermes."

    override fun sleepingAt(place: String) = "Duermes en $place"

    override val recommended = "Recomendado"
    override val yourChoice = "Tu elección"
    override val otherOptions = "Otras opciones"
    override val details = "Detalles"

    override fun otherOptionsCount(count: Int) = if (count == 1) "1 opción más" else "$count opciones más"
    override val chooseThisOne = "Elegir este"
}
