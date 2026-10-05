# No dotenv-load: .env is the application's runtime configuration (production-style secrets
# enforcement, chat integrations). Loaded into a build, it reaches the tests and breaks them.
maven := `if command -v mvnd >/dev/null 2>&1; then printf '%s' mvnd; else printf '%s' ./mvnw; fi`

# .env loaded only where the app runs (run), as in ui-pudl.
app_env := 'set -a; if [ -f .env ]; then . ./.env; fi; set +a;'

default:
    just --list

clean:
    find . -name "output.log*" -exec rm -rf {} \;
    {{maven}} clean

# Build without tests
build: clean
    {{maven}} -DskipTests=true package

# Build with tests
test: clean
    {{maven}} package

# Run tests and generate aggregate coverage report
coverage:
    {{maven}} clean verify
    @echo "Coverage report: coverage-report/target/site/jacoco-aggregate/index.html"

# Run server-streampack locally, with .env as its configuration
run:
    {{maven}} -pl server-streampack -am -DskipTests package
    {{app_env}} java -jar server-streampack/target/server-streampack-*-exec.jar

# Build a GraalVM native executable for server-streampack. This uses mvnw, not mvnd if it's present.
native:
    ./mvnw -pl server-streampack -am -Pnative -DskipTests package

# Log in to the Docker registry. Uses DOCKER_USERNAME/DOCKER_PASSWORD first, then ~/.m2/settings.xml.
docker-login:
    #!/usr/bin/env bash
    set -euo pipefail

    registry="${IMAGE_REGISTRY:-nexus.streampack.dev}"
    username="${DOCKER_USERNAME:-}"
    password="${DOCKER_PASSWORD:-}"

    if [[ -z "$username" || -z "$password" ]]; then
      settings="${MAVEN_SETTINGS:-$HOME/.m2/settings.xml}"
      server_id="${MAVEN_DOCKER_SERVER_ID:-nexus-streampack}"

      if [[ ! -f "$settings" ]]; then
        echo "No Maven settings file found at $settings; set DOCKER_USERNAME and DOCKER_PASSWORD instead." >&2
        exit 1
      fi
      if ! command -v xmllint >/dev/null 2>&1; then
        echo "xmllint is required to read $settings; set DOCKER_USERNAME and DOCKER_PASSWORD instead." >&2
        exit 1
      fi

      username="$(xmllint --xpath "string(/*[local-name()='settings']/*[local-name()='servers']/*[local-name()='server'][*[local-name()='id']='$server_id']/*[local-name()='username'])" "$settings")"
      password="$(xmllint --xpath "string(/*[local-name()='settings']/*[local-name()='servers']/*[local-name()='server'][*[local-name()='id']='$server_id']/*[local-name()='password'])" "$settings")"

      if [[ -z "$username" || -z "$password" ]]; then
        echo "No credentials found for Maven server '$server_id' in $settings; set MAVEN_DOCKER_SERVER_ID or DOCKER_USERNAME/DOCKER_PASSWORD." >&2
        exit 1
      fi
      if [[ "$password" == \{* ]]; then
        echo "Maven server '$server_id' appears to use an encrypted password; set DOCKER_PASSWORD or run docker login manually." >&2
        exit 1
      fi
    fi

    printf '%s' "$password" | docker login "$registry" --username "$username" --password-stdin

# The tag, if given, must match the project version. DOCKER_PUSH_LATEST=false skips latest;
# DOCKER_PUSH=false builds a local image instead of pushing (#105, as ui-pudl).
# Build the multi-platform image and push it to Nexus as the project version and as latest
image tag="":
    #!/usr/bin/env bash
    set -euo pipefail
    version="$(./mvnw -q -N help:evaluate -Dexpression=project.version -DforceStdout | tail -n 1)"
    if [[ -n "{{tag}}" && "{{tag}}" != "$version" ]]; then
      echo "Tag '{{tag}}' does not match the project version '$version'. Update .mvn/maven.config first." >&2
      exit 1
    fi
    {{maven}} -pl server-streampack -am -DskipTests clean package
    if [[ "${DOCKER_PUSH:-true}" == "true" ]]; then
      just _image "$version" push
    else
      just _image "$version" local
    fi

# The image from server-streampack's built jar. mode: local (one platform, into local Docker),
# cache (every platform, kept in buildx's cache, nothing pushed), or push (every platform, pushed;
# after cache, served from it). IMAGE_REGISTRY/IMAGE_REPOSITORY override where it goes.
_image version mode:
    #!/usr/bin/env bash
    set -euo pipefail
    version="{{version}}"
    mode="{{mode}}"
    registry="${IMAGE_REGISTRY:-nexus.streampack.dev}"
    repository="${IMAGE_REPOSITORY:-images/server-streampack}"
    image="${registry}/${repository}"
    platforms="${DOCKER_PLATFORMS:-linux/amd64,linux/arm64}"
    builder="${DOCKER_BUILDX_BUILDER:-streampack-builder}"
    commit="$(git rev-parse --short HEAD 2>/dev/null || printf unknown)"

    mkdir -p target/docker
    cp "server-streampack/target/server-streampack-${version}-exec.jar" target/docker/server-streampack.jar

    labels=(
      --label "org.opencontainers.image.title=server-streampack"
      --label "org.opencontainers.image.version=${version}"
      --label "org.opencontainers.image.revision=${commit}"
      --label "org.opencontainers.image.source=https://github.com/streampack-dev/streampack"
    )
    tags=(-t "${image}:${version}")
    if [[ "${DOCKER_PUSH_LATEST:-true}" == "true" ]]; then
      tags+=(-t "${image}:latest")
    fi

    case "$mode" in
      local)
        docker build "${labels[@]}" "${tags[@]}" .
        echo "Built ${image}:${version} locally"
        exit 0
        ;;
      cache|push) ;;
      *) echo "image mode must be local, cache or push" >&2; exit 1 ;;
    esac

    if [[ "$mode" == "push" && "${DOCKER_LOGIN:-true}" == "true" ]]; then
      just docker-login
    fi
    if ! docker buildx inspect "$builder" >/dev/null 2>&1; then
      docker buildx create --name "$builder" --use >/dev/null
    else
      docker buildx use "$builder" >/dev/null
    fi
    docker buildx inspect --bootstrap >/dev/null

    if [[ "$mode" == "cache" ]]; then
      docker buildx build --platform "$platforms" "${labels[@]}" "${tags[@]}" .
      echo "Built ${image}:${version} for ${platforms} (cached, not pushed)"
    else
      docker buildx build --platform "$platforms" "${labels[@]}" "${tags[@]}" --push .
      echo "Pushed ${image}:${version}$([[ "${DOCKER_PUSH_LATEST:-true}" == "true" ]] && printf ' and :latest')"
    fi

# Releases come from main, up to date with origin's: never from a feature branch.
_on-main:
    #!/usr/bin/env bash
    set -euo pipefail
    branch="$(git branch --show-current)"
    if [[ "$branch" != "main" ]]; then
      echo "Release from main, not ${branch:-a detached HEAD}." >&2
      exit 1
    fi
    git fetch -q origin main
    if [[ -n "$(git rev-list HEAD..origin/main)" ]]; then
      echo "main is behind origin/main. Pull first." >&2
      exit 1
    fi

# A -SNAPSHOT version releases as itself first (0.1.0-SNAPSHOT -> 0.1.0); after that, level is
# patch, minor or major. Two phases, then publishing (#105): build and test every module, then
# build the image for every platform; only if both succeed are the artifacts deployed to Nexus
# (deployAtEnd: all or none) and the image pushed. A failure before publishing puts
# .mvn/maven.config back, so a retry doesn't bump again; a failed image push after Nexus took the
# artifacts keeps the version, since Nexus won't take a release twice.
# Release: bump the version in .mvn/maven.config, deploy the artifacts and push the image
release level="patch": _on-main
    #!/usr/bin/env bash
    set -euo pipefail
    level="{{level}}"
    config_file=".mvn/maven.config"
    case "$level" in
      patch|minor|major) ;;
      *) echo "release level must be one of: patch, minor, major" >&2; exit 1 ;;
    esac

    if ! git diff --quiet -- "$config_file"; then
      echo "$config_file has uncommitted changes (a failed release?). Commit or restore it first." >&2
      exit 1
    fi

    current="$(./mvnw -q -N help:evaluate -Dexpression=project.version -DforceStdout | tail -n 1)"
    if [[ "$current" == *-SNAPSHOT ]]; then
      next="${current%-SNAPSHOT}"
    else
      IFS=. read -r major minor patch <<<"$current"
      if [[ -z "${major:-}" || -z "${minor:-}" || -z "${patch:-}" ]]; then
        printf 'current version is not semantic: <%s>\n' "$current" >&2
        exit 1
      fi
      case "$level" in
        patch) next="${major}.${minor}.$((patch + 1))" ;;
        minor) next="${major}.$((minor + 1)).0" ;;
        major) next="$((major + 1)).0.0" ;;
      esac
    fi

    echo "Releasing $current -> $next"
    perl -0pi -e 's/^-Drevision=.*/-Drevision='"$next"'/m or die "failed to update revision\n"' "$config_file"
    restore=1
    trap 'status=$?; if [[ $status -ne 0 && $restore -eq 1 ]]; then git checkout -- "$config_file"; echo "Release failed; nothing was published, and $config_file is restored to $current." >&2; fi' EXIT

    echo "[1/3] Building and testing $next"
    # mvnd when it's there: with the tests' shared Postgres container (#136) its parallel modules
    # no longer each start one, and the tests that waited on timing (#107) wait long enough now.
    {{maven}} clean verify
    echo "[2/3] Building the image"
    just _image "$next" cache
    echo "[3/3] Publishing"
    {{maven}} deploy -DskipTests -DdeployAtEnd=true
    restore=0
    if ! just _image "$next" push; then
      echo "Nexus has $next, but the image push failed. The version is kept: push the image with 'just image $next', then commit $config_file." >&2
      exit 1
    fi
    echo "Released $next: the artifacts are in Nexus and the image is pushed. Commit $config_file."

# The whole release, as it's done from the shell: release, then commit the new version in .mvn and
# push it. Each step runs only if the one before it worked.
full-release level="patch":
    just release {{level}}
    git add .mvn
    git commit -m "updating release version"
    git push

