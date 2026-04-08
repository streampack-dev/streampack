/* Joseph B. Ottinger (C)2026 */
package dev.streampack.test

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.streams.asSequence
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MockMvcTransactionPolicyTests {
    @Test
    fun `mock mvc tests do not use test-managed transactions`() {
        val root = repositoryRoot()
        val offenders =
            Files.walk(root).use { paths ->
                paths
                    .asSequence()
                    .filter { Files.isRegularFile(it) }
                    .filter { it.toString().endsWith(".kt") }
                    .filter { root.relativize(it).toString().startsWith("service-") }
                    .filter { path ->
                        val text = path.readText()
                        text.contains("@AutoConfigureMockMvc") && hasPropagatingTransaction(text)
                    }
                    .map { root.relativize(it).toString() }
                    .sorted()
                    .toList()
            }

        assertTrue(
            offenders.isEmpty(),
            "MockMvc tests must not use test-managed transactions. Use ResetDatabaseBeforeEach instead:\n" +
                offenders.joinToString("\n"),
        )
    }

    private fun repositoryRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first {
                Files.exists(it.resolve("pom.xml")) &&
                    Files.exists(it.resolve("lib-testsupport")) &&
                    Files.exists(it.resolve("service-blog"))
            }

    private fun hasPropagatingTransaction(text: String): Boolean =
        transactionalAnnotation.findAll(text).any { match ->
            val arguments = match.groups["arguments"]?.value
            arguments == null || !arguments.contains("Propagation.NOT_SUPPORTED")
        }

    private companion object {
        val transactionalAnnotation = Regex("@Transactional(?:\\((?<arguments>[^)]*)\\))?")
    }
}
