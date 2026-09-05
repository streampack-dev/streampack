/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mattermost.operation

import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Role
import dev.streampack.core.service.TypedOperation
import dev.streampack.mattermost.service.MattermostService
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Dispatches `mattermost` admin subcommands. Requires `SUPER_ADMIN` role. */
@Component
class MattermostAdminOperation(private val mattermostService: MattermostService) :
    TypedOperation<String>(String::class) {

    override fun canHandle(payload: String, message: Message<*>): Boolean {
        val trimmed = payload.trim()
        return trimmed == "mattermost" || trimmed.startsWith("mattermost ")
    }

    override fun handle(payload: String, message: Message<*>): OperationOutcome {
        requireRole(message, Role.SUPER_ADMIN)?.let {
            return it
        }

        val args = payload.trim().removePrefix("mattermost").trim()
        if (args.isBlank()) return OperationResult.Success(helpText())

        val tokens = args.split("\\s+".toRegex())
        val subcommand = tokens[0]

        return when (subcommand) {
            "connect" -> handleConnect(tokens.drop(1))
            "disconnect" -> handleDisconnect(tokens.drop(1))
            "remove" -> handleRemove(tokens.drop(1))
            "autoconnect" -> handleAutoconnect(tokens.drop(1))
            "channels" -> handleChannels(tokens.drop(1))
            "join" -> handleJoin(tokens.drop(1))
            "leave" -> handleLeave(tokens.drop(1))
            "autojoin" -> handleAutojoin(tokens.drop(1))
            "mute" -> handleMute(tokens.drop(1))
            "unmute" -> handleUnmute(tokens.drop(1))
            "automute" -> handleAutomute(tokens.drop(1))
            "visible" -> handleVisible(tokens.drop(1))
            "logged" -> handleLogged(tokens.drop(1))
            "signal" -> handleSignal(tokens.drop(1))
            "status" -> handleStatus(tokens.drop(1))
            else ->
                OperationResult.Error(
                    "Unknown Mattermost subcommand '$subcommand'. Use 'mattermost' for available commands."
                )
        }
    }

    private fun handleConnect(args: List<String>): OperationResult {
        if (args.isEmpty() || args.size == 2) {
            return OperationResult.Error("Usage: mattermost connect <name> [<base-url> <token>]")
        }
        return toResult(mattermostService.connect(args[0], args.getOrNull(1), args.getOrNull(2)))
    }

    private fun handleDisconnect(args: List<String>): OperationResult {
        if (args.isEmpty()) return OperationResult.Error("Usage: mattermost disconnect <name>")
        return toResult(mattermostService.disconnect(args[0]))
    }

    private fun handleRemove(args: List<String>): OperationResult {
        if (args.isEmpty()) return OperationResult.Error("Usage: mattermost remove <name>")
        return toResult(mattermostService.remove(args[0]))
    }

    private fun handleAutoconnect(args: List<String>): OperationResult {
        if (args.size < 2) {
            return OperationResult.Error("Usage: mattermost autoconnect <name> <true|false>")
        }
        val enabled =
            args[1].toBooleanStrictOrNull()
                ?: return OperationResult.Error("Invalid boolean: '${args[1]}'")
        return toResult(mattermostService.setAutoconnect(args[0], enabled))
    }

    private fun handleChannels(args: List<String>): OperationResult {
        if (args.isEmpty())
            return OperationResult.Error("Usage: mattermost channels <server> [term]")
        return OperationResult.Success(mattermostService.listChannels(args[0], args.getOrNull(1)))
    }

    private fun handleJoin(args: List<String>): OperationResult {
        if (args.size < 2) {
            return OperationResult.Error("Usage: mattermost join <server> <channel-id-or-name>")
        }
        return toResult(mattermostService.join(args[0], args[1]))
    }

    private fun handleLeave(args: List<String>): OperationResult {
        if (args.size < 2) {
            return OperationResult.Error("Usage: mattermost leave <server> <channel-id-or-name>")
        }
        return toResult(mattermostService.leave(args[0], args[1]))
    }

    private fun handleAutojoin(args: List<String>): OperationResult {
        if (args.size < 3) {
            return OperationResult.Error(
                "Usage: mattermost autojoin <server> <channel-id-or-name> <true|false>"
            )
        }
        val enabled =
            args[2].toBooleanStrictOrNull()
                ?: return OperationResult.Error("Invalid boolean: '${args[2]}'")
        return toResult(mattermostService.setAutojoin(args[0], args[1], enabled))
    }

    private fun handleMute(args: List<String>): OperationResult {
        if (args.size < 2) {
            return OperationResult.Error("Usage: mattermost mute <server> <channel-id-or-name>")
        }
        return toResult(mattermostService.mute(args[0], args[1]))
    }

    private fun handleUnmute(args: List<String>): OperationResult {
        if (args.size < 2) {
            return OperationResult.Error("Usage: mattermost unmute <server> <channel-id-or-name>")
        }
        return toResult(mattermostService.unmute(args[0], args[1]))
    }

    private fun handleAutomute(args: List<String>): OperationResult {
        if (args.size < 3) {
            return OperationResult.Error(
                "Usage: mattermost automute <server> <channel-id-or-name> <true|false>"
            )
        }
        val enabled =
            args[2].toBooleanStrictOrNull()
                ?: return OperationResult.Error("Invalid boolean: '${args[2]}'")
        return toResult(mattermostService.setAutomute(args[0], args[1], enabled))
    }

    private fun handleVisible(args: List<String>): OperationResult {
        if (args.size < 3) {
            return OperationResult.Error(
                "Usage: mattermost visible <server> <channel-id-or-name> <true|false>"
            )
        }
        val enabled =
            args[2].toBooleanStrictOrNull()
                ?: return OperationResult.Error("Invalid boolean: '${args[2]}'")
        return toResult(mattermostService.setVisible(args[0], args[1], enabled))
    }

    private fun handleLogged(args: List<String>): OperationResult {
        if (args.size < 3) {
            return OperationResult.Error(
                "Usage: mattermost logged <server> <channel-id-or-name> <true|false>"
            )
        }
        val enabled =
            args[2].toBooleanStrictOrNull()
                ?: return OperationResult.Error("Invalid boolean: '${args[2]}'")
        return toResult(mattermostService.setLogged(args[0], args[1], enabled))
    }

    private fun handleSignal(args: List<String>): OperationResult {
        if (args.isEmpty()) {
            return OperationResult.Error(
                "Usage: mattermost signal <name> [character]  (omit character to reset)"
            )
        }
        return toResult(mattermostService.setSignal(args[0], args.getOrNull(1)))
    }

    private fun handleStatus(args: List<String>): OperationResult =
        OperationResult.Success(mattermostService.status(args.firstOrNull()))

    private fun toResult(result: String): OperationResult =
        if (result.startsWith("Error:")) OperationResult.Error(result.removePrefix("Error: "))
        else OperationResult.Success(result)

    private fun helpText(): String =
        """
        |Mattermost Admin Commands:
        |  mattermost connect <name> [<base-url> <token>]
        |  mattermost disconnect <name>
        |  mattermost remove <name>
        |  mattermost autoconnect <name> <true|false>
        |  mattermost channels <server> [term]
        |  mattermost join <server> <channel-id-or-name>
        |  mattermost leave <server> <channel-id-or-name>
        |  mattermost autojoin <server> <channel-id-or-name> <true|false>
        |  mattermost mute <server> <channel-id-or-name>
        |  mattermost unmute <server> <channel-id-or-name>
        |  mattermost automute <server> <channel-id-or-name> <true|false>
        |  mattermost visible <server> <channel-id-or-name> <true|false>
        |  mattermost logged <server> <channel-id-or-name> <true|false>
        |  mattermost signal <name> [character]
        |  mattermost status [server]
        """
            .trimMargin()
}
