package edu.cit.garciano.channel;

import edu.cit.garciano.inventory.InventoryService;

import edu.cit.garciano.shop.CancelOrderResponse;
import edu.cit.garciano.shop.OrderService;
import edu.cit.garciano.shop.PlaceOrderRequest;
import edu.cit.garciano.shop.PlaceOrderResponse;

import edu.cit.garciano.supplier.SupplierGateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
class TianggeOrderBridge {

    private static final Logger log =
            LoggerFactory.getLogger(
                    TianggeOrderBridge.class
            );

    private final OrderService orderService;
    private final InventoryService inventoryService;
    private final SupplierGateway supplierGateway;
    private final ChannelStateStore stateStore;

    TianggeOrderBridge(
            OrderService orderService,
            InventoryService inventoryService,
            SupplierGateway supplierGateway,
            ChannelStateStore stateStore
    ) {

        this.orderService =
                orderService;

        this.inventoryService =
                inventoryService;

        this.supplierGateway =
                supplierGateway;

        this.stateStore =
                stateStore;
    }

    // =========================================================
    // ORDER PLACED
    // =========================================================

    @Transactional
    PreparedDecision prepareOrder(
            ChannelGateway.FeedEvent event
    ) {

        /*
         * Look for a previously-created local order.
         *
         * This protects against:
         *
         * - feed redelivery
         * - application restart
         * - Tiangge retrying the same logical order
         */
        var existing =
                stateStore.findOrder(
                        event.orderId()
                );

        if (
                existing.isPresent()
                        &&
                existing.get().shopOrderId() != null
                        &&
                existing.get().decision() != null
        ) {

            ChannelStateStore.ChannelOrderState existingOrder =
                    existing.get();

            // =================================================
            // DECISION ALREADY REACHED TIANGGE
            // =================================================

            /*
             * Do NOT change an already-sent decision.
             *
             * If the event is being replayed after a crash,
             * FeedProcessor will simply finish the durable
             * event bookkeeping.
             */
            if (existingOrder.decisionSent()) {

                log.info(
                        "Reusing already-sent decision {} "
                                + "for Tiangge order {}",
                        existingOrder.decision(),
                        event.orderId()
                );

                return new PreparedDecision(
                        existingOrder.shopOrderId(),
                        existingOrder.decision(),
                        true
                );
            }

            // =================================================
            // UNSENT BACKORDER MUST BE REVALIDATED
            // =================================================

            /*
             * Never blindly resend an old BACKORDERED
             * decision.
             *
             * The LegacySupply PO that justified it may have:
             *
             * - already delivered
             * - been cancelled
             * - moved to another terminal status
             *
             * Re-evaluate it using CURRENT Inventory and
             * CURRENT LegacySupply state.
             */
            if (
                    "BACKORDERED".equals(
                            existingOrder.decision()
                    )
            ) {

                return revalidateExistingBackorder(
                        event,
                        existingOrder
                );
            }

            // =================================================
            // UNSENT ACCEPTED / REJECTED
            // =================================================

            /*
             * ACCEPTED and REJECTED can safely be retried
             * with the same shopOrderId.
             */
            log.info(
                    "Retrying unsent {} decision "
                            + "for Tiangge order {}",
                    existingOrder.decision(),
                    event.orderId()
            );

            return new PreparedDecision(
                    existingOrder.shopOrderId(),
                    existingOrder.decision(),
                    false
            );
        }

        // =====================================================
        // BRAND NEW ORDER
        // =====================================================

        PlaceOrderRequest request =
                toPlaceOrderRequest(
                        event
                );

        // =====================================================
        // INVALID / EMPTY ORDER
        // =====================================================

        if (
                request.items() == null
                        ||
                request.items().isEmpty()
        ) {

            PlaceOrderResponse response =
                    orderService.placeOrder(
                            request
                    );

            saveRejectedDecision(
                    event,
                    response
            );

            return new PreparedDecision(
                    response.orderId(),
                    "REJECTED",
                    false
            );
        }

        /*
         * Validate quantities and product IDs.
         */
        for (
                PlaceOrderRequest.LineItemRequest item :
                request.items()
        ) {

            if (
                    item.quantity() <= 0
                            ||
                    inventoryService.getItem(
                            item.productId()
                    ) == null
            ) {

                PlaceOrderResponse response =
                        orderService.placeOrder(
                                request
                        );

                saveRejectedDecision(
                        event,
                        response
                );

                return new PreparedDecision(
                        response.orderId(),
                        "REJECTED",
                        false
                );
            }
        }

        Map<String, Integer> shortages =
                findShortages(
                        request
                );

        // =====================================================
        // ENOUGH STOCK NOW
        // =====================================================

        if (shortages.isEmpty()) {

            /*
             * placeOrder() is the final authority.
             *
             * It performs the actual locked Inventory
             * reservation.
             */
            PlaceOrderResponse response =
                    orderService.placeOrder(
                            request
                    );

            String decision =
                    "CONFIRMED".equals(
                            response.status()
                    )
                            ? "ACCEPTED"
                            : "REJECTED";

            stateStore.saveOrder(
                    event.orderId(),
                    response.orderId(),
                    decision,
                    "DECISION_PENDING"
            );

            log.info(
                    "Tiangge order {} can be filled immediately -> {}",
                    event.orderId(),
                    decision
            );

            return new PreparedDecision(
                    response.orderId(),
                    decision,
                    false
            );
        }

        // =====================================================
        // STOCK IS MISSING
        // =====================================================

        /*
         * Every product that is short must CURRENTLY have
         * supplier stock on the way.
         *
         * SupplierGateway.hasOpenReorder() refreshes
         * LegacySupply before returning true.
         */
        boolean everyShortageHasOpenReorder =
                shortages
                        .keySet()
                        .stream()
                        .allMatch(
                                supplierGateway::hasOpenReorder
                        );

        // =====================================================
        // VALID BACKORDER
        // =====================================================

        if (everyShortageHasOpenReorder) {

            PlaceOrderResponse response =
                    orderService.placeBackorder(
                            request,
                            "Waiting for existing LegacySupply delivery"
                    );

            stateStore.saveOrder(
                    event.orderId(),
                    response.orderId(),
                    "BACKORDERED",
                    "BACKORDERED"
            );

            log.info(
                    "Tiangge order {} became BACKORDERED "
                            + "because supplier stock is currently on the way",
                    event.orderId()
            );

            return new PreparedDecision(
                    response.orderId(),
                    "BACKORDERED",
                    false
            );
        }

        // =====================================================
        // NO QUALIFYING SUPPLIER PO
        // =====================================================

        /*
         * IMPORTANT:
         *
         * findShortages() was only a snapshot.
         *
         * Inventory may have changed while we were checking
         * LegacySupply.
         *
         * Example:
         *
         * 1. Inventory looked insufficient.
         * 2. We checked LegacySupply.
         * 3. A supplier delivery arrived.
         * 4. Inventory became sufficient.
         *
         * Therefore we MUST NOT automatically return REJECTED.
         *
         * orderService.placeOrder() performs the real,
         * pessimistically-locked Inventory reservation.
         *
         * Its result is the final authority.
         */

        PlaceOrderResponse response =
                orderService.placeOrder(
                        request
                );

        String decision =
                "CONFIRMED".equals(
                        response.status()
                )
                        ? "ACCEPTED"
                        : "REJECTED";

        stateStore.saveOrder(
                event.orderId(),
                response.orderId(),
                decision,
                "DECISION_PENDING"
        );

        if ("ACCEPTED".equals(decision)) {

            log.info(
                    "Tiangge order {} became ACCEPTED because "
                            + "Inventory became available during "
                            + "supplier revalidation",
                    event.orderId()
            );

        } else {

            log.info(
                    "Tiangge order {} rejected because stock is "
                            + "insufficient and no current supplier "
                            + "order is on the way",
                    event.orderId()
            );
        }

        return new PreparedDecision(
                response.orderId(),
                decision,
                false
        );
    }

    // =========================================================
    // REVALIDATE AN EXISTING UNSENT BACKORDER
    // =========================================================

    private PreparedDecision revalidateExistingBackorder(
            ChannelGateway.FeedEvent event,
            ChannelStateStore.ChannelOrderState existingOrder
    ) {

        PlaceOrderRequest request =
                toPlaceOrderRequest(
                        event
                );

        Map<String, Integer> shortages =
                findShortages(
                        request
                );

        // =====================================================
        // STOCK ARRIVED BEFORE WE SENT BACKORDERED
        // =====================================================

        if (shortages.isEmpty()) {

            PlaceOrderResponse response =
                    orderService.resolveBackorder(
                            existingOrder.shopOrderId()
                    );

            /*
             * The local order might already have been
             * cancelled during an earlier interrupted run.
             */
            if (
                    "CANCELLED".equals(
                            response.status()
                    )
            ) {

                stateStore.saveOrder(
                        event.orderId(),
                        existingOrder.shopOrderId(),
                        "REJECTED",
                        "DECISION_PENDING"
                );

                log.info(
                        "Old unsent BACKORDERED decision for {} "
                                + "became REJECTED because the local "
                                + "backorder was already cancelled",
                        event.orderId()
                );

                return new PreparedDecision(
                        existingOrder.shopOrderId(),
                        "REJECTED",
                        false
                );
            }

            if (
                    "CONFIRMED".equals(
                            response.status()
                    )
            ) {

                stateStore.saveOrder(
                        event.orderId(),
                        existingOrder.shopOrderId(),
                        "ACCEPTED",
                        "DECISION_PENDING"
                );

                log.info(
                        "Old unsent BACKORDERED decision for {} "
                                + "became ACCEPTED because stock "
                                + "is now available",
                        event.orderId()
                );

                return new PreparedDecision(
                        existingOrder.shopOrderId(),
                        "ACCEPTED",
                        false
                );
            }
        }

        // =====================================================
        // STILL SHORT:
        // CHECK LEGACYSUPPLY AGAIN
        // =====================================================

        boolean everyShortageStillHasOpenReorder =
                shortages
                        .keySet()
                        .stream()
                        .allMatch(
                                supplierGateway::hasOpenReorder
                        );

        if (everyShortageStillHasOpenReorder) {

            log.info(
                    "Revalidated BACKORDERED decision for {}: "
                            + "supplier stock is still on the way",
                    event.orderId()
            );

            return new PreparedDecision(
                    existingOrder.shopOrderId(),
                    "BACKORDERED",
                    false
            );
        }

        // =====================================================
        // OLD BACKORDER IS NO LONGER VALID
        // =====================================================

        /*
         * The original BACKORDERED decision was NEVER
         * successfully sent to Tiangge.
         *
         * We can therefore abandon the local backorder
         * and send the correct initial decision:
         *
         * REJECTED.
         *
         * cancelBackorder() does not restock anything
         * because backorders never reserved Inventory.
         */
        orderService.cancelBackorder(
                existingOrder.shopOrderId(),
                "Backorder abandoned before Tiangge decision "
                        + "because no supplier order remains open"
        );

        stateStore.saveOrder(
                event.orderId(),
                existingOrder.shopOrderId(),
                "REJECTED",
                "DECISION_PENDING"
        );

        log.info(
                "Old unsent BACKORDERED decision for {} "
                        + "changed to REJECTED because no "
                        + "qualifying supplier PO remains",
                event.orderId()
        );

        return new PreparedDecision(
                existingOrder.shopOrderId(),
                "REJECTED",
                false
        );
    }

    // =========================================================
    // CUSTOMER CANCELLATION
    // =========================================================

    @Transactional
    PreparedCancellation prepareCancellation(
            String tianggeOrderId
    ) {

        ChannelStateStore.ChannelOrderState order =
                stateStore.findOrder(
                                tianggeOrderId
                        )
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "No local order mapping for "
                                                        + tianggeOrderId
                                        )
                        );

        /*
         * Already cancelled locally.
         */
        if (
                "CANCELLATION_LOCAL_DONE".equals(
                        order.status()
                )
                        ||
                "CANCELLED_BY_CUSTOMER".equals(
                        order.status()
                )
        ) {

            return new PreparedCancellation(
                    order.shopOrderId(),
                    true
            );
        }

        // =====================================================
        // ACCEPTED ORDER
        // =====================================================

        if (
                "ACCEPTED".equals(
                        order.decision()
                )
                        ||
                "RESOLVED_ACCEPTED".equals(
                        order.status()
                )
        ) {

            CancelOrderResponse response =
                    orderService.cancelOrder(
                            order.shopOrderId()
                    );

            stateStore.updateOrderStatus(
                    tianggeOrderId,
                    "CANCELLATION_LOCAL_DONE"
            );

            log.info(
                    "Cancelled accepted Tiangge order {} "
                            + "and restored Inventory",
                    tianggeOrderId
            );

            return new PreparedCancellation(
                    response.orderId(),
                    true
            );
        }

        // =====================================================
        // BACKORDERED ORDER
        // =====================================================

        if (
                "BACKORDERED".equals(
                        order.decision()
                )
                        &&
                "BACKORDERED".equals(
                        order.status()
                )
        ) {

            CancelOrderResponse response =
                    orderService.cancelBackorder(
                            order.shopOrderId(),
                            "Customer cancelled Tiangge backorder"
                    );

            stateStore.updateOrderStatus(
                    tianggeOrderId,
                    "CANCELLATION_LOCAL_DONE"
            );

            log.info(
                    "Cancelled Tiangge backorder {}",
                    tianggeOrderId
            );

            return new PreparedCancellation(
                    response.orderId(),
                    true
            );
        }

        throw new IllegalStateException(
                "Tiangge order "
                        + tianggeOrderId
                        + " cannot be cancelled from decision="
                        + order.decision()
                        + ", status="
                        + order.status()
        );
    }

    // =========================================================
    // FIND CURRENT INVENTORY SHORTAGES
    // =========================================================

    private Map<String, Integer> findShortages(
            PlaceOrderRequest request
    ) {

        Map<String, Integer> requestedQuantities =
                aggregateQuantities(
                        request
                );

        Map<String, Integer> shortages =
                new LinkedHashMap<>();

        for (
                Map.Entry<String, Integer> entry :
                requestedQuantities.entrySet()
        ) {

            InventoryService.InventoryView inventory =
                    inventoryService.getItem(
                            entry.getKey()
                    );

            /*
             * Treat an unknown product as a full shortage.
             *
             * Normal validation should catch this before
             * this method is reached.
             */
            if (inventory == null) {

                shortages.put(
                        entry.getKey(),
                        entry.getValue()
                );

                continue;
            }

            int shortage =
                    entry.getValue()
                            - inventory.stock();

            if (shortage > 0) {

                shortages.put(
                        entry.getKey(),
                        shortage
                );
            }
        }

        return shortages;
    }

    // =========================================================
    // CONVERT TIANGGE -> SHOP REQUEST
    // =========================================================

    private PlaceOrderRequest toPlaceOrderRequest(
            ChannelGateway.FeedEvent event
    ) {

        List<PlaceOrderRequest.LineItemRequest> items =
                event.lines()
                        .stream()
                        .map(
                                line ->
                                        new PlaceOrderRequest.LineItemRequest(
                                                line.sellerSku(),
                                                line.quantity()
                                        )
                        )
                        .toList();

        return new PlaceOrderRequest(
                items
        );
    }

    // =========================================================
    // COMBINE DUPLICATE PRODUCT LINES
    // =========================================================

    private Map<String, Integer> aggregateQuantities(
            PlaceOrderRequest request
    ) {

        Map<String, Integer> quantities =
                new LinkedHashMap<>();

        for (
                PlaceOrderRequest.LineItemRequest item :
                request.items()
        ) {

            quantities.merge(
                    item.productId(),
                    item.quantity(),
                    Integer::sum
            );
        }

        return quantities;
    }

    // =========================================================
    // SAVE REJECTED MAPPING
    // =========================================================

    private void saveRejectedDecision(
            ChannelGateway.FeedEvent event,
            PlaceOrderResponse response
    ) {

        stateStore.saveOrder(
                event.orderId(),
                response.orderId(),
                "REJECTED",
                "DECISION_PENDING"
        );

        log.info(
                "Tiangge order {} became REJECTED",
                event.orderId()
        );
    }

    // =========================================================
    // INTERNAL RESULTS
    // =========================================================

    record PreparedDecision(
            Long shopOrderId,
            String decision,
            boolean alreadySent
    ) {
    }

    record PreparedCancellation(
            Long shopOrderId,
            boolean locallyRestocked
    ) {
    }
}