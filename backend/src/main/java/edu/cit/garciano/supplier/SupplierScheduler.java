package edu.cit.garciano.supplier;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class SupplierScheduler {

    private final LegacySupplyGateway gateway;

    SupplierScheduler(LegacySupplyGateway gateway) {
        this.gateway = gateway;
    }

    @Scheduled(
            fixedDelayString =
                    "${supplier.retry-delay-ms:60000}"
    )
    void retryPendingOrders() {
        gateway.retryPendingOrders();
    }

    @Scheduled(
            fixedDelayString =
                    "${supplier.tracking-delay-ms:60000}"
    )
    void trackOpenOrders() {
        gateway.trackOpenOrders();
    }
}