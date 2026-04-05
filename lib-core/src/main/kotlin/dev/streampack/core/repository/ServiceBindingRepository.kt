/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.repository

import com.enigmastation.streampack.core.entity.ServiceBinding
import com.enigmastation.streampack.core.model.Protocol
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

/** Resolves protocol-specific external identities to internal users */
interface ServiceBindingRepository : JpaRepository<ServiceBinding, UUID> {
    @Query(
        "SELECT sb FROM ServiceBinding sb WHERE sb.protocol = :protocol AND sb.serviceId = :serviceId AND sb.externalIdentifier = :externalIdentifier"
    )
    fun resolve(protocol: Protocol, serviceId: String, externalIdentifier: String): ServiceBinding?

    @Query(
        "SELECT sb FROM ServiceBinding sb " +
            "WHERE sb.protocol = :protocol " +
            "AND LOWER(sb.serviceId) = LOWER(:serviceId) " +
            "AND LOWER(sb.externalIdentifier) = LOWER(:externalIdentifier)"
    )
    fun resolveIgnoreCase(
        protocol: Protocol,
        serviceId: String,
        externalIdentifier: String,
    ): ServiceBinding?
}
