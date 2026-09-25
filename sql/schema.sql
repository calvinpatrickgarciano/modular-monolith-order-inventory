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


-- =====================================================
-- SEED INVENTORY
-- =====================================================

INSERT INTO inventory (product_id, name, stock)
VALUES
('P100', 'Wireless Mouse', 25),
('P200', 'Mechanical Keyboard', 10),
('P300', 'USB-C Hub', 0);