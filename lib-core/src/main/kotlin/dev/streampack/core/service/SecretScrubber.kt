/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import kotlin.math.ln
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * One shape of credential (#148).
 *
 * [kind] goes in the marker (`[REDACTED:github-token]`); [description] is how the sender is told
 * about it ("what looked like a GitHub token"), so it carries its own article. Only [group] of a
 * match is replaced, so `password=` and a URL's scheme and host stay readable around the marker.
 * [accept] sees that group's text and can decline it: the generic assignments use it to scrub only
 * values that look random. [marker] replaces the usual `[REDACTED:kind]`, for the one secret that
 * has its own (`hunter2`).
 */
class SecretPattern(
    val kind: String,
    val description: String,
    val regex: Regex,
    val group: Int = 0,
    val accept: (String) -> Boolean = { true },
    val marker: String = "${SecretScrubber.MARKER_PREFIX}$kind]",
)

/** What a scrub did: the text to keep, and the kinds removed from it, in order, once each */
data class ScrubResult(val text: String, val kinds: List<SecretPattern>) {
    val scrubbed: Boolean
        get() = kinds.isNotEmpty()
}

/**
 * Removes credentials from text before it's written to the message log (#148).
 *
 * Detection is by shape, never by asking a model: every message passes through here, so it has to
 * be cheap, and a few dozen precompiled patterns are microseconds. The shapes are the ones gitleaks
 * and GitHub's secret scanning look for: tokens with a vendor's prefix, private key blocks, JWTs,
 * URLs with a password in them, and `password=`/`token:` assignments whose value looks random (the
 * word "password" in a sentence is not a secret).
 *
 * The list is [DEFAULT_PATTERNS]; a deployment can add its own shapes under
 * `streampack.secret-scrubbing.extra-patterns` without a release.
 */
@Component
class SecretScrubber(properties: SecretScrubbingProperties = SecretScrubbingProperties()) {

    private val patterns: List<SecretPattern> =
        DEFAULT_PATTERNS +
            properties.extraPatterns.map {
                SecretPattern(
                    kind = it.kind,
                    description = it.description.ifBlank { "a secret" },
                    regex = Regex(it.pattern),
                )
            }

    fun scrub(text: String): ScrubResult = scrub(text, patterns)

    companion object {
        /** The minimum length of a generic assignment's value before it's considered at all */
        private const val ASSIGNED_MIN_LENGTH = 10

        /**
         * Shannon entropy, in bits per character, above which an assigned value looks random.
         * English words and `changeme` sit below about 3; a dozen random alphanumerics sit near
         * 3.5.
         */
        private const val ASSIGNED_MIN_ENTROPY = 3.0

        /* Token edges: a secret is a whole run of token characters, never part of a longer one */
        private const val START = "(?<![A-Za-z0-9_\\-])"
        private const val END = "(?![A-Za-z0-9_\\-])"

        private val ASSIGNMENT_KEYS =
            listOf(
                    "password",
                    "passwd",
                    "pwd",
                    "passphrase",
                    "secret",
                    "secret[_-]?key",
                    "secret[_-]?access[_-]?key",
                    "client[_-]?secret",
                    "token",
                    "access[_-]?token",
                    "auth[_-]?token",
                    "refresh[_-]?token",
                    "api[_-]?key",
                    "access[_-]?key",
                    "private[_-]?key",
                    // STRIPE_KEY, openai.key: a key with a prefix (#164). A bare `key=` is chat
                    // about maps and config as often as not, so it needs one
                    "(?<=[_.\\-])key",
                )
                .joinToString("|")

        /** Whether an assigned value looks like a generated secret rather than a word or a name */
        fun looksRandom(value: String, minLength: Int = ASSIGNED_MIN_LENGTH): Boolean {
            if (value.length < minLength) return false
            // A placeholder, not a value: ${GITHUB_TOKEN}, {{token}}, %TOKEN%, <your-key>
            if (value.first() in "\$<{%") return false
            val classes =
                listOf<(Char) -> Boolean>(
                        Char::isLowerCase,
                        Char::isUpperCase,
                        Char::isDigit,
                        { c: Char -> !c.isLetterOrDigit() },
                    )
                    .count { test -> value.any(test) }
            return classes >= 2 && entropy(value) >= ASSIGNED_MIN_ENTROPY
        }

        /** Shannon entropy of [value], in bits per character */
        fun entropy(value: String): Double {
            if (value.isEmpty()) return 0.0
            val length = value.length.toDouble()
            return value
                .groupingBy { it }
                .eachCount()
                .values
                .sumOf { count ->
                    val p = count / length
                    -p * ln(p) / ln(2.0)
                }
        }

        /**
         * A random-looking run with characters from at least three classes: what separates
         * `sk-proj-…` from "sk-" in a hyphenated phrase.
         */
        private fun mixed(value: String): Boolean =
            listOf<(Char) -> Boolean>(Char::isLowerCase, Char::isUpperCase, Char::isDigit).count {
                test ->
                value.any(test)
            } >= 3

        /*
         * Order matters where shapes overlap: the Anthropic key before the generic `sk-` one, JWTs
         * before Discord tokens (both are three dotted parts), and the specific shapes before the
         * generic assignments, so `GITHUB_TOKEN=ghp_…` is reported as a GitHub token.
         */
        val DEFAULT_PATTERNS: List<SecretPattern> =
            listOf(
                SecretPattern(
                    "private-key",
                    "a private key",
                    // A pasted key may arrive a line at a time (IRC); the header alone is caught
                    Regex(
                        "-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY(?: BLOCK)?-----[\\s\\S]*?" +
                            "(?:-----END (?:[A-Z0-9]+ )*PRIVATE KEY(?: BLOCK)?-----|\\z)"
                    ),
                ),
                SecretPattern(
                    "github-token",
                    "a GitHub token",
                    Regex(
                        "$START(?:gh[pousr]_[A-Za-z0-9]{36,255}|github_pat_[A-Za-z0-9_]{22,255})$END"
                    ),
                ),
                SecretPattern(
                    "gitlab-token",
                    "a GitLab token",
                    Regex("${START}glpat-[A-Za-z0-9_\\-]{20,}$END"),
                ),
                SecretPattern(
                    "slack-webhook",
                    "a Slack webhook URL",
                    Regex(
                        "https://hooks\\.slack\\.com/(?:services|workflows|triggers)/[A-Za-z0-9/_\\-]+"
                    ),
                ),
                SecretPattern(
                    "slack-token",
                    "a Slack token",
                    Regex(
                        "$START(?:xox[abposr]-[A-Za-z0-9\\-]{10,}|xapp-\\d-[A-Za-z0-9\\-]{10,})$END"
                    ),
                ),
                SecretPattern(
                    "anthropic-key",
                    "an Anthropic API key",
                    Regex("${START}sk-ant-[A-Za-z0-9_\\-]{20,}$END"),
                ),
                SecretPattern(
                    "openai-key",
                    "an OpenAI API key",
                    Regex("${START}sk-(?:proj-|svcacct-|admin-)?[A-Za-z0-9_\\-]{20,}$END"),
                    accept = ::mixed,
                ),
                SecretPattern(
                    "aws-access-key",
                    "an AWS access key",
                    Regex("$START(?:AKIA|ASIA)[A-Z0-9]{16}$END"),
                ),
                SecretPattern(
                    "google-api-key",
                    "a Google API key",
                    Regex("${START}AIza[A-Za-z0-9_\\-]{35}$END"),
                ),
                SecretPattern(
                    "stripe-key",
                    "a Stripe key",
                    Regex("$START(?:sk|rk)_(?:live|test)_[A-Za-z0-9]{20,}$END"),
                ),
                SecretPattern(
                    "npm-token",
                    "an npm token",
                    Regex("${START}npm_[A-Za-z0-9]{36}$END"),
                ),
                SecretPattern(
                    "discord-webhook",
                    "a Discord webhook URL",
                    Regex(
                        "https://(?:(?:ptb|canary)\\.)?discord(?:app)?\\.com/api/webhooks/" +
                            "\\d+/[A-Za-z0-9_\\-]+"
                    ),
                ),
                SecretPattern(
                    "jwt",
                    "a JSON web token",
                    Regex(
                        "${START}eyJ[A-Za-z0-9_\\-]{10,}\\.eyJ[A-Za-z0-9_\\-]{10,}" +
                            "\\.[A-Za-z0-9_\\-]{10,}$END"
                    ),
                ),
                SecretPattern(
                    "discord-token",
                    "a Discord bot token",
                    // The first part is the bot's id in base64, which starts M, N or O
                    Regex(
                        "$START[MNO][A-Za-z0-9_\\-]{23,27}\\.[A-Za-z0-9_\\-]{6,7}" +
                            "\\.[A-Za-z0-9_\\-]{27,40}$END"
                    ),
                ),
                SecretPattern(
                    "url-credentials",
                    "a password in a URL",
                    // Only the user:password part goes; the scheme and host stay readable
                    Regex("\\b[A-Za-z][A-Za-z0-9+.\\-]*://([^\\s:/@]+:[^\\s/@]+)@"),
                    group = 1,
                ),
                SecretPattern(
                    "bearer-token",
                    "a bearer token",
                    Regex("(?i)\\bbearer\\s+([A-Za-z0-9._~+/\\-]+=*)"),
                    group = 1,
                    accept = { looksRandom(it, minLength = 20) },
                ),
                SecretPattern(
                    "hunter2",
                    "a password",
                    // All I see is ******* (bash.org #244321): always a password, wherever it is
                    Regex("(?i)(?<![A-Za-z0-9])hunter2(?![A-Za-z0-9])"),
                    marker = HUNTER2_MARKER,
                ),
                SecretPattern(
                    "secret",
                    "a password or secret",
                    // DB_PASSWORD=…, "api_key": "…", token: … — the key may carry a prefix
                    Regex(
                        "(?i)(?<![A-Za-z0-9])(?:[A-Za-z0-9]+[_.\\-])*(?:$ASSIGNMENT_KEYS)" +
                            "[\"']?\\s*[:=]\\s*[\"']?([^\\s\"'`,;&<>]+)"
                    ),
                    group = 1,
                    accept = { looksRandom(it) },
                ),
            )

        fun scrub(text: String, patterns: List<SecretPattern> = DEFAULT_PATTERNS): ScrubResult {
            var current = text
            val found = mutableListOf<SecretPattern>()
            for (pattern in patterns) {
                current =
                    pattern.regex.replace(current) { match ->
                        val secret = match.groups[pattern.group] ?: return@replace match.value
                        if (
                            secret.value.startsWith(MARKER_PREFIX) || !pattern.accept(secret.value)
                        ) {
                            return@replace match.value
                        }
                        if (pattern !in found) found += pattern
                        val start = secret.range.first - match.range.first
                        val end = secret.range.last + 1 - match.range.first
                        match.value.replaceRange(start, end, pattern.marker)
                    }
            }
            return ScrubResult(current, found)
        }

        const val MARKER_PREFIX = "[REDACTED:"

        private const val HUNTER2_MARKER = "*******"
    }
}

/**
 * Deployment additions to the secret scrubber's shapes, and how often a sender is told.
 *
 * ```yaml
 * streampack:
 *   secret-scrubbing:
 *     extra-patterns:
 *       - kind: internal-token
 *         description: an internal service token
 *         pattern: "\\bitk_[A-Za-z0-9]{32}\\b"
 * ```
 */
@ConfigurationProperties(prefix = "streampack.secret-scrubbing")
data class SecretScrubbingProperties(
    val extraPatterns: List<ExtraPattern> = emptyList(),
    /** The least time between two notices to the same person */
    val noticeInterval: java.time.Duration = java.time.Duration.ofMinutes(5),
) {
    data class ExtraPattern(val kind: String, val pattern: String, val description: String = "")
}
