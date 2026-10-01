package edu.cit.garciano.channel;

import edu.cit.garciano.shop.CancelOrderResponse;
import edu.cit.garciano.shop.OrderService;
import edu.cit.garciano.shop.PlaceOrderResponse;

import edu.cit.garciano.supplier.SupplierGateway;
import edu.cit.garciano.supplier.SupplierOrderDeliveredEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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

    TianggeBackorderResolver(
            OrderService orderService,
            SupplierGateway supplierGateway,
            ChannelGateway channelGateway,
            ChannelStateStore stateStore,
            ChannelOperationContext operationContext,
            ChannelStockOutbox stockOutbox
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
    }

    // =========================================================
    // LEGACYSUPPLY DELIVERY
    // =========================================================

    /*
     * Inventory's SupplierDeliveryListener uses @Order(0).
     *
     * This listener uses @Order(100), so Inventory is
     * restocked FIRST.
     *
     * Only after that do we attempt to resolve waiting
     * marketplace backorders.
     */
    @EventListener
    @Order(100)
    public void handleSupplierDelivery(
            SupplierOrderDeliveredEvent event
    ) {

        log.info(
                "Supplier delivery received for {}. "
                        + "Checking Tiangge backorders...",
                event.productId()
        );

        resolveWaitingBackorders();
    }

    // =========================================================
    // RESTART / FAILURE RECOVERY
    // =========================================================

    /*
     * This is recovery logic.
     *
     * It allows a backorder resolution to continue if
     * the application previously crashed between local
     * reservation and the Tiangge resolution call.
     */
    @Scheduled(
            fixedDelayString =
                    "${channel.tiangge.backorder-retry-ms:5000}",
            initialDelayString =
                    "${channel.tiangge.backorder-retry-ms:5000}"
    )
    public void retryUnresolvedBackorders() {

        resolveWaitingBackorders();
    }

    // =========================================================
    // RESOLUTION
    // =========================================================

    private synchronized void resolveWaitingBackorders() {

        for (
                ChannelStateStore.ChannelOrderState channelOrder :
                stateStore.findBackorderedOrders()
        ) {

            if (channelOrder.shopOrderId() == null) {
                continue;
            }

            PlaceOrderResponse response;

            /*
             * While reserving inventory, stock changes are
             * associated with this Tiangge order.
             *
             * They therefore remain blocked until AFTER
             * Tiangge receives the resolution.
             */
            operationContext.begin(
                    channelOrder.tianggeOrderId()
            );

            try {

                response =
                        orderService.resolveBackorder(
                                channelOrder.shopOrderId()
                        );

            } catch (RuntimeException exception) {

                log.warn(
                        "Unable to resolve Tiangge backorder {}: {}",
                        channelOrder.tianggeOrderId(),
                        exception.getMessage()
                );

                continue;

            } finally {

                operationContext.clear();
            }

            // =================================================
            // SUCCESS: STOCK WAS RESERVED
            // =================================================

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

                    /*
                     * Do not release the stock update.
                     *
                     * The scheduled recovery method will
                     * retry the same resolution.
                     */
                    log.warn(
                            "Tiangge ACCEPTED resolution for {} "
                                    + "is still pending",
                            channelOrder.tianggeOrderId()
                    );

                    continue;
                }

                stateStore.updateOrderStatus(
                        channelOrder.tianggeOrderId(),
                        "RESOLVED_ACCEPTED"
                );

                /*
                 * Correct order required by Tiangge:
                 *
                 * 1. Reserve locally
                 * 2. Send ACCEPTED resolution
                 * 3. Publish resulting stock
                 */
                stockOutbox.releaseForOrder(
                        channelOrder.tianggeOrderId()
                );

                log.info(
                        "Tiangge backorder {} resolved as ACCEPTED",
                        channelOrder.tianggeOrderId()
                );

                continue;
            }

            // =================================================
            // STILL CANNOT FILL
            // =================================================

            if (
                    "BACKORDERED".equals(
                            response.status()
                    )
            ) {

                /*
                 * Check whether ANY product in this order
                 * still has supplier stock coming.
                 *
                 * If yes, continue waiting.
                 */
                boolean anotherRestockComing =
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

                if (anotherRestockComing) {

                    log.info(
                            "Tiangge backorder {} still has "
                                    + "supplier stock on the way",
                            channelOrder.tianggeOrderId()
                    );

                    continue;
                }

                /*
                 * No more supplier order is coming and we
                 * still cannot fill the backorder.
                 *
                 * The lab requires this to become CANCELLED.
                 *
                 * Backordered orders never reserved stock,
                 * so cancelBackorder() does NOT restock.
                 */
                CancelOrderResponse cancelled =
                        orderService.cancelBackorder(
                                channelOrder.shopOrderId(),
                                "Supplier delivery was insufficient "
                                        + "to fill the backorder"
                        );

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

                    continue;
                }

                stateStore.updateOrderStatus(
                        channelOrder.tianggeOrderId(),
                        "RESOLVED_CANCELLED"
                );

                stockOutbox.releaseForOrder(
                        channelOrder.tianggeOrderId()
                );

                log.info(
                        "Tiangge backorder {} resolved as CANCELLED. "
                                + "Local order={}",
                        channelOrder.tianggeOrderId(),
                        cancelled.orderId()
                );
            }
        }
    }
}