package edu.cit.garciano.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

@Component
class TianggeFeedProcessor {

    private static final Logger log =
            LoggerFactory.getLogger(
                    TianggeFeedProcessor.class
            );

    private final ChannelGateway channelGateway;
    private final ChannelStateStore stateStore;
    private final TianggeOrderBridge orderBridge;
    private final ChannelOperationContext operationContext;
    private final ChannelStockOutbox stockOutbox;

    TianggeFeedProcessor(
            ChannelGateway channelGateway,
            ChannelStateStore stateStore,
            TianggeOrderBridge orderBridge,
            ChannelOperationContext operationContext,
            ChannelStockOutbox stockOutbox
    ) {

        this.channelGateway =
                channelGateway;

        this.stateStore =
                stateStore;

        this.orderBridge =
                orderBridge;

        this.operationContext =
                operationContext;

        this.stockOutbox =
                stockOutbox;
    }

    void pollOnce() {

        long cursor =
                stateStore.getCursor();

        ChannelGateway.FeedBatch batch =
                channelGateway.fetchFeed(
                        cursor
                );

        if (batch == null) {
            return;
        }

        for (
                ChannelGateway.FeedEvent event :
                batch.events()
        ) {

            /*
             * Tiangge delivery is at least once.
             *
             * eventId, NOT seq, identifies the
             * logical event.
             */
            if (
                    stateStore.isEventProcessed(
                            event.eventId()
                    )
            ) {

                log.info(
                        "Skipping redelivered event {}",
                        event.eventId()
                );

                /*
                 * It may have a new sequence number,
                 * so still move the durable cursor.
                 */
                stateStore.advanceCursor(
                        event.seq()
                );

                continue;
            }

            boolean success;

            try {

                success =
                        switch (event.type()) {

                            case "ORDER_PLACED" ->
                                    processOrderPlaced(
                                            event
                                    );

                            case "ORDER_CANCELLED" ->
                                    processCancellation(
                                            event
                                    );

                            default -> {

                                log.warn(
                                        "Unknown Tiangge event type: {}",
                                        event.type()
                                );

                                /*
                                 * Do not get stuck forever on
                                 * a future event type.
                                 */
                                yield true;
                            }
                        };

            } catch (Exception exception) {

                log.error(
                        "Unable to process event {}: {}",
                        event.eventId(),
                        exception.getMessage()
                );

                success = false;
            }

            /*
             * Never move past an event that has not
             * finished successfully.
             */
            if (!success) {

                log.warn(
                        "Stopping feed processing at seq {}",
                        event.seq()
                );

                break;
            }

            stateStore.completeEvent(
                    event.eventId(),
                    event.seq(),
                    event.type(),
                    event.orderId()
            );
        }
    }

    private boolean processOrderPlaced(
            ChannelGateway.FeedEvent event
    ) {

        TianggeOrderBridge.PreparedDecision
                prepared;

        /*
         * Keep the Tiangge order context active while
         * the Order transaction commits.
         *
         * InventoryChangedEvent then stores stock as
         * blocked by this Tiangge order.
         */
        operationContext.begin(
                event.orderId()
        );

        try {

            prepared =
                    orderBridge.prepareOrder(
                            event
                    );

        } finally {

            operationContext.clear();
        }

        /*
         * Decision may already have reached Tiangge
         * before a crash.
         */
        if (prepared.alreadySent()) {

            stockOutbox.releaseForOrder(
                    event.orderId()
            );

            return true;
        }

        boolean sent =
                channelGateway.sendDecision(
                        event.orderId(),
                        prepared.decision(),
                        prepared.shopOrderId()
                );

        if (!sent) {
            return false;
        }

        stateStore.markDecisionSent(
                event.orderId()
        );

        /*
         * IMPORTANT:
         *
         * Tiangge has the decision now.
         * Only now may the new stock be published.
         */
        stockOutbox.releaseForOrder(
                event.orderId()
        );

        log.info(
                "Finished Tiangge order {} -> {}",
                event.orderId(),
                prepared.decision()
        );

        return true;
    }

    private boolean processCancellation(
            ChannelGateway.FeedEvent event
    ) {

        operationContext.begin(
                event.orderId()
        );

        try {

            orderBridge.prepareCancellation(
                    event.orderId()
            );

        } finally {

            operationContext.clear();
        }

        /*
         * Inventory has already been restocked locally,
         * but the stock events are still blocked.
         */
        boolean confirmed =
                channelGateway.confirmCancellation(
                        event.orderId()
                );

        if (!confirmed) {
            return false;
        }

        stateStore.updateOrderStatus(
                event.orderId(),
                "CANCELLED_BY_CUSTOMER"
        );

        /*
         * Confirmation FIRST, stock SECOND.
         */
        stockOutbox.releaseForOrder(
                event.orderId()
        );

        log.info(
                "Finished Tiangge cancellation {}",
                event.orderId()
        );

        return true;
    }
}