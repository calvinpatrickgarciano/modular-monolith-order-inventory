package edu.cit.garciano.supplier;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
class LegacySupplyGateway implements SupplierGateway {

    private final SupplierOrderRepository supplierOrderRepository;
    private final LegacySupplyClient legacySupplyClient;
    private final ApplicationEventPublisher eventPublisher;

    LegacySupplyGateway(
            SupplierOrderRepository supplierOrderRepository,
            LegacySupplyClient legacySupplyClient,
            ApplicationEventPublisher eventPublisher
    ) {
        this.supplierOrderRepository = supplierOrderRepository;
        this.legacySupplyClient = legacySupplyClient;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public SupplierOrderResult reorder(
            String productId,
            int unitsNeeded
    ) {

        if (unitsNeeded <= 0) {
            throw new IllegalArgumentException(
                    "Units needed must be greater than 0"
            );
        }

        LegacyProductMapping mapping =
                LegacyProductMapping.find(productId);

        if (mapping == null) {
            throw new IllegalArgumentException(
                    "No supplier mapping for product: "
                            + productId
            );
        }

        /*
         * Convert our individual units into supplier cases.
         *
         * Example:
         * 13 units needed
         * PackSize = 6
         *
         * (13 + 6 - 1) / 6 = 3 cases
         */
        int cases =
                (unitsNeeded + mapping.packSize() - 1)
                        / mapping.packSize();

        if (cases > 99) {
            throw new IllegalArgumentException(
                    "Supplier order exceeds maximum quantity of 99 cases"
            );
        }

        int actualUnitsOrdered =
                cases * mapping.packSize();

        /*
         * Create the local record FIRST.
         *
         * This prevents the reorder from being lost if
         * LegacySupply is unavailable.
         */
        SupplierOrder supplierOrder =
                new SupplierOrder(
                        productId,
                        UUID.randomUUID().toString(),
                        cases,
                        actualUnitsOrdered
                );

        supplierOrder =
                supplierOrderRepository.save(
                        supplierOrder
                );

        /*
         * BuyerRef is based on our local database ID.
         */
        supplierOrder.setBuyerRef(
                "RO-" + supplierOrder.getId()
        );

        supplierOrder =
                supplierOrderRepository.save(
                        supplierOrder
                );

        return attemptSend(supplierOrder);
    }

    SupplierOrderResult attemptSend(
            SupplierOrder supplierOrder
    ) {

        /*
         * If this row already has a PO number,
         * it was already successfully submitted.
         */
        if (supplierOrder.getPoNumber() != null) {

            return toResult(
                    supplierOrder,
                    "Supplier order already submitted"
            );
        }

        LegacyProductMapping mapping =
                LegacyProductMapping.find(
                        supplierOrder.getProductId()
                );

        if (mapping == null) {

            supplierOrder.setStatus(
                    SupplierOrderStatus.FAILED
            );

            supplierOrderRepository.save(
                    supplierOrder
            );

            return toResult(
                    supplierOrder,
                    "Supplier mapping no longer exists"
            );
        }

        try {

            LegacyPurchaseOrderAck acknowledgement =
                    legacySupplyClient.placeOrder(
                            mapping.supplierSku(),
                            supplierOrder.getCases(),
                            supplierOrder.getBuyerRef(),
                            supplierOrder.getRequestId()
                    );

            supplierOrder.setPoNumber(
                    acknowledgement.poNumber()
            );

            supplierOrder.setStatus(
                    mapStatus(
                            acknowledgement.statusCode()
                    )
            );

            supplierOrderRepository.save(
                    supplierOrder
            );

            return toResult(
                    supplierOrder,
                    "Supplier purchase order submitted"
            );

        } catch (SupplierUnavailableException exception) {

            /*
             * IMPORTANT:
             * Do not mark it FAILED.
             *
             * The supplier is temporarily unavailable,
             * so we keep it PENDING for the scheduler.
             */
            supplierOrder.setStatus(
                    SupplierOrderStatus.PENDING
            );

            supplierOrderRepository.save(
                    supplierOrder
            );

            return toResult(
                    supplierOrder,
                    "LegacySupply unavailable. Reorder remains pending."
            );

        } catch (RuntimeException exception) {

            supplierOrder.setStatus(
                    SupplierOrderStatus.FAILED
            );

            supplierOrderRepository.save(
                    supplierOrder
            );

            return toResult(
                    supplierOrder,
                    exception.getMessage()
            );
        }
    }

    void retryPendingOrders() {

        List<SupplierOrder> pendingOrders =
                supplierOrderRepository.findByStatus(
                        SupplierOrderStatus.PENDING
                );

        for (SupplierOrder supplierOrder : pendingOrders) {

            /*
             * attemptSend() uses the SAME requestId
             * already stored in the database.
             *
             * So even after restarting Spring Boot,
             * this reorder keeps the same X-Request-Id.
             */
            attemptSend(supplierOrder);
        }
    }

    void trackOpenOrders() {

        List<SupplierOrder> openOrders =
                supplierOrderRepository.findByStatusIn(
                        List.of(
                                SupplierOrderStatus.ACCEPTED,
                                SupplierOrderStatus.PICKING,
                                SupplierOrderStatus.SHIPPED,
                                SupplierOrderStatus.UNKNOWN
                        )
                );

        for (SupplierOrder supplierOrder : openOrders) {

            if (supplierOrder.getPoNumber() == null) {
                continue;
            }

            try {

                LegacyPurchaseOrderStatus legacyStatus =
                        legacySupplyClient.getStatus(
                                supplierOrder.getPoNumber()
                        );

                SupplierOrderStatus oldStatus =
                        supplierOrder.getStatus();

                SupplierOrderStatus newStatus =
                        mapStatus(
                                legacyStatus.statusCode()
                        );

                supplierOrder.setStatus(
                        newStatus
                );

                supplierOrderRepository.save(
                        supplierOrder
                );

                /*
                 * Only publish the delivery event once.
                 */
                if (
                        newStatus == SupplierOrderStatus.DELIVERED
                                &&
                        oldStatus != SupplierOrderStatus.DELIVERED
                ) {

                    eventPublisher.publishEvent(
                            new SupplierOrderDeliveredEvent(
                                    supplierOrder.getId(),
                                    supplierOrder.getProductId(),
                                    supplierOrder.getUnits()
                            )
                    );
                }

            } catch (SupplierUnavailableException exception) {

                /*
                 * Do nothing.
                 *
                 * Keep the current status and try again
                 * during a later scheduled poll.
                 */
            }
        }
    }

    private SupplierOrderStatus mapStatus(
            int legacyStatusCode
    ) {

        return switch (legacyStatusCode) {

            case 10 ->
                    SupplierOrderStatus.ACCEPTED;

            case 20 ->
                    SupplierOrderStatus.PICKING;

            case 30 ->
                    SupplierOrderStatus.SHIPPED;

            case 40 ->
                    SupplierOrderStatus.DELIVERED;

            default ->
                    SupplierOrderStatus.UNKNOWN;
        };
    }

    private SupplierOrderResult toResult(
            SupplierOrder supplierOrder,
            String message
    ) {

        return new SupplierOrderResult(
                supplierOrder.getId(),
                supplierOrder.getProductId(),
                supplierOrder.getUnits(),
                supplierOrder.getStatus(),
                message
        );
    }
}