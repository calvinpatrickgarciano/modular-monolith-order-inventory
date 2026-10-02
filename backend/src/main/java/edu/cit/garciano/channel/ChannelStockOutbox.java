package edu.cit.garciano.channel;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Component
class ChannelStockOutbox {

    private static final Logger log =
            LoggerFactory.getLogger(
                    ChannelStockOutbox.class
            );

    private static final List<String>
            TIANGGE_PRODUCTS =
            List.of(
                    "P100",
                    "P200",
                    "P300"
            );

    private final ChannelGateway channelGateway;
    private final ChannelStateStore stateStore;

    /*
     * Async workers remain useful for:
     *
     * - supplier deliveries
     * - startup stock
     * - normal background stock changes
     * - retrying failed stock HTTP calls
     */
    private final ExecutorService stockExecutor;

    /*
     * Only one publisher at a time may send
     * updates for a particular SKU.
     */
    private final ConcurrentMap<String, Object>
            skuLocks =
            new ConcurrentHashMap<>();

    ChannelStockOutbox(
            ChannelGateway channelGateway,
            ChannelStateStore stateStore
    ) {

        this.channelGateway =
                channelGateway;

        this.stateStore =
                stateStore;

        AtomicInteger threadNumber =
                new AtomicInteger();

        this.stockExecutor =
                Executors.newFixedThreadPool(
                        3,
                        runnable -> {

                            Thread thread =
                                    new Thread(
                                            runnable
                                    );

                            thread.setName(
                                    "tiangge-stock-"
                                            + threadNumber
                                            .incrementAndGet()
                            );

                            thread.setDaemon(
                                    true
                            );

                            return thread;
                        }
                );
    }

    // =========================================================
    // RECORD STOCK CHANGE
    // =========================================================

    /*
     * Durable database write only.
     *
     * No HTTP request happens here.
     */
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
    }

    // =========================================================
    // NORMAL ASYNC DISPATCH
    // =========================================================

    void dispatchAsync(
            String sellerSku
    ) {

        stockExecutor.execute(
                () ->
                        flushSku(
                                sellerSku
                        )
        );
    }

    private void dispatchAllAsync() {

        for (
                String sellerSku :
                TIANGGE_PRODUCTS
        ) {

            dispatchAsync(
                    sellerSku
            );
        }
    }

    // =========================================================
    // RELEASE STOCK AFTER TIANGGE DECISION
    // =========================================================

    void releaseForOrder(
            String tianggeOrderId
    ) {

        /*
         * Find which products were actually changed
         * by THIS Tiangge operation BEFORE removing
         * the blocked marker.
         *
         * Example:
         *
         * TG-123 reserved only P100
         *
         * affectedSkus = [P100]
         *
         * We should NOT wait for unrelated P200/P300
         * stock traffic.
         */
        List<String> affectedSkus =
                stateStore.findBlockedSkus(
                        tianggeOrderId
                );

        /*
         * The Tiangge decision / cancellation /
         * backorder resolution has successfully
         * reached Tiangge.
         *
         * These stock changes are now allowed out.
         */
        stateStore.unblockStockUpdates(
                tianggeOrderId
        );

        /*
         * CRITICAL:
         *
         * Synchronously publish only the SKUs that
         * were changed by this particular operation.
         *
         * This preserves:
         *
         * decision first
         * stock second
         *
         * while avoiding delays from unrelated SKUs.
         */
        for (
                String sellerSku :
                affectedSkus
        ) {

            flushSku(
                    sellerSku
            );
        }
    }

    // =========================================================
    // FAILURE RECOVERY
    // =========================================================

    /*
     * Background retry for durable unsent stock rows.
     *
     * This does NOT generate new Inventory snapshots.
     */
    @Scheduled(
            fixedDelayString =
                    "${channel.tiangge.stock-retry-ms:1000}",
            initialDelayString =
                    "${channel.tiangge.stock-retry-ms:1000}"
    )
    public void retryPending() {

        dispatchAllAsync();
    }

    // =========================================================
    // PUBLISH ONE SKU IN STRICT ORDER
    // =========================================================

    private void flushSku(
            String sellerSku
    ) {

        Object skuLock =
                skuLocks.computeIfAbsent(
                        sellerSku,
                        ignored ->
                                new Object()
                );

        /*
         * Async and synchronous callers use the
         * exact same per-SKU lock.
         */
        synchronized (skuLock) {

            while (true) {

                var nextUpdate =
                        stateStore
                                .findOldestUnsentStockUpdate(
                                        sellerSku
                                );

                /*
                 * Nothing left for this SKU.
                 */
                if (nextUpdate.isEmpty()) {
                    return;
                }

                ChannelStateStore.StockUpdateState update =
                        nextUpdate.get();

                /*
                 * Never allow a newer stock value
                 * to pass an older blocked value.
                 */
                if (
                        update.blockedByOrderId()
                                != null
                ) {

                    return;
                }

                boolean sent =
                        channelGateway.publishStock(
                                update.sellerSku(),
                                update.available()
                        );

                if (!sent) {

                    log.warn(
                            "Stock update {} for {} remains pending",
                            update.id(),
                            update.sellerSku()
                    );

                    /*
                     * Keep it unsent.
                     *
                     * Scheduled retry will try this exact
                     * durable row again.
                     */
                    return;
                }

                stateStore.markStockUpdateSent(
                        update.id()
                );

                log.info(
                        "Published Tiangge stock update {}: {} = {}",
                        update.id(),
                        update.sellerSku(),
                        update.available()
                );

                /*
                 * Continue in strict ID order.
                 */
            }
        }
    }

    // =========================================================
    // SHUTDOWN
    // =========================================================

    @PreDestroy
    void shutdown() {

        stockExecutor.shutdownNow();
    }
}