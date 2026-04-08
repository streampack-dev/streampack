/* Joseph B. Ottinger (C)2026 */
package dev.streampack.test

import org.springframework.test.context.jdbc.Sql
import org.springframework.test.context.jdbc.SqlConfig
import org.springframework.test.context.jdbc.SqlGroup

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SqlGroup(
    Sql(
        scripts = ["classpath:/dev/streampack/test/truncate-public-tables.sql"],
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD,
        config = SqlConfig(separator = "@@"),
    ),
    Sql(
        scripts = ["classpath:/dev/streampack/test/truncate-public-tables.sql"],
        executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD,
        config = SqlConfig(separator = "@@"),
    ),
)
annotation class ResetDatabaseBeforeEach
