package dev.deeptelar.foodie

import kotlinx.serialization.Serializable

/**
 * A language the traveller can use the planner in: the page is written in it, the model writes
 * what the traveller reads in it, and the microphone listens for it.
 *
 * @property code the language tag of the page, and the start of the browser's tags for it.
 * @property speech the tag the microphone listens for, when the browser's own tag is another language.
 * @property english the name a model is told.
 * @property own the name the traveller picks.
 */
@Serializable
enum class Language(
    val code: String,
    val speech: String,
    val english: String,
    val own: String,
) {
    ENGLISH("en", "en-GB", "English", "English"),
    SPANISH("es", "es-ES", "Spanish", "Español"),
    CATALAN("ca", "ca-ES", "Catalan", "Català"),
    ;

    /** The tag to listen for: the browser's own when it is this language, such as `es-MX`, and [speech] otherwise. */
    fun speechTag(browser: String): String = if (browser.lowercase().startsWith(code)) browser else speech

    companion object {
        /** The language of a browser's tag, such as `ca-ES`, or English for any other. */
        fun of(tag: String): Language = entries.firstOrNull { tag.lowercase().startsWith(it.code) } ?: ENGLISH
    }
}
