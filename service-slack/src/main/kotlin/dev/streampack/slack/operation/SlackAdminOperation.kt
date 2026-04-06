/* Joseph B. Ottinger (C)2026 */
package dev.streampack.slack.operation

import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Role
import dev.streampack.core.service.TypedOperation
import dev.streampack.slack.service.SlackService
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Dispatches "slack" admin subcommands. Requires SUPER_ADMIN role. */
@Component
class SlackAdminOperation(private val slackService: SlackService) :
    TypedOperation<String>(String::class) {

    override fun canHandle(payload: String, message: Message<*>): Boolean {
        val trimmed = payload.trim()
        return trimmed == "slack" || trimmed.startsWith("slack ")
    }

    override fun handle(payload: String, message: Message<*>): OperationOutcome {
        requireRole(message, Role.SUPER_ADMIN)?.let {
            return it
        }

        val args = payload.trim().removePrefix("slack").trim()
        if (args.isBlank()) return OperationResult.Success(helpText())

        val tokens = args.split("\\s+".toRegex())
        val subcommand = tokens[0]

        return when (subcommand) {
            "connect" -> handleConnect(tokens.drop(1))
            "disconnect" -> handleDisconnect(tokens.drop(1))
            "remove" -> handleRemove(tokens.drop(1))
            "autoconnect" -> handleAutoconnect(tokens.drop(1))
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
                    "Unknown Slack subcommand '$subcommand'. Use 'slack' for available commands."
                )
        }
    }

    private fun handleConnect(args: List<String>): OperationResult {
        if (args.isEmpty() || args.size == 2) {
            return OperationResult.Error("Usage: slack connect <name> [<bot-token> <app-token>]")
        }
        val name = args[0]
        val botToken = args.getOrNull(1)
        val appToken = args.getOrNull(2)
        val result = slackService.connect(name, botToken, appToken)
        return toResult(result)
    }

    private fun handleDisconnect(args: List<String>): OperationResult {
        if (args.isEmpty()) return OperationResult.Error("Usage: slack disconnect <name>")
        return toResult(slackService.disconnect(args[0]))
    }

    private fun handleRemove(args: List<String>): OperationResult {
        if (args.isEmpty()) return OperationResult.Error("Usage: slack remove <name>")
        return toResult(slackService.remove(args[0]))
    }

    private fun handleAutoconnect(args: List<String>): OperationResult {
        if (args.size < 2) {
            return OperationResult.Error("Usage: slack autoconnect <name> <true|false>")
        }
        val enabled =
            args[1].toBooleanStrictOrNull()
                ?: return OperationResult.Error("Invalid boolean: '${args[1]}'")
        return toResult(slackService.setAutoconnect(args[0], enabled))
    }

    private fun handleJoin(args: List<String>): OperationResult {
        if (args.size < 2) {
            return OperationResult.Error("Usage: slack join <workspace> <#channel>")
        }
        return toResult(slackService.join(args[0], args[1]))
    }

    private fun handleLeave(args: List<String>): OperationResult {
        if (args.size < 2) {
            return OperationResult.Error("Usage: slack leave <workspace> <#channel>")
        }
        return toResult(slackService.leave(args[0], args[1]))
    }

    private fun handleAutojoin(args: List<String>): OperationResult {
        if (args.size < 3) {
            return OperationResult.Error(
                "Usage: slack autojoin <workspace> <#channel> <true|false>"
            )
        }
        val enabled =
            args[2].toBooleanStrictOrNull()
                ?: return OperationResult.Error("Invalid boolean: '${args[2]}'")
        return toResult(slackService.setAutojoin(args[0], args[1], enabled))
    }

    private fun handleMute(args: List<String>): OperationResult {
        if (args.size < 2) {
            return OperationResult.Error("Usage: slack mute <workspace> <#channel>")
        }
        return toResult(slackService.mute(args[0], args[1]))
    }

    private fun handleUnmute(args: List<String>): OperationResult {
        if (args.size < 2) {
            return OperationResult.Error("Usage: slack unmute <workspace> <#channel>")
        }
        return toResult(slackService.unmute(args[0], args[1]))
    }

    private fun handleAutomute(args: List<String>): OperationResult {
        if (args.size < 3) {
            return OperationResult.Error(
                "Usage: slack automute <workspace> <#channel> <true|false>"
            )
        }
        val enabled =
            args[2].toBooleanStrictOrNull()
                ?: return OperationResult.Error("Invalid boolean: '${args[2]}'")
        return toResult(slackService.setAutomute(args[0], args[1], enabled))
    }

    private fun handleVisible(args: List<String>): OperationResult {
        if (args.size < 3) {
            return OperationResult.Error("Usage: slack visible <workspace> <#channel> <true|false>")
        }
        val enabled =
            args[2].toBooleanStrictOrNull()
                ?: return OperationResult.Error("Invalid boolean: '${args[2]}'")
        return toResult(slackService.setVisible(args[0], args[1], enabled))
    }

    private fun handleLogged(args: List<String>): OperationResult {
        if (args.size < 3) {
            return OperationResult.Error("Usage: slack logged <workspace> <#channel> <true|false>")
        }
        val enabled =
            args[2].toBooleanStrictOrNull()
                ?: return OperationResult.Error("Invalid boolean: '${args[2]}'")
        return toResult(slackService.setLogged(args[0], args[1], enabled))
    }

    private fun handleSignal(args: List<String>): OperationResult {
        if (args.isEmpty()) {
            return OperationResult.Error(
                "Usage: slack signal <name> [character]  (omit character to reset)"
            )
        }
        val signalChar = args.getOrNull(1)
        return toResult(slackService.setSignal(args[0], signalChar))
    }

    private fun handleStatus(args: List<String>): OperationResult {
        val workspaceName = args.firstOrNull()
        return OperationResult.Success(slackService.status(workspaceName))
    }

    /** Converts a service result string into an OperationResult */
    private fun toResult(result: String): OperationResult =
        if (result.startsWith("Error:")) OperationResult.Error(result.removePrefix("Error: "))
        else OperationResult.Success(result)

    private fun helpText(): String =
        """
        |Slack Admin Commands:
        |  slack connect <name> [<bot-token> <app-token>]
        |  slack disconnect <name>
        |  slack remove <name>
        |  slack autoconnect <name> <true|false>
        |  slack join <workspace> <#channel>
        |  slack leave <workspace> <#channel>
        |  slack autojoin <workspace> <#channel> <true|false>
        |  slack mute <workspace> <#channel>
        |  slack unmute <workspace> <#channel>
        |  slack automute <workspace> <#channel> <true|false>
        |  slack visible <workspace> <#channel> <true|false>
        |  slack logged <workspace> <#channel> <true|false>
        |  slack signal <name> [character]
        |  slack status [workspace]
        """
            .trimMargin()
}
