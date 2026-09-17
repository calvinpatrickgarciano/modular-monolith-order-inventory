package edu.cit.garciano.shop;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
@CrossOrigin(origins = "http://localhost:5173")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /*
     * POST /api/orders
     *
     * Creates a multi-item order.
     */
    @PostMapping
    public ResponseEntity<PlaceOrderResponse> placeOrder(
            @RequestBody PlaceOrderRequest request
    ) {

        return ResponseEntity.ok(
                orderService.placeOrder(request)
        );
    }

    /*
     * GET /api/orders
     *
     * Returns order history.
     */
    @GetMapping
    public ResponseEntity<List<OrderHistoryResponse>> getOrders() {

        return ResponseEntity.ok(
                orderService.getOrders()
        );
    }

    /*
     * POST /api/orders/{orderId}/cancel
     *
     * Cancels an order and restores its inventory.
     */
    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<CancelOrderResponse> cancelOrder(
            @PathVariable Long orderId
    ) {

        return ResponseEntity.ok(
                orderService.cancelOrder(orderId)
        );
    }
}