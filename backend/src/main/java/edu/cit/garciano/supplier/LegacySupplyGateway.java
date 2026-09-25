package edu.cit.garciano.supplier;

import org.springframework.context.ApplicationEventPublisher;

import org.springframework.dao.DataIntegrityViolationException;

import org.springframework.stereotype.Service;

import java.util.List;

import java.util.UUID;

@Service

class LegacySupplyGateway implements SupplierGateway {

    /*

     * If a product already has one of these statuses,

     * another reorder must NOT be created for it.

     */

    private static final List<SupplierOrderStatus> OPEN_STATUSES =

            List.of(

                    SupplierOrderStatus.PENDING,

                    SupplierOrderStatus.ACCEPTED,

                    SupplierOrderStatus.PICKING,

                    SupplierOrderStatus.SHIPPED,

                    SupplierOrderStatus.UNKNOWN

            );

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

        /*

         * DUPLICATE PROTECTION:

         *

         * If this product already has a PENDING,

         * ACCEPTED, PICKING, SHIPPED or UNKNOWN reorder,

         * reuse that reorder instead of creating another one.

         */

        var existingOrder =

                supplierOrderRepository

                        .findFirstByProductIdAndStatusInOrderByCreatedAtDesc(

                                productId,

                                OPEN_STATUSES

                        );

        if (existingOrder.isPresent()) {

            return toResult(

                    existingOrder.get(),

                    "An open supplier reorder already exists for "

                            + productId

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

         * Convert individual inventory units into

         * LegacySupply cases and round UP.

         *

         * Example:

         * unitsNeeded = 13

         * packSize = 6

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

         * Generate the request ID ONCE.

         *

         * It is stored in the database and reused

         * across retries and application restarts.

         */

        String requestId =

                UUID.randomUUID().toString();

        SupplierOrder supplierOrder =

                new SupplierOrder(

                        productId,

                        requestId,

                        cases,

                        actualUnitsOrdered

                );

        try {

            /*

             * Save locally BEFORE calling LegacySupply.

             *

             * This prevents a reorder from being lost

             * if the external service is unavailable.

             */

            supplierOrder =

                    supplierOrderRepository.save(

                            supplierOrder

                    );

        } catch (DataIntegrityViolationException exception) {

            /*

             * This protects against two low-stock events

             * arriving almost at the same time when the

             * database unique index is enabled.

             */

            var concurrentOrder =

                    supplierOrderRepository

                            .findFirstByProductIdAndStatusInOrderByCreatedAtDesc(

                                    productId,

                                    OPEN_STATUSES

                            );

            if (concurrentOrder.isPresent()) {

                return toResult(

                        concurrentOrder.get(),

                        "An open supplier reorder already exists for "

                                + productId

                );

            }

            throw exception;

        }

        /*

         * BuyerRef must be unique for this reorder.

         *

         * Example:

         * id = 7

         * BuyerRef = RO-7

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

         * A PO number means LegacySupply has already

         * accepted this local supplier order.

         *

         * Never POST it again.

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

            /*

             * IMPORTANT:

             * placeOrder() receives the requestId that

             * was already saved in supplier_orders.

             *

             * Every retry therefore uses the SAME

             * X-Request-Id.

             */

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

             * Temporary external failure.

             *

             * Do NOT mark it failed and do NOT create

             * another supplier order.

             *

             * Keep the same row PENDING so the scheduler

             * retries it later with the same request ID.

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

             * attemptSend() reuses:

             *

             * - the same local row

             * - the same BuyerRef

             * - the same requestId

             *

             * even after an application restart.

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

                 * Publish the delivery event only when

                 * the order transitions TO DELIVERED.

                 */

                if (

                        newStatus

                                == SupplierOrderStatus.DELIVERED
&&

                        oldStatus

                                != SupplierOrderStatus.DELIVERED

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

                 * Tracking failed temporarily.

                 *

                 * Keep the current status.

                 * The scheduler will try again later.

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

        case 90 ->
                SupplierOrderStatus.CANCELLED;

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
 