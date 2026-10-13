# Nnomm Claim Service

A Kotlin and Spring Boot REST API for managing time-limited deal reservations, inventory, idempotent claim requests, and payment webhooks using PostgreSQL.

## Tech Stack

- Kotlin and Java 21
- Spring Boot
- PostgreSQL 15
- Flyway database migrations
- JDBC transactions and row-level locking
- Docker Compose

## Prerequisites

- Docker and Docker Compose
- JDK 21 for running Gradle tests locally

## Configuration

The application supports these environment variables:

| Variable | Purpose |
|---|---|
| `DATABASE_URL` | PostgreSQL JDBC connection URL |
| `DATABASE_USER` | Database username |
| `DATABASE_PASSWORD` | Database password |
| `JWT_SECRET` | JWT signing secret |
| `WEBHOOK_SECRET` | Payment webhook HMAC secret |
| `CLAIM_TTL_SECONDS` | Reservation time-to-live; defaults to 600 seconds |
| `SERVER_PORT` | Application port; defaults to 8080 |

Use local development secrets only for testing. Configure strong, private secrets in any deployed environment.

## Run with Docker Compose

Start the services:

```bash
docker compose up --build -d
```

The Compose configuration starts PostgreSQL and two application instances. The API instances are exposed on ports `8080` and `8081`.

Check service status:

```bash
docker compose ps
```

Stop the services:

```bash
docker compose down
```

To remove the database volume as well, use `docker compose down -v`. This permanently deletes the local database data.

## Run Tests

On Windows PowerShell:

```powershell
.\gradlew.bat test --no-daemon --console=plain --max-workers=1
```

Ensure PostgreSQL is running and the required database environment variables are configured before running integration tests.

## API Endpoints

| Method | Endpoint | Purpose |
|---|---|---|
| `POST` | `/v1/deals` | Create a deal as a restaurant administrator |
| `GET` | `/v1/deals/{dealId}` | Retrieve deal information and available stock |
| `POST` | `/v1/deals/{dealId}/claims` | Reserve deal inventory with an idempotency key |
| `GET` | `/v1/claims/{claimId}` | Retrieve an authorized claim |
| `POST` | `/v1/webhooks/payments` | Process signed payment events |

Authenticated endpoints require a valid bearer JWT. Claim creation requires an `Idempotency-Key` header. Payment webhooks use the `X-Payment-Signature` header.

## Database Migrations

Flyway applies versioned SQL migrations automatically at application startup.

## Design Notes

See [DESIGN.md](DESIGN.md) for the concurrency model, inventory handling, idempotency strategy, webhook security, and scaling considerations.