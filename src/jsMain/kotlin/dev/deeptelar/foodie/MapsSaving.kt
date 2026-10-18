package dev.deeptelar.foodie

import kotlinx.browser.localStorage
import kotlinx.browser.window
import org.w3c.files.File
import org.w3c.files.FilePropertyBag
import kotlin.js.Promise

/*
 * Taking the route's places to Google Maps from a phone, where Google My Maps is hard to use: the
 * page opens each place in the app to be saved there, and remembers which ones were opened; or it
 * hands the file for My Maps to the phone's share sheet, to be imported later on a computer.
 */

/**
 * Which places of a route the traveller opened in Google Maps to save them, in this browser. Only
 * the last route is kept: [route] names it by its places. A browser that blocks its storage
 * remembers nothing, and the list still works.
 */
internal object OpenedInMaps {
    private const val KEY = "foodie.openedInMaps"

    fun load(route: String): Set<String> =
        try {
            val stored = localStorage.getItem(KEY).orEmpty()
            if (stored.substringBefore('\n') == route) stored.substringAfter('\n', "").split(',').filter { it.isNotEmpty() }.toSet() else emptySet()
        } catch (_: Throwable) {
            emptySet()
        }

    fun save(route: String, opened: Set<String>) {
        try {
            localStorage.setItem(KEY, route + "\n" + opened.joinToString(","))
        } catch (_: Throwable) {
            // The browser blocks its storage: the ticks hold until the page is drawn again.
        }
    }
}

/** Whether this browser can hand a file to the share sheet of the device, as a phone's can. */
internal fun canShareFiles(): Boolean =
    try {
        val navigator = window.navigator.asDynamic()
        navigator.canShare != undefined && navigator.canShare(shareData(File(arrayOf("x"), "x.csv", FilePropertyBag(type = "text/csv")), "", "")) as Boolean
    } catch (_: Throwable) {
        false
    }

/**
 * Hands the file [name] with [text] to the share sheet, with a [title] and a [message]. Returns
 * whether the browser takes a file of that kind: Chrome takes text and CSV files, and no KML.
 */
internal fun shareFile(name: String, text: String, type: String, title: String, message: String): Boolean {
    val navigator = window.navigator.asDynamic()
    val data = shareData(File(arrayOf(text), name, FilePropertyBag(type = type)), title, message)
    val takes =
        try {
            navigator.canShare(data) as Boolean
        } catch (_: Throwable) {
            false
        }
    // A traveller who closes the share sheet rejects the promise: nothing more to do.
    if (takes) (navigator.share(data) as Promise<Any?>).catch { }
    return takes
}

private fun shareData(file: File, title: String, message: String): dynamic {
    val data: dynamic = js("({})")
    data.files = arrayOf(file)
    if (title.isNotEmpty()) data.title = title
    if (message.isNotEmpty()) data.text = message
    return data
}
