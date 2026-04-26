# Release and Deploy `server-streampack`

This repository uses Maven CI-friendly versions.

The single source of truth for the project version is:

```text
.mvn/maven.config
```

The file contains:

```text
-Drevision=<version>
```

All module `pom.xml` files inherit their parent version via `${revision}`. This avoids touching
every child POM when the release version changes.

## Typical Flow

The current repository-side release flow is:

1. On the feature branch, run:

   ```bash
   ./mvnw clean test
   ```

2. Open and merge the pull request.

3. Update local `main`:

   ```bash
   git checkout main
   git pull
   ```

4. If the release version needs to change, edit `.mvn/maven.config` and update the `revision`
   value there.

5. Deploy Maven artifacts and build/push the container image:

   ```bash
   ./mvnw clean deploy -DskipTests=true
   just image
   ```

`just image` reads the resolved Maven project version automatically, so the image tag stays aligned
with the shared Maven revision unless you explicitly override it.

## Helper Recipe

The repository also provides:

```bash
just release patch
just release minor
just release major
```

That helper updates `.mvn/maven.config` and then runs `mvn deploy`.

## Why This Exists

Without CI-friendly versions, changing the project version required editing the root POM and every
child module parent reference. With `${revision}` plus the flatten plugin, development stays
centralized while installed and deployed POMs are still consumable by Maven clients.
