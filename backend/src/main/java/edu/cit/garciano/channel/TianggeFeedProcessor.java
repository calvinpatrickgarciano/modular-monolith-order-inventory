package edu.cit.garciano.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

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
    private final ChannelMarketplaceLock marketplaceLock;

    TianggeFeedProcessor(
            ChannelGateway channelGateway,
            ChannelStateStore stateStore,
            TianggeOrderBridge orderBridge,
            ChannelOperationContext operationContext,
            ChannelStockOutbox stockOutbox,
            ChannelMarketplaceLock marketplaceLock
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

        this.marketplaceLock =
                marketplaceLock;
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

        // =====================================================
        // FEED BACKLOG PRIORITY
        // =====================================================

        boolean backlogActive =
                isFeedBacklog(
                        batch
                );

        boolean backlogChanged =
                marketplaceLock.setFeedBacklogActive(
                        backlogActive
                );

        if (backlogChanged) {

            if (backlogActive) {

                log.warn(
                        "Tiangge feed backlog detected. "
                                + "Background backorder resolution paused."
                );

            } else {

                log.info(
                        "Tiangge feed caught up. "
                                + "Background backorder resolution allowed again."
                );
            }
        }

        // =====================================================
        // FEED DIAGNOSTIC
        // =====================================================

        if (!batch.events().isEmpty()) {

            ChannelGateway.FeedEvent first =
                    batch.events().get(0);

            ChannelGateway.FeedEvent last =
                    batch.events().get(
                            batch.events().size() - 1
                    );

            log.info(
                    "Tiangge feed batch: "
                            + "afterCursor={}, "
                            + "count={}, "
                            + "firstSeq={}, "
                            + "lastSeq={}, "
                            + "nextCursor={}",
                    cursor,
                    batch.events().size(),
                    first.seq(),
                    last.seq(),
                    batch.nextCursor()
            );
        }

        // =====================================================
        // PROCESS EVENTS IN FEED ORDER
        // =====================================================

        for (
                ChannelGateway.FeedEvent event :
                batch.events()
        ) {

            /*
             * Tiangge delivery is at least once.
             *
             * eventId identifies the logical event.
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

                stateStore.advanceCursor(
                        event.seq()
                );

                continue;
            }

            logDeadline(
                    event
            );

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

                                yield true;
                            }
                        };

            } catch (Exception exception) {

                log.error(
                        "Unable to process event {}: {}",
                        event.eventId(),
                        exception.getMessage(),
                        exception
                );

                success = false;
            }

            /*
             * Never move past an event that has not
             * completely finished successfully.
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

    // =========================================================
    // BACKLOG DETECTION
    // =========================================================

    private boolean isFeedBacklog(
            ChannelGateway.FeedBatch batch
    ) {

        /*
         * Tiangge feed requests currently ask for at most
         * 20 events.
         *
         * Receiving a completely full batch strongly
         * indicates that additional events are still
         * waiting behind it.
         */
        if (batch.events().size() >= 20) {

            return true;
        }

        Instant now =
                Instant.now();

        /*
         * Even a smaller batch is still backlog when it
         * contains an event whose deadline has already
         * expired.
         */
        for (
                ChannelGateway.FeedEvent event :
                batch.events()
        ) {

            String deadlineText =
                    event.deadline();

            if (
                    deadlineText == null
                            ||
                    deadlineText.isBlank()
            ) {

                continue;
            }

            try {

                Instant deadline =
                        OffsetDateTime.parse(
                                deadlineText
                        ).toInstant();

                if (!deadline.isAfter(now)) {

                    return true;
                }

            } catch (
                    DateTimeParseException exception
            ) {

                /*
                 * logDeadline() reports malformed
                 * deadlines separately.
                 */
            }
        }

        return false;
    }

    // =========================================================
    // DEADLINE DIAGNOSTIC
    // =========================================================

    private void logDeadline(
            ChannelGateway.FeedEvent event
    ) {

        String deadlineText =
                event.deadline();

        if (
                deadlineText == null
                        ||
                deadlineText.isBlank()
        ) {

            log.warn(
                    "Tiangge event {} has no deadline. "
                            + "type={}, order={}",
                    event.eventId(),
                    event.type(),
                    event.orderId()
            );

            return;
        }

        try {

            Instant deadline =
                    OffsetDateTime.parse(
                            deadlineText
                    ).toInstant();

            Instant now =
                    Instant.now();

            long remainingMs =
                    Duration.between(
                            now,
                            deadline
                    ).toMillis();

            if (remainingMs >= 0) {

                log.info(
                        "Processing Tiangge event {} "
                                + "type={} order={} seq={} "
                                + "deadline={} "
                                + "remainingMs={}",
                        event.eventId(),
                        event.type(),
                        event.orderId(),
                        event.seq(),
                        deadlineText,
                        remainingMs
                );

            } else {

                log.warn(
                        "LATE BEFORE PROCESSING: "
                                + "event={} type={} order={} seq={} "
                                + "deadline={} "
                                + "lateByMs={}",
                        event.eventId(),
                        event.type(),
                        event.orderId(),
                        event.seq(),
                        deadlineText,
                        Math.abs(
                                remainingMs
                        )
                );
            }

        } catch (
                DateTimeParseException exception
        ) {

            log.warn(
                    "Unable to parse Tiangge deadline "
                            + "for event {}: {}",
                    event.eventId(),
                    deadlineText
            );
        }
    }

    // =========================================================
    // ORDER PLACED
    // =========================================================

    private boolean processOrderPlaced(
            ChannelGateway.FeedEvent event
    ) {

        /*
         * Feed work receives priority over the background
         * backorder resolver.
         */
        marketplaceLock.lockForFeed();

        try {

            TianggeOrderBridge.PreparedDecision prepared;

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
             * Decision already reached Tiangge during an
             * earlier delivery of this same order.
             */
            if (prepared.alreadySent()) {

                stockOutbox.releaseForOrder(
                        event.orderId()
                );

                log.info(
                        "Finished replayed Tiangge order {} -> {}",
                        event.orderId(),
                        prepared.decision()
                );

                return true;
            }

            /*
             * DECISION FIRST.
             */
            boolean sent =
                    channelGateway.sendDecision(
                            event.orderId(),
                            prepared.decision(),
                            prepared.shopOrderId()
                    );

            if (!sent) {

                log.warn(
                        "Tiangge decision for {} remains pending",
                        event.orderId()
                );

                return false;
            }

            stateStore.markDecisionSent(
                    event.orderId()
            );

            /*
             * STOCK SECOND.
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

        } finally {

            marketplaceLock.unlock();
        }
    }

    // =========================================================
    // CUSTOMER CANCELLATION
    // =========================================================

    private boolean processCancellation(
            ChannelGateway.FeedEvent event
    ) {

        /*
         * Cancellations are deadline-sensitive too.
         */
        marketplaceLock.lockForFeed();

        try {

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
             * CONFIRMATION FIRST.
             */
            boolean confirmed =
                    channelGateway.confirmCancellation(
                            event.orderId()
                    );

            if (!confirmed) {

                log.warn(
                        "Tiangge cancellation confirmation for {} "
                                + "remains pending",
                        event.orderId()
                );

                return false;
            }

            stateStore.updateOrderStatus(
                    event.orderId(),
                    "CANCELLED_BY_CUSTOMER"
            );

            /*
             * STOCK SECOND.
             */
            stockOutbox.releaseForOrder(
                    event.orderId()
            );

            log.info(
                    "Finished Tiangge cancellation {}",
                    event.orderId()
            );

            return true;

        } finally {

            marketplaceLock.unlock();
        }
    }
}