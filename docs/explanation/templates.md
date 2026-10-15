# Templates

Some of the text the server produces belongs to the deployment, not to the code: an AI prompt's voice, the wording of a sign-in mail, the site's name in it. That text lives in templates, files an operator can replace without a rebuild.

## One Mechanism for Prompts and Mail

Prompts came first. `lib-generative` looks for a prompt in the mounted `generative/` directory, as a `.clj` file and then a `.txt` file, and falls back to the default bundled in the jar. Mail uses the same lookup, with the same directory and file names. An operator learns one rule: a file named after the template replaces it.

The mail is sent from `lib-core`, which `lib-generative` depends on, so `lib-core` can't call it. `lib-core` defines a small interface, `TextTemplates`, and `lib-generative` implements it. In a server with `lib-generative` (every streampack server), overrides work. Without it, the bundled default is used.

## Placeholders

A mail has to carry things that change every time: a code, a link, a post's title. A `.clj` template gets them as a map and can do anything with them. Most changes need less than a program, so a `.txt` template may write `{{code}}`, and the server fills it in.

Two rules keep this safe:

- **A missing value stays visible.** `{{cdoe}}`, mistyped, appears in the mail as `{{cdoe}}`, not as nothing. The operator sees the mistake in the first mail they send themselves.
- **A value isn't read as a template.** A submitted post's text goes into the new-draft mail as written, so a post containing `{{siteName}}` can't change what the mail says.

## Why the Defaults Aren't Copied Out

To edit a template, an operator copies its default into `generative/`. The server doesn't do that copying. The directory is mounted read-only, so the server can't write to it, which is the point: whoever can change a prompt or a mail changes what the server says. The defaults are listed in full in [Prompt and Message Templates](../reference/templates.md), and a test keeps that page the same as the bundled files.

## Names in Templates

A mail says the site's name, `BLOG_SITE_NAME`, as `{{siteName}}`. The site's name defaults to the deployment's name, `STREAMPACK_NAME`, which also names the server elsewhere: in `/features`, the IRC identity, the user agent and the MCP server. The feeds' title defaults to the site's name too. So one setting is enough to rename a deployment, and each name can still be set on its own. See [Name the Deployment](../how-to/deploy/name-the-deployment.md).
