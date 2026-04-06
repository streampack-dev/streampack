/* Joseph B. Ottinger (C)2026 */
package dev.streampack.markov.model

/** Request to generate a Markov chain sentence in the style of a given user */
data class MarkovRequest(val username: String)
