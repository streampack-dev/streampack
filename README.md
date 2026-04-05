# Streampack

A content hub and knowledge management system built on Enterprise Application Integration (EAI) principles.
The initial deployment target is [bytecode.news](https://bytecode.news), a community-driven JVM ecosystem news site.

## Prerequisites

- **JDK 25** or later
- **Maven 3.8.0** or later (wrapper included via `mvnw`; also compatible with `mvnd`)
- **PostgreSQL 17** (via Docker or system service)
- **Docker** (required for running tests, optional for production database)

## Database Setup

Tests use Testcontainers and manage their own PostgreSQL instance automatically - you only need Docker running.

For the production application, you need a PostgreSQL database.
Flyway handles all schema migrations on startup.

### Option A: Docker

```bash
docker run -d \
  --name nevet-db \
  -e POSTGRES_USER=nevet \
  -e POSTGRES_PASSWORD=nevet \
  -e POSTGRES_DB=nevet \
  -p 5432:5432 \
  postgres:17
```

### Option B: Existing Docker PostgreSQL

If you already have a PostgreSQL container running for other projects, create the database inside it:

```bash
docker exec -it <your-postgres-container> psql -U postgres -c "CREATE USER nevet WITH PASSWORD 'nevet';"
docker exec -it <your-postgres-container> psql -U postgres -c "CREATE DATABASE nevet OWNER nevet;"
```

Replace `<your-postgres-container>` with your container name (use `docker ps` to find it).
If your existing container maps to a non-default port, set `DB_URL` accordingly in your `.env.properties`.

### Option C: System PostgreSQL

```bash
createuser -P nevet       # set password when prompted
createdb -O nevet nevet
```

## Configuration

The application reads configuration from environment variables with sensible defaults for local development.
You can also place a `.env.properties` file in the project root:

```properties
DB_URL=jdbc:postgresql://localhost:5432/nevet
DB_USERNAME=nevet
DB_PASSWORD=nevet
JWT_SECRET=some-secret-key-for-development
CONSOLE_ENABLED=false
```

If you're using the defaults (database named `nevet`, user `nevet`, password `nevet` on localhost:5432), no configuration file is needed.

### Configuration Reference

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_URL` | `jdbc:postgresql://localhost:5432/nevet` | JDBC connection URL |
| `DB_USERNAME` | `nevet` | Database username |
| `DB_PASSWORD` | `nevet` | Database password |
| `JWT_SECRET` | `change-me-in-production` | Secret key for JWT signing |
| `BASE_URL` | `http://localhost:8080` | Public base URL for the application |
| `MAIL_HOST` | `localhost` | SMTP server hostname |
| `MAIL_PORT` | `25` | SMTP server port |
| `CONSOLE_ENABLED` | `false` | Enable the interactive console adapter |

## Building

```bash
# Build everything and run tests (requires Docker for Testcontainers)
./mvnw clean install

# Build without tests
./mvnw clean install -DskipTests

# Run tests only
./mvnw test

# Run a single test class
./mvnw test -pl service-blog -Dtest=LoginOperationTests

# Format code (runs automatically during compile, but can be invoked directly)
./mvnw spotless:apply
```

## Running

Build the bootable JAR and start it:

```bash
./mvnw clean package -DskipTests
java -jar app/target/app-1.0.jar
```

On first boot, Flyway runs all database migrations and a superadmin account is created automatically.
The generated password is logged to the console - look for the `SuperAdminBootstrap` log output.

The application starts on port 8080 by default.
The Swagger UI is available at `http://localhost:8080/swagger-ui.html`.

## Console Mode

The interactive console lets you send commands directly to the operation pipeline as the superadmin user.
Enable it by setting `CONSOLE_ENABLED=true` in your `.env.properties` or as an environment variable:

```bash
CONSOLE_ENABLED=true java -jar app/target/app-1.0.jar
```

The console presents a `>` prompt.
Type commands and see results:

```
> calc 2+3
The result of 2+3 is: 5.0
> calc (42/3.14)*4
The result of (42/3.14)*4 is: 53.50318471337579
> exit
```

Type `exit` or `quit` to stop.

## Project Structure

| Module | Purpose |
|--------|---------|
| `lib-core` | Shared domain model, event system, user management |
| `lib-blog` | Blog domain entities and repositories |
| `lib-test` | Shared test infrastructure (Testcontainers config, test channel override) |
| `operation-calc` | Calculator operation for algebraic expressions |
| `service-blog` | HTTP adapter, REST controllers, blog operations |
| `service-console` | Interactive console adapter |
| `app` | Assembly module - produces the bootable JAR |

## Documentation

| Document | Audience | Description |
|----------|----------|-------------|
| [User Guide](docs/user-guide.md) | End users | Complete command reference for all bot operations |
| [API Reference](docs/api-reference.md) | Frontend developers | REST endpoint documentation, request/response shapes |
| [Content Authoring](docs/content-authoring.md) | Authors / editors | Markdown guidance for posts and comments, including admonition syntax |
| [Architecture](docs/architecture.md) | Backend contributors | Messaging pipeline, operation system, module design |
| [Deployment](docs/deployment.md) | Ops / self-hosters | Local setup, production deployment, adapter configuration |
| [Authentication](docs/authentication.md) | All | OTP, OIDC, JWT, account lifecycle |
| [MCP Guide](docs/mcp.md) | AI/tool integrators | MCP endpoint connection, tool discovery, and JSON-RPC usage |

## License

All user-submitted content is licensed under Creative Commons CC BY-SA 4.0.
