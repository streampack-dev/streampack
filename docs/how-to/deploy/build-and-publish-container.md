# Build and Publish the Server Container

`server-streampack` is packaged as a Spring Boot executable jar outside Docker. The Docker build copies a staged jar into a GraalVM CE JDK image.

Build and push the default multi-platform image:

```bash
just image <version>
```

If no explicit tag is provided, `just image` uses the resolved Maven project version. In this
repository that version is sourced from `.mvn/maven.config` via `-Drevision=...`.

Defaults:

| Setting | Default |
|---------|---------|
| `IMAGE_REGISTRY` | `nexus.streampack.dev` |
| `IMAGE_REPOSITORY` | `images/server-streampack` |
| `DOCKER_PLATFORMS` | `linux/amd64,linux/arm64` |
| `DOCKER_PUSH_LATEST` | `false` |

The recipe builds:

```bash
./mvnw -pl server-streampack -am -DskipTests clean package
```

Then it stages:

```text
server-streampack/target/server-streampack-<version>-exec.jar
```

as:

```text
target/docker/server-streampack.jar
```

The `.dockerignore` file keeps the Docker context small and only allows that staged jar.

## Registry Login

`just docker-login` uses `DOCKER_USERNAME` and `DOCKER_PASSWORD` first. If those are not set, it reads `~/.m2/settings.xml` and looks for the Maven server id `nexus-streampack`.

Override the Maven server id with:

```bash
MAVEN_DOCKER_SERVER_ID=some-id just docker-login
```

If Maven uses encrypted passwords, run `docker login` manually or set `DOCKER_PASSWORD`.
