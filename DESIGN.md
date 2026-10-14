# Design Decisions

## 1. Concurrent claims across instances

`ClaimService.createClaim` locks the deal row using `SELECT ... FOR NO KEY UPDATE`. Requests for the same deal serialize at PostgreSQL, including requests handled by different application instances. Each request then expires old reservations, checks for an existing active claim, calculates held stock, and inserts the reservation in a transaction. A JVM mutex would only coordinate threads inside one process and cannot protect a second instance. The partial unique index `uq_claims_active_customer_deal` adds database-level protection against duplicate active claims per customer and deal.

## 2. Idempotency after a crash

The idempotency record, claim, and stored response are written in the same database transaction. If the transaction commits but the HTTP response is lost, a retry with the same customer and key returns the stored response. If the transaction rolls back before commit, the retry can execute the request again. A request still being processed receives a conflict response rather than creating a second claim.

## 3. Payment and expiry race

Both paths must serialize updates to the claim row. If payment locks and commits first while the claim is still reserved and unexpired, the status becomes `PAID`; expiry must not change it. If expiry commits first, the status becomes `EXPIRED`; a later successful payment requires `REFUND_PENDING`. If payment arrives after the reservation deadline, it must not consume stock as a paid claim. Stock calculations count paid/review claims and only unexpired reservations. These outcomes rely on transaction ordering and must be verified with a concurrent integration test.

## 4. Webhook signatures

The HMAC is calculated over the exact raw request bytes and timestamp. Parsing and re-serializing JSON can change whitespace, property order, or escaping and would authenticate different bytes. The timestamp limits replay of captured requests. Constant-time signature comparison reduces timing leakage when checking a supplied signature.

## 5. Isolation level

The implementation uses PostgreSQL transactions with row locks and database constraints. The deal lock serializes stock allocation for one deal, while the claim lock serializes payment processing for one claim. The partial unique index prevents duplicate active customer claims even if application logic regresses. Without a shared database lock, concurrent requests could both read the same available stock and oversell it.

## 6. Most important constraint/index

`uq_claims_active_customer_deal` enforces one active claim per customer per deal. Dropping it removes the final database guard against duplicate active claims. Stock safety also depends on locking the deal row before reading held quantity and inserting a claim.

## 7. Traffic grows 100×

The deal row becomes a contention point because all reservations for one deal serialize. PostgreSQL connection capacity and lock wait time would likely become bottlenecks. I would measure lock waits and query latency, improve indexes and connection-pool sizing, and consider atomic inventory allocation or per-deal inventory rows. I would preserve database-enforced invariants rather than move correctness into an in-process lock.
