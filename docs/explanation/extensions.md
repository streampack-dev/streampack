# Extensions

An extension is a jar the operator adds to a deployment, outside the image: an operation of their own, a banner, a few settings. The image doesn't change, so the deployment still upgrades by changing `STREAMPACK_VERSION`.

## How the Jars Get In

The server is a Spring Boot executable jar. Started with `java -jar`, it sees only what was built into it. The image starts it through Spring Boot's `PropertiesLauncher` instead, which reads `loader.path`, a list of directories and jars to add to the classpath. The image sets `loader.path` to the `ext/` directory.

From there, nothing is special. The server scans the package `dev.streampack` for components, so a component in an extension jar under that package is found like a built-in one. An operation joins the chain, a service can be injected, and a scheduled method runs.

## HTTP Endpoints

A controller is a component too, so an extension's `@RestController` under `dev.streampack` serves requests beside the server's own. The server's security lets every request reach a controller; each controller checks who is calling. An extension does that through `RequestCaller`, the same lookup the server's controllers use, so a user signs in once for both. Its endpoints also appear in the server's live OpenAPI document.

Nothing keeps an extension's paths apart from the server's. The deployer trusts what is in `ext/`, so the convention is enough: an extension's endpoints go under `/ext/<name>/`. See [Add an HTTP Endpoint to an Extension](../how-to/develop/add-an-http-endpoint-to-an-extension.md).

## Why the Directory Comes First

`PropertiesLauncher` puts `loader.path` ahead of the server's own classes. That's what makes `banner.txt` and `logback.xml` there replace the server's: when two resources share a name, the first one on the classpath wins.

The same rule makes `application.yml` dangerous. Spring reads `classpath:application.yml` from the first place it finds it, so a file there would replace the server's whole configuration, every default with it. The start would fail somewhere unrelated, such as the database connection. So the server checks for that file before reading any configuration, and stops with the reason. Spring also reads `classpath:config/application.yml`, on top of the main file and at a higher precedence, so `config/application.yml` in `ext/` is the place for added settings.

## What an Extension Is Trusted With

An extension runs inside the server, with everything the server has: the database, the credentials in its environment, the network, and every user's data. There's no sandbox. Treat an extension jar as you'd treat a change to the server's own source:

- Add only jars you've built, or would accept into the build.
- Mount `ext/` read-only, so nothing in the container can change the code that runs next time.
- Keep `ext/` as private as `.env`. Whoever can write to it can run code as the server.

The server logs which jars it loaded at every start, so an unexpected one shows up.

## Versions

An extension is compiled against the streampack libraries it uses, with `provided` scope, so it uses the server's copies at run time. Those libraries change between releases. Build the extension against the server version you run, and rebuild it when you upgrade.
