/* Joseph B. Ottinger (C)2026 */
package dev.streampack.irc.operation

import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.RedactionRule
import dev.streampack.core.model.Role
import dev.streampack.core.service.TypedOperation
import dev.streampack.irc.service.IrcService
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Dispatches "irc" admin subcommands. Requires SUPER_ADMIN role. */
@Component
class IrcAdminOperation(private val ircService: IrcService) :
    TypedOperation<String>(String::class) {

    override val redactionRules = listOf(RedactionRule("irc connect", setOf(5, 6)))

    override fun canHandle(payload: String, message: Message<*>): Boolean {
        val trimmed = payload.trim()
        return trimmed == "irc" || trimmed.startsWith("irc ")
    }

    override fun handle(payload: String, message: Message<*>): OperationOutcome {
        requireRole(message, Role.SUPER_ADMIN)?.let {
            return it
        }

        val args = payload.trim().removePrefix("irc").trim()
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
            "allow-ops" -> handleAllowOps(tokens.drop(1))
            "signal" -> handleSignal(tokens.drop(1))
            "status" -> handleStatus(tokens.drop(1))
            else ->
                OperationResult.Error(
                    "Unknown IRC subcommand '$subcommand'. Use 'irc' for available commands."
                )
        }
    }

    private fun handleConnect(args: List<String>): OperationResult {
        if (args.isEmpty() || args.size == 2) {
            return OperationResult.Error(
                "Usage: irc connect <name> [<host> <nick> [saslAccount] [saslPassword]]"
            )
        }
        val name = args[0]
        val host = args.getOrNull(1)
        val nick = args.getOrNull(2)
        val saslAccount = args.getOrNull(3)
        val saslPassword = args.getOrNull(4)
        val result = ircService.connect(name, host, nick, saslAccount, saslPassword)
        return if (result.startsWith("Error:"))
            OperationResult.Error(result.removePrefix("Error: "))
        else OperationResult.Success(result)
    }

    private fun handleDisconnect(args: List<String>): OperationResult {
        if (args.isEmpty()) return OperationResult.Error("Usage: irc disconnect <name>")
        val result = ircService.disconnect(args[0])
        return if (result.startsWith("Error:"))
            OperationResult.Error(result.removePrefix("Error: "))
        else OperationResult.Success(result)
    }

    private fun handleRemove(args: List<String>): OperationResult {
        if (args.isEmpty()) return OperationResult.Error("Usage: irc remove <name>")
        val result = ircService.remove(args[0])
        return if (result.startsWith("Error:"))
            OperationResult.Error(result.removePrefix("Error: "))
        else OperationResult.Success(result)
    }

    private fun handleAutoconnect(args: List<String>): OperationResult {
        if (args.size < 2) {
            return OperationResult.Error("Usage: irc autoconnect <name> <true|false>")
        }
        val enabled =
            args[1].toBooleanStrictOrNull()
                ?: return OperationResult.Error("Invalid boolean: '${args[1]}'")
        val result = ircService.setAutoconnect(args[0], enabled)
        return if (result.startsWith("Error:"))
            OperationResult.Error(result.removePrefix("Error: "))
        else OperationResult.Success(result)
    }

    private fun handleJoin(args: List<String>): OperationResult {
        if (args.size < 2) return OperationResult.Error("Usage: irc join <network> <#channel>")
        val result = ircService.join(args[0], args[1])
        return if (result.startsWith("Error:"))
            OperationResult.Error(result.removePrefix("Error: "))
        else OperationResult.Success(result)
    }

    private fun handleLeave(args: List<String>): OperationResult {
        if (args.size < 2) return OperationResult.Error("Usage: irc leave <network> <#channel>")
        val result = ircService.leave(args[0], args[1])
        return if (result.startsWith("Error:"))
            OperationResult.Error(result.removePrefix("Error: "))
        else OperationResult.Success(result)
    }

    private fun handleAutojoin(args: List<String>): OperationResult {
        if (args.size < 3) {
            return OperationResult.Error("Usage: irc autojoin <network> <#channel> <true|false>")
        }
        val enabled =
            args[2].toBooleanStrictOrNull()
                ?: return OperationResult.Error("Invalid boolean: '${args[2]}'")
        val result = ircService.setAutojoin(args[0], args[1], enabled)
        return if (result.startsWith("Error:"))
            OperationResult.Error(result.removePrefix("Error: "))
        else OperationResult.Success(result)
    }

    private fun handleMute(args: List<String>): OperationResult {
        if (args.size < 2) return OperationResult.Error("Usage: irc mute <network> <#channel>")
        val result = ircService.mute(args[0], args[1])
        return if (result.startsWith("Error:"))
            OperationResult.Error(result.removePrefix("Error: "))
        else OperationResult.Success(result)
    }

    private fun handleUnmute(args: List<String>): OperationResult {
        if (args.size < 2) return OperationResult.Error("Usage: irc unmute <network> <#channel>")
        val result = ircService.unmute(args[0], args[1])
        return if (result.startsWith("Error:"))
            OperationResult.Error(result.removePrefix("Error: "))
        else OperationResult.Success(result)
    }

    private fun handleAutomute(args: List<String>): OperationResult {
        if (args.size < 3) {
            return OperationResult.Error("Usage: irc automute <network> <#channel> <true|false>")
        }
        val enabled =
            args[2].toBooleanStrictOrNull()
                ?: return OperationResult.Error("Invalid boolean: '${args[2]}'")
        val result = ircService.setAutomute(args[0], args[1], enabled)
        return if (result.startsWith("Error:"))
            OperationResult.Error(result.removePrefix("Error: "))
        else OperationResult.Success(result)
    }

    private fun handleVisible(args: List<String>): OperationResult {
        if (args.size < 3) {
            return OperationResult.Error("Usage: irc visible <network> <#channel> <true|false>")
        }
        val enabled =
            args[2].toBooleanStrictOrNull()
                ?: return OperationResult.Error("Invalid boolean: '${args[2]}'")
        val result = ircService.setVisible(args[0], args[1], enabled)
        return if (result.startsWith("Error:"))
            OperationResult.Error(result.removePrefix("Error: "))
        else OperationResult.Success(result)
    }

    private fun handleLogged(args: List<String>): OperationResult {
        if (args.size < 3) {
            return OperationResult.Error("Usage: irc logged <network> <#channel> <true|false>")
        }
        val enabled =
            args[2].toBooleanStrictOrNull()
                ?: return OperationResult.Error("Invalid boolean: '${args[2]}'")
        val result = ircService.setLogged(args[0], args[1], enabled)
        return if (result.startsWith("Error:"))
            OperationResult.Error(result.removePrefix("Error: "))
        else OperationResult.Success(result)
    }

    private fun handleAllowOps(args: List<String>): OperationResult {
        if (args.size < 3) {
            return OperationResult.Error("Usage: irc allow-ops <network> <#channel> <true|false>")
        }
        val enabled =
            args[2].toBooleanStrictOrNull()
                ?: return OperationResult.Error("Invalid boolean: '${args[2]}'")
        val result = ircService.setAllowOps(args[0], args[1], enabled)
        return if (result.startsWith("Error:"))
            OperationResult.Error(result.removePrefix("Error: "))
        else OperationResult.Success(result)
    }

    private fun handleSignal(args: List<String>): OperationResult {
        if (args.isEmpty()) {
            return OperationResult.Error(
                "Usage: irc signal <name> [character]  (omit character to reset)"
            )
        }
        val signalChar = args.getOrNull(1)
        val result = ircService.setSignal(args[0], signalChar)
        return if (result.startsWith("Error:"))
            OperationResult.Error(result.removePrefix("Error: "))
        else OperationResult.Success(result)
    }

    private fun handleStatus(args: List<String>): OperationResult {
        val networkName = args.firstOrNull()
        return OperationResult.Success(ircService.status(networkName))
    }

    private fun helpText(): String =
        """
        |IRC Admin Commands:
        |  irc connect <name> [<host> <nick> [saslAccount] [saslPassword]]
        |  irc disconnect <name>
        |  irc remove <name>
        |  irc autoconnect <name> <true|false>
        |  irc join <network> <#channel>
        |  irc leave <network> <#channel>
        |  irc autojoin <network> <#channel> <true|false>
        |  irc mute <network> <#channel>
        |  irc unmute <network> <#channel>
        |  irc automute <network> <#channel> <true|false>
        |  irc visible <network> <#channel> <true|false>
        |  irc logged <network> <#channel> <true|false>
        |  irc allow-ops <network> <#channel> <true|false>
        |  irc signal <name> [character]
        |  irc status [network]
        """
            .trimMargin()
}
