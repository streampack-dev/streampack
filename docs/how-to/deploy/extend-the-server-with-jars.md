# Extend the Server with Jars

Add your own operations, a banner, or extra settings to a running deployment without rebuilding the image. The files go in a directory beside `compose.yaml`, mounted into the container at `/opt/streampack/ext`.

## Mount the Directory

1. Make the directory beside `compose.yaml`:

   ```bash
   mkdir -p ext
   ```

2. Mount it in the server's service in `compose.yaml`, read-only:

   ```yaml
       volumes:
         - ./ext:/opt/streampack/ext:ro
   ```

   The compose file in [Run the Published Image](run-the-published-image.md) and the setup tool's already have this line.

## Add an Extension Jar

1. Copy the jar into `ext/`:

   ```bash
   cp my-extension.jar ext/
   ```

2. Recreate the server:

   ```bash
   docker compose up -d --force-recreate server-streampack
   ```

3. Check that it loaded:

   ```bash
   docker compose logs server-streampack | grep 'Extensions from'
   ```

   The line names each jar in the directory.

To remove an extension, delete the jar, and recreate the server.

## Replace the Banner

1. Put the banner text in `ext/banner.txt`.
2. Recreate the server.

## Add Settings

1. Put the settings in `ext/config/application.yml`:

   ```yaml
   streampack:
     blog:
       feed:
         description: News for the team
   ```

2. Recreate the server.

Don't put `application.yml` at the top of `ext/`. It would replace the server's whole configuration, so the server stops at startup and says so. For a setting that has an environment variable, `.env` is simpler.

## When the Server Doesn't Start

Read the log:

```bash
docker compose logs server-streampack
```

An extension built for another server version, or one that needs a bean the server doesn't have, stops the start. Remove the jar, and recreate the server.

See [The `ext/` Directory](../../reference/ext-directory.md) for everything the directory can hold.
