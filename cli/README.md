# bcn

`bcn` is a command-line client for the BCN API (bytecode.news). It is a Go program in this directory. It is not part of the server and not part of `just build` or `just test`.

## Build

You need Go 1.27 or later.

From the repository root, run:

```bash
just build-cli
```

This runs `go vet` and the tests, then writes the binary to `cli/target/bcn`. To run the tests only, run `just test-cli`. To put `bcn` on your path, copy `cli/target/bcn` to a directory on your `PATH`.

## Sign in

```bash
bcn login
```

`bcn` asks for your email address, the API emails you a code, and `bcn` asks for the code. The session is stored in `bcn/session.json` in your configuration directory, with mode 0600:

- On macOS, `~/Library/Application Support/bcn/session.json`.
- On Linux, `$XDG_CONFIG_HOME/bcn/session.json`, or `~/.config/bcn/session.json`.

`bcn` refreshes an expired session by itself. When the refresh token has expired too, sign in again. Only email sign-in is supported.

`--server URL` sets the API (default `https://api.bytecode.news`). A session belongs to one server; the other commands take the same flag.

## Write a post as a foojay.io draft

Give the post and your foojay.io checkout:

```bash
bcn foojay https://bytecode.news/posts/2026/11/where-does-the-experience-come-from ~/work/foojay
```

The post can also be given as `2026/11/where-does-the-experience-come-from`. The directory is optional (default: the current directory):

- A foojay checkout, known by its `draft/` directory: `bcn` writes `draft/bcn-<slug>/index.md` in it.
- The `draft/` directory itself, or any other directory: `bcn` writes `bcn-<slug>/index.md` in it.

The file has the format of foojay's `template/post.md`:

- `title`, `description` (the post's excerpt) and `canonical` (the post's URL on bytecode.news) are filled in.
- `authors`, `categories` and `image` keep foojay's placeholders: `your-author-slug`, `[]` and `""`. foojay's pull-request check rejects all three, so fill them in. A comment above `categories` lists the post's BCN tags as a hint. Put the image file in the same folder.
- In the body, a leading `# ` title line is dropped, because foojay shows the title itself. Factoid links (`[[selector]]`, `[[text|selector]]`) become plain text. Links and images to a bytecode.news path become absolute URLs. Admonitions (`!!! note`, `??? tip "Title"`) become foojay's shortcodes (`{{< note >}}` … `{{< /note >}}`): foojay has note, tip, important, warning and caution, so other kinds map to the nearest one. A title becomes a bold first line, and a collapsible block is shown open. Code, footnotes and mermaid blocks stay as they are.

You must be signed in as an admin: the API gives a post's Markdown source only to admins.

Options:

- `--site URL`: the site for `canonical` and absolute links (default `https://bytecode.news`).
- `--force`: replace an existing `index.md`.

`bcn` prints warnings for problems foojay's check would report, such as an empty description or one of 160 characters or more. It does not open a pull request.
