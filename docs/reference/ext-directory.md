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

- **Library.** Compile against `lib-extension-api` with the `provided` scope. It holds the operation contracts and models, the command parser, `JacksonMappers` and `TickListener`, and the services the server implements for extensions (below). It brings no database or persistence. If you need more, add other streampack libraries, `lib-core` included, also `provided`. The server already has all of them, so the jar holds only your own classes.
- **Version.** Build against the version of the server you run. An extension built for another version may not load.
- **Package.** Put components under `dev.streampack`, for example `dev.streampack.example`, so the server's scan finds them.
- **Example.** The repository's `example-extension` module is a complete example. See [Write and Load an Extension](../tutorials/write-and-load-an-extension.md).

## What the Server Gives an Extension

Inject these into your components. The server implements each one.

| Interface | What it does |
|-----------|--------------|
| `ExtensionDataStore` | Keeps your data, so your jar ships no SQL. Each document is a JSON object, found by your extension's name, a collection you name, and an id: `get`, `put`, `delete` and `list`. |
| `ProvenanceStateService` | Keeps one document for each channel and key, such as a game in progress. |
| `TransformerChainService` | Applies the server's egress transformers to a result you send by yourself, such as a timer's message. |
| `ServerInfo` | The server's `version`, and `isAtLeast("1.16.11")`. A development build has no version and counts as new enough. |

## Declaring a Minimum Server Version

Declare an `Extension` bean with your extension's name, and the oldest server it runs on:

```kotlin
@Component
class GreetExtension : Extension {
    override val name = "greet"
    override val requiresServer = "1.16.11"
}
```

At startup, a server older than that stops, and names each extension that needs a newer server and the version it needs. Upgrade the server, or remove those jars from `ext/`. A development build, which has no version, logs a warning and loads the extension. The bean is optional: an extension without one loads on any server.
