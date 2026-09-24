package edu.cit.garciano.inventory;

import edu.cit.garciano.inventory.event.LowStockEvent;
import edu.cit.garciano.supplier.SupplierGateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class LowStockReorderListener {

    private final SupplierGateway supplierGateway;
    private final int threshold;

    LowStockReorderListener(
            SupplierGateway supplierGateway,
            @Value("${inventory.low-stock-threshold:5}")
            int threshold
    ) {
        this.supplierGateway = supplierGateway;
        this.threshold = threshold;
    }

    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT
    )
    @Transactional(
            propagation = Propagation.REQUIRES_NEW
    )
    public void handleLowStock(
            LowStockEvent event
    ) {
        int unitsNeeded =
                Math.max(
                        1,
                        threshold - event.remainingStock()
                );

        supplierGateway.reorder(
                event.productId(),
                unitsNeeded
        );
    }
}