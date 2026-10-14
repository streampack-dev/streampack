/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import dev.streampack.core.model.SecretRequirement

/** The environment variables the server needs set before its next start; names, never values. */
data class SecretRequirementsResponse(val required: List<SecretRequirement>)
