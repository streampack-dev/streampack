# Command Parser

The Streampack command parser lives in `lib-core` under `dev.streampack.core.parser`.

It is a small grammar-and-matching layer for text commands. It is not yet the universal command
entry point for every operation in the codebase, but it is the preferred foundation for new
text-command parsing work.

## Design

The parser has four parts:

- `CommandLexer`
  Tokenizes raw input, strips an optional leading `!`, preserves quoted segments, and marks whether
  the input was explicitly triggered.
- `CommandPattern`
  Declares fixed leading literals plus typed positional arguments.
- `CommandArgType`
  Validates and converts one token into a typed value.
- `CommandPatternMatcher`
  Tries patterns in order, returns the first full match, and otherwise returns the strongest
  failure reason among literal-prefix matches.

The parser is intentionally conservative:

- no hidden backtracking DSL
- no magical optional-argument inference
- one-token-at-a-time argument validation
- plain return types that are easy to inspect in tests

## Example

This pattern set models a tiny boolean command tree:

```kotlin
val booleanArgType = ChoiceArgType(setOf("true", "false"))

fun booleanArg(name: String, helpText: String? = null) =
    CommandArgSpec(name, booleanArgType, helpText)

val matcher =
    CommandPatternMatcher(
        listOf(
            CommandPattern(
                name = "boolean_xor",
                literals = listOf("boolean", "xor"),
                args =
                    listOf(
                        booleanArg("left", "Left boolean operand"),
                        booleanArg("right", "Right boolean operand"),
                    ),
                summary = "Exclusive-or over two boolean inputs",
            ),
            CommandPattern(
                name = "boolean_and",
                literals = listOf("boolean", "and"),
                args =
                    listOf(
                        booleanArg("left", "Left boolean operand"),
                        booleanArg("right", "Right boolean operand"),
                    ),
                summary = "Logical and over two boolean inputs",
            ),
            CommandPattern(
                name = "boolean_or",
                literals = listOf("boolean", "or"),
                args =
                    listOf(
                        booleanArg("left", "Left boolean operand"),
                        booleanArg("right", "Right boolean operand"),
                    ),
                summary = "Logical or over two boolean inputs",
            ),
            CommandPattern(
                name = "boolean_not",
                literals = listOf("boolean", "not"),
                args = listOf(booleanArg("value", "Boolean input to negate")),
                summary = "Logical not over one boolean input",
            ),
            CommandPattern(
                name = "boolean_help",
                literals = listOf("boolean", "help"),
                summary = "Show boolean command help",
            ),
        )
    )
```

Matching:

```kotlin
val result = matcher.match("boolean xor true false")
```

Produces:

```kotlin
CommandMatchResult.Match(
    patternName = "boolean_xor",
    captures = mapOf("left" to "true", "right" to "false"),
    triggered = false,
    tokens = listOf("boolean", "xor", "true", "false"),
)
```

## Grammar Rendering

`CommandPatternMatcher` can render its own grammar from the declared patterns:

```kotlin
matcher.describeGrammar()
```

Returns:

```text
boolean xor <left:one-of(false|true)> <right:one-of(false|true)>
boolean and <left:one-of(false|true)> <right:one-of(false|true)>
boolean or <left:one-of(false|true)> <right:one-of(false|true)>
boolean not <value:one-of(false|true)>
boolean help
```

And richer help output:

```kotlin
matcher.describeHelp()
```

Returns:

```text
boolean xor <left:one-of(false|true)> <right:one-of(false|true)> -- Exclusive-or over two boolean inputs
  <left:one-of(false|true)>: Left boolean operand
  <right:one-of(false|true)>: Right boolean operand
boolean and <left:one-of(false|true)> <right:one-of(false|true)> -- Logical and over two boolean inputs
  <left:one-of(false|true)>: Left boolean operand
  <right:one-of(false|true)>: Right boolean operand
boolean or <left:one-of(false|true)> <right:one-of(false|true)> -- Logical or over two boolean inputs
  <left:one-of(false|true)>: Left boolean operand
  <right:one-of(false|true)>: Right boolean operand
boolean not <value:one-of(false|true)> -- Logical not over one boolean input
  <value:one-of(false|true)>: Boolean input to negate
boolean help -- Show boolean command help
```

This is the current self-documentation surface for issue `#18`: plain list-of-strings output that a
caller can publish in chat, logs, or a higher-level help system.

## Failure Semantics

If no literal prefix matches, `match(...)` returns `null`.

If a literal prefix matches but the full command fails, the matcher returns the strongest failure:

1. `InvalidArgument`
2. `MissingArguments`
3. `TooManyArguments`

This means callers can distinguish:

- "this is not my command"
- "this command is mine, but the user used it incorrectly"

without needing a second parser pass.

## Built-in Argument Types

- `StringArgType`
- `UsernameArgType`
- `HttpUrlArgType`
- `PositiveIntArgType`
- `IntRangeArgType`
- `ChoiceArgType`

Each argument type exposes a `syntaxName` used in grammar/help rendering.

## Guidance

- Prefer one pattern per command form instead of trying to encode too much optionality into one
  shape.
- Use `ChoiceArgType` for small enumerations that should appear clearly in help output.
- Use explicit summaries and argument help text when the command has a non-trivial meaning.
- Keep grammar rendering close to the parser definitions instead of hand-maintaining parallel help
  text when possible.

## Current Scope

The parser is adopted by some newer text operations, but not every command in Streampack uses it
yet. The current goal is to make the parser itself unambiguous and self-describing first, then
incrementally migrate command handlers toward it over time.
