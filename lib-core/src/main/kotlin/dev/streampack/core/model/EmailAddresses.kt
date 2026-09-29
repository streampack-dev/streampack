/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.model

/**
 * How email addresses are stored and compared. Sign-in looks accounts up by the normalized address,
 * so every address a user record holds must be normalized too, or its owner can't be found.
 */
object EmailAddresses {
    private val plausible = Regex("""[^@\s]+@[^@\s]+""")

    fun normalize(address: String): String = address.trim().lowercase()

    /** Something shaped like an address; whether it receives mail is the sign-in code's job. */
    fun isPlausible(address: String): Boolean = plausible.matches(normalize(address))

    const val IN_USE = "Email address is already in use"
}
