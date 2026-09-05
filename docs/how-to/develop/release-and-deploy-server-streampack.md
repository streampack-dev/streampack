# Version and Publish Maven Artifacts to Nexus

Use this procedure to version and publish the Streampack Maven reactor to the project Nexus. This
is the source-artifact release; publishing the runnable `server-streampack` container is a separate
step.

## What Gets Published

Running Maven `deploy` from the repository root publishes every module listed in the root `pom.xml`
to Nexus under the `dev.streampack` group. For each JAR module, the build attaches:

- the compiled JAR
- a `-sources.jar` built by `maven-source-plugin`
- a flattened consumer POM with the concrete release version

The parent and coverage aggregator are published as POM artifacts. The flatten plugin resolves the
CI-friendly `${revision}` placeholder before those POMs are installed or deployed.

## Configure Nexus Credentials

Maven takes the deployment repository URLs from the root `pom.xml`. Both the release and snapshot
repositories use the server ID `nexus-streampack`, so the matching credentials belong in Maven
settings rather than in this repository.

Add this server to `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>nexus-streampack</id>
      <username>${env.NEXUS_USERNAME}</username>
      <password>${env.NEXUS_PASSWORD}</password>
    </server>
  </servers>
</settings>
```

Set `NEXUS_USERNAME` and `NEXUS_PASSWORD` in the release shell. An existing Maven master-password
configuration may be used instead. Do not add credentials to a POM, `.mvn/maven.config`, or a
committed environment file.

The account needs upload permission for:

| Version | Nexus repository |
|---------|------------------|
| `1.2.10` | `maven-releases` |
| `1.2.10-SNAPSHOT` | `maven-snapshots` |

Maven chooses the target from the version suffix; no repository flag is required. Treat a release
version as immutable. If that version already exists in Nexus, choose a new version rather than
trying to overwrite it.

## Choose and Check the Version

The single source of truth is `.mvn/maven.config`:

```text
-Drevision=1.2.10
```

All module parent references use `${revision}`, so do not run `versions:set` or edit every child
POM. Confirm the version Maven resolves before publishing:

```bash
./mvnw -q -N help:evaluate -Dexpression=project.version -DforceStdout
```

Use semantic versions:

- increment patch for compatible fixes and small features
- increment minor for backwards-compatible feature releases
- increment major for incompatible changes
- append `-SNAPSHOT` only for repeatable development publications

## Publish a Release

Publish from an up-to-date, clean `main` after the changes being released have been merged:

```bash
git switch main
git pull --ff-only
git status --short
./mvnw clean verify
```

Update the `-Drevision` line in `.mvn/maven.config`, check the resolved value, and create the release
commit locally:

```bash
./mvnw -q -N help:evaluate -Dexpression=project.version -DforceStdout
git add .mvn/maven.config
git commit -m "Release 1.2.10"
```

Deploy the full reactor from that clean commit, then push only after Nexus accepts the release:

```bash
./mvnw clean deploy
git push origin main
```

The `deploy` lifecycle compiles and tests the project before upload. Only use `-DskipTests=true`
when the exact commit has already passed the full build and there is a deliberate reason not to run
tests again.

After a successful deploy, inspect the version in Nexus and confirm that a representative library,
operation, and service each contain their POM, main JAR, and `-sources.jar`.

## Use the Release Helper

The repository provides a shortcut that calculates a semantic version, updates
`.mvn/maven.config`, and invokes Maven `deploy`:

```bash
just release          # patch by default
just release patch
just release minor
just release major
```

For example, when the current version is `1.2.9`, `just release patch` publishes `1.2.10`. If the
current value ends in `-SNAPSHOT`, the suffix is removed before calculating the next version.

The helper does not switch branches, pull, require a clean worktree, commit the version change,
create a Git tag, or publish the container image. It updates the version before Maven runs, so a
failed deployment leaves the new value in `.mvn/maven.config`; diagnose the failure and retry that
same version only if Nexus did not accept a release artifact.

## Publish the Server Container

After Maven artifacts are available, build and push the matching server image:

```bash
just image
```

`just image` reads the resolved Maven version and uses it as the image tag. See
[Build and Publish the Server Container](../deploy/build-and-publish-container.md) for registry
credentials, image-name overrides, and multi-platform build details.

## Troubleshooting

- `401` or `403`: confirm that the settings server ID is exactly `nexus-streampack`, the environment
  variables are visible in the current shell, and the Nexus account can upload to the selected
  repository.
- A release redeploy is rejected: increment the version. Do not overwrite a partially or fully
  published immutable release.
- Artifacts went to the wrong repository: check whether the resolved version ends in `-SNAPSHOT`.
- A module is missing: always deploy from the repository root; deploying with `-pl` publishes only
  the selected reactor subset and its requested dependencies.
- Maven cannot resolve `${revision}` downstream: inspect the deployed POM. The deployed file should
  be the flattened POM containing the concrete version.

## Why CI-Friendly Versions Are Used

Without CI-friendly versions, changing the project version required editing the root POM and every
child module parent reference. `${revision}` keeps development versioning centralized, while the
flatten plugin produces ordinary POMs that Maven consumers can resolve from Nexus.
