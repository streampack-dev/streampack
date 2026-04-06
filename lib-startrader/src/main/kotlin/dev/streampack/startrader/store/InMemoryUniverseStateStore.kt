/* Joseph B. Ottinger (C)2026 */
package dev.streampack.startrader.store

import dev.streampack.startrader.model.UniverseState
import org.springframework.stereotype.Component

@Component
class InMemoryUniverseStateStore : UniverseStateStore {
    @Volatile private var state: UniverseState? = null

    override fun save(state: UniverseState) {
        this.state = state
    }

    override fun load(): UniverseState? = state
}
