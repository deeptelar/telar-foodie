package dev.deeptelar.foodie

/*
 * Models write Markdown even when nobody asked for it, and the page shows the agent's words as
 * text. The few marks they use most are read here, so that the page can show them as bold and
 * italic; everything else stays as it was written, and none of it becomes HTML.
 */

/** A piece of the agent's words, and how it is shown. */
data class Run(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
)

/**
 * The agent's [text] as the page shows it: `**bold**` and `*italic*` become marks, and code in
 * backticks plain words. A heading loses its `#` and is bold, and an item of a list starts with a
 * bullet. A mark that is not closed is shown as it is.
 */
fun marked(text: String): List<Run> {
    val runs = mutableListOf<Run>()
    text.trim().lines().forEachIndexed { index, raw ->
        if (index > 0) runs += Run("\n")
        val line = raw.trimEnd()
        val heading = HEADING.matchEntire(line)
        val item = ITEM.matchEntire(line)
        when {
            heading != null -> runs += inline(heading.groupValues[1]).map { it.copy(bold = true) }
            item != null -> {
                runs += Run(item.groupValues[1] + "• ")
                runs += inline(item.groupValues[2])
            }
            else -> runs += inline(line)
        }
    }
    return runs.fold(mutableListOf()) { joined, run ->
        val last = joined.lastOrNull()
        if (last != null && last.bold == run.bold && last.italic == run.italic) joined[joined.size - 1] = last.copy(text = last.text + run.text) else joined += run
        joined
    }
}

/** The marks within one line. */
private fun inline(line: String): List<Run> {
    val runs = mutableListOf<Run>()
    var at = 0
    for (mark in MARK.findAll(line)) {
        if (mark.range.first > at) runs += Run(line.substring(at, mark.range.first))
        val (bold, italic, code) = mark.destructured
        runs +=
            when {
                bold.isNotEmpty() -> inline(bold).map { it.copy(bold = true) }
                italic.isNotEmpty() -> inline(italic).map { it.copy(italic = true) }
                else -> listOf(Run(code))
            }
        at = mark.range.last + 1
    }
    if (at < line.length) runs += Run(line.substring(at))
    return runs
}

private val HEADING = Regex("""\s{0,3}#{1,6}\s+(.*?)\s*#*""")

private val ITEM = Regex("""(\s*)[-*+]\s+(.*)""")

/** Bold, italic or code. An italic does not start or end with a space, so "2 * 3 * 4" stays as it is. */
private val MARK = Regex("""\*\*([^*]+?)\*\*|\*([^*\s](?:[^*]*[^*\s])?)\*|`([^`]+)`""")
