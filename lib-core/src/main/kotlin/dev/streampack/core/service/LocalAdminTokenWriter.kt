/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.config.StreampackProperties
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.repository.UserRepository
import jakarta.annotation.PreDestroy
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Writes a short-lived super-admin token to a file, for a setup tool on the same box to open the
 * web console with (#209). A fresh server has no way to sign in yet (codes go by mail or a direct
 * message, and neither is set up), and anyone who can read this file can already read `.env` and
 * the database, so it gives them nothing new.
 *
 * Off unless `STREAMPACK_LOCAL_ADMIN_TOKEN_FILE` names a file. The file is replaced when the server
 * is ready and every half lifetime after, so it always holds a token with time left; it's readable
 * by the server's own user alone, and removed on shutdown. The token itself is never logged.
 */
@Component
class LocalAdminTokenWriter(
    private val properties: StreampackProperties.LocalAdminTokenProperties,
    private val jwtService: JwtService,
    private val superAdmin: () -> UserPrincipal?,
) {
    @Autowired
    constructor(
        properties: StreampackProperties,
        jwtService: JwtService,
        userRepository: UserRepository,
    ) : this(
        properties.localAdminToken,
        jwtService,
        { userRepository.findActiveByRole(Role.SUPER_ADMIN).firstOrNull()?.toUserPrincipal() },
    )

    private val logger = LoggerFactory.getLogger(LocalAdminTokenWriter::class.java)
    private val file: Path? = properties.file.takeIf { it.isNotBlank() }?.let { Path.of(it) }
    private var renewal: ScheduledExecutorService? = null

    /** Half the lifetime, and never more often than once a minute. */
    val renewEvery: Duration = maxOf(properties.lifetime.dividedBy(2), Duration.ofMinutes(1))

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (file == null) return
        write()
        renewal =
            Executors.newSingleThreadScheduledExecutor { task ->
                    Thread(task, "local-admin-token").apply { isDaemon = true }
                }
                .also {
                    it.scheduleAtFixedRate(
                        { write() },
                        renewEvery.toSeconds(),
                        renewEvery.toSeconds(),
                        TimeUnit.SECONDS,
                    )
                }
    }

    /** Writes a new token; false when there's no file named, no super-admin, or it couldn't. */
    fun write(): Boolean {
        val target = file ?: return false
        val principal = superAdmin()
        if (principal == null) {
            Files.deleteIfExists(target)
            logger.warn(
                "No super-admin account, so no local admin token was written. Set ADMIN_EMAIL " +
                    "and restart: the account is created at startup when none exists.",
            )
            return false
        }
        return try {
            val token = jwtService.generateToken(principal, properties.lifetime)
            val temporary = privateFile(target.resolveSibling(".${target.fileName}.new"))
            Files.writeString(temporary, token + "\n")
            Files.move(
                temporary,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            logger.info(
                "Local admin token for '{}' written to {}, valid for {}",
                principal.username,
                target,
                properties.lifetime,
            )
            true
        } catch (e: Exception) {
            logger.error("The local admin token couldn't be written to {}: {}", target, e.message)
            false
        }
    }

    @PreDestroy
    fun remove() {
        renewal?.shutdownNow()
        file?.let { Files.deleteIfExists(it) }
    }

    /** A new, empty file at [path] that only its owner can read and write. */
    private fun privateFile(path: Path): Path {
        Files.deleteIfExists(path)
        if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
            return Files.createFile(
                path,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
            )
        }
        return Files.createFile(path).also {
            val file = it.toFile()
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
        }
    }
}
