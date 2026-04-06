/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mail.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Controls activation of the mail egress service */
@ConfigurationProperties(prefix = "streampack.mail")
data class MailServiceProperties(val enabled: Boolean = false)
