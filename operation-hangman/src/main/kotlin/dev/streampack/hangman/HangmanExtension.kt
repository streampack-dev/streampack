/* Joseph B. Ottinger (C)2026 */
package dev.streampack.hangman

import dev.streampack.core.service.Extension
import org.springframework.stereotype.Component

/** Hangman, as the server knows it among its extensions (#294). */
@Component
class HangmanExtension : Extension {
    override val name = NAME

    companion object {
        /** What hangman's data is kept under in the server's store. */
        const val NAME = "hangman"
    }
}
