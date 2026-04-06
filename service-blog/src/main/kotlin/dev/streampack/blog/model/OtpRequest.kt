/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

/** Request to send a one-time passcode to the given email address */
data class OtpRequest(val email: String)
