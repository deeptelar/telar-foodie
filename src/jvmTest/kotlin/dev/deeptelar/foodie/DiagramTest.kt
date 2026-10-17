package dev.deeptelar.foodie

import dev.deeptelar.foodie.sources.GuidePage
import dev.deeptelar.foodie.sources.Sources
import dev.deeptelar.telar.test.ScriptedChatModel
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** The README shows the graph as Telar draws it, so a change of the graph that the README misses fails here. */
class DiagramTest {
    @Test
    fun `the README draws the graph as it is`() {
        val sources = Sources(geocoder = { null }, guide = { GuidePage() }, map = { _, _, _, _ -> emptyList() }, reviews = { _, _, _ -> emptyList() })
        val diagram = planner(ScriptedChatModel { }, sources).topology.toMermaid(interruptBefore = setOf(Nodes.REVIEW))

        val readme = File("README.md").readText()
        assertTrue("```mermaid\n${diagram.trimEnd()}\n```" in readme, "Put this in the README:\n$diagram")
    }
}
