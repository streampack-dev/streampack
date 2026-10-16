# Give a Deployment Its Own Name

In this tutorial, you give a deployment its own name, `lantern`, and see where the name appears. You set the site's name and the feed's title, and you change the wording of the sign-in mail. It takes about fifteen minutes.

You need:

- A deployment run with Docker Compose, with `generative/` mounted. The setup tool's deployments, and the compose file in [Run the Published Image](../how-to/deploy/run-the-published-image.md), have it. This tutorial uses `~/streampack`.
- Mail turned on, so you can receive the sign-in mail. Without mail, skip step 4.

## 1. Name the Deployment

Add the name to `.env`:

```dotenv
STREAMPACK_NAME=lantern
```

Recreate the server, and wait until it answers:

```bash
cd ~/streampack
docker compose up -d
curl -fsS http://localhost:8080/features
```

In the answer, `version.name` and `siteName` are both `lantern`. The site's name follows the deployment's name until you give it one of its own.

## 2. Give the Site Its Own Name

The site's name is what readers see. Give it capital letters:

```dotenv
BLOG_SITE_NAME=Lantern
```

Recreate the server again, then look at the feed:

```bash
docker compose up -d
curl -fsS http://localhost:8080/blog/rss.xml | head -5
```

The channel's title is `Lantern`. The feed's title follows the site's name.

## 3. Give the Feed Its Own Title

```dotenv
BLOG_FEED_TITLE=Lantern Dispatches
BLOG_FEED_DESCRIPTION=News for the team
```

Recreate the server, and look at the feed again. Its title and description are the ones you set. `/feed.xml` and the two Atom feeds have them too.

## 4. Change the Sign-In Mail

1. Open [Prompt and Message Templates](../reference/templates.md), and copy the text of `mail-sign-in-code`.
2. Save it as `~/streampack/generative/mail-sign-in-code.txt`.
3. Change the subject line and the first sentence:

   ```text
   Subject: Your {{siteName}} code

   Here is your code: {{code}}
   ```

4. Ask for a sign-in code: sign in to one of the front ends with your email address.

The mail's subject is "Your Lantern code". You didn't restart anything: the server reads the template each time it sends the mail.

## What You've Learned

- `STREAMPACK_NAME` names the deployment, and is the default for the site's name.
- `BLOG_SITE_NAME` is the default for the feed's title, and appears in the mail as `{{siteName}}`.
- A file in `generative/` named after a template replaces it, without a restart.

## Next

- [Name the Deployment](../how-to/deploy/name-the-deployment.md): every name the setting reaches.
- [Customize Prompts and Messages](../how-to/deploy/configure-generative-prompts.md): the other templates.
- [Templates](../explanation/templates.md): why they work this way.
