/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.model

/** An issue or change request as seen from a forge API or webhook payload. */
data class ForgeItem(val number: Int, val title: String, val url: String)

/** A release as seen from a forge API or webhook payload. */
data class ForgeReleaseInfo(val tag: String, val name: String?, val url: String)
