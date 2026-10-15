# Name the Deployment

A deployment calls itself `nevet` unless you name it. The name appears in `GET /features`, in the `version` command, as the site's name (and so in the mail and the feeds), in the IRC identity, in the MCP server's name, and in the user agent the server sends to other sites.

1. Add the name to `.env`:

   ```dotenv
   STREAMPACK_NAME=gholem
   ```

2. Recreate the server:

   ```bash
   docker compose up -d
   ```

3. Check it:

   ```bash
   curl -fsS http://localhost:8080/features
   ```

   `version.name` and `siteName` are now `gholem`.

## Give One Thing a Different Name

`STREAMPACK_NAME` is only the default. To give one of these its own name, set its variable too:

| Variable | Default |
|----------|---------|
| `BLOG_SITE_NAME` | the name |
| `IRC_IDENTITY` | the name, then `IRC Bridge` |
| `KARMA_IMMUNE_SUBJECTS` | the name |
| `STREAMPACK_FETCH_USERAGENT` | the name, then `(+BASE_URL)` |
| `BLOG_FEED_TITLE` | the site's name |

The mail the server sends says the site's name. To change its wording too, see [Customize Prompts and Messages](configure-generative-prompts.md#change-a-mail).

For example, a lower-case name with a site name in title case:

```dotenv
STREAMPACK_NAME=gholem
BLOG_SITE_NAME=Gholem
```

See [Environment Variables](../../reference/environment-variables.md).
