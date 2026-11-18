# Manage Factoids

This guide shows how to change, forget, lock, alias and find factoids in chat. The examples show
commands without the bot's prefix, and the tests run each one. For the full list of commands, see
[Factoids](../../reference/factoids.md).

## Set or Change a Factoid

Use `=` or the word `is`. Setting a factoid again replaces its text.

<!-- factoid-example: set-with-is -->
```text
<alice> kotlin is A modern JVM language
<bot> ok, alice: updated kotlin.
<alice> kotlin
<bot> kotlin is A modern JVM language.
```

## Set an Attribute When the Shorthand Is Awkward

Use `factoid set <selector>.<attribute> <value>`. This form has no `=` or `is` to confuse, so it
works for any selector and any value.

<!-- factoid-example: factoid-set -->
```text
<alice> factoid set spring boot.text An opinionated framework
<bot> ok, alice: updated spring boot.
<alice> spring boot
<bot> spring boot is An opinionated framework.
```

## Remove One Attribute

Use `factoid unset <selector>.<attribute>`. The rest of the factoid stays.

<!-- factoid-example: factoid-unset -->
```text
<alice> project=sample
<bot> ok, alice: updated project.
<alice> project.tags=foo,bar
<bot> ok, alice: updated project.
<alice> factoid unset project.tags
<bot> ok, unset tags on project.
<alice> project.tags
<bot> (no reply)
```

## Forget a Factoid

`forget <selector>` deletes the whole factoid. `<selector>.forget` does the same.

<!-- factoid-example: forget -->
```text
<alice> ephemeral=temporary
<bot> ok, alice: updated ephemeral.
<alice> forget ephemeral
<bot> ok, forgot ephemeral.
<alice> ephemeral
<bot> (no reply)
```

To forget one attribute only, name it:

<!-- factoid-example: forget-one-attribute -->
```text
<alice> multi=some text
<bot> ok, alice: updated multi.
<alice> multi.see=other
<bot> ok, alice: updated multi.
<alice> forget multi.see
<bot> ok, forgot see on multi.
<alice> multi
<bot> multi is some text.
```

## Make an Alias

Set the `see` attribute to the selector of another factoid. The alias then says that factoid.

<!-- factoid-example: see-alias -->
```text
<alice> jdk=the Java Development Kit
<bot> ok, alice: updated jdk.
<alice> java development kit.see=jdk
<bot> ok, alice: updated java development kit.
<alice> java development kit
<bot> jdk is the Java Development Kit.
```

An alias that still has its own text says the other factoid. Forget `see` to bring the factoid's
own text back:

<!-- factoid-example: see-removed -->
```text
<alice> a=goodbye
<bot> ok, alice: updated a.
<alice> b=hello
<bot> ok, alice: updated b.
<alice> a.see=b
<bot> ok, alice: updated a.
<alice> a
<bot> b is hello.
<alice> forget a.see
<bot> ok, forgot see on a.
<alice> a
<bot> a is goodbye.
```

## Link Related Factoids

The `seealso` attribute names related factoids, separated by commas. A name that is a factoid
becomes a link: on IRC it shows with the signal character, such as `!kotlin`. Other names stay as
plain text.

<!-- factoid-example: see-also -->
```text
<alice> java=A programming language
<bot> ok, alice: updated java.
<alice> kotlin=A modern JVM language
<bot> ok, alice: updated kotlin.
<alice> java.seealso=kotlin,python
<bot> ok, alice: updated java.
<alice> java.seealso
<bot> See also: !kotlin and python
<alice> java
<bot> java is A programming language. See also: !kotlin and python
```

`seealso` differs from `see`. A factoid with `seealso` still says its own text. A factoid with `see`
says another factoid.

## Lock a Factoid

An admin can lock a factoid. Then nobody can change it or forget it until an admin unlocks it.

<!-- factoid-example: lock -->
```text
<alice> keepsafe=Not to be lost
<bot> ok, alice: updated keepsafe.
<admin> keepsafe.lock
<bot> ok, keepsafe is now locked.
<alice> keepsafe=Something else
<bot> Factoid 'keepsafe' is locked.
<alice> forget keepsafe
<bot> Factoid 'keepsafe' is locked.
<alice> keepsafe
<bot> keepsafe is Not to be lost.
```

<!-- factoid-example: unlock -->
```text
<alice> keepsafe=Not to be lost
<bot> ok, alice: updated keepsafe.
<admin> keepsafe.lock
<bot> ok, keepsafe is now locked.
<alice> keepsafe.unlock
<bot> Lock/unlock requires admin privileges.
<admin> keepsafe.unlock
<bot> ok, keepsafe is now unlocked.
```

## Find Factoids

`search <words>` looks in factoid text. `tag <tag>` finds factoids by tag. See
[Teach the Infobot a Factoid](../../tutorials/teach-the-infobot-a-factoid.md#3-tag-factoids).

<!-- factoid-example: search -->
```text
<alice> spring=A Java framework
<bot> ok, alice: updated spring.
<alice> spring boot=Opinionated Spring
<bot> ok, alice: updated spring boot.
<alice> search spring
<bot> Search for 'spring' matched: !spring and !spring boot
<alice> search zzzznonexistent
<bot> No factoids found searching for 'zzzznonexistent'.
```

## Related

- [Write Factoid Text](write-factoid-text.md)
- [Use the Infobot](use-the-infobot.md)
