package dev.deeptelar.foodie

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TryOutTest {
    private val tryOut = TryOut(Provider.OPENAI, "google/gemma-4-31b-it", "https://openrouter.ai/api/v1", "sk-or-v1-abc", until = 1_000.0)

    @Test
    fun `a link opens with its dish, however it is typed, and with no other`() =
        runTest {
            val code = tryOut.lock("Pastel de nata")

            assertEquals(tryOut, TryOut.unlock(code, " pastél de NATA "))
            assertNull(TryOut.unlock(code, "bifana"))
            assertNull(TryOut.unlock(code.dropLast(10), "pastel de nata"))
            // The key is not in the link as it is.
            assertEquals(false, "sk-or" in code)
        }

    @Test
    fun `a friend's key plans until the try-out ends, and the traveller's own key stays`() {
        val trying = Settings(claudeKey = "sk-ant-own").trying(tryOut)

        assertEquals(Provider.OPENAI, trying.provider)
        assertEquals("sk-or-v1-abc", trying.openAiKey)
        assertEquals(trying, trying.ended(now = 999.0))
        val ended = trying.ended(now = 1_000.0)
        assertEquals("", ended.openAiKey)
        assertEquals("sk-ant-own", ended.claudeKey)
        assertNull(ended.borrowed)
        // A key the traveller typed is theirs: no end forgets it.
        assertEquals("sk-or-own", trying.copy(openAiKey = "sk-or-own").own(Provider.OPENAI).ended(now = 2_000.0).openAiKey)
    }

    @Test
    fun `a friend's Google key comes with the link only when shared, and ends with it`() =
        runTest {
            val mine = Settings(provider = Provider.OPENAI, openAiKey = "sk-or-v1-abc", googleKey = "AIza-mine")
            assertEquals("", mine.tryOut(until = 1_000.0)!!.google)
            val shared = TryOut.unlock(mine.tryOut(until = 1_000.0, google = true)!!.lock("bifana"), "bifana")!!

            val trying = Settings(googleKey = "AIza-old").trying(shared)
            assertEquals("AIza-mine", trying.googleKey)
            assertEquals("", trying.ended(now = 1_000.0).googleKey)
            // One the friend typed over it is theirs.
            assertEquals("AIza-own", trying.copy(googleKey = "AIza-own").ownGoogle().ended(now = 1_000.0).googleKey)
            // Without one shared, the friend's own stays.
            assertEquals("AIza-old", Settings(googleKey = "AIza-old").trying(tryOut).ended(now = 1_000.0).googleKey)
        }

    @Test
    fun `a link that the proxy's friends command made opens here, and asks Google through the proxy`() =
        runTest {
            // Made by proxy/src/link.mjs with the dish "La Bomba de Pastél".
            val code = "9Q-yAw0CcvouJvW5NmqJYi41hRtRUUD-xzSuUq4NBqRN6Gjiln2FBujI9TGhAG0DCw9OOWfAM6Q5IXPLNw3A5IR-y9A5wlDhlWusj0zg8uUzSEPjdt4VFZ-xQHvSA2__c3ZrFkSkitvYNTT8eAN0yRzb7G7EM7atoJsSVW6b1IhhYox4n_BgAyTlVaEHzQfIN36nWT5prirQqpLC3-Flv-UwyVkmiYURyFlCS4cQSeUy-dCfc_J5gKu1Y35n3xAGVT132oYefVpxxGFygRk"
            val opened = TryOut.unlock(code, "bomba de pastel")!!

            assertEquals(TryOut(Provider.OPENAI, "google/gemma-4-31b-it", "https://proxy.test/v1", "fr-ana", 1_000.0, "fr-ana", "https://proxy.test"), opened)
            val trying = Settings().trying(opened)
            assertEquals("https://proxy.test", trying.placesUrl)
            assertEquals("", trying.ended(now = 1_000.0).placesUrl)
        }
}
