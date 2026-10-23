package dev.deeptelar.foodie

import kotlin.test.Test
import kotlin.test.assertEquals

class LanguageTest {
    @Test
    fun `the browser's tag picks the language, and any other is English`() {
        assertEquals(Language.CATALAN, Language.of("ca-ES"))
        assertEquals(Language.SPANISH, Language.of("es-MX"))
        assertEquals(Language.GERMAN, Language.of("de-AT"))
        assertEquals(Language.ENGLISH, Language.of("pt-PT"))
    }

    @Test
    fun `the microphone keeps the browser's own variant of the language`() {
        assertEquals("es-MX", Language.SPANISH.speechTag("es-MX"))
        // A traveller who speaks Catalan on a phone set to Spanish.
        assertEquals("ca-ES", Language.CATALAN.speechTag("es-ES"))
        assertEquals("en-US", Language.ENGLISH.speechTag("en-US"))
    }
}
