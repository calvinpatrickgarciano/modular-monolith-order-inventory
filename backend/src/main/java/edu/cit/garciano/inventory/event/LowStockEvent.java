package edu.cit.garciano.inventory.event;

public record LowStockEvent(
        String productId,
        String productName,
        int remainingStock
) {
}