package edu.cit.garciano.shop;

import edu.cit.garciano.inventory.InventoryService;

import java.util.List;

public record CancelOrderResponse(
        Long orderId,
        String status,
        String reason,
        List<InventoryService.InventoryView> inventory
) {
}