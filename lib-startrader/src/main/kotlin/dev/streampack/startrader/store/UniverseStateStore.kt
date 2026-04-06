/* Joseph B. Ottinger (C)2026 */
package dev.streampack.startrader.store

import dev.streampack.startrader.model.UniverseState

interface UniverseStateStore {
    fun save(state: UniverseState)

    fun load(): UniverseState?
}
