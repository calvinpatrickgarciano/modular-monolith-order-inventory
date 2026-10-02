package edu.cit.garciano.supplier;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
class LegacySupplyGateway implements SupplierGateway {

    /*
     * Local statuses considered open for duplicate protection.
     */
    private static final List<SupplierOrderStatus> OPEN_STATUSES =
            List.of(
                    SupplierOrderStatus.PENDING,
                    SupplierOrderStatus.ACCEPTED,
                    SupplierOrderStatus.PICKING,
                    SupplierOrderStatus.SHIPPED,
                    SupplierOrderStatus.UNKNOWN
            );

    /*
     * Tiangge BACKORDERED is allowed only when
     * LegacySupply CURRENTLY reports one of these.
     */
    private static final List<SupplierOrderStatus>
            BACKORDER_ELIGIBLE_STATUSES =
            List.of(
                    SupplierOrderStatus.ACCEPTED,
                    SupplierOrderStatus.PICKING,
                    SupplierOrderStatus.SHIPPED
            );

    private final SupplierOrderRepository supplierOrderRepository;
    private final LegacySupplyClient legacySupplyClient;
    private final ApplicationEventPublisher eventPublisher;

    LegacySupplyGateway(
            SupplierOrderRepository supplierOrderRepository,
            LegacySupplyClient legacySupplyClient,
            ApplicationEventPublisher eventPublisher
    ) {

        this.supplierOrderRepository =
                supplierOrderRepository;

        this.legacySupplyClient =
                legacySupplyClient;

        this.eventPublisher =
                eventPublisher;
    }

    // =========================================================
    // CREATE OR REUSE SUPPLIER REORDER
    // =========================================================

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
         * Duplicate protection.
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
                LegacyProductMapping.find(
                        productId
                );

        if (mapping == null) {

            throw new IllegalArgumentException(
                    "No supplier mapping for product: "
                            + productId
            );
        }

        /*
         * Convert inventory units into supplier cases.
         */
        int cases =
                (
                        unitsNeeded
                                + mapping.packSize()
                                - 1
                )
                        / mapping.packSize();

        if (cases > 99) {

            throw new IllegalArgumentException(
                    "Supplier order exceeds maximum quantity of 99 cases"
            );
        }

        int unitsOrdered =
                cases
                        * mapping.packSize();

        /*
         * Generate the idempotency key ONCE.
         */
        String requestId =
                UUID.randomUUID()
                        .toString();

        SupplierOrder supplierOrder =
                new SupplierOrder(
                        productId,
                        requestId,
                        cases,
                        unitsOrdered
                );

        try {

            /*
             * Persist before talking to LegacySupply.
             */
            supplierOrder =
                    supplierOrderRepository.save(
                            supplierOrder
                    );

        } catch (DataIntegrityViolationException exception) {

            /*
             * Another low-stock event may have created
             * the reorder first.
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
         * ID now exists, so BuyerRef cannot become RO-null.
         */
        supplierOrder.setBuyerRef(
                "RO-" + supplierOrder.getId()
        );

        supplierOrder =
                supplierOrderRepository.save(
                        supplierOrder
                );

        return attemptSend(
                supplierOrder
        );
    }

    // =========================================================
    // LAB 4 - IS SUPPLIER STOCK REALLY STILL ON THE WAY?
    // =========================================================

    @Override
    public boolean hasOpenReorder(
            String productId
    ) {

        /*
         * First find a LOCAL supplier order which appears
         * eligible for Tiangge backordering.
         */
        var existingOrder =
                supplierOrderRepository
                        .findFirstByProductIdAndStatusInOrderByCreatedAtDesc(
                                productId,
                                BACKORDER_ELIGIBLE_STATUSES
                        );

        if (existingOrder.isEmpty()) {
            return false;
        }

        SupplierOrder supplierOrder =
                existingOrder.get();

        /*
         * An acknowledged LegacySupply order must have
         * a PO number.
         */
        if (supplierOrder.getPoNumber() == null) {
            return false;
        }

        try {

            /*
             * CRITICAL LAB 4 FIX:
             *
             * Do NOT trust only the local DB status.
             *
             * Ask LegacySupply for the CURRENT status
             * immediately before allowing BACKORDERED.
             */
            SupplierOrderStatus currentStatus =
                    refreshSupplierStatus(
                            supplierOrder
                    );

            return BACKORDER_ELIGIBLE_STATUSES.contains(
                    currentStatus
            );

        } catch (SupplierUnavailableException exception) {

            /*
             * We cannot prove that the supplier order is
             * currently still on the way.
             *
             * Be conservative and DO NOT create a new
             * Tiangge BACKORDERED decision.
             */
            return false;
        }
    }

    // =========================================================
    // SEND PURCHASE ORDER
    // =========================================================

    SupplierOrderResult attemptSend(
            SupplierOrder supplierOrder
    ) {

        /*
         * PO number means LegacySupply already accepted it.
         * Never POST the same local supplier order again.
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
             * The persisted requestId is reused across retries.
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
             * Keep the SAME order pending.
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

    // =========================================================
    // RETRY PENDING PURCHASE ORDERS
    // =========================================================

    void retryPendingOrders() {

        List<SupplierOrder> pendingOrders =
                supplierOrderRepository.findByStatus(
                        SupplierOrderStatus.PENDING
                );

        for (
                SupplierOrder supplierOrder :
                pendingOrders
        ) {

            attemptSend(
                    supplierOrder
            );
        }
    }

    // =========================================================
    // TRACK SUPPLIER ORDERS
    // =========================================================

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

        for (
                SupplierOrder supplierOrder :
                openOrders
        ) {

            if (supplierOrder.getPoNumber() == null) {
                continue;
            }

            try {

                /*
                 * Use the same refresh method as
                 * hasOpenReorder().
                 *
                 * This keeps status handling consistent.
                 */
                refreshSupplierStatus(
                        supplierOrder
                );

            } catch (SupplierUnavailableException exception) {

                /*
                 * Temporary LegacySupply problem.
                 * Scheduler will try again later.
                 */
            }
        }
    }

    // =========================================================
    // REFRESH ONE PURCHASE ORDER FROM LEGACYSUPPLY
    // =========================================================

    /*
     * synchronized prevents hasOpenReorder() and the normal
     * tracking scheduler from publishing the same delivery
     * event at the same time.
     */
    private synchronized SupplierOrderStatus refreshSupplierStatus(
            SupplierOrder supplierOrder
    ) {

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

        /*
         * Keep our local supplier table synchronized with
         * LegacySupply's CURRENT status.
         */
        if (newStatus != oldStatus) {

            supplierOrder.setStatus(
                    newStatus
            );

            supplierOrderRepository.save(
                    supplierOrder
            );
        }

        /*
         * Delivery event must happen exactly once.
         *
         * hasOpenReorder() may discover delivery before
         * SupplierScheduler does.
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

        return newStatus;
    }

    // =========================================================
    // LEGACYSUPPLY STATUS MAPPING
    // =========================================================

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

            /*
             * Status 90 was discovered during Lab 3.
             * No stock will arrive.
             */
            case 90 ->
                    SupplierOrderStatus.CANCELLED;

            /*
             * Never assume an unknown code means delivery.
             */
            default ->
                    SupplierOrderStatus.UNKNOWN;
        };
    }

    // =========================================================
    // RESULT TRANSLATION
    // =========================================================

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