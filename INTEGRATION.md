# LegacySupply Integration

## 1. Product Mapping

The following mappings were taken from my own LegacySupply catalog.

| Inventory Product ID | Product Name | SupplierSku | PackSize |
|---|---|---|---:|
| P100 | Wireless Mouse | MHY-8821 | 6 |
| P200 | Mechanical Keyboard | MHY-1706 | 24 |
| P300 | USB-C Hub | MHY-6162 | 6 |

LegacySupply returned the following descriptions:

- `MHY-8821` - WIRELESS MOUSE 2.4GHZ
- `MHY-1706` - KEYBOARD MECH TKL
- `MHY-6162` - USB HUB 4-PORT

Supplier-specific values such as `SupplierSku` and `PackSize` remain inside the `edu.cit.garciano.supplier` Anti-Corruption Layer.

The Order and Inventory modules continue using their own internal product IDs such as `P100`, `P200`, and `P300`.

---

## 2. Session Behaviour

LegacySupply uses short-lived sessions.

A session is created using:

```text
POST /auth/token
```

The returned session token is sent on authenticated requests using:

```text
X-LS-Session
```

### Observed Behaviour

During testing, I successfully obtained new sessions several times and also observed LegacySupply rejecting expired sessions with:

```text
E-AUTH-07
```

The LegacySupply verification page also confirmed that my integration successfully renewed expired sessions.

One observed sequence was:

```text
20:52:47 - POST /auth/token returned 200 OK
20:53:57 - authenticated status request still returned 200 OK
20:54:59 - authenticated request returned 401 E-AUTH-07
```

### Session Measurement

**Session start time:** `20:52:47`

**Last successful request time:** `20:53:57`

**First rejected request time:** `20:54:59`

The session was therefore confirmed valid for at least:

```text
1 minute 10 seconds
```

and had expired by:

```text
2 minutes 12 seconds
```

after it was created.

Because the exact expiry occurred somewhere between the last successful request and the first rejected request, the observed session lifetime was approximately two minutes.

### Session Renewal

The Supplier adapter does not depend on a fixed session duration.

If an authenticated LegacySupply request returns HTTP `401`, the current cached session is discarded and the client authenticates again.

The failed operation is then retried using the new session.

This allows the integration to recover automatically when a LegacySupply session expires.

---

## 3. Errors Observed

The following errors were encountered or observed while testing LegacySupply.

| Error Code | HTTP Status | Meaning / Situation |
|---|---:|---|
| E-FMT-01 | 415 | Unsupported media type or incorrect request body format. |
| E-FMT-02 | 400 | Malformed XML request. |
| E-AUTH-02 | 401 | Authenticated request was made without a valid session header. |
| E-AUTH-07 | 401 | The LegacySupply session had expired or was no longer valid. |
| E-SYS-50 | 503 | LegacySupply returned a processing error. |
| E-SYS-99 | 503 | LegacySupply was temporarily unavailable. |

During some `503` failures, `GET /ping` could still return `200 OK`.

This showed that the LegacySupply service could still be reachable while a specific operation such as authentication or purchase-order processing was temporarily unavailable.

The final integration treats temporary server failures differently from permanent application errors.

Temporary failures keep the supplier reorder locally so it can be retried later.

---

## 4. Qty and Uom

`Qty` is the number of supplier packages being ordered.

It does not represent the number of individual inventory units.

LegacySupply returned:

```text
Uom = CS
```

Therefore, the supplier quantity is expressed in cases.

`PackSize` describes how many individual inventory units are inside one supplier case.

### Worked Example

For:

```text
P100 - Wireless Mouse
SupplierSku = MHY-8821
PackSize = 6
Uom = CS
```

If Inventory needs:

```text
13 individual units
```

the Supplier ACL calculates:

```text
13 / 6 = 2.17
```

LegacySupply only accepts whole cases, so the value must be rounded up:

```text
Qty = 3 cases
```

The actual number of inventory units ordered becomes:

```text
3 × 6 = 18 units
```

Therefore, Inventory can request units using its own model while the Supplier ACL converts those units into LegacySupply cases.

---

## 5. LegacySupply Interface

Base URL:

```text
https://legacysupply.onrender.com/api/v1
```

LegacySupply exchanges XML encoded in UTF-8.

Requests containing an XML body use:

```text
Content-Type: application/xml
```

Authenticated requests use:

```text
X-LS-Session: <session-token>
```

Purchase-order creation also uses:

```text
X-Request-Id: <unique-request-id>
```

The health-check endpoint is:

```text
GET /ping
```

and does not require a LegacySupply session.

---

## 6. Authentication

Authentication uses:

```text
POST /auth/token
```

Example request:

```xml
<AuthRequest>
    <ClientId>YOUR-STUDENT-ID</ClientId>
    <ApiKey>YOUR-API-KEY</ApiKey>
</AuthRequest>
```

A successful response contains a session token.

The application stores the token inside `LegacySupplyClient`.

The token is reused until LegacySupply rejects it.

If LegacySupply returns HTTP `401`, the client clears the current token, authenticates again, and retries the request.

My LegacySupply self-check confirmed that expired sessions were encountered and renewed successfully.

---

## 7. Catalog Discovery

The supplier catalog is retrieved using:

```text
GET /catalog
```

Some products observed in my LegacySupply catalog were:

| SupplierSku | Description | PackSize |
|---|---|---:|
| MHY-7624 | USB-C CABLE 1M BRAIDED | 20 |
| MHY-8821 | WIRELESS MOUSE 2.4GHZ | 6 |
| MHY-1706 | KEYBOARD MECH TKL | 24 |
| MHY-6162 | USB HUB 4-PORT | 6 |
| MHY-5170 | SSD EXT 1TB | 10 |
| MHY-8658 | HEADSET W/ MIC | 10 |
| MHY-3552 | WEBCAM 1080P | 24 |
| MHY-6508 | MOUSE PAD XL | 24 |
| MHY-6971 | CHARGER GAN 65W | 20 |
| MHY-5398 | FLASH DRIVE 64GB | 20 |

The application does not expose these LegacySupply SKUs directly to the Inventory module.

They are translated through `LegacyProductMapping`.

---

## 8. Anti-Corruption Layer

LegacySupply is isolated behind an Anti-Corruption Layer located in:

```text
edu.cit.garciano.supplier
```

The public interface used by the rest of the application is:

```text
SupplierGateway
```

The supplier module also exposes its own domain types such as:

```text
SupplierOrderResult
SupplierOrderStatus
SupplierOrderDeliveredEvent
```

LegacySupply-specific implementation details remain inside the supplier package.

Examples include:

```text
LegacySupplyClient
LegacySupplyGateway
LegacySupplyXml
LegacyProductMapping
LegacyPurchaseOrderAck
LegacyPurchaseOrderStatus
SupplierOrder
SupplierOrderRepository
SupplierScheduler
SupplierUnavailableException
```

Other modules therefore do not need to understand:

```text
LegacySupply XML
LegacySupply HTTP endpoints
LegacySupply session tokens
LegacySupply SupplierSku values
LegacySupply-specific response objects
```

This prevents the legacy supplier model from spreading into the rest of the modular monolith.

---

## 9. Automatic Low-Stock Reordering

The Inventory module publishes a `LowStockEvent` when inventory falls below the configured low-stock threshold.

`LowStockReorderListener` receives the event and calculates how many units are needed.

Instead of directly communicating with LegacySupply, the listener calls:

```text
SupplierGateway.reorder(...)
```

The Supplier ACL then converts the internal product ID and required units into the format required by LegacySupply.

For example:

```text
Inventory product = P100
SupplierSku = MHY-8821
PackSize = 6
```

This keeps the Inventory module independent of LegacySupply-specific details.

---

## 10. Supplier Order Persistence

Supplier reorders are stored locally in:

```text
supplier_orders
```

The table contains:

```text
id
product_id
buyer_ref
request_id
po_number
cases
units
status
created_at
updated_at
```

The local row is created before the purchase order is sent to LegacySupply.

This is important because a supplier reorder must not disappear if LegacySupply is unavailable.

For example, a reorder may remain:

```text
PENDING
```

until the external supplier becomes available again.

---

## 11. BuyerRef

Every supplier reorder needs its own unique `BuyerRef`.

The final implementation first saves the `SupplierOrder` in the database so that PostgreSQL generates its ID.

After the ID exists, the application creates:

```text
RO-{supplierOrderId}
```

For example:

```text
SupplierOrder ID = 1
BuyerRef = RO-1
```

### Duplicate Discovered During Testing

During an earlier version of the implementation, BuyerRef was generated before the local supplier order had received its database-generated ID.

Because the ID was still `null`, two purchase orders were sent with:

```text
BuyerRef = RO-null
```

LegacySupply recorded:

```text
PO-100184 - RO-null
PO-100185 - RO-null
```

The final implementation fixes this by saving the local supplier order first and generating BuyerRef only after the ID is available.

---

## 12. X-Request-Id and Idempotency

Every LegacySupply purchase-order request includes:

```text
X-Request-Id
```

The application generates the request ID once when the local supplier order is created.

The request ID is then stored in the `supplier_orders` table.

If the same supplier order must be retried, the application reuses the stored request ID instead of generating a new one.

This provides idempotency across:

```text
temporary failures
HTTP retries
scheduled retries
application restarts
```

The LegacySupply self-check confirmed:

```text
14 of 14 order requests contained X-Request-Id
```

It also recorded a safe replay, showing that LegacySupply was able to recognize one repeated request without creating another supplier order.

---

## 13. Duplicate Protection

The final implementation uses multiple levels of duplicate protection.

Before creating a new supplier reorder, `LegacySupplyGateway` checks whether the same product already has an open order with one of these statuses:

```text
PENDING
ACCEPTED
PICKING
SHIPPED
UNKNOWN
```

If an open reorder already exists, the existing supplier order is returned instead of creating another one.

The database also contains the following partial unique index:

```sql
CREATE UNIQUE INDEX IF NOT EXISTS uq_supplier_open_reorder_product
ON supplier_orders(product_id)
WHERE status IN (
    'PENDING',
    'ACCEPTED',
    'PICKING',
    'SHIPPED',
    'UNKNOWN'
);
```

This prevents two open supplier reorders from existing for the same product.

The combination of:

```text
unique BuyerRef
persistent X-Request-Id
existing-open-order checking
database unique index
```

protects the final implementation from duplicate supplier reorders.

The LegacySupply checker still contains one historical duplicate created by the earlier `RO-null` implementation, but the cause was identified and corrected.

---

## 14. Purchase Order Creation

Purchase orders are sent using:

```text
POST /purchase-orders
```

Example request:

```xml
<PurchaseOrder>
    <SupplierSku>MHY-8821</SupplierSku>
    <Qty>1</Qty>
    <BuyerRef>RO-1</BuyerRef>
</PurchaseOrder>
```

The request also contains:

```text
X-LS-Session
X-Request-Id
```

LegacySupply returns information such as:

```text
PoNumber
StatusCode
SupplierSku
Qty
Uom
BuyerRef
CreatedAt
```

One automatic supplier order created by the application was:

```text
PO-100202
BuyerRef = RO-1
SupplierSku = MHY-8821
Qty = 1 case
Units represented = 6
```

---

## 15. Resilience

`LegacySupplyClient` uses short network timeouts so the application does not wait indefinitely for the supplier.

The request timeout is approximately:

```text
3 seconds
```

A supplier operation is attempted no more than:

```text
3 times
```

Short backoff delays are used between attempts.

Temporary failures such as:

```text
HTTP 429
HTTP 503
E-SYS-50
E-SYS-99
```

do not cause the application to create a new supplier reorder.

If LegacySupply remains unavailable after the immediate attempts, the local order remains:

```text
PENDING
```

The scheduler can try that same order again later.

Most importantly, the same stored:

```text
BuyerRef
X-Request-Id
SupplierOrder row
```

is reused.

---

## 16. Outage Recovery

During manual contract-discovery testing, the following purchase order was blocked while LegacySupply was unavailable:

```text
BuyerRef = MANUAL-RO-001
```

The request was rejected at approximately:

```text
19:20:20
```

After LegacySupply recovered, the purchase order was successfully placed as:

```text
PO-100037
```

at approximately:

```text
19:37:01
```

During the manual test, I kept the same purchase-order information and retried the same logical request.

In the final application, this behavior is automatic.

If an automatic reorder cannot be sent, it remains stored in:

```text
supplier_orders
```

with status:

```text
PENDING
```

`SupplierScheduler` later calls:

```text
retryPendingOrders()
```

which retries the stored supplier order.

This prevents low-stock reorders from being lost during an external supplier outage.

---

## 17. Scheduler

`SupplierScheduler` is responsible for background supplier processing.

It performs two main operations:

```text
retryPendingOrders()
trackOpenOrders()
```

The retry operation attempts to resend local orders that are still `PENDING`.

The tracking operation checks the current state of purchase orders that have already been accepted by LegacySupply.

The configured delay is approximately:

```text
60 seconds
```

This keeps polling at a reasonable rate.

The LegacySupply self-check confirmed:

```text
11 status checks
0 rate-limited requests
```

Therefore, the scheduler was able to track supplier orders without hitting the LegacySupply rate limit.

---

## 18. Status Mapping

The Supplier ACL converts LegacySupply numeric status codes into application-owned statuses.

| LegacySupply StatusCode | SupplierOrderStatus |
|---:|---|
| 10 | ACCEPTED |
| 20 | PICKING |
| 30 | SHIPPED |
| 40 | DELIVERED |
| 90 | CANCELLED |
| Any other value | UNKNOWN |

The original LegacySupply documentation described status codes:

```text
10
20
30
40
```

During actual testing, however, the following order returned an undocumented status:

```text
PO-100069
BuyerRef = MANUAL-RO-003
StatusCode = 90
```

The LegacySupply verification information showed that this order represented stock that would never arrive.

Based on this observed behavior, the final integration maps:

```text
StatusCode 90 -> CANCELLED
```

This mapping was added only after observing the real LegacySupply behavior.

---

## 19. Cancelled Supplier Orders

A cancelled supplier order is considered a terminal supplier state.

When LegacySupply returns:

```text
StatusCode = 90
```

the local order is stored as:

```text
CANCELLED
```

A cancelled supplier order does not generate:

```text
SupplierOrderDeliveredEvent
```

Therefore, the Inventory module does not add units that were never delivered.

The cancelled order also does not continue through the normal delivery-tracking process.

This protects the inventory quantity from being increased incorrectly.

---

## 20. Unknown Status Handling

If LegacySupply returns a status code that the application does not recognize, the ACL maps it to:

```text
UNKNOWN
```

An unknown status is not treated as delivered.

Therefore:

```text
no delivery event is published
no inventory is added
```

The order may continue to be tracked until LegacySupply returns a recognized terminal state.

This allows the system to safely handle future undocumented LegacySupply status values.

---

## 21. Delivery Tracking

Open supplier orders are checked using:

```text
GET /purchase-orders/{PoNumber}
```

The scheduler updates the local `SupplierOrderStatus` based on the LegacySupply response.

When an order changes to:

```text
DELIVERED
```

the supplier module publishes:

```text
SupplierOrderDeliveredEvent
```

The event contains:

```text
supplierOrderId
productId
unitsDelivered
```

The Inventory module listens for this event and restocks the product.

The event is only published when the supplier order transitions to `DELIVERED`.

This avoids repeatedly restocking the same delivered purchase order.

---

## 22. End-to-End Example

An automatic reorder was successfully created for:

```text
P100 - Wireless Mouse
```

The application created:

```text
PO-100202
BuyerRef = RO-1
SupplierSku = MHY-8821
Qty = 1 case
Units = 6
```

The purchase order was tracked until LegacySupply returned:

```text
StatusCode = 40
```

which was mapped to:

```text
DELIVERED
```

Before the supplier delivery, P100 inventory was:

```text
4 units
```

The supplier delivered one case containing:

```text
6 units
```

After `SupplierOrderDeliveredEvent` was processed, the P100 inventory became:

```text
10 units
```

The Notification module also recorded a message similar to:

```text
Supplier delivery received for P100. 6 units were added to inventory.
```

This demonstrates the complete integration flow:

```text
Inventory falls below threshold
        ↓
LowStockEvent
        ↓
LowStockReorderListener
        ↓
SupplierGateway
        ↓
LegacySupplyGateway
        ↓
LegacySupply purchase order
        ↓
SupplierScheduler tracks order
        ↓
StatusCode 40 / DELIVERED
        ↓
SupplierOrderDeliveredEvent
        ↓
Inventory restock
        ↓
Notification
```

---

## 23. Manual Purchase Orders Used for Contract Discovery

The following manual purchase orders were observed during LegacySupply contract discovery:

| PoNumber | BuyerRef | SupplierSku | Qty | Final StatusCode |
|---|---|---|---:|---:|
| PO-100037 | MANUAL-RO-001 | MHY-8821 | 1 | 40 |
| PO-100068 | MANUAL-RO-002 | MHY-1706 | 1 | 40 |
| PO-100069 | MANUAL-RO-003 | MHY-1706 | 1 | 90 |

These requests helped confirm:

```text
authentication behavior
XML request format
BuyerRef behavior
X-Request-Id behavior
Qty and Uom meaning
outage behavior
delivery tracking
undocumented cancellation behavior
```

---

## 24. LegacySupply Verification Results

The LegacySupply verification page recorded the following results during testing:

| Integration Check | Result |
|---|---|
| Signed in to LegacySupply | Met |
| Read the catalog | Met |
| Placed at least 3 purchase orders | Met |
| Renews expired sessions | Met |
| Sends X-Request-Id on every order | Met |
| Orders blocked by an outage were placed later | Met |
| Tracked an order to delivered | Met |
| Polls without hitting the rate limit | Met |

The verification page recorded:

```text
19 sign-ins
3 catalog reads
6 purchase orders on file
12 requests using an expired session
14 of 14 order requests with X-Request-Id
1 blocked order successfully placed later
2 delivered orders observed
11 status checks
0 rate-limited requests
```

One duplicate was also recorded from an earlier version of the integration:

```text
PO-100184
PO-100185
BuyerRef = RO-null
```

The cause was identified as generating BuyerRef before the database-generated supplier order ID existed.

The final implementation corrected this issue by:

```text
saving the supplier order before generating BuyerRef
checking for an existing open reorder
reusing a persistent X-Request-Id
adding a database unique index for open reorders
```

The verification traffic also revealed a cancelled supplier order:

```text
PO-100069
StatusCode = 90
```

The final application now handles this condition using:

```text
SupplierOrderStatus.CANCELLED
```

and does not restock inventory for the cancelled order.

---

## 25. Final Integration Behaviour

The final LegacySupply integration satisfies the main design goals of the laboratory activity.

The application:

- keeps LegacySupply-specific details inside the Supplier Anti-Corruption Layer;
- maps internal product IDs to supplier SKUs;
- converts inventory units into supplier cases;
- obtains and automatically renews short-lived LegacySupply sessions;
- sends an `X-Request-Id` with purchase-order requests;
- stores supplier reorders before communicating with the external supplier;
- preserves pending reorders during outages;
- retries pending supplier orders using the same stored identity;
- prevents multiple open reorders for the same product;
- periodically tracks supplier purchase orders;
- safely handles undocumented supplier statuses;
- treats StatusCode `90` as a cancelled supplier order based on observed behavior;
- publishes a delivery event only after a supplier order is actually delivered;
- and restocks Inventory only for units that actually arrive.

The Supplier ACL therefore protects the rest of the modular monolith from LegacySupply-specific XML, HTTP, session, SKU, and status-code details while still providing reliable supplier integration.