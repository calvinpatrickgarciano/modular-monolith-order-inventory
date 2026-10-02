# Lab 3 and Lab 4 Reflection

# Lab 3 - LegacySupply Reflection

## 1. LegacySupply holds more than one order for BuyerRef "RO-null": PO-100184 (20:36:38) and PO-100185 (20:39:10). Reconstruct the sequence of events that produced the duplicate, and describe the change you made (or would make) so it cannot happen again.

The duplicate happened because the BuyerRef was created using the supplier order ID before the supplier order had been saved to the database. Since the database had not generated the ID yet, the BuyerRef became `RO-null`. Another low-stock event later created another supplier order with the same `RO-null` BuyerRef, so LegacySupply accepted two different purchase orders. I fixed this by saving the supplier order first and only then creating its BuyerRef using the generated ID, such as `RO-1`. I also check for an existing open reorder before creating another one so the same product will not create unnecessary duplicate supplier orders.

## 2. At 23:38:08 your request for BuyerRef "RO-17" (X-Request-Id 12521d1b-977f-41fa-8fee-0003f6b46475) received a 503, but LegacySupply had already created PO-100385. Walk through exactly what your adapter did next, and explain why that did or did not result in a second order.

When the request for `RO-17` received a 503, my adapter did not create a new BuyerRef or generate a new X-Request-Id. The request ID is stored with the supplier order in PostgreSQL, so every retry of the same local supplier order uses the same `12521d1b-977f-41fa-8fee-0003f6b46475` value. The client retried the request using the same order identity even though LegacySupply had already created PO-100385. Because the retry used the same idempotency information, it was treated as a replay of the same purchase order instead of a new logical order. If LegacySupply stayed unavailable, the local supplier order would remain `PENDING` and the scheduler would retry that same stored order later.

## 3. PO-100069 (BuyerRef "MANUAL-RO-003") ended with StatusCode 90, which is not in the documentation. How did you work out what it means, and what does your system now do with the stock that will never arrive?

I worked out the meaning of StatusCode 90 by observing the result of PO-100069 and how LegacySupply behaved after that status appeared. The purchase order stopped progressing and the expected stock never arrived, so I treated StatusCode 90 as a cancelled supplier order. My status mapping now converts code `90` to `CANCELLED`. A cancelled supplier order does not publish a delivery event, so Inventory does not add stock that was never actually delivered. The cancelled order is therefore treated as a terminal supplier order instead of continuing to wait for stock.

# Lab 4 - Marketplace Reflection

## 1. Tiangge order TG-EAYXJC (2 x P100 and 3 x P200) was accepted at 23:40:47. At that moment your last published stock for P100 was 0, and the stock Tiangge worked out from your own decisions, cancellations and deliveries was 0. Where did your application's stock figure come from, and why did it disagree?

My application got its stock figure from the local `Inventory` database through the Inventory service, not from Tiangge's last published stock value. The order-processing logic checks the current quantity stored locally when deciding whether an order can be filled. At that moment, my local Inventory already showed available P100 stock, while Tiangge still had P100 as 0 based on the stock information it had received. This meant the local Inventory and the stock visible to Tiangge were temporarily out of sync because a local stock change had not yet been reflected successfully on the marketplace side. I later used a durable stock outbox and controlled the ordering of Tiangge decisions, resolutions, and stock publications so marketplace stock updates would follow the inventory changes more safely.

## 2. Event evt_dc4bc8e8fe62c4c0 (order TG-C7AL9S) reached your application twice, as seq 1 and seq 4, and you processed it once. Show the code and the stored data that made the second delivery harmless, and explain what would happen if your application restarted between the two.

Before processing a Tiangge event, my feed processor checks whether the event ID has already been processed:

```java
if (stateStore.isEventProcessed(event.eventId())) {
    stateStore.advanceCursor(event.seq());
    continue;
}
```

`ChannelStateStore.isEventProcessed()` checks PostgreSQL using the event ID:

```sql
SELECT COUNT(*)
FROM channel_processed_events
WHERE event_id = ?
```

The stored data for this event in my database was:

```text
event_id:         evt_dc4bc8e8fe62c4c0
seq:              1
event_type:       ORDER_PLACED
tiangge_order_id: TG-C7AL9S
```

After the first successful processing, the event ID was stored in `channel_processed_events`, and the insert uses `ON CONFLICT (event_id) DO NOTHING`. When the same event appeared again as seq 4, the application found that the event ID already existed and skipped the business operation, so the order was not processed twice. If the application restarted between seq 1 and seq 4, the same protection would still work because the processed event ID and feed cursor are stored in PostgreSQL instead of only in application memory.

## 3. Order TG-46Q2VB was backordered at 22:56:01 and accepted at 23:05:54, after PO-100372 was delivered at 23:01:59. Trace how the delivery reached your Inventory and what then resumed the backordered order.

My LegacySupply scheduler continued polling PO-100372 until LegacySupply reported that it had changed to the delivered status. `LegacySupplyGateway` detected the transition to `DELIVERED`, saved the new supplier order status, and published a `SupplierOrderDeliveredEvent` containing the product and delivered quantity. The Inventory delivery listener handled that event first and added the delivered units to the local Inventory. After Inventory had been restocked, `TianggeBackorderResolver` received the delivery event and scheduled another backorder-resolution scan. The resolver called `resolveBackorder()` again, and because the required stock was now available, the local order became `CONFIRMED`. The application then sent an `ACCEPTED` resolution to Tiangge, marked the marketplace order as resolved, and released the related stock update afterward.