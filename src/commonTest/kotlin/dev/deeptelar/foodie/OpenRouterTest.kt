package dev.deeptelar.foodie

import kotlin.test.Test
import kotlin.test.assertEquals

class OpenRouterTest {
    @Test
    fun `the list keeps the models that call tools and write text, with their price a million tokens`() {
        val json =
            """
            {"data": [
              {"id": "google/gemma-4-31b-it", "name": "Google: Gemma 4 31B", "architecture": {"output_modalities": ["text"]},
               "pricing": {"prompt": "0.0000001", "completion": "0.0000004"}, "supported_parameters": ["tools", "temperature"]},
              {"id": "anthropic/claude-haiku-5.5:batch", "name": "Anthropic: Claude Haiku 5.5 (batch)", "architecture": {"output_modalities": ["text"]},
               "pricing": {"prompt": "0.00000005", "completion": "0.00000025"}, "supported_parameters": ["tools"]},
              {"id": "google/nano-banana", "name": "Google: Nano Banana", "architecture": {"output_modalities": ["image", "text"]}, "supported_parameters": ["tools"]},
              {"id": "some/old-model", "name": "Some: Old Model", "architecture": {"output_modalities": ["text"]}, "supported_parameters": ["temperature"]},
              {"id": "anthropic/claude-haiku-5.5", "name": "Anthropic: Claude Haiku 5.5", "architecture": {"output_modalities": ["text"]}, "supported_parameters": ["tools"]}
            ]}
            """.trimIndent()

        val models = openRouterModels(json)

        assertEquals(listOf("anthropic/claude-haiku-5.5", "google/gemma-4-31b-it"), models.map { it.id })
        assertEquals(null, models[0].price)
        assertEquals(0.1, models[1].price!!.input, 1e-9)
        assertEquals(0.4, models[1].price!!.output, 1e-9)
    }
}
