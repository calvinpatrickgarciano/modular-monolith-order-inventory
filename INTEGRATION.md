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

Supplier-specific values such as SupplierSku and PackSize will remain inside the `edu.cit.garciano.supplier` Anti-Corruption Layer. The Order and Inventory modules will continue using their own product IDs.

---

## 2. Session Behaviour

LegacySupply uses short-lived sessions.

A session is created using:

`POST /auth/token`

The returned token is sent on authenticated requests using:

`X-LS-Session`

### Observed Behaviour

I successfully obtained new sessions several times and also observed LegacySupply rejecting expired sessions with:

`E-AUTH-07 - Session not valid.`

The LegacySupply self-check page confirms that my integration has renewed expired sessions. It recorded multiple sign-ins and requests made using an expired session.

### Session Measurement

**Session start time:** TBD

**Last successful request time:** TBD

**First rejected request time:** TBD

**Measured session duration:** TBD

### Measurement Method

I will obtain one fresh session and record its `IssuedAt` value. I will then reuse the same session for repeated authenticated `GET /catalog` requests until LegacySupply returns `E-AUTH-07`.

The difference between the session start time and the first rejected request will be used as the observed session lifetime.

The Supplier adapter will not depend on a fixed session duration. Instead, it will request a new session automatically whenever LegacySupply stops accepting the current one.

---

## 3. Errors Observed

The following errors were actually encountered while manually testing LegacySupply.

| Error Code | HTTP Status | Cause |
|---|---:|---|
| E-FMT-01 | 415 | I initially sent an authentication request using an unsupported media/body format. |
| E-FMT-02 | 400 | I sent a malformed XML authentication document. |
| E-AUTH-02 | 401 | I sent an authenticated request without a session header LegacySupply accepted. |
| E-AUTH-07 | 401 | I reused a session after LegacySupply stopped accepting it. |
| E-SYS-50 | 503 | LegacySupply returned a processing error while I was requesting a session. |
| E-SYS-99 | 503 | LegacySupply was temporarily unavailable during authentication, purchase-order creation, and BuyerRef lookup testing. |

During some `503` failures, `GET /ping` still returned `200 OK`. This showed that LegacySupply could be reachable while an individual operation was unavailable.

---

## 4. Qty and Uom

`Qty` is the number of supplier packages being ordered. It does not necessarily mean individual inventory units.

`Uom` is the unit of measure LegacySupply uses.

My successful purchase order returned:

`Uom = CS`

Therefore, the supplier quantity is expressed in cases.

`PackSize` tells how many individual inventory units are contained inside one supplier case.

### Worked Example

For `P100 - Wireless Mouse`:

- SupplierSku: `MHY-8821`
- PackSize: `6`
- Uom: `CS`

If Inventory needs 13 individual units:

13 / 6 = 2.17

LegacySupply only accepts whole-number quantities, so the result must be rounded up.

Qty = 3 cases

The actual number of units delivered would be:

3 × 6 = 18 units

Therefore, Inventory can request 13 units in its own terms while the Supplier ACL converts that into 3 LegacySupply cases.

---

## 5. LegacySupply Interface

Base URL:

`https://legacysupply.onrender.com/api/v1`

LegacySupply exchanges XML encoded in UTF-8.

Requests containing a body use:

`Content-Type: application/xml`

Authenticated requests use:

`X-LS-Session: <session-token>`

The health-check endpoint is:

`GET /ping`

It does not require a session.

---

## 6. Authentication

Endpoint:

`POST /auth/token`

Request format:

```xml
<AuthRequest>
    <ClientId>YOUR-STUDENT-ID</ClientId>
    <ApiKey>YOUR-API-KEY</ApiKey>
</AuthRequest>