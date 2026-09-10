package edu.cit.garciano.inventory;

public interface InventoryService {

    InventoryView getItem(String productId);

    ReservationResult reserve(String productId, int quantity);

    record InventoryView(
            String productId,
            String name,
            int stock
    ) {
    }

    record ReservationResult(
            boolean success,
            String reason,
            InventoryView inventory
    ) {
    }
}