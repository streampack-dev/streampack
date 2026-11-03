# Build and Publish the Server Container

`server-streampack` is packaged as a Spring Boot executable jar outside Docker. The Docker build copies a staged jar into `eclipse-temurin:25-jre-resolute`: the Temurin Java 25 runtime on Ubuntu 26.04, the same base as ui-pudl's image. The tag names the Ubuntu release, so the OS changes only when the Dockerfile does. Before #238 the base was GraalVM's `jdk-community:25`, whose moving tag went to Oracle Linux 10; that needs an x86-64-v3 processor, and the server stopped at once on older ones.

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

## Build Cache

The multi-platform build runs in the `streampack-builder` buildx builder, which ui-pudl, ui-primate and ui-nextjs use too. Its cache is in the builder's own Docker volume, so `docker system df` and `docker system prune` do not see it.

After each build, the image recipe trims that cache to 10 GB. It keeps the layers used most recently, so the next build stays fast.

To see the cache size:

```bash
docker buildx du --builder streampack-builder
```

To clear all of the cache:

```bash
docker buildx prune --builder streampack-builder -f
```
