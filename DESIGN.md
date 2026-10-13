# Nnomm Claim Service — Design

## 1. Concurrency and Inventory Safety

PostgreSQL is the source of truth for inventory and claim state. Claim creation runs inside a database transaction and locks the corresponding deal row using `SELECT ... FOR UPDATE`. Requests for the same deal are serialized while the service expires stale reservations, checks existing active claims, calculates held stock, and creates a reservation. This prevents concurrent requests from overselling inventory across multiple application instances.

A reservation holds inventory while its status is `RESERVED` and its expiry time is in the future. Successful payment transitions it to `PAID`. Expiry transitions it to `EXPIRED` and releases the held inventory. `NEEDS_REVIEW` continues to hold inventory, while `REFUND_PENDING` does not. Database constraints and indexes provide additional protection.

## 2. Idempotency and Crash Recovery

Idempotency records are scoped by customer and key, with a unique database constraint on `(customer_id, idempotency_key)`. The service records the deal and quantity associated with each key. Reusing a key with different parameters returns `422`; completed requests replay their original response, while concurrent requests cannot independently create duplicate claims.

The idempotency record and claim response are persisted within the transaction. Incomplete records older than the recovery threshold can be reclaimed, preventing a crashed request from permanently blocking a key. Completed records are retained for the required 24-hour replay window.

## 3. Payment and Expiry Ordering

Payment events and claim updates are processed transactionally. The service locks the claim row before deciding its next state. A valid successful payment received before expiry can mark a reservation as `PAID` if the amount matches. An amount mismatch moves the claim to `NEEDS_REVIEW`. A successful payment arriving after expiry moves the claim to `REFUND_PENDING`, because its inventory has already been released. Duplicate event IDs are ignored using a database uniqueness constraint.

## 4. Webhook Authentication and Security

Payment webhooks are authenticated with HMAC-SHA256 over the timestamp, a period, and the exact raw request body bytes. The timestamp must be within five minutes of the server time. The supplied signature is compared against the computed signature using a constant-time comparison. Malformed, stale, or invalid signatures are rejected.

Customer and restaurant-admin requests use HS256 JWTs. Authorization checks restrict claim access to the owning customer or an administrator for the associated restaurant. SQL operations use parameterized queries. Secrets, tokens, and signatures must not be written to logs.

## 5. Database and Scaling

PostgreSQL transactions and row-level locks coordinate concurrent requests across both application instances; an in-process mutex would not protect requests handled by different instances. Indexes support deal inventory checks, claim expiry processing, customer claim lookups, idempotency records, and duplicate webhook detection.

At 100 times the traffic, I would first measure database contention and query latency, optimize indexes and connection-pool settings, and move expiry processing to reliable batched workers if needed. I would preserve database-backed inventory coordination and add load tests and operational metrics before introducing more complex distributed infrastructure.

## 6. Operational Configuration

The application uses environment variables for database credentials, JWT and webhook secrets, and reservation TTL. Docker Compose runs two application instances against the same PostgreSQL database. Flyway manages schema migrations so both instances use the same schema.