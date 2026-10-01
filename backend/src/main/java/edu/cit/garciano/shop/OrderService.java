package edu.cit.garciano.shop;

import edu.cit.garciano.inventory.InventoryService;
import edu.cit.garciano.shop.event.OrderCancelledEvent;
import edu.cit.garciano.shop.event.OrderPlacedEvent;
import edu.cit.garciano.shop.event.OrderRejectedEvent;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OrderService {

    private final InventoryService inventoryService;
    private final OrderRepository orderRepository;
    private final ApplicationEventPublisher eventPublisher;

    public OrderService(
            InventoryService inventoryService,
            OrderRepository orderRepository,
            ApplicationEventPublisher eventPublisher
    ) {
        this.inventoryService = inventoryService;
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
    }

    // =========================================================
    // NORMAL ORDER
    // =========================================================

    @Transactional
    public PlaceOrderResponse placeOrder(
            PlaceOrderRequest request
    ) {

        if (
                request.items() == null
                        || request.items().isEmpty()
        ) {

            return createRejectedOrder(
                    request,
                    "Order must contain at least one item",
                    null
            );
        }

        /*
         * Combine duplicate product quantities first.
         *
         * Example:
         *
         * P100 x 10
         * P100 x 20
         *
         * Validate against 30 total.
         */
        Map<String, Integer> requestedQuantities =
                new LinkedHashMap<>();

        for (
                PlaceOrderRequest.LineItemRequest item :
                request.items()
        ) {

            if (item.quantity() <= 0) {

                return createRejectedOrder(
                        request,
                        "Quantity must be greater than 0 for "
                                + item.productId(),
                        item.productId()
                );
            }

            requestedQuantities.merge(
                    item.productId(),
                    item.quantity(),
                    Integer::sum
            );
        }

        /*
         * STEP 1:
         * Validate the ENTIRE order before reserving.
         */
        for (
                Map.Entry<String, Integer> entry :
                requestedQuantities.entrySet()
        ) {

            String productId =
                    entry.getKey();

            int requestedQuantity =
                    entry.getValue();

            InventoryService.InventoryView inventory =
                    inventoryService.getItem(
                            productId
                    );

            if (inventory == null) {

                return createRejectedOrder(
                        request,
                        "Product not found: "
                                + productId,
                        productId
                );
            }

            if (
                    requestedQuantity
                            > inventory.stock()
            ) {

                return createRejectedOrder(
                        request,
                        "Insufficient stock for "
                                + productId
                                + ". Available: "
                                + inventory.stock(),
                        productId
                );
            }
        }

        /*
         * STEP 2:
         * Everything passed validation.
         * Reserve every item.
         */
        List<PlaceOrderResponse.ItemOutcome>
                outcomes =
                new ArrayList<>();

        for (
                PlaceOrderRequest.LineItemRequest item :
                request.items()
        ) {

            InventoryService.ReservationResult result =
                    inventoryService.reserve(
                            item.productId(),
                            item.quantity()
                    );

            /*
             * Normally this should never fail because
             * everything was validated first.
             *
             * Throwing here rolls back the whole transaction.
             */
            if (!result.success()) {

                throw new IllegalStateException(
                        "Reservation failed after validation for "
                                + item.productId()
                );
            }

            outcomes.add(
                    new PlaceOrderResponse.ItemOutcome(
                            item.productId(),
                            "RESERVED"
                    )
            );
        }

        /*
         * STEP 3:
         * Save the confirmed order.
         */
        Order order =
                new Order(
                        "CONFIRMED",
                        "All items reserved successfully"
                );

        for (
                PlaceOrderRequest.LineItemRequest item :
                request.items()
        ) {

            order.addItem(
                    new OrderItem(
                            item.productId(),
                            item.quantity()
                    )
            );
        }

        Order savedOrder =
                orderRepository.save(
                        order
                );

        /*
         * STEP 4:
         * Publish domain event.
         */
        eventPublisher.publishEvent(
                new OrderPlacedEvent(
                        savedOrder.getOrderId()
                )
        );

        return new PlaceOrderResponse(
                savedOrder.getOrderId(),
                "CONFIRMED",
                "All items reserved successfully",
                outcomes,
                inventoryService.getAllItems()
        );
    }

    // =========================================================
    // ORDER HISTORY
    // =========================================================

    @Transactional(readOnly = true)
    public List<OrderHistoryResponse> getOrders() {

        return orderRepository
                .findAll()
                .stream()
                .map(
                        order ->
                                new OrderHistoryResponse(
                                        order.getOrderId(),
                                        order.getStatus(),
                                        order.getReason(),
                                        order.getCreatedAt(),

                                        order.getItems()
                                                .stream()
                                                .map(
                                                        item ->
                                                                new OrderHistoryResponse
                                                                        .OrderItemView(
                                                                        item.getProductId(),
                                                                        item.getQuantity()
                                                                )
                                                )
                                                .toList()
                                )
                )
                .toList();
    }

    // =========================================================
    // NORMAL ORDER CANCELLATION
    // =========================================================

    @Transactional
    public CancelOrderResponse cancelOrder(
            Long orderId
    ) {

        Order order =
                orderRepository.findById(
                        orderId
                )
                        .orElseThrow(
                                () ->
                                        new OrderNotFoundException(
                                                "Order "
                                                        + orderId
                                                        + " does not exist"
                                        )
                        );

        if (
                "CANCELLED".equals(
                        order.getStatus()
                )
        ) {

            throw new OrderConflictException(
                    "Order "
                            + orderId
                            + " is already cancelled"
            );
        }

        if (
                !"CONFIRMED".equals(
                        order.getStatus()
                )
        ) {

            throw new OrderConflictException(
                    "Only confirmed orders can be cancelled"
            );
        }

        /*
         * Restore every reserved item.
         */
        for (
                OrderItem item :
                order.getItems()
        ) {

            inventoryService.restock(
                    item.getProductId(),
                    item.getQuantity()
            );
        }

        order.setStatus(
                "CANCELLED"
        );

        order.setReason(
                "Order cancelled and inventory restocked"
        );

        orderRepository.save(
                order
        );

        eventPublisher.publishEvent(
                new OrderCancelledEvent(
                        order.getOrderId()
                )
        );

        return new CancelOrderResponse(
                order.getOrderId(),
                order.getStatus(),
                order.getReason(),
                inventoryService.getAllItems()
        );
    }

    // =========================================================
    // LAB 4 - CREATE BACKORDER
    // =========================================================

    @Transactional
    public PlaceOrderResponse placeBackorder(
            PlaceOrderRequest request,
            String reason
    ) {

        if (
                request.items() == null
                        || request.items().isEmpty()
        ) {

            throw new IllegalArgumentException(
                    "Backorder must contain at least one item"
            );
        }

        /*
         * Validate all requested products.
         *
         * A backorder does NOT reserve stock yet.
         */
        for (
                PlaceOrderRequest.LineItemRequest item :
                request.items()
        ) {

            if (item.quantity() <= 0) {

                throw new IllegalArgumentException(
                        "Quantity must be greater than 0 for "
                                + item.productId()
                );
            }

            InventoryService.InventoryView inventory =
                    inventoryService.getItem(
                            item.productId()
                    );

            if (inventory == null) {

                throw new IllegalArgumentException(
                        "Product not found: "
                                + item.productId()
                );
            }
        }

        Order order =
                new Order(
                        "BACKORDERED",
                        reason
                );

        for (
                PlaceOrderRequest.LineItemRequest item :
                request.items()
        ) {

            order.addItem(
                    new OrderItem(
                            item.productId(),
                            item.quantity()
                    )
            );
        }

        Order savedOrder =
                orderRepository.save(
                        order
                );

        List<PlaceOrderResponse.ItemOutcome>
                outcomes =
                request.items()
                        .stream()
                        .map(
                                item ->
                                        new PlaceOrderResponse.ItemOutcome(
                                                item.productId(),
                                                "WAITING_FOR_STOCK"
                                        )
                        )
                        .toList();

        return new PlaceOrderResponse(
                savedOrder.getOrderId(),
                "BACKORDERED",
                reason,
                outcomes,
                inventoryService.getAllItems()
        );
    }

    // =========================================================
    // LAB 4 - RESOLVE BACKORDER
    // =========================================================

    @Transactional
    public PlaceOrderResponse resolveBackorder(
            Long orderId
    ) {

        Order order =
                orderRepository.findById(
                        orderId
                )
                        .orElseThrow(
                                () ->
                                        new OrderNotFoundException(
                                                "Order "
                                                        + orderId
                                                        + " does not exist"
                                        )
                        );

        /*
         * Already resolved successfully.
         */
        if (
                "CONFIRMED".equals(
                        order.getStatus()
                )
        ) {

            return new PlaceOrderResponse(
                    order.getOrderId(),
                    order.getStatus(),
                    order.getReason(),

                    order.getItems()
                            .stream()
                            .map(
                                    item ->
                                            new PlaceOrderResponse.ItemOutcome(
                                                    item.getProductId(),
                                                    "RESERVED"
                                            )
                            )
                            .toList(),

                    inventoryService.getAllItems()
            );
        }

        if (
                !"BACKORDERED".equals(
                        order.getStatus()
                )
        ) {

            throw new OrderConflictException(
                    "Order "
                            + orderId
                            + " is not backordered"
            );
        }

        /*
         * Combine duplicate product lines.
         */
        Map<String, Integer> requestedQuantities =
                new LinkedHashMap<>();

        for (
                OrderItem item :
                order.getItems()
        ) {

            requestedQuantities.merge(
                    item.getProductId(),
                    item.getQuantity(),
                    Integer::sum
            );
        }

        /*
         * Validate the whole backorder first.
         *
         * Do not reserve anything until every
         * product has enough stock.
         */
        for (
                Map.Entry<String, Integer> entry :
                requestedQuantities.entrySet()
        ) {

            InventoryService.InventoryView inventory =
                    inventoryService.getItem(
                            entry.getKey()
                    );

            if (
                    inventory == null
                            ||
                    inventory.stock()
                            < entry.getValue()
            ) {

                return new PlaceOrderResponse(
                        order.getOrderId(),
                        "BACKORDERED",
                        "Still waiting for enough inventory",

                        order.getItems()
                                .stream()
                                .map(
                                        item ->
                                                new PlaceOrderResponse
                                                        .ItemOutcome(
                                                        item.getProductId(),
                                                        "WAITING_FOR_STOCK"
                                                )
                                )
                                .toList(),

                        inventoryService.getAllItems()
                );
            }
        }

        /*
         * Everything is now available.
         *
         * Reserve all required stock.
         */
        for (
                Map.Entry<String, Integer> entry :
                requestedQuantities.entrySet()
        ) {

            InventoryService.ReservationResult result =
                    inventoryService.reserve(
                            entry.getKey(),
                            entry.getValue()
                    );

            if (!result.success()) {

                /*
                 * Causes the entire transaction to roll back.
                 */
                throw new IllegalStateException(
                        "Backorder reservation failed for "
                                + entry.getKey()
                );
            }
        }

        order.setStatus(
                "CONFIRMED"
        );

        order.setReason(
                "Backorder filled after supplier delivery"
        );

        orderRepository.save(
                order
        );

        eventPublisher.publishEvent(
                new OrderPlacedEvent(
                        order.getOrderId()
                )
        );

        return new PlaceOrderResponse(
                order.getOrderId(),
                "CONFIRMED",
                order.getReason(),

                order.getItems()
                        .stream()
                        .map(
                                item ->
                                        new PlaceOrderResponse.ItemOutcome(
                                                item.getProductId(),
                                                "RESERVED"
                                        )
                        )
                        .toList(),

                inventoryService.getAllItems()
        );
    }

    // =========================================================
    // LAB 4 - CANCEL BACKORDER
    // =========================================================

    @Transactional
    public CancelOrderResponse cancelBackorder(
            Long orderId,
            String reason
    ) {

        Order order =
                orderRepository.findById(
                        orderId
                )
                        .orElseThrow(
                                () ->
                                        new OrderNotFoundException(
                                                "Order "
                                                        + orderId
                                                        + " does not exist"
                                        )
                        );

        /*
         * Safe if retried after a restart.
         */
        if (
                "CANCELLED".equals(
                        order.getStatus()
                )
        ) {

            return new CancelOrderResponse(
                    order.getOrderId(),
                    order.getStatus(),
                    order.getReason(),
                    inventoryService.getAllItems()
            );
        }

        if (
                !"BACKORDERED".equals(
                        order.getStatus()
                )
        ) {

            throw new OrderConflictException(
                    "Only a backordered order can be cancelled this way"
            );
        }

        /*
         * IMPORTANT:
         *
         * Backorders never reserved stock.
         * Therefore there is nothing to restock.
         */
        order.setStatus(
                "CANCELLED"
        );

        order.setReason(
                reason
        );

        orderRepository.save(
                order
        );

        eventPublisher.publishEvent(
                new OrderCancelledEvent(
                        order.getOrderId()
                )
        );

        return new CancelOrderResponse(
                order.getOrderId(),
                order.getStatus(),
                order.getReason(),
                inventoryService.getAllItems()
        );
    }

    // =========================================================
    // REJECTED ORDER HELPER
    // =========================================================

    private PlaceOrderResponse createRejectedOrder(
            PlaceOrderRequest request,
            String reason,
            String failedProductId
    ) {

        Order order =
                new Order(
                        "REJECTED",
                        reason
                );

        /*
         * Store only valid known products.
         *
         * This avoids FK problems if the request
         * contains an invalid product ID.
         */
        if (request.items() != null) {

            for (
                    PlaceOrderRequest.LineItemRequest item :
                    request.items()
            ) {

                if (
                        item.quantity() > 0
                                &&
                        inventoryService.getItem(
                                item.productId()
                        ) != null
                ) {

                    order.addItem(
                            new OrderItem(
                                    item.productId(),
                                    item.quantity()
                            )
                    );
                }
            }
        }

        Order savedOrder =
                orderRepository.save(
                        order
                );

        List<PlaceOrderResponse.ItemOutcome>
                outcomes =
                new ArrayList<>();

        if (request.items() != null) {

            for (
                    PlaceOrderRequest.LineItemRequest item :
                    request.items()
            ) {

                String outcome;

                if (
                        item.productId().equals(
                                failedProductId
                        )
                ) {

                    outcome =
                            "FAILED";

                } else {

                    outcome =
                            "NOT_RESERVED";
                }

                outcomes.add(
                        new PlaceOrderResponse.ItemOutcome(
                                item.productId(),
                                outcome
                        )
                );
            }
        }

        eventPublisher.publishEvent(
                new OrderRejectedEvent(
                        savedOrder.getOrderId(),
                        reason
                )
        );

        return new PlaceOrderResponse(
                savedOrder.getOrderId(),
                "REJECTED",
                reason,
                outcomes,
                inventoryService.getAllItems()
        );
    }
}