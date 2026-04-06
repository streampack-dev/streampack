/* Joseph B. Ottinger (C)2026 */
package dev.streampack.config

import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.ImportRuntimeHints

/**
 * Placeholder for native-image tuning. As reflection, proxy, and resource issues are discovered
 * during GraalVM adoption, register them here so the app-native profile remains the single source
 * of truth for AOT hints.
 */
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(NativeRuntimeHints::class)
class NativeRuntimeHintsConfiguration

class NativeRuntimeHints : RuntimeHintsRegistrar {

    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        // Intentionally empty for now. Add reflection/resource/proxy hints here as native build
        // failures surface.
    }
}
