# Write Factoid Text

This guide shows the special forms in a factoid's text: answers without "is", arguments, random
choices and references to other factoids. The examples show commands without the bot's prefix,
and the tests run each one. A random answer shows as `one of:`, then the possible answers.

## Answer Without "is"

Start the text with `<reply>`. The bot then says the text as it is, without "selector is" in front.

<!-- factoid-example: reply -->
```text
<alice> greet=<reply>Hello there!
<bot> ok, alice: updated greet.
<alice> greet
<bot> Hello there!
```

## Take an Argument

Put `$1` in the text. The words after the selector replace `$1`. Without those words, the bot
answers with an error.

<!-- factoid-example: argument -->
```text
<alice> ask=Please ask $1 for help
<bot> ok, alice: updated ask.
<alice> ask joe
<bot> ask is Please ask joe for help.
<alice> ask
<bot> ask: Not enough arguments to replace placeholder.
```

A factoid without `$1` takes no words after its selector. See
[Factoids](../../reference/factoids.md#arguments).

## Pick a Random Answer

Put the choices in parentheses, separated by `|`. The bot picks one choice each time. Parentheses
without a `|` are plain text.

<!-- factoid-example: choice -->
```text
<alice> coin=<reply>(heads|tails)
<bot> ok, alice: updated coin.
<alice> coin
<bot> one of: heads. | tails.
```

Choices and `$1` work together:

<!-- factoid-example: choice-with-argument -->
```text
<alice> farewell=<reply>(hello|goodbye) $1
<bot> ok, alice: updated farewell.
<alice> farewell world
<bot> one of: hello world. | goodbye world.
```

Choice groups can go inside other choice groups:

<!-- factoid-example: choices-nested -->
```text
<alice> 8ball=<reply>(Yes|No|Maybe|Ask me( tomorrow| again| again tomorrow)|It's (unclear|unknowable)).
<bot> ok, alice: updated 8ball.
<alice> 8ball
<bot> one of: Yes. | No. | Maybe. | Ask me tomorrow. | Ask me again. | Ask me again tomorrow. | It's unclear. | It's unknowable.
```

An empty choice, as in `(something|)`, says nothing for that part.

## Say Another Factoid's Text

Inside a choice, `~selector` says the text of another factoid. If that factoid does not exist, the
choice says nothing.

<!-- factoid-example: reference -->
```text
<alice> target=<reply>resolved
<bot> ok, alice: updated target.
<alice> ref=<reply>(~target|literal)
<bot> ok, alice: updated ref.
<alice> ref
<bot> one of: resolved. | literal.
```

## See the Text as It Was Set

`<selector>.literal` shows the text without changes: no `<reply>` handling, no `$1` and no choices.
Use it to see the text before you change it.

<!-- factoid-example: literal -->
```text
<alice> ask=Please ask $1 for help
<bot> ok, alice: updated ask.
<alice> ask.literal
<bot> Please ask $1 for help
```

## Related

- [Manage Factoids](manage-factoids.md)
- [Factoids](../../reference/factoids.md)
