package edu.cit.garciano.inventory.event;

public record InventoryChangedEvent(
        String productId,
        int availableStock
) {
}