# Teach the Infobot a Factoid

A factoid is a short fact that the infobot keeps and says on request. In this tutorial, you create a
factoid, add a web address and tags to it, and make a second name for it.

You need a running infobot that you can talk to: on IRC, Discord, Slack, Mattermost or the console.
[Run the Infobot on IRC](run-infobot-on-irc.md) shows one way.

## How to Read the Examples

The examples show what you type and what the bot answers:

```text
<alice> what you type
<bot> what the bot answers
```

The examples show commands without a prefix. On a chat service, address the bot first. With the
default signal character, type `!spring`, not `spring`. An answer the bot picks at random shows as
`one of:`, then the possible answers. A word with `!` in front of it in an answer, such as
`!kotlin`, names another factoid.

The tests run every example on this page, so the answers are the bot's real answers.

## 1. Create a Factoid

Type a name, `=`, and the text. Then type the name to read the factoid.

<!-- factoid-example: create-and-read -->
```text
<alice> spring=A Java framework
<bot> ok, alice: updated spring.
<alice> spring
<bot> spring is A Java framework.
```

The name is the factoid's *selector*. The bot puts "spring is" in front of the text and adds a
period.

## 2. Add a Web Address

A factoid has *attributes*: its text, web addresses, tags and more. Type the selector, a period,
the attribute's name, `=`, and the value.

<!-- factoid-example: urls -->
```text
<alice> spring=A Java framework
<bot> ok, alice: updated spring.
<alice> spring.url=https://spring.io
<bot> ok, alice: updated spring.
<alice> spring.url
<bot> URL: https://spring.io
<alice> spring
<bot> spring is A Java framework. URL: https://spring.io
```

The bot puts the web addresses after the text when it says the factoid.

## 3. Tag Factoids

Tags group factoids. Separate the tags with commas. `tag` finds every factoid with a tag.

<!-- factoid-example: tags -->
```text
<alice> intellij=A Java IDE
<bot> ok, alice: updated intellij.
<alice> intellij.tags=ide, jetbrains
<bot> ok, alice: updated intellij.
<alice> eclipse=Another Java IDE
<bot> ok, alice: updated eclipse.
<alice> eclipse.tags=ide, eclipse-foundation
<bot> ok, alice: updated eclipse.
<alice> spring=A Java framework
<bot> ok, alice: updated spring.
<alice> spring.tags=framework
<bot> ok, alice: updated spring.
<alice> tag ide
<bot> Factoids tagged 'ide': !eclipse and !intellij
<alice> tag zzzznonexistent
<bot> No factoids found with tag 'zzzznonexistent'.
```

## 4. Give a Factoid a Second Name

The `see` attribute makes an alias: a selector that says another factoid. When you forget the
alias, the factoid it points to stays.

<!-- factoid-example: tutorial-alias -->
```text
<alice> spring=A Java framework
<bot> ok, alice: updated spring.
<alice> springframework.see=spring
<bot> ok, alice: updated springframework.
<alice> springframework
<bot> spring is A Java framework.
<alice> forget springframework
<bot> ok, forgot springframework.
<alice> springframework
<bot> (no reply)
```

## Next Steps

- [Manage Factoids](../how-to/infobot/manage-factoids.md): change, forget, lock, aliases and
  search.
- [Write Factoid Text](../how-to/infobot/write-factoid-text.md): answers without "is", arguments
  and random choices.
- [Factoids](../reference/factoids.md): every command and attribute.
- [How Factoids Work](../explanation/factoids.md): why the bot answers as it does.
