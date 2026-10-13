package dev.deeptelar.foodie

import kotlinx.browser.document
import org.w3c.dom.Element
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

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
