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
