package edu.cit.garciano.channel;

import edu.cit.garciano.inventory.event.InventoryChangedEvent;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class TianggeInventorySyncListener {

    private final ChannelStockOutbox stockOutbox;
    private final ChannelOperationContext operationContext;

    TianggeInventorySyncListener(
            ChannelStockOutbox stockOutbox,
            ChannelOperationContext operationContext
    ) {

        this.stockOutbox =
                stockOutbox;

        this.operationContext =
                operationContext;
    }

    // =========================================================
    // STEP 1:
    // STORE THE STOCK CHANGE INSIDE THE SAME DB TRANSACTION
    // =========================================================

    /*
     * BEFORE_COMMIT is important.
     *
     * The stock-outbox row is now written while the
     * Inventory transaction is still active.
     *
     * Therefore stock updates for the same product follow
     * the same ordering as the Inventory row lock.
     *
     * If the Inventory transaction rolls back, this outbox
     * row rolls back too.
     */
    @TransactionalEventListener(
            phase = TransactionPhase.BEFORE_COMMIT
    )
    public void saveInventoryChanged(
            InventoryChangedEvent event
    ) {

        if (!isTianggeProduct(
                event.productId()
        )) {

            return;
        }

        stockOutbox.record(
                event.productId(),
                event.availableStock(),
                operationContext.currentOrderId()
        );
    }

    // =========================================================
    // STEP 2:
    // AFTER COMMIT, ASK THE OUTBOX TO SEND
    // =========================================================

    /*
     * Actual HTTP publishing is done only AFTER the
     * Inventory transaction successfully commits.
     *
     * The HTTP call runs on the stock-outbox executor,
     * not on the Tiangge order-feed thread.
     */
    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT
    )
    public void dispatchInventoryChanged(
            InventoryChangedEvent event
    ) {

        if (!isTianggeProduct(
                event.productId()
        )) {

            return;
        }

        stockOutbox.dispatchAsync(
                event.productId()
        );
    }

    private boolean isTianggeProduct(
            String productId
    ) {

        return switch (productId) {

            case "P100",
                 "P200",
                 "P300" -> true;

            default -> false;
        };
    }
}