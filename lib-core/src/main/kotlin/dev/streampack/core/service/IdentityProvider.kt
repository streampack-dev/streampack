/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.model.Protocol

/**
 * Optional contract for protocol adapters that provenance user identities. Implementations validate
 * and normalize (serviceId, externalIdentifier) pairs for their protocol, enabling admin commands
 * like "link user" to verify that identities are well-formed before creating service bindings.
 *
 * Protocol adapters that do not provenance users (e.g., RSS) simply do not implement this
 * interface.
 */
interface IdentityProvider {
    val protocol: Protocol

    /** Validate a (serviceId, externalIdentifier) pair for this protocol */
    fun resolveIdentity(serviceId: String, externalIdentifier: String): IdentityResolution

    /** Describe how this protocol identifies users so admins can discover valid field values */
    fun describeIdentity(): IdentityDescription
}
