# The Local Admin Token

A fresh server has a super-admin (created from `ADMIN_EMAIL`) but no way for anyone to sign in as it yet. Sign-in has no passwords: it's a one-time code, sent by mail or as a Mattermost direct message, and on a fresh box neither is set up. Setting them up is exactly what the super-admin is needed for.

The console solves this by being on the inside: it reads the server's own standard input and acts as the super-admin. That works for a jar run in a terminal, and awkwardly under Docker (see [Use the Console in Docker](../how-to/deploy/use-the-console-in-docker.md)). A setup tool would rather use the web console API that ui-pudl uses, from outside the container, and for that it needs a token.

So, when asked, the server writes one: `STREAMPACK_LOCAL_ADMIN_TOKEN_FILE` names a file, and when the server is ready it writes a super-admin JWT there.

## Why This Is Safe Enough

The question is who can read the file, and what they could do without it.

The setup tool keeps the file inside the server's container, on a `tmpfs` (memory, never the host's disk), and reads it with `docker compose exec`. Reading it needs Docker access on the box, and Docker access is root there in all but name: it can read `.env` (with `JWT_SECRET`, the key every token is signed with, and `DB_PASSWORD`), reach the database, and run anything in any container. With `JWT_SECRET` alone, someone could sign a super-admin token of their own. The file gives nobody anything they didn't have.

Being on the box, with Docker, is the boundary, as it already is for `.env`. That's "good enough", not perfect: it's the same trust as the box itself, and no more.

A deployment that writes the file to a host directory instead must keep that directory as private as `.env`. The server runs as root in its image, so such a file belongs to root and is readable by root alone.

## What Keeps It Contained

- **Off unless asked for.** Without `STREAMPACK_LOCAL_ADMIN_TOKEN_FILE`, nothing is written.
- **Short-lived.** Each token is valid for `STREAMPACK_LOCAL_ADMIN_TOKEN_LIFETIME` (an hour by default). The file is replaced when the server is ready and every half lifetime after that, so it always holds a token with time left, and a stolen copy stops working soon. A client whose token has expired (a `401`, or the console stream closing) reads the file again.
- **Private.** The file is created mode `0600` and replaced in one atomic move, so it's never readable by others or seen half-written.
- **Gone at shutdown.** It's deleted when the server stops, and written afresh on the next start.
- **Never logged.** The log says where the token was written and for whom, never the token.
- **Kept in the container.** The setup tool's compose file gives the server a `tmpfs` for it, so it's never on the host's disk, and no other service can see it.
- **Signed by the server.** The setup tool never mints tokens. The server stays the only thing that signs them, with the key it already has, so revoking `JWT_SECRET` revokes these too.

## What It Can Do

It's an ordinary super-admin token, so what the super-admin can do over HTTP, it can do. The web console checks the account's current role for every command, as it does for anyone, so a super-admin who is later demoted or suspended can't use an old token to act as one.
