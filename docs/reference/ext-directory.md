# The `ext/` Directory

The image puts one directory on the server's classpath, ahead of the server's own classes: `/opt/streampack/ext`, or `STREAMPACK_EXT_DIR`. Mount a directory of your own there to add extension jars and classpath files without rebuilding the image.

To use it, see [Extend the Server with Jars](../how-to/deploy/extend-the-server-with-jars.md). For why it works this way and what it trusts, see [Extensions](../explanation/extensions.md).

## Contents

| Entry | Effect |
|-------|--------|
| `*.jar` | Goes on the classpath. Spring components under the package `dev.streampack` (operations, services, scheduled tasks) are found by the server's scan, and load like the built-in ones. |
| `banner.txt` | Replaces the banner the server prints when it starts. |
| `config/application.yml` | Adds settings, at a higher precedence than the server's built-in `application.yml`. The environment (`.env`) still wins over it. |
| `logback.xml` | Replaces the server's logging configuration. |
| Any other file or directory | Becomes a classpath resource, by its path in the directory. A file with the same path as one in the server replaces it. |
| `application.yml`, `application.yaml` or `application.properties` at the top | Stops the start. Such a file would replace the server's whole configuration, not just the settings in it. Move it to `config/`. |

## Behaviour

- **Order.** The directory and its jars come before the server's own classes and libraries. A resource or class found in both is taken from `ext/`.
- **Missing or empty.** A directory that's missing or empty changes nothing.
- **When it's read.** The directory is read once, when the server starts. To add, change or remove a jar, recreate the container: `docker compose up -d --force-recreate server-streampack`.
- **What the server reports.** At startup the server logs one line: `Extensions from /opt/streampack/ext: jars …; resources …`.
- **Mounting.** Mount the directory read-only. The server never writes to it.

## Settings

| Variable | Default | Meaning |
|----------|---------|---------|
| `STREAMPACK_EXT_DIR` | `/opt/streampack/ext` | The directory. The image passes it to Spring Boot's `PropertiesLauncher` as `loader.path`. |

## Writing an Extension Jar

- **Library.** Compile against `lib-core` (and any other streampack library you use) with the `provided` scope. The server already has them, so the jar holds only your own classes.
- **Version.** Build against the version of the server you run. An extension built for another version may not load.
- **Package.** Put components under `dev.streampack`, for example `dev.streampack.example`, so the server's scan finds them.
- **Example.** The repository's `example-extension` module is a complete example. See [Write and Load an Extension](../tutorials/write-and-load-an-extension.md).
