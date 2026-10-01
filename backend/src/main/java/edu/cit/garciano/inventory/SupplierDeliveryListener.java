package edu.cit.garciano.inventory;

import edu.cit.garciano.supplier.SupplierOrderDeliveredEvent;

import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
class SupplierDeliveryListener {

    private final InventoryService inventoryService;

    SupplierDeliveryListener(
            InventoryService inventoryService
    ) {

        this.inventoryService =
                inventoryService;
    }

    @EventListener
    @Order(0)
    public void handleSupplierDelivery(
            SupplierOrderDeliveredEvent event
    ) {

        inventoryService.restock(
                event.productId(),
                event.unitsDelivered()
        );
    }
}