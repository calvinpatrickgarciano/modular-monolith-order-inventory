package edu.cit.garciano.channel;

import edu.cit.garciano.inventory.InventoryService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
class TianggeStartupPublisher {

    private static final Logger log =
            LoggerFactory.getLogger(
                    TianggeStartupPublisher.class
            );

    private static final Set<String>
            TIANGGE_PRODUCTS =
            Set.of(
                    "P100",
                    "P200",
                    "P300"
            );

    private final ChannelGateway channelGateway;
    private final InventoryService inventoryService;
    private final ChannelStockOutbox stockOutbox;
    private final ChannelStartupState startupState;

    TianggeStartupPublisher(
            ChannelGateway channelGateway,
            InventoryService inventoryService,
            ChannelStockOutbox stockOutbox,
            ChannelStartupState startupState
    ) {

        this.channelGateway =
                channelGateway;

        this.inventoryService =
                inventoryService;

        this.stockOutbox =
                stockOutbox;

        this.startupState =
                startupState;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void goLive() {

        // =====================================================
        // TASK 1
        // FIRST TIANGGE CALL MUST BE HEARTBEAT
        // =====================================================

        log.info(
                "Sending initial Tiangge heartbeat..."
        );

        channelGateway.heartbeat();

        // =====================================================
        // TASK 2
        // LISTINGS
        // =====================================================

        log.info(
                "Publishing Tiangge listings..."
        );

        channelGateway.publishListings();

        // =====================================================
        // INITIAL STOCK SNAPSHOT
        // =====================================================

        /*
         * IMPORTANT:
         *
         * Never call channelGateway.publishStock()
         * directly here.
         *
         * The initial snapshot must enter the SAME durable
         * outbox as every later InventoryChangedEvent.
         *
         * This guarantees:
         *
         * initial value
         *       ↓
         * accepted-order value
         *       ↓
         * supplier-delivery value
         *
         * can never be transmitted out of order.
         */

        log.info(
                "Recording initial Tiangge stock snapshot..."
        );

        List<InventoryService.InventoryView> inventory =
                inventoryService
                        .getAllItems()
                        .stream()
                        .filter(
                                item ->
                                        TIANGGE_PRODUCTS.contains(
                                                item.productId()
                                        )
                        )
                        .toList();

        /*
         * Record ALL three current values first.
         *
         * The feed is still gated at this point, so no
         * marketplace order can create a later stock row
         * before these snapshot rows exist.
         */
        for (
                InventoryService.InventoryView item :
                inventory
        ) {

            stockOutbox.record(
                    item.productId(),
                    item.stock(),
                    null
            );
        }

        /*
         * Startup state becomes ready only AFTER all initial
         * stock rows have been durably recorded.
         */
        startupState.markReady();

        log.info(
                "Tiangge startup initialization complete"
        );

        /*
         * Actual network publishing can now run asynchronously.
         *
         * Even if Tiangge times out, newer stock changes cannot
         * jump ahead because ChannelStockOutbox preserves
         * per-SKU order.
         */
        for (
                InventoryService.InventoryView item :
                inventory
        ) {

            stockOutbox.dispatchAsync(
                    item.productId()
            );
        }
    }
}