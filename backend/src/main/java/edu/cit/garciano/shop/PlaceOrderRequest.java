package edu.cit.garciano.shop;

import java.util.List;

public record PlaceOrderRequest(
        List<LineItemRequest> items
) {

    public record LineItemRequest(
            String productId,
            int quantity
    ) {
    }
}