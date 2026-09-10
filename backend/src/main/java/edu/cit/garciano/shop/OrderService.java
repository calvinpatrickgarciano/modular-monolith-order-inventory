package edu.cit.garciano.shop;

import edu.cit.garciano.inventory.InventoryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final InventoryService inventoryService;
    private final OrderRepository orderRepository;

    public OrderService(
            InventoryService inventoryService,
            OrderRepository orderRepository
    ) {
        this.inventoryService = inventoryService;
        this.orderRepository = orderRepository;
    }

    @Transactional
    public PlaceOrderResponse placeOrder(
            PlaceOrderRequest request
    ) {

        InventoryService.ReservationResult reservation =
                inventoryService.reserve(
                        request.productId(),
                        request.quantity()
                );

        String status;

        if (reservation.success()) {
            status = "CONFIRMED";
        } else {
            status = "REJECTED";
        }

        Order order = new Order(
                request.productId(),
                request.quantity(),
                status,
                reservation.reason()
        );

        orderRepository.save(order);

        return new PlaceOrderResponse(
                status,
                reservation.reason(),
                reservation.inventory()
        );
    }
}