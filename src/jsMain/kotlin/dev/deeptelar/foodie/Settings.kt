package dev.deeptelar.foodie

import dev.deeptelar.telar.agent.ChatModel
import dev.deeptelar.telar.anthropic.AnthropicChatModel
import dev.deeptelar.telar.openai.OpenAiChatModel
import io.ktor.client.HttpClient
import kotlinx.browser.localStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What the traveller set up: which model plans, and the keys. It is kept in the `localStorage` of
 * this browser and nowhere else. A key leaves the browser only in a request to its own provider.
 */
@Serializable
data class Settings(
    val provider: Provider = Provider.CLAUDE,
    val claudeKey: String = "",
    val claudeModel: String = CLAUDE_MODELS.first().first,
    val openAiKey: String = "",
    val openAiModel: String = "gpt-5",
    val openAiUrl: String = OpenAiChatModel.OPENAI_BASE_URL,
    val googleKey: String = "",
    /** Where the traces of the runs go: the `/v1/traces` of an OpenTelemetry collector, or empty for none. */
    val traceUrl: String = "",
    /** A header that every request of traces sends, such as the key of a backend: "Name: value". */
    val traceHeader: String = "",
    /** The language the traveller chose, or `null` for the browser's. */
    val language: Language? = null,
) {
    /** The header of [traceHeader], as a name and a value; none when it has no name. */
    val traceHeaders: Map<String, String>
        get() {
            val name = traceHeader.substringBefore(':').trim()
            return if (name.isEmpty() || ':' !in traceHeader) emptyMap() else mapOf(name to traceHeader.substringAfter(':').trim())
        }

    /** Whether a model can be asked with these settings. A local server such as Ollama needs no key, but then it has another address. */
    val hasModel: Boolean
        get() =
            when (provider) {
                Provider.CLAUDE -> claudeKey.isNotBlank()
                Provider.OPENAI -> openAiKey.isNotBlank() || openAiUrl.trim() != OpenAiChatModel.OPENAI_BASE_URL
            }

    /** The name of the model of these settings, without its key: a saved plan says which model planned it. */
    val modelName: String
        get() =
            when (provider) {
                Provider.CLAUDE -> claudeModel
                Provider.OPENAI -> openAiModel.trim()
            }

    /** The model of these settings, or `null` when it has no key yet. */
    fun model(client: HttpClient): ChatModel? =
        when {
            !hasModel -> null
            provider == Provider.CLAUDE ->
                AnthropicChatModel(
                    client = client,
                    apiKey = claudeKey.trim(),
                    model = claudeModel,
                    // A browser may only call the API with this header. That is acceptable here because
                    // the key is the traveller's own; never ship a page with a key of yours in it.
                    headers = mapOf("anthropic-dangerous-direct-browser-access" to "true"),
                )
            else -> openAi(client)
        }

    /**
     * The model that chooses the restaurants. That takes many rounds with long requests, and
     * picking from a list of candidates needs little thought: a reasoning model of OpenAI thinks
     * less there than it does by default, which saves most of the wait.
     */
    fun planModel(client: HttpClient): ChatModel? =
        if (hasModel && provider == Provider.OPENAI && thinksLess(openAiModel.trim())) openAi(client, buildJsonObject { put("reasoning_effort", "low") }) else model(client)

    private fun openAi(client: HttpClient, parameters: JsonObject = JsonObject(emptyMap())): OpenAiChatModel =
        OpenAiChatModel(client = client, apiKey = openAiKey.trim().ifEmpty { null }, model = openAiModel.trim(), parameters = parameters, baseUrl = openAiUrl.trim().trimEnd('/'))

    /** The reasoning models of OpenAI that take a lower effort. A `pro` model only thinks hard. */
    private fun thinksLess(model: String): Boolean = REASONING.any { model.startsWith(it) } && "pro" !in model

    fun save() {
        localStorage.setItem(KEY, Json.encodeToString(this))
    }

    companion object {
        /** How the names of OpenAI's reasoning models start. */
        private val REASONING = listOf("gpt-5", "o3", "o4")

        private const val KEY = "foodie.settings"

        val CLAUDE_MODELS: List<Pair<String, String>> =
            listOf(
                "claude-opus-5-5" to "Claude Opus 5.5",
                "claude-sonnet-5-5" to "Claude Sonnet 5.5",
                "claude-haiku-5-5" to "Claude Haiku 5.5",
            )

        private val json = Json { ignoreUnknownKeys = true }

        /** The stored settings, or the defaults when nothing is stored or the browser blocks its storage. */
        fun load(): Settings =
            try {
                localStorage.getItem(KEY)?.let { json.decodeFromString<Settings>(it) } ?: Settings()
            } catch (_: Throwable) {
                Settings()
            }
    }
}

@Serializable
enum class Provider(val label: String) {
    CLAUDE("Claude"),
    OPENAI("OpenAI, Gemini, Ollama or another server with the API of OpenAI"),
}
