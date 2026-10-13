
CREATE TABLE restaurants (
    id UUID PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE deals (
    id UUID PRIMARY KEY,
    restaurant_id UUID NOT NULL REFERENCES restaurants(id),
    title VARCHAR(100) NOT NULL,
    price_paise INTEGER NOT NULL CHECK (price_paise BETWEEN 1 AND 1000000),
    stock_total INTEGER NOT NULL CHECK (stock_total BETWEEN 1 AND 10000),
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (ends_at > starts_at)
);

CREATE TABLE claims (
    id UUID PRIMARY KEY,
    deal_id UUID NOT NULL REFERENCES deals(id),
    customer_id UUID NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 3),
    amount_paise INTEGER NOT NULL CHECK (amount_paise > 0),
    status VARCHAR(20) NOT NULL CHECK (
        status IN (
            'RESERVED',
            'PAID',
            'EXPIRED',
            'NEEDS_REVIEW',
            'REFUND_PENDING'
        )
    ),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_claims_deal_status_expiry
    ON claims (deal_id, status, expires_at);

CREATE INDEX idx_claims_customer
    ON claims (customer_id, id);

CREATE TABLE idempotency_records (
    customer_id UUID NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL
        CHECK (idempotency_key ~ '^[A-Za-z0-9_-]{1,64}$'),
    deal_id UUID NOT NULL REFERENCES deals(id),
    quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 3),
    request_hash VARCHAR(64) NOT NULL,
    response_status INTEGER,
    response_body JSONB,
    claim_id UUID REFERENCES claims(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (customer_id, idempotency_key)
);

CREATE INDEX idx_idempotency_created_at
    ON idempotency_records (created_at);

CREATE TABLE payment_events (
    event_id VARCHAR(200) PRIMARY KEY,
    claim_id UUID NOT NULL REFERENCES claims(id),
    event_type VARCHAR(50) NOT NULL CHECK (
        event_type IN ('payment.succeeded', 'payment.failed')
    ),
    amount_paise INTEGER NOT NULL CHECK (amount_paise >= 0),
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
