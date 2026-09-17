package edu.cit.garciano.notification;

import edu.cit.garciano.shop.event.OrderCancelledEvent;
import edu.cit.garciano.inventory.event.LowStockEvent;
import edu.cit.garciano.shop.event.OrderPlacedEvent;
import edu.cit.garciano.shop.event.OrderRejectedEvent;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class NotificationEventListener {

    private final NotificationRepository notificationRepository;

    NotificationEventListener(
            NotificationRepository notificationRepository
    ) {
        this.notificationRepository = notificationRepository;
    }

    @EventListener
    public void handleOrderPlaced(OrderPlacedEvent event) {

        String message =
                "Order O"
                        + event.orderId()
                        + " has been confirmed. "
                        + "Your items are now reserved.";

        notificationRepository.save(
                new Notification(message)
        );
    }

    @EventListener
    public void handleOrderRejected(OrderRejectedEvent event) {

        String message =
                "Order O"
                        + event.orderId()
                        + " could not be completed. "
                        + event.reason();

        notificationRepository.save(
                new Notification(message)
        );
    }

    @EventListener
public void handleOrderCancelled(
        OrderCancelledEvent event
) {

    String message =
            "Order O"
                    + event.orderId()
                    + " was cancelled. "
                    + "Reserved items were returned to inventory.";

    notificationRepository.save(
            new Notification(message)
    );
}

    @EventListener
    public void handleLowStock(LowStockEvent event) {

        String message =
                "Low stock alert: "
                        + event.productName()
                        + " ("
                        + event.productId()
                        + ") has only "
                        + event.remainingStock()
                        + " left. Reorder soon.";

        notificationRepository.save(
                new Notification(message)
        );
    }
}