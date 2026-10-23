package dev.deeptelar.foodie

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkedTest {
    @Test
    fun `bold and italic become marks, and the stars go`() {
        assertEquals(
            listOf(Run("Lunch", bold = true), Run(": "), Run("Suquet de peix", italic = true), Run(" at "), Run("El Barquero", bold = true), Run(".")),
            marked("**Lunch**: *Suquet de peix* at **El Barquero**."),
        )
    }

    @Test
    fun `a heading is bold, and an item of a list starts with a bullet`() {
        assertEquals(
            listOf(Run("Day 1", bold = true), Run("\n• "), Run("Breakfast", bold = true), Run(": pan con tomate\n  • at Sa Bodegueta")),
            marked("## Day 1  \n- **Breakfast**: pan con tomate\n  * at Sa Bodegueta"),
        )
    }

    @Test
    fun `words without marks, and marks that are not closed, stay as they are, and code loses its backticks`() {
        assertEquals(listOf(Run("Two * three * four, and **half")), marked("Two * three * four, and **half"))
        assertEquals(listOf(Run("Ask for set_meal.")), marked("Ask for `set_meal`."))
        assertEquals(listOf(Run("Enjoy Palma!")), marked("  Enjoy Palma!  \n"))
    }
}
