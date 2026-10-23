package dev.deeptelar.foodie

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import org.w3c.files.File

/*
 * The page is built with the DOM directly. Everything that comes from a source or from a model
 * goes into the page as text, never as HTML, so nothing a source wrote can run as a script.
 */

/** Adds an element to this one and returns it. */
fun Element.add(tag: String, classes: String = "", text: String? = null, build: HTMLElement.() -> Unit = {}): HTMLElement {
    val element = document.createElement(tag) as HTMLElement
    if (classes.isNotEmpty()) element.className = classes
    if (text != null) element.textContent = text
    appendChild(element)
    element.build()
    return element
}

/** Adds the words of a model to this element with the marks [marked] reads in them, each piece as text. */
fun Element.words(text: String) {
    for (run in marked(text)) {
        when {
            run.bold && run.italic -> add("strong").add("em", text = run.text)
            run.bold -> add("strong", text = run.text)
            run.italic -> add("em", text = run.text)
            else -> appendChild(document.createTextNode(run.text))
        }
    }
}

fun Element.button(label: String, classes: String = "", onClick: () -> Unit): HTMLButtonElement =
    (add("button", classes, label) as HTMLButtonElement).apply {
        type = "button"
        onclick = { onClick() }
    }

/** A link that opens in a new tab. An address that is not a web address gets no link: the text is shown as it is. */
fun Element.link(label: String, address: String, classes: String = ""): HTMLElement {
    if (!address.startsWith("https://") && !address.startsWith("http://")) return add("span", classes, label)
    return (add("a", classes, label) as HTMLAnchorElement).apply {
        href = address
        target = "_blank"
        rel = "noopener noreferrer"
    }
}

/** A link that calls [number] on a phone. A number without digits gets no link. */
fun Element.phoneLink(number: String, classes: String = ""): HTMLElement {
    val dial = number.filter { it.isDigit() || it == '+' }
    if (dial.none { it.isDigit() }) return add("span", classes, number)
    return (add("a", classes, "📞 $number") as HTMLAnchorElement).apply { href = "tel:$dial" }
}

/** A labelled input. [onChange] gets every change of its text. */
fun Element.field(label: String, value: String, type: String = "text", placeholder: String = "", onChange: (String) -> Unit): HTMLInputElement {
    val wrapper = add("label", "field")
    wrapper.add("span", "field-label", label)
    return (wrapper.add("input") as HTMLInputElement).apply {
        this.type = type
        this.value = value
        this.placeholder = placeholder
        autocomplete = "off"
        oninput = { onChange(this.value) }
    }
}

fun Element.clear() {
    while (firstChild != null) removeChild(firstChild!!)
}

/** Gives the browser [text] to save as a file called [name]. */
fun download(name: String, text: String, type: String = "application/json") {
    val address = URL.createObjectURL(Blob(arrayOf(text), BlobPropertyBag(type = type)))
    val anchor = document.createElement("a") as HTMLAnchorElement
    anchor.href = address
    anchor.download = name
    document.body?.appendChild(anchor)
    anchor.click()
    anchor.remove()
    // The browser has read the address once the click is handled.
    window.setTimeout({ URL.revokeObjectURL(address) }, 1000)
}

/** Asks the traveller for a file of one of the [types], and gives it to [onPicked]. Nothing happens when they pick none. */
fun pickFile(types: String, onPicked: (File) -> Unit) {
    val input = document.createElement("input") as HTMLInputElement
    input.type = "file"
    input.accept = types
    input.onchange = { input.files?.item(0)?.let(onPicked) }
    input.click()
}
