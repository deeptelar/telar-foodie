package dev.deeptelar.foodie

import kotlinx.browser.document
import org.w3c.dom.HTMLElement

fun main() {
    App(document.getElementById("app") as HTMLElement).open()
}
