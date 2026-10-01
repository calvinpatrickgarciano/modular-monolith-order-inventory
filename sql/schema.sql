-- =====================================================
-- RESET TABLES
-- =====================================================

DROP TABLE IF EXISTS notifications;
DROP TABLE IF EXISTS order_items;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS inventory;


-- =====================================================
-- INVENTORY
-- =====================================================

CREATE TABLE inventory (
    product_id VARCHAR(20) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    stock INTEGER NOT NULL CHECK (stock >= 0)
);


-- =====================================================
-- ORDERS
-- =====================================================

CREATE TABLE orders (
    order_id BIGSERIAL PRIMARY KEY,
    status VARCHAR(20) NOT NULL
        CHECK (status IN ('CONFIRMED', 'REJECTED', 'CANCELLED')),
    reason VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);


-- =====================================================
-- ORDER ITEMS
-- One order can contain multiple products
-- =====================================================

CREATE TABLE order_items (
    order_item_id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    product_id VARCHAR(20) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),

    CONSTRAINT fk_order
        FOREIGN KEY (order_id)
        REFERENCES orders(order_id)
        ON DELETE CASCADE,

    CONSTRAINT fk_product
        FOREIGN KEY (product_id)
        REFERENCES inventory(product_id)
);

CREATE TABLE supplier_orders (
   id BIGSERIAL PRIMARY KEY,
   product_id VARCHAR(20) NOT NULL,
   buyer_ref VARCHAR(40) UNIQUE,
   request_id VARCHAR(80) NOT NULL UNIQUE,
   po_number VARCHAR(100),
   cases INTEGER,
   units INTEGER NOT NULL,
   status VARCHAR(30) NOT NULL,
   created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
   updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
   CONSTRAINT fk_supplier_product
       FOREIGN KEY (product_id)
       REFERENCES inventory(product_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_supplier_open_reorder_product
ON supplier_orders(product_id)
WHERE status IN (
   'PENDING',
   'ACCEPTED',
   'PICKING',
   'SHIPPED',
   'UNKNOWN'
);


-- =====================================================
-- NOTIFICATIONS
-- =====================================================

CREATE TABLE notifications (
    notification_id BIGSERIAL PRIMARY KEY,
    message VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS channel_feed_state (
    id SMALLINT PRIMARY KEY,
    last_cursor BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT channel_feed_state_singleton
        CHECK (id = 1)
);

INSERT INTO channel_feed_state (
    id,
    last_cursor
)
VALUES (
    1,
    0
)
ON CONFLICT (id) DO NOTHING;


CREATE TABLE IF NOT EXISTS channel_processed_events (
    event_id VARCHAR(100) PRIMARY KEY,
    seq BIGINT NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    tiangge_order_id VARCHAR(40) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);


CREATE TABLE IF NOT EXISTS channel_orders (
    tiangge_order_id VARCHAR(40) PRIMARY KEY,
    shop_order_id BIGINT,
    decision VARCHAR(20),
    decision_sent BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(30) NOT NULL DEFAULT 'NEW',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);


CREATE TABLE IF NOT EXISTS channel_stock_updates (
    id BIGSERIAL PRIMARY KEY,
    seller_sku VARCHAR(40) NOT NULL,
    available INTEGER NOT NULL,
    blocked_by_order_id VARCHAR(40),
    sent BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT channel_stock_nonnegative
        CHECK (available >= 0)
);


CREATE INDEX IF NOT EXISTS idx_channel_events_seq
ON channel_processed_events(seq);


CREATE INDEX IF NOT EXISTS idx_channel_stock_pending
ON channel_stock_updates(sent, blocked_by_order_id);


-- =====================================================
-- SEED INVENTORY
-- =====================================================

INSERT INTO inventory (product_id, name, stock)
VALUES
('P100', 'Wireless Mouse', 25),
('P200', 'Mechanical Keyboard', 10),
('P300', 'USB-C Hub', 0);

CREATE TABLE IF NOT EXISTS channel_feed_state (
    id SMALLINT PRIMARY KEY,
    last_cursor BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT channel_feed_state_singleton
        CHECK (id = 1)
);

INSERT INTO channel_feed_state (
    id,
    last_cursor
)
VALUES (
    1,
    0
)
ON CONFLICT (id) DO NOTHING;


CREATE TABLE IF NOT EXISTS channel_processed_events (
    event_id VARCHAR(100) PRIMARY KEY,
    seq BIGINT NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    tiangge_order_id VARCHAR(40) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);


CREATE TABLE IF NOT EXISTS channel_orders (
    tiangge_order_id VARCHAR(40) PRIMARY KEY,
    shop_order_id BIGINT,
    decision VARCHAR(20),
    decision_sent BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(30) NOT NULL DEFAULT 'NEW',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);


CREATE TABLE IF NOT EXISTS channel_stock_updates (
    id BIGSERIAL PRIMARY KEY,
    seller_sku VARCHAR(40) NOT NULL,
    available INTEGER NOT NULL,
    blocked_by_order_id VARCHAR(40),
    sent BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT channel_stock_nonnegative
        CHECK (available >= 0)
);


CREATE INDEX IF NOT EXISTS idx_channel_events_seq
ON channel_processed_events(seq);


CREATE INDEX IF NOT EXISTS idx_channel_stock_pending
ON channel_stock_updates(
    sent,
    blocked_by_order_id
);