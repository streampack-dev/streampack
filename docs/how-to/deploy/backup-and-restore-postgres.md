# Back Up and Restore PostgreSQL

Prefer logical backups with `pg_dump` and `pg_restore`.

## Back Up From Docker Compose

For a custom-format dump:

```bash
docker compose exec -T db pg_dump -U nevet -d nevet --format custom > nevet.dump
```

For a plain SQL dump:

```bash
docker compose exec -T db pg_dump -U nevet -d nevet > nevet.sql
```

## Restore

For custom format:

```bash
docker compose exec -T db pg_restore -U nevet -d nevet --clean --if-exists < nevet.dump
```

For plain SQL:

```bash
docker compose exec -T db psql -U nevet -d nevet < nevet.sql
```

The `-T` option disables TTY allocation so shell redirection works correctly from the host.

## Data Files

Do not rely on copying PostgreSQL data files while the database is running. If you want data files on the host, use a bind mount such as:

```yaml
volumes:
  - ./data/postgres:/var/lib/postgresql/data
```

Still use logical dumps for normal backups and restores.
