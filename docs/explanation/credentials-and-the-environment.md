# How Credentials Reach the Environment

Streampack's integrations are set up at run time, by a super-admin at the console: `mattermost connect work https://mm.example.com <token>`, `gitlab add group/project <token>`, and so on. The credentials typed there have to end up somewhere, and Streampack's rule is that, in production, they live in the environment, not the database.

## Why the Environment

A database is copied: into backups, into dumps for debugging, onto a laptop to reproduce a bug. A token stored in it travels with every copy. An environment variable stays on the box, in `.env`, alongside the other secrets (`JWT_SECRET`, `DB_PASSWORD`) that are kept the same way. The database then holds a reference, `env://MATTERMOST_WORK_TOKEN`, which is useless without the box.

## Why Not at Once

The server can't write `.env`: it runs in a container, `.env` is the host's, and a server editing its own secrets file would be a worse arrangement than the one it replaces. So a credential arrives in two steps:

1. **At the console**, the credential is stored as typed and used straight away, so `mattermost connect` connects. Some replies name the variable it will move to (`gitlab add`, `github add`); the `secrets` command names them all.
2. **At the next start**, each service's startup guard looks at every stored credential:
   - one stored as typed, whose variable is now set, is replaced by an `env://` reference, and the log says `Externalized literal credential to …`;
   - one stored as typed, whose variable isn't set, stops the start;
   - a reference whose variable isn't set stops the start too.

   When a start is stopped, the log names the variables in a `SECURITY STARTUP CHECK FAILED` block. It never shows their values.

Between the two steps, the server knows what it will need. The `secrets` command and `GET /admin/secrets` say so, by name, before a restart rather than after a failed one. The setup tool uses that list to ask for the values, write them into `.env`, and recreate the container. [Move Credentials into the Environment](../how-to/deploy/move-credentials-into-the-environment.md) shows the same steps by hand.

## Development

With `STREAMPACK_SECURITY_ENFORCE_EXTERNAL_SECRETS=false`, credentials stay as typed and no start is stopped. That suits a laptop, and nothing else.
