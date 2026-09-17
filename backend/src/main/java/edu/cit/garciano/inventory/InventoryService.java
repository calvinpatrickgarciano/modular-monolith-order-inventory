package edu.cit.garciano.inventory;

import java.util.List;

public interface InventoryService {

    InventoryView getItem(String productId);

    List<InventoryView> getAllItems();

    ReservationResult reserve(String productId, int quantity);

    InventoryView restock(String productId, int quantity);

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