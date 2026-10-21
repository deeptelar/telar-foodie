package dev.deeptelar.foodie

import kotlinx.browser.document
import kotlinx.browser.window

/**
 * Counts a step of a visit with GoatCounter, whose script the page loads: a plan started, a route
 * ready, a change sent. An event is a fixed name and never carries anything of the trip. A step
 * before the script has loaded, as the sample starting, waits for the page's load event, which the
 * script holds back. Nothing is counted when the script is missing after it, as when a blocker
 * stops it, and the script counts nothing on localhost.
 */
internal fun count(event: String) {
    if (document.readyState.toString() != "complete") {
        window.addEventListener("load", { count(event) }, js("{once: true}"))
        return
    }
    val counter = window.asDynamic().goatcounter
    if (counter == null || jsTypeOf(counter.count) != "function") return
    val vars = js("{}")
    vars.path = event
    vars.event = true
    counter.count(vars)
}
