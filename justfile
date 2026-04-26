set dotenv-load := true
maven := `if command -v mvnd >/dev/null 2>&1; then printf '%s' mvnd; else printf '%s' ./mvnw; fi`

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

# Build and push a multi-platform JVM server-streampack image. Override IMAGE_REGISTRY/IMAGE_REPOSITORY as needed.
image tag="":
    #!/usr/bin/env bash
    set -euo pipefail

    version="{{tag}}"
    if [[ -z "$version" ]]; then
      version="$(./mvnw -q -N help:evaluate -Dexpression=project.version -DforceStdout | tail -n 1)"
    fi

    registry="${IMAGE_REGISTRY:-nexus.streampack.dev}"
    repository="${IMAGE_REPOSITORY:-images/server-streampack}"
    image="${registry}/${repository}"
    platforms="${DOCKER_PLATFORMS:-linux/amd64,linux/arm64}"
    builder="${DOCKER_BUILDX_BUILDER:-streampack-builder}"

    {{maven}} -pl server-streampack -am -DskipTests clean package

    mkdir -p target/docker
    cp "server-streampack/target/server-streampack-${version}-exec.jar" target/docker/server-streampack.jar

    if [[ "${DOCKER_LOGIN:-true}" == "true" ]]; then
      just docker-login
    fi

    if ! docker buildx inspect "$builder" >/dev/null 2>&1; then
      docker buildx create --name "$builder" --use >/dev/null
    else
      docker buildx use "$builder" >/dev/null
    fi
    docker buildx inspect --bootstrap >/dev/null

    tags=(-t "${image}:${version}")
    if [[ "${DOCKER_PUSH_LATEST:-false}" == "true" ]]; then
      tags+=(-t "${image}:latest")
    fi

    docker buildx build \
      --platform "$platforms" \
      "${tags[@]}" \
      --push \
      .

# Bump the shared revision in .mvn/maven.config and deploy. Defaults to a patch release.
release level="patch":
    #!/usr/bin/env bash
    set -euo pipefail
    level="{{level}}"
    config_file=".mvn/maven.config"
    case "$level" in
      patch|minor|major) ;;
      *)
        echo "release level must be one of: patch, minor, major" >&2
        exit 1
        ;;
    esac

    current="$(./mvnw -q -N help:evaluate -Dexpression=project.version -DforceStdout | tail -n 1)"
    base="${current%-SNAPSHOT}"

    IFS=. read -r major minor patch <<<"$base"
    if [[ -z "${major:-}" || -z "${minor:-}" || -z "${patch:-}" ]]; then
      printf 'current version is not semantic: <%s>\n' "$current" >&2
      exit 1
    fi

    case "$level" in
      patch)
        next="${major}.${minor}.$((patch + 1))"
        ;;
      minor)
        next="${major}.$((minor + 1)).0"
        ;;
      major)
        next="$((major + 1)).0.0"
        ;;
    esac

    echo "Releasing $current -> $next"
    perl -0pi -e 's/^-Drevision=.*/-Drevision='"$next"'/m or die "failed to update revision\n"' "$config_file"
    {{maven}} deploy
