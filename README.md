# Nnomm Claim Service

A Kotlin/Spring Boot and PostgreSQL service for flash-deal claims, reservations, and signed payment webhooks.

## Requirements

- Java 21
- Docker with Docker Compose
- PostgreSQL (provided by Docker Compose)

## Run locally

Start the services:

```bash
docker compose up -d --build
docker compose ps
docker compose logs -f
```

The local development database is exposed on port `5434`; the application instances use ports `8080` and `8081`. Check `compose.yaml` for the exact service names and environment configuration.

## Run tests

PowerShell:

```powershell
$env:JAVA_TOOL_OPTIONS = "-Duser.timezone=UTC"
$env:DATABASE_URL = "jdbc:postgresql://localhost:5434/nnomm"
$env:DATABASE_USER = "nnomm"
$env:DATABASE_PASSWORD = "<your-local-database-password>"

.\gradlew.bat test --no-daemon --console=plain --max-workers=1
```

Never commit real credentials. Configure the webhook signing secret through `WEBHOOK_SECRET`; do not use the development fallback in production.

## Correctness approach

- Deal-row locking serializes stock allocation across application instances.
- Idempotency records store completed responses for retries.
- A partial unique index prevents multiple active claims for one customer and deal.
- Webhook signatures use the raw request body and a timestamp.
- Payment events are persisted to make duplicate event delivery idempotent.

## Assumptions

- Reservation expiry is determined using UTC timestamps.
- Money is stored as integer paise.
- A recorded payment failure blocks a later success for the same claim under the current conservative event-ordering policy. A production payment integration should correlate provider payment attempts and use provider event ordering where available.
- Expired reservations no longer count as held stock.

## AI assistance

AI assistance was used to help review implementation choices, draft tests, troubleshoot the local build, and prepare documentation. All generated changes must be reviewed and understood by the candidate before submission.

One suggestion not adopted was relying on a Kotlin `synchronized` block or mutex to protect inventory. It cannot coordinate separate service instances; PostgreSQL locking and constraints provide shared enforcement.

## Future improvements

- Add a provider-specific payment-attempt and event-ordering model.
- Add a repeatable concurrent integration test for payment-versus-expiry races.
- Add structured metrics for lock waits, stock conflicts, webhook replays, and request latency.
- Add production-grade secret management and operational health checks.
