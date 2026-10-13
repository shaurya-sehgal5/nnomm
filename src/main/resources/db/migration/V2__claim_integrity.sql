
-- Expired claims no longer block a new claim.
CREATE UNIQUE INDEX uq_claims_active_customer_deal
    ON claims (deal_id, customer_id)
    WHERE status IN ('RESERVED', 'PAID', 'NEEDS_REVIEW');

-- Support stock availability and expiry queries.
CREATE INDEX idx_claims_deal_held
    ON claims (deal_id, status);

CREATE INDEX idx_claims_reserved_expiry
    ON claims (expires_at)
    WHERE status = 'RESERVED';

-- Support payment event history and claim lookups.
CREATE INDEX idx_payment_events_claim
    ON payment_events (claim_id);

-- Make completed idempotency records easy to find during cleanup.
CREATE INDEX idx_idempotency_completed
    ON idempotency_records (completed_at)
    WHERE completed_at IS NOT NULL;
