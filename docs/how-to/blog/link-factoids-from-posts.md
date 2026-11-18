# Link Factoids from Posts

This guide shows how to link a post to factoids, so a reader can see what a term means.

## Link a Term

Put the selector in double square brackets:

```markdown
The service runs on [[spring boot]].
```

To show other words as the link, put the words first, then `|`, then the selector:

```markdown
The service runs on [[Spring's opinionated starter|spring boot]].
```

## Where the Link Goes

- If the factoid has a URL, the link goes to that URL.
- If not, the link goes to the factoid's page, `/factoids/<selector>`.
- If the factoid has text, the reader sees that text when they point at the link.

A link to a factoid that doesn't exist, or has no text, shows no text.

When a factoid changes, the posts that link to it are rendered again. You don't have to edit the
post.

## Related

- [Manage Factoids](../infobot/manage-factoids.md)
- [Blog HTTP API: factoid links](../../reference/blog-http-api.md)
