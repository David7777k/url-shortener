# Trimly

URL shortener with Redis caching, expiring links, and click statistics. Redirects return HTTP 302; click events are queued and written to PostgreSQL in batches.

Java 21 · Spring Boot · PostgreSQL 17 · Redis 7 · Flyway · Testcontainers

## Main decisions

- **Cache-aside reads:** resolve from Redis, then fall back to PostgreSQL. Missing codes have a shorter cache lifetime. Links with an expiry are read from the database.
- **Collision handling:** the database's unique constraint rejects duplicate codes. Creation retries with a new code in a separate transaction.
- **Redirect analytics:** a bounded in-memory queue keeps click inserts out of the response path. A full queue drops events and counts the drops.
- **Creation rate limit:** a Redis Lua token bucket limits `POST /api/v1/links` by the transport client address. Unverified API-key and forwarded-address headers do not change the bucket. The default configuration allows requests through if Redis is unavailable.
- **Redirect caching:** responses use `302` and `Cache-Control: no-store` so visits continue to reach the service.

## Run

Requires JDK 21 and Docker. Compose starts the database and Redis; the application runs locally.

```bash
docker compose up -d postgres redis
./mvnw spring-boot:run
```

On Windows use `.\mvnw.cmd spring-boot:run`.

The API listens on http://localhost:8080. Database, Redis, and server settings are listed in [`.env.example`](.env.example) and [application.yml](src/main/resources/application.yml). Export environment overrides in the shell used to start Maven.

Alternatively, run the application and its dependencies with `docker compose up --build`.

## Link management

Creation returns a `managementToken` once. Keep it private: send it in the `X-Management-Token` header to read details, inspect statistics, or delete the link. Redirects require no token. The database stores only a SHA-256 digest of the random 256-bit token. Management responses use `Cache-Control: no-store`.

Missing or incorrect credentials return `404`. This is a per-link bearer capability, without user accounts or token recovery. Use HTTPS when deployed.

**Migration:** V3 adds the token hash. Existing links keep working as redirects but have no management token; their management endpoints are inaccessible. A database administrator must handle any required cleanup. New links receive tokens automatically.

## Try it

```bash
curl -i http://localhost:8080/api/v1/links \
  -H "Content-Type: application/json" \
  -d '{"targetUrl":"https://example.com"}'
```

Save the returned `managementToken`. Copy the returned `code`, then open `http://localhost:8080/<code>`.

| Method | Endpoint | Result |
|---|---|---|
| POST | `/api/v1/links` | Create a link |
| GET | `/{code}` | Redirect to the target |
| GET | `/api/v1/links/{code}` | Read link details |
| GET | `/api/v1/links/{code}/stats?days=30` | Read click statistics |
| DELETE | `/api/v1/links/{code}` | Delete a link |

## Code to read

| Area | Entry point |
|---|---|
| Creation, resolution, and deletion | [LinkService](src/main/java/io/github/david7777k/trimly/link/LinkService.java) |
| Redis cache and fallback | [LinkCache](src/main/java/io/github/david7777k/trimly/link/LinkCache.java) |
| Click buffering | [ClickRecorder](src/main/java/io/github/david7777k/trimly/click/ClickRecorder.java) |
| Request limiting | [RateLimiter](src/main/java/io/github/david7777k/trimly/ratelimit/RateLimiter.java) |
| Integration tests | [Tests](src/test/java/io/github/david7777k/trimly) |

## Measurements

The repository includes a [local k6 comparison](benchmark/README.md) with a warmed cache and about 200,000 stored links. At 2,000 requests per second, the recorded p95 was 2.75 ms without caching and 1.84 ms with caching.

These are existing measurements from one machine and one run per configuration, not a production capacity claim. The benchmark notes describe the setup and reproduction steps.

## Verification

```bash
./mvnw verify
```

Docker is required for Testcontainers. Regression tests exercise cross-link token isolation, missing credentials, cache corruption, and attempts to reset the limit using headers. Tests cover link operations, code generation, caching, Redis outages, rate limiting, and click analytics.

## Tradeoffs and limits

- Click events are held in memory. Queue overflow, process termination, or write failures can lose statistics.
- Cache eviction during deletion does not provide strong consistency against concurrent cache fills.
- Clients sharing a transport address share a creation limit. Configure a trusted reverse proxy deliberately before deployment; arbitrary forwarded headers are ignored.
- The fail-open limiter prioritizes availability during Redis outages and does not enforce a limit in that case.

Before public deployment, the service needs deployment-specific abuse controls, a token recovery/rotation policy if required, and a clear policy for analytics retention.

## License

[MIT](LICENSE)
