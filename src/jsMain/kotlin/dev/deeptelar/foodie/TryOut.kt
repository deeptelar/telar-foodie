package dev.deeptelar.foodie

import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.khronos.webgl.set
import kotlin.js.Promise

/**
 * A model to try the planner with until [until], as a friend shares it: what a try-out link
 * carries, locked with a secret dish. The link has it after the `#`, which a browser sends to no
 * server, not even to the one of the page.
 *
 * The lock keeps a forwarded link from working without the dish, and no more: a dish is easy to
 * guess for whoever tries many. What stops a key is the provider's limit on it, and deleting it.
 *
 * @property url the address of a server with the API of OpenAI; empty for Claude.
 * @property until when the page forgets the key, in milliseconds since 1970.
 */
@Serializable
data class TryOut(
    val provider: Provider,
    val model: String,
    val url: String,
    val key: String,
    val until: Double,
) {
    /** Locks this with [dish]: the salt, the IV and the encrypted JSON, in Base64 for an address. */
    suspend fun lock(dish: String): String {
        val salt = random(SALT_BYTES)
        val iv = random(IV_BYTES)
        val sealed = Uint8Array((subtle.encrypt(aes(iv), key(dish, salt), bytes(Json.encodeToString(this))) as Promise<ArrayBuffer>).await())
        return base64(salt + iv + sealed.toByteArray())
    }

    companion object {
        /** What starts the part of an address after the `#` that is a try-out. */
        const val PREFIX = "try="

        private const val SALT_BYTES = 16
        private const val IV_BYTES = 12

        /** Makes trying many dishes slow; a phone opens a link in about a second. */
        private const val ROUNDS = 600_000

        private val json = Json { ignoreUnknownKeys = true }

        /** The try-out [code] locks, or `null` when [dish] is not its dish or the code was cut short. */
        suspend fun unlock(code: String, dish: String): TryOut? =
            try {
                val all = unbase64(code)
                val salt = all.copyOfRange(0, SALT_BYTES)
                val iv = all.copyOfRange(SALT_BYTES, SALT_BYTES + IV_BYTES)
                val sealed = all.copyOfRange(SALT_BYTES + IV_BYTES, all.size).toUint8Array()
                val opened = Uint8Array((subtle.decrypt(aes(iv), key(dish, salt), sealed) as Promise<ArrayBuffer>).await())
                json.decodeFromString<TryOut>(opened.toByteArray().decodeToString())
            } catch (_: Throwable) {
                null
            }

        /** The dish as the traveller may type it: "Pastel de Nata", "pastel de nata " and "pastél de nata" are one dish. */
        fun secret(dish: String): String = dish.simplified()

        private val subtle: dynamic get() = window.asDynamic().crypto.subtle

        private suspend fun key(dish: String, salt: ByteArray): dynamic {
            val material = (subtle.importKey("raw", bytes(secret(dish)), "PBKDF2", false, arrayOf("deriveKey")) as Promise<dynamic>).await()
            val derivation: dynamic = js("({ name: 'PBKDF2', hash: 'SHA-256' })")
            derivation.salt = salt.toUint8Array()
            derivation.iterations = ROUNDS
            val aes: dynamic = js("({ name: 'AES-GCM', length: 256 })")
            return (subtle.deriveKey(derivation, material, aes, false, arrayOf("encrypt", "decrypt")) as Promise<dynamic>).await()
        }

        private fun aes(iv: ByteArray): dynamic {
            val parameters: dynamic = js("({ name: 'AES-GCM' })")
            parameters.iv = iv.toUint8Array()
            return parameters
        }

        private fun random(count: Int): ByteArray = Uint8Array(count).also { window.asDynamic().crypto.getRandomValues(it) }.toByteArray()

        private fun bytes(text: String): Uint8Array = text.encodeToByteArray().toUint8Array()

        private fun base64(bytes: ByteArray): String =
            window.btoa(bytes.joinToString("") { (it.toInt() and 0xff).toChar().toString() }).replace('+', '-').replace('/', '_').trimEnd('=')

        private fun unbase64(text: String): ByteArray {
            val padded = text.trim().replace('-', '+').replace('_', '/').let { it + "=".repeat((4 - it.length % 4) % 4) }
            return window.atob(padded).map { it.code.toByte() }.toByteArray()
        }

        private fun ByteArray.toUint8Array(): Uint8Array = Uint8Array(size).also { array -> forEachIndexed { i, byte -> array[i] = byte } }

        private fun Uint8Array.toByteArray(): ByteArray = ByteArray(length) { this[it] }
    }
}
