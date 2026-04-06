/* Joseph B. Ottinger (C)2026 */
package dev.streampack.poetry.model

/** Request to analyze a poem's meter, rhyme scheme, and form */
data class PoemAnalysisRequest(val text: String)
