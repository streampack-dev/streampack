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

# Build a GraalVM native executable for app. This uses mvnw, not mvnd if it's present.
native:
    ./mvnw -pl app -am -Pnative -DskipTests package

# Bump the project version and deploy. Defaults to a patch release.
release level="patch":
    #!/usr/bin/env bash
    set -euo pipefail
    level="{{level}}"
    case "$level" in
      patch|minor|major) ;;
      *)
        echo "release level must be one of: patch, minor, major" >&2
        exit 1
        ;;
    esac

    current="$({{maven}} -q -N help:evaluate -Dexpression=project.version -DforceStdout)"
    base="${current%-SNAPSHOT}"

    IFS=. read -r major minor patch <<<"$base"
    if [[ -z "${major:-}" || -z "${minor:-}" || -z "${patch:-}" ]]; then
      echo "current version is not semantic: $current" >&2
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
    {{maven}} -N versions:set -DnewVersion="$next"
    {{maven}} -N versions:commit
    {{maven}} deploy
