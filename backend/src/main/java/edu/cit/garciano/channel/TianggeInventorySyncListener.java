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

    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT
    )
    public void handleInventoryChanged(
            InventoryChangedEvent event
    ) {

        if (!isTianggeProduct(
                event.productId()
        )) {

            return;
        }

        /*
         * For Tiangge-originated orders this contains
         * the TG order ID, so stock waits until AFTER
         * our decision/cancellation confirmation.
         *
         * React UI orders and supplier deliveries have
         * no Tiangge order context and publish normally.
         */
        stockOutbox.record(
                event.productId(),
                event.availableStock(),
                operationContext.currentOrderId()
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