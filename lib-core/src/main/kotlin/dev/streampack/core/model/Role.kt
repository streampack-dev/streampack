/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.model

/** Hierarchical user roles: GUEST < USER < ADMIN < SUPER_ADMIN */
enum class Role {
    GUEST,
    USER,
    ADMIN,
    SUPER_ADMIN,
}
