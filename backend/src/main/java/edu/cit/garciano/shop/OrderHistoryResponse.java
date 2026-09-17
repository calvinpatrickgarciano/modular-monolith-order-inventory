package edu.cit.garciano.shop;

import java.time.OffsetDateTime;
import java.util.List;

public record OrderHistoryResponse(
        Long orderId,
        String status,
        String reason,
        OffsetDateTime createdAt,
        List<OrderItemView> items
) {

    public record OrderItemView(
            String productId,
            int quantity
    ) {
    }
}