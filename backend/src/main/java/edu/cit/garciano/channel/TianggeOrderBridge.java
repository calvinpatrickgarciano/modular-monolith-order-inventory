package edu.cit.garciano.channel;

import edu.cit.garciano.inventory.InventoryService;

import edu.cit.garciano.shop.CancelOrderResponse;
import edu.cit.garciano.shop.OrderService;
import edu.cit.garciano.shop.PlaceOrderRequest;
import edu.cit.garciano.shop.PlaceOrderResponse;

import edu.cit.garciano.supplier.SupplierGateway;
import edu.cit.garciano.supplier.SupplierOrderResult;
import edu.cit.garciano.supplier.SupplierOrderStatus;

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
         * If this Tiangge order was already created
         * locally, reuse it.
         *
         * This prevents duplicate Shop orders after
         * retries or application restarts.
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

            ChannelStateStore.ChannelOrderState
                    order =
                    existing.get();

            return new PreparedDecision(
                    order.shopOrderId(),
                    order.decision(),
                    order.decisionSent()
            );
        }

        /*
         * Convert Tiangge lines into the same
         * request used by the React UI.
         */
        PlaceOrderRequest request =
                toPlaceOrderRequest(
                        event
                );

        /*
         * Validate quantities and products before
         * deciding whether supplier restock can help.
         *
         * Invalid requests go through the normal
         * OrderService and become REJECTED.
         */
        if (containsInvalidLine(request)) {

            PlaceOrderResponse response =
                    orderService.placeOrder(
                            request
                    );

            stateStore.saveOrder(
                    event.orderId(),
                    response.orderId(),
                    "REJECTED",
                    "DECISION_PENDING"
            );

            return new PreparedDecision(
                    response.orderId(),
                    "REJECTED",
                    false
            );
        }

        /*
         * Calculate the TOTAL quantity requested
         * for each product.
         */
        Map<String, Integer>
                requestedQuantities =
                aggregateQuantities(
                        request
                );

        Map<String, Integer>
                shortages =
                new LinkedHashMap<>();

        /*
         * Find which products cannot currently
         * be filled.
         */
        for (
                Map.Entry<String, Integer> entry :
                requestedQuantities.entrySet()
        ) {

            InventoryService.InventoryView inventory =
                    inventoryService.getItem(
                            entry.getKey()
                    );

            /*
             * Product validation above means this
             * normally cannot be null.
             */
            if (inventory == null) {

                PlaceOrderResponse response =
                        orderService.placeOrder(
                                request
                        );

                stateStore.saveOrder(
                        event.orderId(),
                        response.orderId(),
                        "REJECTED",
                        "DECISION_PENDING"
                );

                return new PreparedDecision(
                        response.orderId(),
                        "REJECTED",
                        false
                );
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

        // =====================================================
        // CASE 1:
        // EVERYTHING IS AVAILABLE NOW
        // =====================================================

        if (shortages.isEmpty()) {

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

            return new PreparedDecision(
                    response.orderId(),
                    decision,
                    false
            );
        }

        // =====================================================
        // CASE 2:
        // STOCK IS MISSING
        //
        // Make sure EVERY shortage has supplier stock
        // on the way before answering BACKORDERED.
        // =====================================================

        boolean everyShortageHasRestock =
                true;

        for (
                Map.Entry<String, Integer> shortage :
                shortages.entrySet()
        ) {

            SupplierOrderResult result;

            try {

                /*
                 * If an open reorder already exists,
                 * our Lab 3 gateway reuses it.
                 *
                 * Otherwise it attempts to create one.
                 */
                result =
                        supplierGateway.reorder(
                                shortage.getKey(),
                                shortage.getValue()
                        );

            } catch (RuntimeException exception) {

                log.warn(
                        "Unable to secure supplier restock for {}: {}",
                        shortage.getKey(),
                        exception.getMessage()
                );

                everyShortageHasRestock =
                        false;

                break;
            }

            if (
                    !isRestockOnTheWay(
                            result,
                            shortage.getValue()
                    )
            ) {

                everyShortageHasRestock =
                        false;

                break;
            }
        }

        // =====================================================
        // CASE 2A:
        // ALL MISSING PRODUCTS HAVE SUPPLIER STOCK COMING
        // =====================================================

        if (everyShortageHasRestock) {

            PlaceOrderResponse response =
                    orderService.placeBackorder(
                            request,
                            "Waiting for LegacySupply delivery"
                    );

            stateStore.saveOrder(
                    event.orderId(),
                    response.orderId(),
                    "BACKORDERED",
                    "BACKORDERED"
            );

            log.info(
                    "Tiangge order {} became BACKORDERED "
                            + "as supplier stock is on the way",
                    event.orderId()
            );

            return new PreparedDecision(
                    response.orderId(),
                    "BACKORDERED",
                    false
            );
        }

        // =====================================================
        // CASE 2B:
        // CANNOT FILL AND CANNOT GUARANTEE RESTOCK
        //
        // Use the normal OrderService so the rejection
        // is also a real order in our Order module.
        // =====================================================

        PlaceOrderResponse response =
                orderService.placeOrder(
                        request
                );

        stateStore.saveOrder(
                event.orderId(),
                response.orderId(),
                "REJECTED",
                "DECISION_PENDING"
        );

        return new PreparedDecision(
                response.orderId(),
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

        ChannelStateStore.ChannelOrderState
                order =
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
         * Do not restock twice.
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

        /*
         * Normal ACCEPTED order:
         * stock was reserved, so cancellation
         * restores Inventory.
         */
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

            return new PreparedCancellation(
                    response.orderId(),
                    true
            );
        }

        /*
         * A BACKORDERED order never reserved stock.
         *
         * Cancel it without restocking anything.
         */
        if (
                "BACKORDERED".equals(
                        order.decision()
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

            return new PreparedCancellation(
                    response.orderId(),
                    true
            );
        }

        throw new IllegalStateException(
                "Tiangge order "
                        + tianggeOrderId
                        + " cannot be cancelled from state "
                        + order.decision()
        );
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private PlaceOrderRequest toPlaceOrderRequest(
            ChannelGateway.FeedEvent event
    ) {

        List<PlaceOrderRequest.LineItemRequest>
                items =
                event.lines()
                        .stream()
                        .map(
                                line ->
                                        new PlaceOrderRequest
                                                .LineItemRequest(
                                                line.sellerSku(),
                                                line.quantity()
                                        )
                        )
                        .toList();

        return new PlaceOrderRequest(
                items
        );
    }

    private boolean containsInvalidLine(
            PlaceOrderRequest request
    ) {

        if (
                request.items() == null
                        ||
                request.items().isEmpty()
        ) {

            return true;
        }

        for (
                PlaceOrderRequest.LineItemRequest item :
                request.items()
        ) {

            if (item.quantity() <= 0) {
                return true;
            }

            if (
                    inventoryService.getItem(
                            item.productId()
                    ) == null
            ) {

                return true;
            }
        }

        return false;
    }

    private Map<String, Integer>
    aggregateQuantities(
            PlaceOrderRequest request
    ) {

        Map<String, Integer>
                quantities =
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

    /*
     * BACKORDERED is only valid if LegacySupply
     * actually has stock coming.
     *
     * PENDING is deliberately NOT included here.
     *
     * PENDING means our local reorder exists,
     * but LegacySupply may not have accepted the PO yet.
     */
    private boolean isRestockOnTheWay(
            SupplierOrderResult result,
            int unitsNeeded
    ) {

        if (result == null) {
            return false;
        }

        /*
         * The supplier order must contain enough
         * units to cover this shortage.
         */
        if (
                result.unitsOrdered()
                        < unitsNeeded
        ) {

            return false;
        }

        SupplierOrderStatus status =
                result.status();

        return status
                == SupplierOrderStatus.ACCEPTED

                || status
                == SupplierOrderStatus.PICKING

                || status
                == SupplierOrderStatus.SHIPPED

                /*
                 * UNKNOWN can still represent an
                 * acknowledged LegacySupply PO whose
                 * returned status code is unfamiliar.
                 */
                || status
                == SupplierOrderStatus.UNKNOWN;
    }

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