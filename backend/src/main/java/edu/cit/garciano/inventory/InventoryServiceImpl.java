package edu.cit.garciano.inventory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class InventoryServiceImpl implements InventoryService {

    private final InventoryRepository inventoryRepository;

    InventoryServiceImpl(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public InventoryView getItem(String productId) {
        return inventoryRepository.findById(productId)
                .map(this::toView)
                .orElse(null);
    }

    @Override
    @Transactional
    public ReservationResult reserve(String productId, int quantity) {

        if (quantity <= 0) {
            return new ReservationResult(
                    false,
                    "Quantity must be greater than 0",
                    getItem(productId)
            );
        }

        InventoryItem item = inventoryRepository
                .findForUpdate(productId)
                .orElse(null);

        if (item == null) {
            return new ReservationResult(
                    false,
                    "Product not found",
                    null
            );
        }

        if (quantity > item.getStock()) {
            return new ReservationResult(
                    false,
                    "Insufficient stock. Available stock: " + item.getStock(),
                    toView(item)
            );
        }

        item.setStock(item.getStock() - quantity);

        inventoryRepository.save(item);

        return new ReservationResult(
                true,
                "Stock reserved successfully",
                toView(item)
        );
    }

    private InventoryView toView(InventoryItem item) {
        return new InventoryView(
                item.getProductId(),
                item.getName(),
                item.getStock()
        );
    }
}