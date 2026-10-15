/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.model.SecretRequirement

/**
 * Something that stores credentials as [dev.streampack.core.model.SecretRef]s and can say which of
 * their environment variables the next start needs: each service's secret startup guard.
 */
interface SecretRequirementSource {
    fun secretRequirements(): List<SecretRequirement>
}
