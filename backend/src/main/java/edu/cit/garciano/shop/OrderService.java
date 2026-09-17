package edu.cit.garciano.shop;

import edu.cit.garciano.shop.event.OrderCancelledEvent;
import edu.cit.garciano.inventory.InventoryService;
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

    @Transactional
    public PlaceOrderResponse placeOrder(PlaceOrderRequest request) {

        if (request.items() == null || request.items().isEmpty()) {
            return createRejectedOrder(
                    request,
                    "Order must contain at least one item",
                    null
            );
        }

        /*
         * Combine quantities for validation.
         *
         * Example:
         * P100 x 10
         * P100 x 20
         *
         * We must validate against 30 total,
         * not check 10 and 20 separately.
         */
        Map<String, Integer> requestedQuantities =
                new LinkedHashMap<>();

        for (PlaceOrderRequest.LineItemRequest item : request.items()) {

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
         * Validate EVERYTHING before reserving anything.
         */
        for (Map.Entry<String, Integer> entry
                : requestedQuantities.entrySet()) {

            String productId = entry.getKey();
            int requestedQuantity = entry.getValue();

            InventoryService.InventoryView inventory =
                    inventoryService.getItem(productId);

            if (inventory == null) {
                return createRejectedOrder(
                        request,
                        "Product not found: " + productId,
                        productId
                );
            }

            if (requestedQuantity > inventory.stock()) {
                return createRejectedOrder(
                        request,
                        "Insufficient stock for " + productId
                                + ". Available: "
                                + inventory.stock(),
                        productId
                );
            }
        }

        /*
         * STEP 2:
         * All products passed validation.
         * Now reserve every item.
         */
        List<PlaceOrderResponse.ItemOutcome> outcomes =
                new ArrayList<>();

        for (PlaceOrderRequest.LineItemRequest item : request.items()) {

            InventoryService.ReservationResult result =
                    inventoryService.reserve(
                            item.productId(),
                            item.quantity()
                    );

            /*
             * This should normally never fail because we
             * validated everything first.
             *
             * Throwing an exception here causes the entire
             * @Transactional operation to roll back.
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
         * Save the confirmed order and its line items.
         */
        Order order = new Order(
                "CONFIRMED",
                "All items reserved successfully"
        );

        for (PlaceOrderRequest.LineItemRequest item : request.items()) {
            order.addItem(
                    new OrderItem(
                            item.productId(),
                            item.quantity()
                    )
            );
        }

        Order savedOrder = orderRepository.save(order);

        /*
         * STEP 4:
         * Publish event.
         *
         * OrderService does NOT call Notification directly.
         */
        eventPublisher.publishEvent(
                new OrderPlacedEvent(savedOrder.getOrderId())
        );

        return new PlaceOrderResponse(
                savedOrder.getOrderId(),
                "CONFIRMED",
                "All items reserved successfully",
                outcomes,
                inventoryService.getAllItems()
        );
    }

@Transactional(readOnly = true)
public List<OrderHistoryResponse> getOrders() {

    return orderRepository.findAll()
            .stream()
            .map(order -> new OrderHistoryResponse(
                    order.getOrderId(),
                    order.getStatus(),
                    order.getReason(),
                    order.getCreatedAt(),
                    order.getItems()
                            .stream()
                            .map(item ->
                                    new OrderHistoryResponse.OrderItemView(
                                            item.getProductId(),
                                            item.getQuantity()
                                    )
                            )
                            .toList()
            ))
            .toList();
}

@Transactional
public CancelOrderResponse cancelOrder(Long orderId) {

    Order order = orderRepository.findById(orderId)
            .orElseThrow(() ->
                    new OrderNotFoundException(
                            "Order " + orderId + " does not exist"
                    )
            );

    if ("CANCELLED".equals(order.getStatus())) {
        throw new OrderConflictException(
                "Order " + orderId + " is already cancelled"
        );
    }

    if (!"CONFIRMED".equals(order.getStatus())) {
        throw new OrderConflictException(
                "Only confirmed orders can be cancelled"
        );
    }

    /*
     * Reverse the original module-to-module integration.
     *
     * Order → Inventory
     * through InventoryService only.
     */
    for (OrderItem item : order.getItems()) {

        inventoryService.restock(
                item.getProductId(),
                item.getQuantity()
        );
    }

    order.setStatus("CANCELLED");
    order.setReason("Order cancelled and inventory restocked");

    orderRepository.save(order);

eventPublisher.publishEvent(
        new OrderCancelledEvent(order.getOrderId())
);

    return new CancelOrderResponse(
            order.getOrderId(),
            order.getStatus(),
            order.getReason(),
            inventoryService.getAllItems()
    );
}


    private PlaceOrderResponse createRejectedOrder(
            PlaceOrderRequest request,
            String reason,
            String failedProductId
    ) {

        Order order = new Order(
                "REJECTED",
                reason
        );

        /*
         * Only store valid known products as order items.
         * This prevents FK/quantity constraint problems
         * for malformed requests.
         */
        if (request.items() != null) {
            for (PlaceOrderRequest.LineItemRequest item : request.items()) {

                if (item.quantity() > 0
                        && inventoryService.getItem(item.productId()) != null) {

                    order.addItem(
                            new OrderItem(
                                    item.productId(),
                                    item.quantity()
                            )
                    );
                }
            }
        }

        Order savedOrder = orderRepository.save(order);

        List<PlaceOrderResponse.ItemOutcome> outcomes =
                new ArrayList<>();

        if (request.items() != null) {

            for (PlaceOrderRequest.LineItemRequest item : request.items()) {

                String outcome;

                if (item.productId().equals(failedProductId)) {
                    outcome = "FAILED";
                } else {
                    outcome = "NOT_RESERVED";
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