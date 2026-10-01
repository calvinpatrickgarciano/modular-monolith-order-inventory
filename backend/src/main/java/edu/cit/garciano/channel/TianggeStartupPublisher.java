package edu.cit.garciano.channel;

import edu.cit.garciano.inventory.InventoryService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

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

    TianggeStartupPublisher(
            ChannelGateway channelGateway,
            InventoryService inventoryService
    ) {

        this.channelGateway =
                channelGateway;

        this.inventoryService =
                inventoryService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void goLive() {

        /*
         * TASK 1
         */
        log.info(
                "Sending initial Tiangge heartbeat..."
        );

        channelGateway.heartbeat();

        /*
         * TASK 2
         */
        log.info(
                "Publishing Tiangge listings..."
        );

        channelGateway.publishListings();

        /*
         * Initial stock snapshot.
         *
         * Later stock changes are event-driven.
         */
        log.info(
                "Publishing initial Tiangge stock..."
        );

        inventoryService
                .getAllItems()
                .stream()
                .filter(
                        item ->
                                TIANGGE_PRODUCTS.contains(
                                        item.productId()
                                )
                )
                .forEach(
                        item ->
                                channelGateway.publishStock(
                                        item.productId(),
                                        item.stock()
                                )
                );
    }
}