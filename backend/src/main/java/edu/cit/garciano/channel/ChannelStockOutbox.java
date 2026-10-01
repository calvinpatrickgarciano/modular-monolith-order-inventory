package edu.cit.garciano.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class ChannelStockOutbox {

    private static final Logger log =
            LoggerFactory.getLogger(
                    ChannelStockOutbox.class
            );

    private final ChannelGateway channelGateway;
    private final ChannelStateStore stateStore;

    ChannelStockOutbox(
            ChannelGateway channelGateway,
            ChannelStateStore stateStore
    ) {

        this.channelGateway =
                channelGateway;

        this.stateStore =
                stateStore;
    }

    void record(
            String sellerSku,
            int available,
            String blockedByOrderId
    ) {

        stateStore.saveStockUpdate(
                sellerSku,
                available,
                blockedByOrderId
        );

        /*
         * React orders / supplier deliveries
         * are not blocked by a Tiangge decision.
         */
        if (blockedByOrderId == null) {

            flushReady();
        }
    }

    void releaseForOrder(
            String tianggeOrderId
    ) {

        stateStore.unblockStockUpdates(
                tianggeOrderId
        );

        flushReady();
    }

    /*
     * This timer does NOT poll Inventory.
     *
     * It only retries stock updates that were
     * originally produced by InventoryChangedEvent.
     */
    @Scheduled(
            fixedDelayString =
                    "${channel.tiangge.stock-retry-ms:5000}",
            initialDelayString =
                    "${channel.tiangge.stock-retry-ms:5000}"
    )
    public void retryPending() {

        flushReady();
    }

    private synchronized void flushReady() {

        for (
                ChannelStateStore.StockUpdateState update :
                stateStore.findReadyStockUpdates()
        ) {

            boolean sent =
                    channelGateway.publishStock(
                            update.sellerSku(),
                            update.available()
                    );

            if (!sent) {

                log.warn(
                        "Stock update {} remains pending",
                        update.id()
                );

                /*
                 * Preserve stock update order.
                 */
                break;
            }

            stateStore.markStockUpdateSent(
                    update.id()
            );
        }
    }
}