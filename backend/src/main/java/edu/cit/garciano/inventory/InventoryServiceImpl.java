package edu.cit.garciano.inventory;

import edu.cit.garciano.inventory.event.LowStockEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
class InventoryServiceImpl implements InventoryService {

    private final InventoryRepository inventoryRepository;
    private final ApplicationEventPublisher eventPublisher;

    private final int lowStockThreshold;

    InventoryServiceImpl(
            InventoryRepository inventoryRepository,
            ApplicationEventPublisher eventPublisher,
            @Value("${inventory.low-stock-threshold:5}") int lowStockThreshold
    ) {
        this.inventoryRepository = inventoryRepository;
        this.eventPublisher = eventPublisher;
        this.lowStockThreshold = lowStockThreshold;
    }

    @Override
    @Transactional(readOnly = true)
    public InventoryView getItem(String productId) {
        return inventoryRepository.findById(productId)
                .map(this::toView)
                .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<InventoryView> getAllItems() {
        return inventoryRepository.findAll()
                .stream()
                .map(this::toView)
                .toList();
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

        if (item.getStock() < lowStockThreshold) {
            eventPublisher.publishEvent(
                    new LowStockEvent(
                            item.getProductId(),
                            item.getName(),
                            item.getStock()
                    )
            );
        }

        return new ReservationResult(
                true,
                "Stock reserved successfully",
                toView(item)
        );
    }

    @Override
    @Transactional
    public InventoryView restock(String productId, int quantity) {

        if (quantity <= 0) {
            throw new IllegalArgumentException(
                    "Restock quantity must be greater than 0"
            );
        }

        InventoryItem item = inventoryRepository
                .findForUpdate(productId)
                .orElseThrow(
                        () -> new IllegalArgumentException(
                                "Product not found: " + productId
                        )
                );

        item.setStock(item.getStock() + quantity);

        inventoryRepository.save(item);

        return toView(item);
    }

    private InventoryView toView(InventoryItem item) {
        return new InventoryView(
                item.getProductId(),
                item.getName(),
                item.getStock()
        );
    }
}