package edu.cit.garciano.shop;

import edu.cit.garciano.inventory.InventoryService;

public record PlaceOrderResponse(
        String status,
        String reason,
        InventoryService.InventoryView inventory
) {
}