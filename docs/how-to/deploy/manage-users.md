# Manage Users

This page is for the operator of a deployment. It shows how to find the first account, see who has an account, add people, give them a role, connect their chat identities to their accounts, and remove or close accounts.

Type the commands at the server's console (`streampack-setup console`). They also work in a direct message to the bot, from a chat account linked to an account with the role they need.

## Roles

Every account has one role. From least to most:

| Role | In short |
|---|---|
| `guest` | Can use the bot. Can't do anything that needs an account. |
| `user` | An ordinary account. This is the default. |
| `admin` | Can see and change users below `admin`, and moderate. |
| `super_admin` | Can do everything, including adding users, linking identities and setting up chat servers. |

Each command checks the role it needs, and new features set their own. When a command needs more than you have, it says so: `Insufficient privileges: requires ADMIN`. The user commands on this page need:

| Command | Needs |
|---|---|
| `whoami` | nothing |
| `list users`, `show user` | `admin` (email addresses only for `super_admin`) |
| `alter user` | `admin` for users below `admin`; `super_admin` for everyone else |
| `create user`, `link user`, `unlink user` | `super_admin` |

Give `admin` only to people you trust a lot. It is a large step up from `user`.

## Find the First Super-Admin

A new server makes one account by itself: a `super_admin` for the address in `ADMIN_EMAIL` (in `.env`). Its username is the part of that address before the `@`. For `lee@example.com` it is `lee`.

To check who you are at the console:

```text
> whoami
You're lee (System Administrator), super_admin.
```

## See Who Has an Account

```text
> list users
3 users:
  lee (System Administrator): super_admin, active, 1 linked, lee@example.com
  dana (Dana Kim): admin, active, 1 linked, dana@example.com
  sam (Sam Kim): user, active, 0 linked, sam.kim@example.com
```

Each line gives the username, the display name, the role, the state (`active` or `suspended`), and how many identities are linked to the account. Add a role to see only those accounts: `list users admin`.

To see one account, with each identity linked to it:

```text
> show user dana
dana (Dana Kim)
  Role: admin
  State: active
  Email: dana@example.com (verified)
  Linked identities:
    mattermost work g7rxf6ynnjgq9bir3bbtoc3xtw
```

An `admin` sees the same, without email addresses.

## Add a Person

There are two ways. Pick one for each person.

**They sign in, and you set their role.** Anyone who signs in to the site with an email address gets a `user` account for it. The username is the part of the address before the `@`. After they sign in, find them with `list users` and change the role if they need more:

```text
> alter user dana role admin
Updated dana (Dana Kim), admin.
```

**You make the account first.** Give a username, an email address, a display name and, if they need more than `user`, a role. Put a display name with spaces in quotes:

```text
> create user dana dana@example.com "Dana Kim" admin
Created dana (Dana Kim), admin.
```

When they sign in with that address, they get this account. An account made this way starts with its email address marked verified.

The command refuses to guess. An unknown role, or a display name with spaces and no quotes, is an error, and nothing is made:

```text
> create user dana dana@example.com "Dana Kim" chief
error: Unknown role 'chief': use guest, user, admin, super_admin
> create user dana dana@example.com Dana Kim admin
error: Too many arguments. A display name with spaces goes in quotes: create user dana dana@example.com "Dana Kim" admin
```

## Link Chat Identities to an Account

The bot knows people on chat by their identity there: an IRC host, a Slack, Discord or Mattermost user id. A link tells the server which account an identity belongs to, so the person has that account's role when they talk to the bot. Without a link, they are anonymous.

**Link before the person signs in to the site through chat.** A chat sign-in finds the account by its link. If there is no link yet, it makes a new account, and the person then has two.

### The Easy Way: Ask the Person to Send `whoami`

Ask the person to send `whoami` to the bot in a direct message. If they aren't linked, the bot answers with the line to type:

```text
You're not linked to an account. An admin can link you with: link user <username> mattermost work g7rxf6ynnjgq9bir3bbtoc3xtw
```

Put their username in place of `<username>` and type the line at the console:

```text
> link user dana mattermost work g7rxf6ynnjgq9bir3bbtoc3xtw
Linked mattermost work g7rxf6ynnjgq9bir3bbtoc3xtw to dana (Dana Kim), admin.
```

When they send `whoami` again, the bot names the account:

```text
You're dana (Dana Kim), admin. Linked as mattermost work g7rxf6ynnjgq9bir3bbtoc3xtw.
```

In a channel, the bot doesn't answer `whoami`. It says `Ask me that in a direct message.`, so it doesn't announce anyone's role or identity.

### By Protocol

`link help` shows the form for each protocol, and the servers registered for it:

```text
> link help mattermost
MATTERMOST: link user <username> mattermost <server> <username-or-user-id>
  Servers: work
```

| Protocol | The server part | The identity part |
|---|---|---|
| Mattermost | The name you gave the server in `mattermost connect`. | Their Mattermost username (`dana.k` or `@dana.k`), while the server is connected. Otherwise, their user id: 26 lower-case letters and digits. To find your own id without admin rights, open `https://<your-mattermost>/api/v4/users/me` in a browser where you are signed in. The id is the `"id"` field. |
| IRC | The network's name from `irc connect`. | `ident@host`, as `whoami` shows it. Leave out any `~` at the start of the ident. |
| Slack | The workspace's name from `slack connect`. | Their member id: in Slack, open their profile, select **⋮**, then **Copy member ID**. |
| Discord | The server (guild) id. In Discord, turn on **Settings > Advanced > Developer Mode**, then right-click the server and select **Copy Server ID**. | Their user id: right-click them and select **Copy User ID**. A link is for one Discord server. |

The server checks the identity before it stores the link. For Mattermost it looks up a username and stores the id. An unknown server or user is an error, and nothing is stored:

```text
> link user sam mattermost work sam.k
error: 'work' is not connected; give the user id, or connect first
> link user sam irc libera sam@host.example
error: Unknown IRC network: libera
```

### Remove a Link

Give the same identity that `show user` lists. For Mattermost, the username works too:

```text
> unlink user dana mattermost work g7rxf6ynnjgq9bir3bbtoc3xtw
Unlinked g7rxf6ynnjgq9bir3bbtoc3xtw from dana
```

## How People Sign In

There are no passwords. A person asks for a one-time code on the site's sign-in page, and the server sends it:

- **By email**, when mail is set up (`MAIL_ENABLED=true`; see [Configure Email with Postfix](configure-email-postfix.md)). Signing in with an address that has no account makes one.
- **By chat direct message**, from the bot, on a connected Mattermost server. The person gives the server and their chat username. A linked identity signs in to its account.

The super-admin can also sign in through the console, which uses the local admin token ([The Local Admin Token](../../explanation/local-admin-token.md)).

## Change an Account

```text
> alter user sam role guest
Updated sam (Sam), guest.
> alter user sam displayname Sam Kim
Updated sam (Sam Kim), guest.
> alter user sam email sam.kim@example.com
Updated sam (Sam Kim), guest.
> alter user sam username samk
Updated samk (Sam Kim), guest.
```

- Everything after `displayname` is the display name, so no quotes are needed.
- A new email address must be verified again: the person signs in with it once.
- Nobody can change their own role.
- An `admin` can change only accounts below `admin`, and can't give a role of `admin` or higher.

## Suspend, Unsuspend or Erase an Account

These are in the HTTP API only. There is no console command for them yet. Send them with an admin's or super-admin's token:

| Action | Request | Result |
|---|---|---|
| Suspend | `PUT /admin/users/<username>/suspend` | The account can't sign in, and its chat identities are treated as anonymous. `list users` shows it as `suspended`. |
| Unsuspend | `PUT /admin/users/<username>/unsuspend` | The account works again. |
| Erase | `DELETE /admin/users/<username>` | The account is closed for good. It no longer appears in `list users` or `show user`. |

A super-admin account can't be suspended. The requests are in the server's OpenAPI description, [`docs/openapi.json`](../../openapi.json), under `/admin/users`.

## See Also

- [Admin Text Operations](../../reference/admin-text-operations.md), for every admin command.
- [Configure Protocols](../infobot/configure-protocols.md), to connect IRC, Slack, Discord and Mattermost.
