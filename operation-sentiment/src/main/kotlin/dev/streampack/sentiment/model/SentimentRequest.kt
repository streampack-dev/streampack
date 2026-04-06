/* Joseph B. Ottinger (C)2026 */
package dev.streampack.sentiment.model

/** Request to analyze sentiment for a target provenance URI */
data class SentimentRequest(val targetUri: String)
