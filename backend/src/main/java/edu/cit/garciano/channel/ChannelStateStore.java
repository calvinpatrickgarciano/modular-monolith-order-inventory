package edu.cit.garciano.channel;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Component
class ChannelStateStore {

    private final JdbcTemplate jdbcTemplate;

    ChannelStateStore(
            JdbcTemplate jdbcTemplate
    ) {

        this.jdbcTemplate =
                jdbcTemplate;
    }

    // =========================================================
    // FEED CURSOR
    // =========================================================

    long getCursor() {

        Long cursor =
                jdbcTemplate.queryForObject(
                        """
                        SELECT last_cursor
                        FROM channel_feed_state
                        WHERE id = 1
                        """,
                        Long.class
                );

        return cursor == null
                ? 0L
                : cursor;
    }

    void advanceCursor(
            long cursor
    ) {

        jdbcTemplate.update(
                """
                UPDATE channel_feed_state
                SET
                    last_cursor = GREATEST(
                        last_cursor,
                        ?
                    ),
                    updated_at = NOW()
                WHERE id = 1
                """,
                cursor
        );
    }

    // =========================================================
    // PROCESSED EVENT IDs
    // =========================================================

    boolean isEventProcessed(
            String eventId
    ) {

        Integer count =
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM channel_processed_events
                        WHERE event_id = ?
                        """,
                        Integer.class,
                        eventId
                );

        return count != null
                && count > 0;
    }

    /*
     * Only call this after the event has been
     * completely handled successfully.
     *
     * The processed event and cursor are saved
     * together in one DB transaction.
     */
    @Transactional
    void completeEvent(
            String eventId,
            long seq,
            String eventType,
            String tianggeOrderId
    ) {

        jdbcTemplate.update(
                """
                INSERT INTO channel_processed_events (
                    event_id,
                    seq,
                    event_type,
                    tiangge_order_id
                )
                VALUES (?, ?, ?, ?)
                ON CONFLICT (event_id)
                DO NOTHING
                """,
                eventId,
                seq,
                eventType,
                tianggeOrderId
        );

        advanceCursor(seq);
    }

    // =========================================================
    // TIANGGE ORDER → LOCAL SHOP ORDER
    // =========================================================

    Optional<ChannelOrderState> findOrder(
            String tianggeOrderId
    ) {

        List<ChannelOrderState> rows =
                jdbcTemplate.query(
                        """
                        SELECT
                            tiangge_order_id,
                            shop_order_id,
                            decision,
                            decision_sent,
                            status
                        FROM channel_orders
                        WHERE tiangge_order_id = ?
                        """,

                        (resultSet, rowNumber) ->
                                new ChannelOrderState(
                                        resultSet.getString(
                                                "tiangge_order_id"
                                        ),

                                        resultSet.getObject(
                                                "shop_order_id",
                                                Long.class
                                        ),

                                        resultSet.getString(
                                                "decision"
                                        ),

                                        resultSet.getBoolean(
                                                "decision_sent"
                                        ),

                                        resultSet.getString(
                                                "status"
                                        )
                                ),

                        tianggeOrderId
                );

        return rows
                .stream()
                .findFirst();
    }

    void saveOrder(
            String tianggeOrderId,
            Long shopOrderId,
            String decision,
            String status
    ) {

        jdbcTemplate.update(
                """
                INSERT INTO channel_orders (
                    tiangge_order_id,
                    shop_order_id,
                    decision,
                    status
                )
                VALUES (?, ?, ?, ?)

                ON CONFLICT (tiangge_order_id)
                DO UPDATE SET
                    shop_order_id =
                        COALESCE(
                            EXCLUDED.shop_order_id,
                            channel_orders.shop_order_id
                        ),

                    decision =
                        COALESCE(
                            EXCLUDED.decision,
                            channel_orders.decision
                        ),

                    status =
                        EXCLUDED.status,

                    updated_at =
                        NOW()
                """,

                tianggeOrderId,
                shopOrderId,
                decision,
                status
        );
    }

    void markDecisionSent(
            String tianggeOrderId
    ) {

        jdbcTemplate.update(
                """
                UPDATE channel_orders
                SET
                    decision_sent = TRUE,
                    updated_at = NOW()
                WHERE tiangge_order_id = ?
                """,
                tianggeOrderId
        );
    }

    void updateOrderStatus(
            String tianggeOrderId,
            String status
    ) {

        jdbcTemplate.update(
                """
                UPDATE channel_orders
                SET
                    status = ?,
                    updated_at = NOW()
                WHERE tiangge_order_id = ?
                """,
                status,
                tianggeOrderId
        );
    }

// =========================================================
// BACKORDERS
// =========================================================

List<ChannelOrderState> findBackorderedOrders() {

    return jdbcTemplate.query(
            """
                SELECT
                    co.tiangge_order_id,
                    co.shop_order_id,
                    co.decision,
                    co.decision_sent,
                    co.status
                FROM channel_orders co
                WHERE
                    co.decision = 'BACKORDERED'
                    AND co.status = 'BACKORDERED'

                ORDER BY

                    /*
                     * Highest priority:
                     *
                     * Backorders that are currently blocking
                     * one or more unsent stock updates.
                     *
                     * These must be resolved first because an
                     * old blocked row can prevent every newer
                     * stock update for the same SKU from being
                     * published.
                     */
                    CASE
                        WHEN EXISTS (
                            SELECT 1
                            FROM channel_stock_updates csu
                            WHERE
                                csu.sent = FALSE
                                AND csu.blocked_by_order_id =
                                    co.tiangge_order_id
                        )
                        THEN 0
                        ELSE 1
                    END,

                    /*
                     * Within the same priority group, keep
                     * oldest-first processing.
                     */
                    co.created_at
            """,

            (resultSet, rowNumber) ->
                    new ChannelOrderState(
                            resultSet.getString(
                                    "tiangge_order_id"
                            ),

                            resultSet.getObject(
                                    "shop_order_id",
                                    Long.class
                            ),

                            resultSet.getString(
                                    "decision"
                            ),

                            resultSet.getBoolean(
                                    "decision_sent"
                            ),

                            resultSet.getString(
                                    "status"
                            )
                    )
    );
}
    // =========================================================
    // STOCK OUTBOX
    // =========================================================

    long saveStockUpdate(
            String sellerSku,
            int available,
            String blockedByOrderId
    ) {

        Long id =
                jdbcTemplate.queryForObject(
                        """
                        INSERT INTO channel_stock_updates (
                            seller_sku,
                            available,
                            blocked_by_order_id
                        )
                        VALUES (?, ?, ?)
                        RETURNING id
                        """,

                        Long.class,

                        sellerSku,
                        available,
                        blockedByOrderId
                );

        if (id == null) {

            throw new IllegalStateException(
                    "Unable to save channel stock update"
            );
        }

        return id;
    }

    Optional<StockUpdateState> findOldestUnsentStockUpdate(
        String sellerSku
) {

    List<StockUpdateState> rows =
            jdbcTemplate.query(
                    """
                    SELECT
                        id,
                        seller_sku,
                        available,
                        blocked_by_order_id
                    FROM channel_stock_updates
                    WHERE
                        seller_sku = ?
                        AND sent = FALSE
                    ORDER BY id
                    LIMIT 1
                    """,

                    (resultSet, rowNumber) ->
                            new StockUpdateState(
                                    resultSet.getLong(
                                            "id"
                                    ),

                                    resultSet.getString(
                                            "seller_sku"
                                    ),

                                    resultSet.getInt(
                                            "available"
                                    ),

                                    resultSet.getString(
                                            "blocked_by_order_id"
                                    )
                            ),

                    sellerSku
            );

    return rows
            .stream()
            .findFirst();
}
List<String> findBlockedSkus(
        String tianggeOrderId
) {

    return jdbcTemplate.queryForList(
            """
                SELECT DISTINCT seller_sku
                FROM channel_stock_updates
                WHERE
                    sent = FALSE
                    AND blocked_by_order_id = ?
                ORDER BY seller_sku
            """,
            String.class,
            tianggeOrderId
    );
}
    void unblockStockUpdates(
            String tianggeOrderId
    ) {

        jdbcTemplate.update(
                """
                UPDATE channel_stock_updates
                SET blocked_by_order_id = NULL
                WHERE
                    sent = FALSE
                    AND blocked_by_order_id = ?
                """,
                tianggeOrderId
        );
    }

    void markStockUpdateSent(
            long id
    ) {

        jdbcTemplate.update(
                """
                UPDATE channel_stock_updates
                SET sent = TRUE
                WHERE id = ?
                """,
                id
        );
    }

    // =========================================================
    // INTERNAL DOMAIN RECORDS
    // =========================================================

    record ChannelOrderState(
            String tianggeOrderId,
            Long shopOrderId,
            String decision,
            boolean decisionSent,
            String status
    ) {
    }

    record StockUpdateState(
            long id,
            String sellerSku,
            int available,
            String blockedByOrderId
    ) {
    }
}