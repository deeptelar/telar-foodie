package dev.deeptelar.foodie

import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement

/*
 * Speaking instead of typing, with the Web Speech API of the browser. The browser turns the voice
 * into text; most browsers do that on their maker's servers (Chrome on Google's), so the recording
 * goes there, and the page only ever gets the text. Firefox has no speech recognition: there the
 * buttons are not shown.
 */

/** One recording. [onText] gets everything heard so far: what is settled, and what may still change. */
private class Dictation(
    /** The button that started it. */
    val owner: Any,
    private val language: Language,
    private val texts: Texts,
    private val onText: (settled: String, guess: String) -> Unit,
    private val onEnd: (problem: String?) -> Unit,
) {
    private val recognition: dynamic = js("new (window.SpeechRecognition || window.webkitSpeechRecognition)()")
    private var problem: String? = null

    fun start() {
        recognition.lang = language.speechTag(window.navigator.language)
        recognition.interimResults = true
        // Chrome on Android repeats what it heard when it listens on through pauses: there it stops at the first pause.
        recognition.continuous = "Android" !in window.navigator.userAgent
        recognition.onresult = { event: dynamic ->
            var settled = ""
            var guess = ""
            val results = event.results
            for (index in 0 until (results.length as Int)) {
                val result = results[index]
                val text = result[0].transcript as String
                if (result.isFinal as Boolean) settled += text else guess += text
            }
            onText(settled.trim(), guess.trim())
        }
        recognition.onerror = { event: dynamic ->
            problem =
                when (event.error as String) {
                    "not-allowed", "service-not-allowed" -> texts.micNotAllowed
                    "network" -> texts.micNoNetwork
                    "audio-capture" -> texts.micMissing
                    // Silence, or a stop by the traveller, is no problem.
                    else -> null
                }
        }
        recognition.onend = { onEnd(problem) }
        recognition.start()
    }

    fun stop() {
        recognition.stop()
    }

    companion object {
        val supported: Boolean get() = js("('SpeechRecognition' in window) || ('webkitSpeechRecognition' in window)") as Boolean

        /** The recording that runs now. There is one at most: a second button stops the first. */
        var current: Dictation? = null

        fun stopAll() {
            current?.stop()
            current = null
        }
    }
}

/** Whether this browser can turn speech into text. */
val speechSupported: Boolean get() = Dictation.supported

/** Stops the recording that runs, before the page is drawn again. */
fun stopSpeaking() = Dictation.stopAll()

/**
 * A microphone button for a text field, that listens for [language]. What the traveller says is
 * added to what [read] gives, and [write] gets the text while it is heard. Nothing is added when
 * the browser cannot hear.
 */
fun Element.speakButton(language: Language, texts: Texts, read: () -> String, write: (String) -> Unit) {
    if (!speechSupported) return
    val row = this
    var note: HTMLElement? = null
    lateinit var button: HTMLButtonElement
    button =
        button(texts.speak, "ghost speak") {
            // A second tap on the same button stops it; a tap on another one stops that one and starts here.
            val mine = Dictation.current?.owner == button
            Dictation.stopAll()
            if (mine) return@button
            note?.remove()
            note = null
            val before = read().trimEnd()
            fun joined(text: String) = listOf(before, text.trim()).filter { it.isNotEmpty() }.joinToString(" ")
            var settled = ""
            val dictation =
                Dictation(
                    owner = button,
                    language = language,
                    texts = texts,
                    onText = { heard, guess ->
                        settled = heard
                        write(joined("$heard $guess"))
                    },
                    onEnd = { problem ->
                        // A guess that never settled is dropped.
                        write(joined(settled))
                        button.textContent = texts.speak
                        button.classList.remove("listening")
                        if (Dictation.current?.owner == button) Dictation.current = null
                        if (problem != null) note = row.add("p", "hint small", problem)
                    },
                )
            Dictation.current = dictation
            button.textContent = texts.stopListening
            button.classList.add("listening")
            try {
                dictation.start()
            } catch (e: Throwable) {
                button.textContent = texts.speak
                button.classList.remove("listening")
                Dictation.current = null
                note = row.add("p", "hint small", texts.micFailed(e.message.orEmpty()))
            }
        }
}
