package edu.cit.garciano.supplier;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
class LegacySupplyGateway implements SupplierGateway {

    /*
     * These are supplier orders that are still considered open.
     *
     * CANCELLED, DELIVERED and FAILED are intentionally
     * not included.
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
         * DUPLICATE PROTECTION
         *
         * If this product already has an open supplier order,
         * reuse it instead of creating another purchase order.
         */
        var existingOrder =
                supplierOrderRepository
                        .findFirstByProductIdAndStatusInOrderByCreatedAtDesc(
                                productId,
                                OPEN_STATUSES
                        );

        if (existingOrder.isPresent()) {

            SupplierOrder order =
                    existingOrder.get();

            return toResult(
                    order,
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
         * Convert individual units into LegacySupply cases.
         *
         * Example:
         *
         * unitsNeeded = 13
         * packSize = 6
         *
         * cases = 3
         */
        int cases =
                (
                        unitsNeeded
                                + mapping.packSize()
                                - 1
                )
                        / mapping.packSize();

        /*
         * LegacySupply allows up to 99 cases.
         */
        if (cases > 99) {

            throw new IllegalArgumentException(
                    "Supplier order exceeds maximum quantity of 99 cases"
            );
        }

        int actualUnitsOrdered =
                cases
                        * mapping.packSize();

        /*
         * Generate the request ID ONCE.
         *
         * It is stored locally and reused across:
         *
         * - HTTP retries
         * - scheduler retries
         * - application restarts
         */
        String requestId =
                UUID.randomUUID()
                        .toString();

        SupplierOrder supplierOrder =
                new SupplierOrder(
                        productId,
                        requestId,
                        cases,
                        actualUnitsOrdered
                );

        try {

            /*
             * Save BEFORE calling LegacySupply.
             *
             * This guarantees the reorder exists locally
             * even if LegacySupply is unavailable.
             */
            supplierOrder =
                    supplierOrderRepository.save(
                            supplierOrder
                    );

        } catch (DataIntegrityViolationException exception) {

            /*
             * Database-level duplicate protection.
             *
             * Two low-stock events might happen almost
             * simultaneously.
             *
             * If the unique open-order index rejects one,
             * reuse the existing open supplier order.
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
         * BuyerRef must only be created AFTER the local
         * database generated the supplier order ID.
         *
         * This prevents the old RO-null problem.
         *
         * Example:
         *
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

        /*
         * Try immediately.
         *
         * If LegacySupply is unavailable, attemptSend()
         * leaves the order PENDING so the scheduler can retry it.
         */
        return attemptSend(
                supplierOrder
        );
    }

    // =========================================================
    // LAB 4 - CHECK IF RESTOCK IS STILL COMING
    // =========================================================

    @Override
    public boolean hasOpenReorder(
            String productId
    ) {

        return supplierOrderRepository
                .findFirstByProductIdAndStatusInOrderByCreatedAtDesc(
                        productId,
                        OPEN_STATUSES
                )
                .isPresent();
    }

    // =========================================================
    // SEND SUPPLIER ORDER
    // =========================================================

    SupplierOrderResult attemptSend(
            SupplierOrder supplierOrder
    ) {

        /*
         * If a PO number already exists, LegacySupply already
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
             *
             * The request ID came from the database.
             *
             * Every retry therefore reuses the SAME
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
             * Temporary supplier failure.
             *
             * Do NOT create another supplier order.
             * Do NOT generate another request ID.
             *
             * Keep this exact local row PENDING.
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

            /*
             * Non-temporary application / supplier error.
             */
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
    // RETRY PENDING ORDERS
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

            /*
             * attemptSend() reuses:
             *
             * - the same SupplierOrder row
             * - the same BuyerRef
             * - the same requestId
             */
            attemptSend(
                    supplierOrder
            );
        }
    }

    // =========================================================
    // TRACK OPEN ORDERS
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

            /*
             * An accepted supplier order should have a PO number.
             */
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
                 * Only publish the delivery event ONCE,
                 * when the order transitions to DELIVERED.
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
                 * Tracking temporarily failed.
                 *
                 * Keep the current status.
                 * SupplierScheduler will try again later.
                 */
            }
        }
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
             * Discovered during Lab 3:
             *
             * StatusCode 90 means the supplier order
             * was cancelled and the stock will not arrive.
             */
            case 90 ->
                    SupplierOrderStatus.CANCELLED;

            /*
             * Any future undocumented LegacySupply status
             * is kept safe as UNKNOWN.
             *
             * UNKNOWN does not restock Inventory.
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