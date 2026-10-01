package edu.cit.garciano.channel;

import java.util.List;

public interface ChannelGateway {

    void heartbeat();

    void publishListings();

    boolean publishStock(
            String sellerSku,
            int available
    );

    FeedBatch fetchFeed(
            long after
    );

    boolean sendDecision(
            String tianggeOrderId,
            String decision,
            Long shopOrderId
    );

    boolean confirmCancellation(
            String tianggeOrderId
    );

    /*
     * TASK 6
     *
     * Resolves a Tiangge BACKORDERED order.
     *
     * status:
     * ACCEPTED or CANCELLED
     */
    boolean sendResolution(
            String tianggeOrderId,
            String status
    );

    record FeedBatch(
            List<FeedEvent> events,
            long nextCursor
    ) {
    }

    record FeedEvent(
            long seq,
            String eventId,
            String type,
            String orderId,
            String deadline,
            List<Line> lines
    ) {
    }

    record Line(
            String sellerSku,
            int quantity
    ) {
    }
}