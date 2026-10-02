package edu.cit.garciano.channel;

import edu.cit.garciano.shop.CancelOrderResponse;
import edu.cit.garciano.shop.OrderService;
import edu.cit.garciano.shop.PlaceOrderResponse;

import edu.cit.garciano.supplier.SupplierGateway;
import edu.cit.garciano.supplier.SupplierOrderDeliveredEvent;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
class TianggeBackorderResolver {

    private static final Logger log =
            LoggerFactory.getLogger(
                    TianggeBackorderResolver.class
            );

    private final OrderService orderService;
    private final SupplierGateway supplierGateway;
    private final ChannelGateway channelGateway;
    private final ChannelStateStore stateStore;
    private final ChannelOperationContext operationContext;
    private final ChannelStockOutbox stockOutbox;
    private final ChannelStartupState startupState;
    private final ChannelMarketplaceLock marketplaceLock;

    /*
     * Backorder work gets its own thread.
     *
     * Slow LegacySupply/Tiangge operations must not occupy
     * the Spring scheduling pool used by the normal feed.
     */
    private final ExecutorService resolverExecutor;

    /*
     * Never run two complete backorder scans at once.
     */
    private final AtomicBoolean resolverRunning =
            new AtomicBoolean(false);

    TianggeBackorderResolver(
            OrderService orderService,
            SupplierGateway supplierGateway,
            ChannelGateway channelGateway,
            ChannelStateStore stateStore,
            ChannelOperationContext operationContext,
            ChannelStockOutbox stockOutbox,
            ChannelStartupState startupState,
            ChannelMarketplaceLock marketplaceLock
    ) {

        this.orderService =
                orderService;

        this.supplierGateway =
                supplierGateway;

        this.channelGateway =
                channelGateway;

        this.stateStore =
                stateStore;

        this.operationContext =
                operationContext;

        this.stockOutbox =
                stockOutbox;

        this.startupState =
                startupState;

        this.marketplaceLock =
                marketplaceLock;

        this.resolverExecutor =
                Executors.newSingleThreadExecutor(
                        runnable -> {

                            Thread thread =
                                    new Thread(
                                            runnable
                                    );

                            thread.setName(
                                    "tiangge-backorder-resolver"
                            );

                            thread.setDaemon(
                                    true
                            );

                            return thread;
                        }
                );
    }

    // =========================================================
    // SUPPLIER DELIVERY
    // =========================================================

    @EventListener
    @Order(100)
    public void handleSupplierDelivery(
            SupplierOrderDeliveredEvent event
    ) {

        if (!startupState.isReady()) {

            return;
        }

        /*
         * SupplierDeliveryListener in Inventory has already
         * performed the restock before this listener runs.
         *
         * Do not resolve synchronously on the supplier thread.
         */
        log.info(
                "Supplier delivery received for {}. "
                        + "Scheduling Tiangge backorder check...",
                event.productId()
        );

        triggerResolution();
    }

    // =========================================================
    // RECOVERY SCHEDULER
    // =========================================================

    @Scheduled(
            fixedDelayString =
                    "${channel.tiangge.backorder-retry-ms:10000}",
            initialDelayString =
                    "${channel.tiangge.backorder-retry-ms:10000}"
    )
    public void retryUnresolvedBackorders() {

        if (!startupState.isReady()) {

            return;
        }

        /*
         * Scheduler only triggers the dedicated worker.
         * It never performs the long scan itself.
         */
        triggerResolution();
    }

    // =========================================================
    // START ONE BACKORDER SCAN
    // =========================================================

    private void triggerResolution() {

        if (
                !resolverRunning.compareAndSet(
                        false,
                        true
                )
        ) {

            return;
        }

        resolverExecutor.execute(
                () -> {

                    try {

                        resolveWaitingBackorders();

                    } catch (Exception exception) {

                        log.error(
                                "Unexpected backorder resolver error: {}",
                                exception.getMessage(),
                                exception
                        );

                    } finally {

                        resolverRunning.set(
                                false
                        );
                    }
                }
        );
    }

    // =========================================================
    // RESOLVE WAITING BACKORDERS
    // =========================================================

    private void resolveWaitingBackorders() {

        /*
         * The normal Tiangge feed has hard deadlines.
         *
         * When old feed work is waiting, do not start an
         * ordinary background backorder scan.
         */
        if (marketplaceLock.isFeedBacklogActive()) {

            log.info(
                    "Skipping Tiangge backorder scan "
                            + "while feed backlog is active"
            );

            return;
        }

        log.info(
                "Starting Tiangge backorder scan..."
        );

        for (
                ChannelStateStore.ChannelOrderState channelOrder :
                stateStore.findBackorderedOrders()
        ) {

            /*
             * Feed may have become backlogged after the
             * scan began.
             */
            if (marketplaceLock.isFeedBacklogActive()) {

                log.info(
                        "Stopping Tiangge backorder scan "
                                + "because feed backlog became active"
                );

                break;
            }

            if (
                    channelOrder.shopOrderId()
                            == null
            ) {

                continue;
            }

            processBackorder(
                    channelOrder
            );
        }

        log.info(
                "Finished Tiangge backorder scan"
        );
    }

    // =========================================================
    // PROCESS ONE BACKORDER
    // =========================================================

    private void processBackorder(
            ChannelStateStore.ChannelOrderState channelOrder
    ) {

        PlaceOrderResponse response;

        // =====================================================
        // PHASE 1
        // =====================================================

        /*
         * Background work does NOT wait for this lock.
         *
         * If feed work is active or waiting, simply skip
         * this backorder and retry during a later scan.
         */
        if (!marketplaceLock.tryLockForBackground()) {

            return;
        }

        try {

            try {

                response =
                        resolveLocalBackorder(
                                channelOrder
                        );

            } catch (RuntimeException exception) {

                log.warn(
                        "Unable to resolve Tiangge backorder {}: {}",
                        channelOrder.tianggeOrderId(),
                        exception.getMessage()
                );

                return;
            }

            /*
             * CONFIRMED/CANCELLED are terminal local states.
             *
             * Complete the Tiangge resolution while still
             * holding the shared marketplace lock.
             */
            if (
                    handleTerminalResolution(
                            channelOrder,
                            response
                    )
            ) {

                return;
            }

        } finally {

            marketplaceLock.unlock();
        }

        /*
         * Only BACKORDERED reaches this point.
         */
        if (
                !"BACKORDERED".equals(
                        response.status()
                )
        ) {

            return;
        }

        // =====================================================
        // SLOW SUPPLIER CHECK - OUTSIDE MARKETPLACE LOCK
        // =====================================================

        /*
         * This may perform live LegacySupply HTTP requests.
         *
         * It MUST remain outside ChannelMarketplaceLock.
         *
         * Otherwise a slow LegacySupply response could stop
         * the normal Tiangge order feed.
         */
        boolean supplierStockStillComing;

        try {

            supplierStockStillComing =
                    response.items()
                            .stream()
                            .map(
                                    PlaceOrderResponse
                                            .ItemOutcome::productId
                            )
                            .distinct()
                            .anyMatch(
                                    supplierGateway::hasOpenReorder
                            );

        } catch (RuntimeException exception) {

            /*
             * If supplier state cannot be verified, leave the
             * backorder alone and retry later.
             */
            log.warn(
                    "Unable to verify supplier state for "
                            + "Tiangge backorder {}: {}",
                    channelOrder.tianggeOrderId(),
                    exception.getMessage()
            );

            return;
        }

        if (supplierStockStillComing) {

            log.info(
                    "Tiangge backorder {} still has "
                            + "supplier stock on the way",
                    channelOrder.tianggeOrderId()
            );

            return;
        }

        // =====================================================
        // PHASE 2
        // NO SUPPLIER STOCK APPEARS TO REMAIN
        // =====================================================

        /*
         * Inventory could have changed while we were making
         * the LegacySupply HTTP calls.
         *
         * Therefore reacquire the lock and call
         * resolveBackorder() ONE MORE TIME before cancelling.
         *
         * Background work again refuses to wait in front of
         * the deadline-sensitive feed.
         */
        if (!marketplaceLock.tryLockForBackground()) {

            return;
        }

        try {

            try {

                response =
                        resolveLocalBackorder(
                                channelOrder
                        );

            } catch (RuntimeException exception) {

                log.warn(
                        "Unable to revalidate Tiangge backorder {}: {}",
                        channelOrder.tianggeOrderId(),
                        exception.getMessage()
                );

                return;
            }

            /*
             * Stock may have arrived while the LegacySupply
             * request was in flight.
             */
            if (
                    handleTerminalResolution(
                            channelOrder,
                            response
                    )
            ) {

                return;
            }

            if (
                    !"BACKORDERED".equals(
                            response.status()
                    )
            ) {

                return;
            }

            // =================================================
            // STILL BACKORDERED + NO QUALIFYING SUPPLIER STOCK
            // =================================================

            /*
             * Tell Tiangge FIRST.
             *
             * If this network request fails, leave the local
             * backorder untouched so the operation can safely
             * be retried later.
             */
            boolean sent =
                    channelGateway.sendResolution(
                            channelOrder.tianggeOrderId(),
                            "CANCELLED"
                    );

            if (!sent) {

                log.warn(
                        "Tiangge CANCELLED resolution for {} "
                                + "is still pending",
                        channelOrder.tianggeOrderId()
                );

                return;
            }

            CancelOrderResponse cancelled;

            try {

                cancelled =
                        orderService.cancelBackorder(
                                channelOrder.shopOrderId(),
                                "No qualifying supplier delivery "
                                        + "remains for this backorder"
                        );

            } catch (RuntimeException exception) {

                log.warn(
                        "Tiangge accepted cancellation for {} "
                                + "but local cleanup failed: {}",
                        channelOrder.tianggeOrderId(),
                        exception.getMessage()
                );

                return;
            }

            stateStore.updateOrderStatus(
                    channelOrder.tianggeOrderId(),
                    "RESOLVED_CANCELLED"
            );

            /*
             * Any stock rows associated with this order may
             * now be released only after Tiangge has accepted
             * the resolution.
             */
            stockOutbox.releaseForOrder(
                    channelOrder.tianggeOrderId()
            );

            log.info(
                    "Tiangge backorder {} resolved as CANCELLED. "
                            + "Local order={}",
                    channelOrder.tianggeOrderId(),
                    cancelled.orderId()
            );

        } finally {

            marketplaceLock.unlock();
        }
    }

    // =========================================================
    // LOCAL BACKORDER RESOLUTION
    // =========================================================

    private PlaceOrderResponse resolveLocalBackorder(
            ChannelStateStore.ChannelOrderState channelOrder
    ) {

        /*
         * Any InventoryChangedEvent generated by
         * resolveBackorder() must be blocked by the correct
         * Tiangge order until its resolution is sent.
         */
        operationContext.begin(
                channelOrder.tianggeOrderId()
        );

        try {

            return orderService.resolveBackorder(
                    channelOrder.shopOrderId()
            );

        } finally {

            operationContext.clear();
        }
    }

    // =========================================================
    // FINISH CONFIRMED/CANCELLED LOCAL STATES
    // =========================================================

    /*
     * Caller MUST hold ChannelMarketplaceLock.
     *
     * Returns true if the local response is already terminal,
     * even when the Tiangge HTTP call failed and must be
     * retried during the next scan.
     */
    private boolean handleTerminalResolution(
            ChannelStateStore.ChannelOrderState channelOrder,
            PlaceOrderResponse response
    ) {

        // =====================================================
        // FILLED / ALREADY CONFIRMED
        // =====================================================

        if (
                "CONFIRMED".equals(
                        response.status()
                )
        ) {

            boolean sent =
                    channelGateway.sendResolution(
                            channelOrder.tianggeOrderId(),
                            "ACCEPTED"
                    );

            if (!sent) {

                log.warn(
                        "Tiangge ACCEPTED resolution for {} "
                                + "is still pending",
                        channelOrder.tianggeOrderId()
                );

                return true;
            }

            stateStore.updateOrderStatus(
                    channelOrder.tianggeOrderId(),
                    "RESOLVED_ACCEPTED"
            );

            /*
             * Resolution FIRST, stock SECOND.
             */
            stockOutbox.releaseForOrder(
                    channelOrder.tianggeOrderId()
            );

            log.info(
                    "Tiangge backorder {} resolved as ACCEPTED",
                    channelOrder.tianggeOrderId()
            );

            return true;
        }

        // =====================================================
        // ALREADY CANCELLED LOCALLY
        // =====================================================

        if (
                "CANCELLED".equals(
                        response.status()
                )
        ) {

            boolean sent =
                    channelGateway.sendResolution(
                            channelOrder.tianggeOrderId(),
                            "CANCELLED"
                    );

            if (!sent) {

                log.warn(
                        "Tiangge CANCELLED resolution for {} "
                                + "is still pending",
                        channelOrder.tianggeOrderId()
                );

                return true;
            }

            stateStore.updateOrderStatus(
                    channelOrder.tianggeOrderId(),
                    "RESOLVED_CANCELLED"
            );

            stockOutbox.releaseForOrder(
                    channelOrder.tianggeOrderId()
            );

            log.info(
                    "Recovered stale backorder {} "
                            + "and resolved it as CANCELLED",
                    channelOrder.tianggeOrderId()
            );

            return true;
        }

        return false;
    }

    // =========================================================
    // SHUTDOWN
    // =========================================================

    @PreDestroy
    void shutdown() {

        resolverExecutor.shutdownNow();
    }
}