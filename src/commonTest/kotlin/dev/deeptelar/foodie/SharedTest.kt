package dev.deeptelar.foodie

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SharedTest {
    @Test
    fun `nodes that ask at the same time get one read`() =
        runTest {
            var reads = 0
            val shared =
                Shared<String, String> { key ->
                    reads++
                    delay(100)
                    "page of $key"
                }

            val pages = List(3) { async { shared.get("Lisbon") } }.awaitAll()

            assertEquals(List(3) { "page of Lisbon" }, pages)
            assertEquals(1, reads)
        }

    @Test
    fun `a read that failed is asked again`() =
        runTest {
            var reads = 0
            val shared = Shared<String, String> { if (++reads == 1) throw IllegalStateException("busy") else "page" }

            assertFailsWith<IllegalStateException> { shared.get("Lisbon") }
            assertEquals("page", shared.get("Lisbon"))
        }
}
