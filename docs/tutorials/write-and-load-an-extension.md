# Write and Load an Extension

In this tutorial, you change the repository's example extension, build it, and load it into a running server from its `ext/` directory. You then call it from the console. The server image doesn't change. It takes about twenty minutes.

You need:

- A deployment run with Docker Compose, with `ext/` mounted. The setup tool's deployments and the compose file in [Run the Published Image](../how-to/deploy/run-the-published-image.md) have it. This tutorial uses `~/streampack`.
- JDK 25, and Git.
- The version of the server you run, from `STREAMPACK_VERSION` in `.env`. This tutorial uses `1.16.0`.

## 1. Get the Source at Your Server's Version

An extension is built against the streampack libraries of the server it runs in. Check out the release you run:

```bash
git clone https://github.com/streampack-dev/streampack.git
cd streampack
git checkout v1.16.0
```

## 2. Read the Example

Open `example-extension/src/main/kotlin/dev/streampack/example/GreetOperation.kt`:

```kotlin
@Component
class GreetOperation : TypedOperation<String>(String::class) {
    override val operationGroup: String = "greet"

    override fun canHandle(payload: String, message: Message<*>): Boolean =
        payload.trim().startsWith("greet ", ignoreCase = true)

    override fun handle(payload: String, message: Message<*>): OperationOutcome {
        val name = payload.trim().substringAfter(' ').trim()
        return OperationResult.Success("Hello, $name, from an extension.")
    }
}
```

- `@Component` and the package `dev.streampack.example` let the server's scan find it.
- `canHandle` claims messages that start with `greet `.
- `handle` answers them.

Open `example-extension/pom.xml`. `lib-core` has the `provided` scope: the server has it already, so the jar holds only the extension's classes.

## 3. Change the Answer

In `handle`, change the answer:

```kotlin
        return OperationResult.Success("Hello, $name. This answer comes from my own jar.")
```

In `example-extension/src/test/kotlin/dev/streampack/example/GreetOperationTests.kt`, change the expected text to match.

## 4. Build It

```bash
./mvnw -pl example-extension -am -Dtest=GreetOperationTests -Dsurefire.failIfNoSpecifiedTests=false package
```

Maven builds the libraries the extension needs, runs the extension's tests (and not the libraries' own), and writes the jar:

```text
example-extension/target/example-extension-1.16.0.jar
```

## 5. Load It

Copy the jar into the deployment's `ext/` directory, and recreate the server:

```bash
cp example-extension/target/example-extension-1.16.0.jar ~/streampack/ext/
cd ~/streampack
docker compose up -d --force-recreate server-streampack
```

When the server has started, its log names the jar:

```bash
docker compose logs server-streampack | grep 'Extensions from'
```

```text
Extensions from /opt/streampack/ext: jars example-extension-1.16.0.jar
```

## 6. Call It

Open the console. With the setup tool:

```bash
./streampack-setup console
```

Then type:

```text
> greet world
Hello, world. This answer comes from my own jar.
```

The server ran your code, from a jar outside its image.

## 7. Remove It

```bash
rm ~/streampack/ext/example-extension-1.16.0.jar
docker compose up -d --force-recreate server-streampack
```

`greet world` now gets no answer from the extension.

## What You've Learned

- An extension is a jar of Spring components under `dev.streampack`, built against the server's libraries with the `provided` scope.
- Jars in `ext/` load when the server starts, and the log names them.
- Adding or removing one needs a recreated container, not a new image.

## Next

- [Write Your First Operation](write-your-first-operation.md): the kinds of operation, and their outcomes.
- [Extend the Server with Jars](../how-to/deploy/extend-the-server-with-jars.md): banners and settings, too.
- [Extensions](../explanation/extensions.md): what an extension is trusted with.
